/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven.render.vk;

import java.nio.*;
import java.util.*;
import java.util.function.*;
import haven.*;
import haven.render.*;
import haven.render.vk.VkEnvironment.Attach;
import haven.render.vk.VkEnvironment.TexBind;
import haven.render.vk.VkRender.*;
import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.util.vma.Vma.*;

/*
 * Executes recorded renders on the render thread. All methods are
 * called with the VkExec monitor held.
 *
 * A unit is one queue submission: an optional transfer command
 * buffer (uploads and updates) followed by the main command buffer.
 * Units rotate through a small set of slots; a slot's GPU resources
 * (command buffers, the ring of host-visible memory, readbacks,
 * deferred deletions) are recycled once its fence has signaled.
 */
public class VkExec {
    public static final int SLOTS = 3;
    public static final int QUERIES = 64;
    public static final int CHUNK = 4 << 20;
    final VkEnvironment env;
    final VkDevice dev;
    private final Slot[] slots = new Slot[SLOTS];
    private final Deque<Slot> inflight = new ArrayDeque<>();
    private int nextslot = 0;
    private Slot unit = null;
    private long surface;
    private Swapchain swap = null;
    private boolean swapdirty = false, lastvsync = true;
    private VkTexture bcolor, bdepth;
    private Coord bsize = null;
    private final VkTexture dummytex;
    private final VkBuf dummyvtx;
    private final long dummysmp;
    private long nunits = 0, npresent = 0;

    static class Chunk {
	final long buf, alloc, addr;
	final int size;
	int pos = 0;

	Chunk(long buf, long alloc, long addr, int size) {
	    this.buf = buf;
	    this.alloc = alloc;
	    this.addr = addr;
	    this.size = size;
	}
    }

    class Ring {
	final List<Chunk> chunks = new ArrayList<>();
	int cur = 0;
	/* Result of the last alloc() */
	long rbuf, raddr;
	int roff;

	void alloc(int sz, int align) {
	    while(true) {
		if(cur >= chunks.size())
		    chunks.add(mkchunk(Math.max(CHUNK, sz + align)));
		Chunk c = chunks.get(cur);
		int off = (c.pos + align - 1) & ~(align - 1);
		if(off + sz <= c.size) {
		    c.pos = off + sz;
		    rbuf = c.buf;
		    roff = off;
		    raddr = c.addr + off;
		    return;
		}
		if((c.pos == 0) && (sz + align > c.size)) {
		    /* Too big for this chunk even when empty. */
		    Chunk n = mkchunk(sz + align);
		    chunks.add(cur, n);
		    continue;
		}
		cur++;
	    }
	}

	void reset() {
	    for(Chunk c : chunks)
		c.pos = 0;
	    cur = 0;
	    /* Drop oversized chunks, keep a few normal ones. */
	    for(Iterator<Chunk> i = chunks.iterator(); i.hasNext();) {
		Chunk c = i.next();
		if((c.size > CHUNK) || (chunks.size() > 4)) {
		    vmaDestroyBuffer(env.vma, c.buf, c.alloc);
		    i.remove();
		}
	    }
	}

	void destroy() {
	    for(Chunk c : chunks)
		vmaDestroyBuffer(env.vma, c.buf, c.alloc);
	    chunks.clear();
	}
    }

    private Chunk mkchunk(int size) {
	try(MemoryStack st = stackPush()) {
	    VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(st).sType$Default().size(size)
		.usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
		.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
	    VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(st).usage(VMA_MEMORY_USAGE_AUTO)
		.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT)
		.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
	    LongBuffer bp = st.mallocLong(1);
	    PointerBuffer ap = st.mallocPointer(1);
	    VmaAllocationInfo info = VmaAllocationInfo.calloc(st);
	    VkEnvironment.check(vmaCreateBuffer(env.vma, bci, aci, bp, ap, info), "vmaCreateBuffer (ring)");
	    return(new Chunk(bp.get(0), ap.get(0), info.pMappedData(), size));
	}
    }

    class Slot {
	final long pool, fence, acquire, qpool;
	final VkCommandBuffer xcb, mcb;
	final Ring ring = new Ring();
	final List<Runnable> completions = new ArrayList<>();
	final List<VkObject> deletions = new ArrayList<>();
	final List<Consumer<Long>> tscb = new ArrayList<>();
	boolean inflight = false;
	boolean xused, mused;
	/* Layout of textures at the start of the unit, for those the
	 * main buffer transitions, so uploads can restore them. */
	final Map<VkTexture, Integer> startlayouts = new IdentityHashMap<>();

	Slot() {
	    try(MemoryStack st = stackPush()) {
		LongBuffer lp = st.mallocLong(1);
		VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(st).sType$Default()
		    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(env.qfam);
		VkEnvironment.check(vkCreateCommandPool(dev, pci, null, lp), "vkCreateCommandPool");
		pool = lp.get(0);
		VkCommandBufferAllocateInfo aci = VkCommandBufferAllocateInfo.calloc(st).sType$Default()
		    .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(2);
		PointerBuffer pp = st.mallocPointer(2);
		VkEnvironment.check(vkAllocateCommandBuffers(dev, aci, pp), "vkAllocateCommandBuffers");
		xcb = new VkCommandBuffer(pp.get(0), dev);
		mcb = new VkCommandBuffer(pp.get(1), dev);
		VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(st).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
		VkEnvironment.check(vkCreateFence(dev, fci, null, lp), "vkCreateFence");
		fence = lp.get(0);
		VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(st).sType$Default();
		VkEnvironment.check(vkCreateSemaphore(dev, sci, null, lp), "vkCreateSemaphore");
		acquire = lp.get(0);
		if(env.ts_bits > 0) {
		    VkQueryPoolCreateInfo qci = VkQueryPoolCreateInfo.calloc(st).sType$Default()
			.queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(QUERIES);
		    VkEnvironment.check(vkCreateQueryPool(dev, qci, null, lp), "vkCreateQueryPool");
		    qpool = lp.get(0);
		} else {
		    qpool = 0;
		}
	    }
	}

	void destroy() {
	    ring.destroy();
	    if(qpool != 0)
		vkDestroyQueryPool(dev, qpool, null);
	    vkDestroySemaphore(dev, acquire, null);
	    vkDestroyFence(dev, fence, null);
	    vkDestroyCommandPool(dev, pool, null);
	}
    }

    VkExec(VkEnvironment env, long surface) {
	this.env = env;
	this.dev = env.dev;
	this.surface = surface;
	for(int i = 0; i < SLOTS; i++)
	    slots[i] = new Slot();
	/* Stand-ins for unsupplied vertex attributes and sampler
	 * array elements. */
	dummyvtx = new VkBuf(env, 16, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
	ByteBuffer vd = Utils.mkbbuf(16);
	vd.putFloat(0).putFloat(0).putFloat(0).putFloat(1).flip();
	dummytex = new VkTexture(env, VkFormats.texfmt(new VectorFormat(4, NumberFormat.UNORM8), false), VK_IMAGE_VIEW_TYPE_2D,
				 1, 1, 1, 1, 1, false, null);
	dummysmp = env.sampler(new Texture2D(1, 1, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null).sampler(), dummytex);
	ByteBuffer td = Utils.mkbbuf(4);
	td.put((byte)0).put((byte)0).put((byte)0).put((byte)-1).flip();
	env.prep(ex -> {
		ex.upload(dummyvtx, 0, vd);
		ex.inittex(dummytex);
		ex.uploadtex(dummytex, 0, 0, 1, 1, 1, td);
	    });
    }

    /* Slots and units */

    private void retire(Slot s) {
	if(!s.inflight)
	    return;
	while(true) {
	    int rv = vkWaitForFences(dev, s.fence, true, 1000000000L);
	    if(rv == VK_SUCCESS)
		break;
	    if(rv != VK_TIMEOUT)
		throw(new VkEnvironment.VkException("vkWaitForFences", rv));
	}
	s.inflight = false;
	for(Runnable r : s.completions)
	    r.run();
	s.completions.clear();
	if(!s.tscb.isEmpty()) {
	    int n = s.tscb.size();
	    long[] res = new long[n];
	    int rv = vkGetQueryPoolResults(dev, s.qpool, 0, n, res, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
	    for(int i = 0; i < n; i++) {
		Consumer<Long> cb = s.tscb.get(i);
		long ns = (rv == VK_SUCCESS) ? (long)(res[i] * (double)env.ts_period) : 0;
		env.callback(() -> cb.accept(ns));
	    }
	    s.tscb.clear();
	}
	for(VkObject obj : s.deletions)
	    obj.destroy0();
	s.deletions.clear();
    }

    private void retire(boolean block) {
	while(!inflight.isEmpty()) {
	    Slot s = inflight.peekFirst();
	    if(!block && (vkGetFenceStatus(dev, s.fence) != VK_SUCCESS))
		break;
	    inflight.removeFirst();
	    retire(s);
	}
    }

    private Slot begin() {
	if(unit != null)
	    return(unit);
	Slot s = slots[nextslot];
	nextslot = (nextslot + 1) % SLOTS;
	while(s.inflight) {
	    Slot o = inflight.removeFirst();
	    retire(o);
	}
	vkResetCommandPool(dev, s.pool, 0);
	s.ring.reset();
	s.xused = s.mused = false;
	s.startlayouts.clear();
	unit = s;
	try(MemoryStack st = stackPush()) {
	    VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
	    VkEnvironment.check(vkBeginCommandBuffer(s.mcb, bi), "vkBeginCommandBuffer");
	}
	if(s.qpool != 0)
	    vkCmdResetQueryPool(s.mcb, s.qpool, 0, QUERIES);
	resetbound();
	return(s);
    }

    private void xbegin() {
	Slot s = begin();
	if(s.xused)
	    return;
	try(MemoryStack st = stackPush()) {
	    VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(st).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
	    VkEnvironment.check(vkBeginCommandBuffer(s.xcb, bi), "vkBeginCommandBuffer");
	    VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, st).sType$Default()
		.srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
		.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
	    vkCmdPipelineBarrier(s.xcb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, mb, null, null);
	}
	s.xused = true;
    }

    private void submit(boolean present, int imgidx) {
	Slot s = unit;
	if(s == null)
	    return;
	endpass();
	try(MemoryStack st = stackPush()) {
	    if(s.xused) {
		VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, st).sType$Default()
		    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
		    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		vkCmdPipelineBarrier(s.xcb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, mb, null, null);
		VkEnvironment.check(vkEndCommandBuffer(s.xcb), "vkEndCommandBuffer");
	    }
	    VkEnvironment.check(vkEndCommandBuffer(s.mcb), "vkEndCommandBuffer");
	    VkEnvironment.check(vkResetFences(dev, s.fence), "vkResetFences");
	    PointerBuffer cbs = st.mallocPointer(s.xused ? 2 : 1);
	    if(s.xused)
		cbs.put(s.xcb);
	    cbs.put(s.mcb).flip();
	    VkSubmitInfo si = VkSubmitInfo.calloc(st).sType$Default().pCommandBuffers(cbs);
	    if(present) {
		si.waitSemaphoreCount(1).pWaitSemaphores(st.longs(s.acquire)).pWaitDstStageMask(st.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
		    .pSignalSemaphores(st.longs(swap.done[imgidx]));
	    }
	    VkEnvironment.check(vkQueueSubmit(env.queue, si, s.fence), "vkQueueSubmit");
	    s.inflight = true;
	    inflight.addLast(s);
	    unit = null;
	    nunits++;
	    if(present) {
		VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(st).sType$Default()
		    .pWaitSemaphores(st.longs(swap.done[imgidx]))
		    .swapchainCount(1).pSwapchains(st.longs(swap.sc)).pImageIndices(st.ints(imgidx));
		int rv = vkQueuePresentKHR(env.queue, pi);
		if((rv == VK_ERROR_OUT_OF_DATE_KHR) || (rv == VK_SUBOPTIMAL_KHR))
		    swapdirty = true;
		else if(rv == VK_ERROR_SURFACE_LOST_KHR)
		    lostsurface();
		else
		    VkEnvironment.check(rv, "vkQueuePresentKHR");
		npresent++;
	    }
	}
    }

    void process(List<Consumer<VkExec>> prep, List<VkRender> renders, Coord wsz) {
	retire(false);
	if((wsz != null) && (wsz.x > 0) && (wsz.y > 0) && !wsz.equals(bsize))
	    resizeback(wsz);
	for(Consumer<VkExec> item : prep)
	    item.accept(this);
	for(VkRender r : renders) {
	    try {
		run(r);
	    } finally {
		r.dispose();
	    }
	}
	if(unit != null)
	    submit(false, 0);
	List<VkObject> reap = env.reapable();
	if(!reap.isEmpty()) {
	    Slot last = inflight.peekLast();
	    if(last != null) {
		last.deletions.addAll(reap);
	    } else {
		for(VkObject obj : reap)
		    obj.destroy0();
	    }
	}
    }

    /* Per-render arena location, while executing a render. */
    private long abuf;
    private int aoff;

    void run(VkRender r) {
	long pbuf = abuf;
	int poff = aoff;
	Slot aunit = null;
	try {
	    for(VkRender.Cmd cmd : r.cmds) {
		begin();
		if((unit != aunit) && (r.apos > 0)) {
		    /* The arena lives in the ring of the unit that
		     * executes the commands; a swap in the middle of a
		     * render starts a new unit. */
		    unit.ring.alloc(r.apos, 256);
		    abuf = unit.ring.rbuf;
		    aoff = unit.ring.roff;
		    memCopy(memAddress(r.arena, 0), unit.ring.raddr, r.apos);
		    aunit = unit;
		}
		cmd.exec(this, r);
	    }
	} finally {
	    abuf = pbuf;
	    aoff = poff;
	}
    }

    /* Transfers */

    private static void copyout(ByteBuffer src, long dst, int n) {
	if(src.isDirect()) {
	    memCopy(memAddress(src), dst, n);
	} else {
	    ByteBuffer s = src.duplicate();
	    s.limit(s.position() + n);
	    memByteBuffer(dst, n).put(s);
	}
    }

    void upload(VkBuf buf, int off, ByteBuffer data) {
	int n = data.remaining();
	if(n == 0)
	    return;
	n = Math.min(n, buf.size + VkBuf.PAD - off);
	xbegin();
	Ring ring = unit.ring;
	ring.alloc(n, 16);
	copyout(data, ring.raddr, n);
	try(MemoryStack st = stackPush()) {
	    VkBufferCopy.Buffer reg = VkBufferCopy.calloc(1, st).srcOffset(ring.roff).dstOffset(off).size(n);
	    vkCmdCopyBuffer(unit.xcb, ring.rbuf, buf.buf, reg);
	}
    }

    private void imgbarrier(VkCommandBuffer cb, MemoryStack st, VkTexture tex, int from, int to, int srcstage, int dststage,
			    int srcaccess, int dstaccess, int level, int nlevels, int layer, int nlayers) {
	VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, st).sType$Default()
	    .srcAccessMask(srcaccess).dstAccessMask(dstaccess)
	    .oldLayout(from).newLayout(to)
	    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
	    .image(tex.image);
	ib.subresourceRange().set(tex.aspect(), level, nlevels, layer, nlayers);
	vkCmdPipelineBarrier(cb, srcstage, dststage, 0, null, null, ib);
    }

    void inittex(VkTexture tex) {
	xbegin();
	try(MemoryStack st = stackPush()) {
	    imgbarrier(unit.xcb, st, tex, VK_IMAGE_LAYOUT_UNDEFINED, tex.steady,
		       VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		       0, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
		       0, tex.levels, 0, tex.layers);
	}
	tex.layout = tex.steady;
    }

    void uploadtex(VkTexture tex, int level, int layer, int w, int h, int d, ByteBuffer data) {
	if(data == null)
	    return;
	int n = Math.min(data.remaining(), w * h * d * tex.fmt.size());
	xbegin();
	Ring ring = unit.ring;
	ring.alloc(n, 16);
	copyout(data, ring.raddr, n);
	int restore = unit.startlayouts.getOrDefault(tex, tex.layout);
	if(restore == VK_IMAGE_LAYOUT_UNDEFINED)
	    restore = tex.steady;
	try(MemoryStack st = stackPush()) {
	    imgbarrier(unit.xcb, st, tex, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
		       VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
		       0, VK_ACCESS_TRANSFER_WRITE_BIT, level, 1, layer, 1);
	    VkBufferImageCopy.Buffer reg = VkBufferImageCopy.calloc(1, st).bufferOffset(ring.roff).bufferRowLength(0).bufferImageHeight(0);
	    reg.imageSubresource().set(tex.aspect(), level, layer, 1);
	    reg.imageOffset().set(0, 0, 0);
	    reg.imageExtent().set(w, h, d);
	    vkCmdCopyBufferToImage(unit.xcb, ring.rbuf, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, reg);
	    imgbarrier(unit.xcb, st, tex, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, restore,
		       VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		       VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT, level, 1, layer, 1);
	}
	if(tex.layout == VK_IMAGE_LAYOUT_UNDEFINED)
	    tex.layout = restore;
    }

    /* Default framebuffer */

    private void resizeback(Coord sz) {
	if(bcolor != null)
	    bcolor.dispose();
	if(bdepth != null)
	    bdepth.dispose();
	bcolor = new VkTexture(env, VkFormats.texfmt(new VectorFormat(4, NumberFormat.UNORM8), false), VK_IMAGE_VIEW_TYPE_2D,
			       sz.x, sz.y, 1, 1, 1, true, null);
	bdepth = new VkTexture(env, VkFormats.texfmt(Texture.DEPTH, false), VK_IMAGE_VIEW_TYPE_2D,
			       sz.x, sz.y, 1, 1, 1, true, null);
	inittex(bcolor);
	inittex(bdepth);
	bsize = sz;
    }

    private Attach resolve(Object att) {
	if(att == VkEnvironment.DEFCOLOR)
	    return(new Attach(bcolor, 0));
	if(att == VkEnvironment.DEFDEPTH)
	    return(new Attach(bdepth, 0));
	return((Attach)att);
    }

    /* Render passes */

    private Targets pass = null;
    private int passw, passh;

    private void endpass() {
	if(pass != null) {
	    vkCmdEndRendering(unit.mcb);
	    pass = null;
	}
    }

    private void tomain(MemoryStack st, VkTexture tex, int to) {
	if(tex.layout == to)
	    return;
	if(!unit.startlayouts.containsKey(tex))
	    unit.startlayouts.put(tex, tex.layout);
	imgbarrier(unit.mcb, st, tex, tex.layout, to,
		   VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
		   VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
		   0, tex.levels, 0, tex.layers);
	tex.layout = to;
    }

    private void fullbarrier(MemoryStack st, VkCommandBuffer cb) {
	VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, st).sType$Default()
	    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
	    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
	vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, mb, null, null);
    }

    private void beginpass(Targets t) {
	endpass();
	unit.mused = true;
	try(MemoryStack st = stackPush()) {
	    int w = Integer.MAX_VALUE, h = Integer.MAX_VALUE;
	    Attach[] col = new Attach[t.color.length];
	    for(int i = 0; i < col.length; i++) {
		if(t.color[i] != null) {
		    col[i] = resolve(t.color[i]);
		    if(col[i].tex == null)
			throw(new IllegalStateException("no default framebuffer yet"));
		    Coord sz = col[i].tex.sz(col[i].level);
		    w = Math.min(w, sz.x);
		    h = Math.min(h, sz.y);
		    if(col[i].tex.steady != VK_IMAGE_LAYOUT_GENERAL)
			col[i].tex.steady = VK_IMAGE_LAYOUT_GENERAL;
		    tomain(st, col[i].tex, VK_IMAGE_LAYOUT_GENERAL);
		}
	    }
	    Attach depth = (t.depth == null) ? null : resolve(t.depth);
	    if(depth != null) {
		if(depth.tex == null)
		    throw(new IllegalStateException("no default framebuffer yet"));
		Coord sz = depth.tex.sz(depth.level);
		w = Math.min(w, sz.x);
		h = Math.min(h, sz.y);
		tomain(st, depth.tex, VK_IMAGE_LAYOUT_GENERAL);
	    }
	    fullbarrier(st, unit.mcb);
	    VkRenderingAttachmentInfo.Buffer cai = VkRenderingAttachmentInfo.calloc(col.length, st);
	    for(int i = 0; i < col.length; i++) {
		VkRenderingAttachmentInfo a = cai.get(i).sType$Default();
		if(col[i] != null) {
		    a.imageView(col[i].tex.attview(col[i].level)).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
			.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
		} else {
		    a.imageView(VK_NULL_HANDLE).imageLayout(VK_IMAGE_LAYOUT_UNDEFINED)
			.loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
		}
	    }
	    VkRenderingInfo ri = VkRenderingInfo.calloc(st).sType$Default().layerCount(1).pColorAttachments(cai);
	    ri.renderArea().offset().set(0, 0);
	    ri.renderArea().extent().set(w, h);
	    if(depth != null) {
		VkRenderingAttachmentInfo dai = VkRenderingAttachmentInfo.calloc(st).sType$Default()
		    .imageView(depth.tex.attview(depth.level)).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
		    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
		ri.pDepthAttachment(dai);
	    }
	    vkCmdBeginRendering(unit.mcb, ri);
	    passw = w;
	    passh = h;
	}
	pass = t;
    }

    private void ckpass(Targets t) {
	if((pass == null) || !pass.sameatt(t))
	    beginpass(t);
    }

    /* Bound state, tracked per command buffer */

    private long bpipe, blayout, bubuf;
    private int buoff;
    private Object[] btex;
    private Area bvp, bsc;
    private int bvpw, bvph;
    private int bcull, bdop;
    private boolean bdtest, bdwrite, bbias, bff;
    private float bbfac, bbunits, blw;
    private FColor bconst;
    private boolean dynvalid;

    private void resetbound() {
	bpipe = blayout = bubuf = 0;
	buoff = -1;
	btex = null;
	dynvalid = false;
	bff = false;
    }

    private static int clampi(int v, int min, int max) {
	return(Math.max(min, Math.min(max, v)));
    }

    private void setdyn(MemoryStack st, Dyn d, FColor bc) {
	VkCommandBuffer cb = unit.mcb;
	boolean all = !dynvalid;
	if(all || !Utils.eq(d.vp, bvp) || (bvpw != passw) || (bvph != passh)) {
	    VkViewport.Buffer vp = VkViewport.calloc(1, st);
	    if(d.vp != null)
		vp.get(0).x(d.vp.ul.x).y(d.vp.ul.y).width(d.vp.br.x - d.vp.ul.x).height(d.vp.br.y - d.vp.ul.y);
	    else
		vp.get(0).x(0).y(0).width(passw).height(passh);
	    vp.get(0).minDepth(0).maxDepth(1);
	    vkCmdSetViewport(cb, 0, vp);
	    bvp = d.vp;
	}
	if(all || !Utils.eq(d.sc, bsc) || (bvpw != passw) || (bvph != passh)) {
	    VkRect2D.Buffer sc = VkRect2D.calloc(1, st);
	    if(d.sc != null) {
		int x0 = clampi(d.sc.ul.x, 0, passw), y0 = clampi(d.sc.ul.y, 0, passh);
		int x1 = clampi(d.sc.br.x, x0, passw), y1 = clampi(d.sc.br.y, y0, passh);
		sc.get(0).offset().set(x0, y0);
		sc.get(0).extent().set(x1 - x0, y1 - y0);
	    } else {
		sc.get(0).offset().set(0, 0);
		sc.get(0).extent().set(passw, passh);
	    }
	    vkCmdSetScissor(cb, 0, sc);
	    bsc = d.sc;
	}
	bvpw = passw;
	bvph = passh;
	if(all || !bff) {
	    vkCmdSetFrontFace(cb, VkEnvironment.FRONT_FACE);
	    bff = true;
	}
	if(all || (d.cull != bcull)) {
	    vkCmdSetCullMode(cb, d.cull);
	    bcull = d.cull;
	}
	if(all || (d.dtest != bdtest)) {
	    vkCmdSetDepthTestEnable(cb, d.dtest);
	    bdtest = d.dtest;
	}
	if(all || (d.dop != bdop)) {
	    vkCmdSetDepthCompareOp(cb, d.dop);
	    bdop = d.dop;
	}
	boolean dw = d.dwrite && d.dtest;
	if(all || (dw != bdwrite)) {
	    vkCmdSetDepthWriteEnable(cb, dw);
	    bdwrite = dw;
	}
	if(all || (d.bias != bbias)) {
	    vkCmdSetDepthBiasEnable(cb, d.bias);
	    bbias = d.bias;
	}
	if(all || (d.bfac != bbfac) || (d.bunits != bbunits)) {
	    vkCmdSetDepthBias(cb, d.bunits, 0, d.bfac);
	    bbfac = d.bfac;
	    bbunits = d.bunits;
	}
	float lw = env.wideLines ? Math.max(env.linemin, Math.min(env.linemax, d.lw)) : 1;
	if(all || (lw != blw)) {
	    vkCmdSetLineWidth(cb, lw);
	    blw = lw;
	}
	if(all || !Utils.eq(bc, bconst)) {
	    if(bc != null)
		vkCmdSetBlendConstants(cb, st.floats(bc.r, bc.g, bc.b, bc.a));
	    else
		vkCmdSetBlendConstants(cb, st.floats(0, 0, 0, 0));
	    bconst = bc;
	}
	dynvalid = true;
    }

    private static boolean texeq(Object[] a, Object[] b) {
	if((a == null) || (b == null) || (a.length != b.length))
	    return(false);
	for(int i = 0; i < a.length; i++) {
	    Object x = a[i], y = b[i];
	    if(x == y)
		continue;
	    if((x instanceof TexBind[]) && (y instanceof TexBind[])) {
		if(!Arrays.equals((TexBind[])x, (TexBind[])y))
		    return(false);
	    } else if(!Utils.eq(x, y)) {
		return(false);
	    }
	}
	return(true);
    }

    private void imageinfo(VkDescriptorImageInfo info, TexBind tb) {
	if((tb == null) || (tb.tex == null))
	    tb = new TexBind(dummytex, dummysmp);
	info.sampler(tb.sampler).imageView(tb.tex.view).imageLayout(tb.tex.layout);
    }

    void draw(DrawCmd d) {
	ckpass(d.tgt);
	VkCommandBuffer cb = unit.mcb;
	try(MemoryStack st = stackPush()) {
	    long pipe = d.prog.pipeline(d.key);
	    if(pipe != bpipe) {
		vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
		bpipe = pipe;
	    }
	    setdyn(st, d.dyn, d.tgt.bconst);
	    VkProgram prog = d.prog;
	    int nw = ((prog.ubosize > 0) ? 1 : 0) + prog.samplers.length;
	    if(nw > 0) {
		long ubuf = (d.ubo >= 0) ? abuf : 0;
		int uoff = (d.ubo >= 0) ? (aoff + d.ubo) : -1;
		if((prog.layout != blayout) || (ubuf != bubuf) || (uoff != buoff) || !texeq(d.tex, btex)) {
		    VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(nw, st);
		    int wi = 0;
		    if(prog.ubosize > 0) {
			VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, st);
			bi.get(0).buffer(ubuf).offset(uoff).range(prog.ubosize);
			w.get(wi++).sType$Default().dstBinding(0).dstArrayElement(0).descriptorCount(1)
			    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(bi);
		    }
		    for(int i = 0; i < prog.samplers.length; i++) {
			int n = prog.scount[i];
			VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(n, st);
			Object tv = d.tex[i];
			for(int e = 0; e < n; e++) {
			    TexBind tb;
			    if(tv instanceof TexBind[])
				tb = (e < ((TexBind[])tv).length) ? ((TexBind[])tv)[e] : null;
			    else
				tb = (e == 0) ? (TexBind)tv : null;
			    imageinfo(ii.get(e), tb);
			}
			w.get(wi++).sType$Default().dstBinding(prog.ubind[prog.samplers[i]]).dstArrayElement(0).descriptorCount(n)
			    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
		    }
		    vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, prog.layout, 0, w);
		    blayout = prog.layout;
		    bubuf = ubuf;
		    buoff = uoff;
		    btex = d.tex;
		}
	    }
	    VkProgram.VertexKey vk = d.key.vk;
	    if(vk.nbind > 0) {
		LongBuffer bufs = st.mallocLong(vk.nbind), offs = st.mallocLong(vk.nbind);
		for(int b = 0; b < vk.nbind; b++) {
		    int src = vk.bsrc[b];
		    Object o = (src < 0) ? dummyvtx : d.geo.vsrc[src];
		    if(o instanceof VkBuf) {
			bufs.put(b, ((VkBuf)o).buf);
			offs.put(b, 0);
		    } else {
			bufs.put(b, abuf);
			offs.put(b, aoff + (Integer)o);
		    }
		}
		vkCmdBindVertexBuffers(cb, 0, bufs, offs);
	    }
	    Geometry geo = d.geo;
	    if(geo.isrc != null) {
		if(geo.isrc instanceof VkBuf)
		    vkCmdBindIndexBuffer(cb, ((VkBuf)geo.isrc).buf, 0, geo.itype);
		else
		    vkCmdBindIndexBuffer(cb, abuf, aoff + (Integer)geo.isrc, geo.itype);
		vkCmdDrawIndexed(cb, geo.count, geo.ninst, geo.first, 0, 0);
	    } else {
		vkCmdDraw(cb, geo.count, geo.ninst, geo.first, 0);
	    }
	}
    }

    void clear(ClearCmd c) {
	ckpass(c.tgt);
	try(MemoryStack st = stackPush()) {
	    VkClearAttachment.Buffer ca = VkClearAttachment.calloc(1, st);
	    if(c.loc >= 0) {
		ca.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(c.loc);
		VkClearColorValue col = ca.get(0).clearValue().color();
		Attach att = resolve(c.tgt.color[c.loc]);
		if(att.tex.fmt.integer) {
		    col.int32(0, (int)c.color.r).int32(1, (int)c.color.g).int32(2, (int)c.color.b).int32(3, (int)c.color.a);
		} else {
		    col.float32(0, c.color.r).float32(1, c.color.g).float32(2, c.color.b).float32(3, c.color.a);
		}
	    } else {
		ca.get(0).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
		ca.get(0).clearValue().depthStencil().depth((float)c.depth).stencil(0);
	    }
	    VkClearRect.Buffer cr = VkClearRect.calloc(1, st);
	    int x0 = 0, y0 = 0, x1 = passw, y1 = passh;
	    if(c.area != null) {
		x0 = clampi(c.area.ul.x, 0, passw); y0 = clampi(c.area.ul.y, 0, passh);
		x1 = clampi(c.area.br.x, x0, passw); y1 = clampi(c.area.br.y, y0, passh);
	    }
	    if((x1 <= x0) || (y1 <= y0))
		return;
	    cr.get(0).rect().offset().set(x0, y0);
	    cr.get(0).rect().extent().set(x1 - x0, y1 - y0);
	    cr.get(0).baseArrayLayer(0).layerCount(1);
	    vkCmdClearAttachments(unit.mcb, ca, cr);
	}
    }

    /* Readback */

    void pget(PGetCmd p) {
	endpass();
	Attach att = resolve(p.img);
	if(att.tex == null) {
	    env.callback(() -> p.cb.accept(p.dst));
	    return;
	}
	VkTexture tex = att.tex;
	Coord sz = tex.sz(att.level);
	int x0 = clampi(p.area.ul.x, 0, sz.x), y0 = clampi(p.area.ul.y, 0, sz.y);
	int x1 = clampi(p.area.br.x, x0, sz.x), y1 = clampi(p.area.br.y, y0, sz.y);
	int w = x1 - x0, h = y1 - y0;
	Area area = p.area;
	if((w <= 0) || (h <= 0)) {
	    env.callback(() -> p.cb.accept(p.dst));
	    return;
	}
	unit.mused = true;
	int tsz = tex.fmt.size();
	int bsz = w * h * tsz;
	long rbuf, ralloc, raddr;
	try(MemoryStack st = stackPush()) {
	    VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(st).sType$Default().size(bsz)
		.usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
	    VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(st).usage(VMA_MEMORY_USAGE_AUTO)
		.flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
	    LongBuffer bp = st.mallocLong(1);
	    PointerBuffer ap = st.mallocPointer(1);
	    VmaAllocationInfo info = VmaAllocationInfo.calloc(st);
	    VkEnvironment.check(vmaCreateBuffer(env.vma, bci, aci, bp, ap, info), "vmaCreateBuffer (readback)");
	    rbuf = bp.get(0);
	    ralloc = ap.get(0);
	    raddr = info.pMappedData();

	    int from = tex.layout;
	    if(from != VK_IMAGE_LAYOUT_GENERAL) {
		if(!unit.startlayouts.containsKey(tex))
		    unit.startlayouts.put(tex, tex.layout);
		imgbarrier(unit.mcb, st, tex, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
			   VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
			   VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, 0, tex.levels, 0, tex.layers);
	    } else {
		fullbarrier(st, unit.mcb);
	    }
	    VkBufferImageCopy.Buffer reg = VkBufferImageCopy.calloc(1, st).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
	    reg.imageSubresource().set(tex.aspect(), att.level, 0, 1);
	    reg.imageOffset().set(x0, y0, 0);
	    reg.imageExtent().set(w, h, 1);
	    vkCmdCopyImageToBuffer(unit.mcb, tex.image, (from == VK_IMAGE_LAYOUT_GENERAL) ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, rbuf, reg);
	    if(from != VK_IMAGE_LAYOUT_GENERAL) {
		imgbarrier(unit.mcb, st, tex, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, from,
			   VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
			   0, VK_ACCESS_MEMORY_READ_BIT, 0, tex.levels, 0, tex.layers);
	    }
	    fullbarrier(st, unit.mcb);
	}
	VkFormats.TexFmt tfmt = tex.fmt;
	unit.completions.add(() -> {
		vmaInvalidateAllocation(env.vma, ralloc, 0, VK_WHOLE_SIZE);
		ByteBuffer src = memByteBuffer(raddr, bsz);
		ByteBuffer dst = p.dst.duplicate().order(ByteOrder.nativeOrder());
		int rw = area.br.x - area.ul.x;
		int esz = p.fmt.size();
		/* Rows of the requested area that were outside the image
		 * are left untouched. */
		for(int y = 0; y < h; y++) {
		    ByteBuffer srow = src.duplicate().order(ByteOrder.nativeOrder());
		    srow.position(y * w * tsz);
		    ByteBuffer drow = dst.duplicate().order(ByteOrder.nativeOrder());
		    drow.position(dst.position() + ((((y + y0 - area.ul.y) * rw) + (x0 - area.ul.x)) * esz));
		    VkFormats.convert(srow, tfmt.nc, tfmt.cf, null, drow, p.fmt.nc, p.fmt.cf, w);
		}
		vmaDestroyBuffer(env.vma, rbuf, ralloc);
		env.callback(() -> p.cb.accept(p.dst));
	    });
    }

    void timestamp(Consumer<Long> cb) {
	Slot s = unit;
	if((s.qpool == 0) || (s.tscb.size() >= QUERIES)) {
	    env.callback(() -> cb.accept(0L));
	    return;
	}
	s.mused = true;
	vkCmdWriteTimestamp(s.mcb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s.qpool, s.tscb.size());
	s.tscb.add(cb);
    }

    /* Presentation */

    class Swapchain {
	final long sc;
	final long[] images, done;
	final int format, w, h;
	final Coord want;
	final boolean vsync;

	Swapchain(Swapchain old, boolean vsync, Coord want) {
	    try(MemoryStack st = stackPush()) {
		VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(st);
		VkEnvironment.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(env.pdev, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
		int w, h;
		if(caps.currentExtent().width() != 0xffffffff) {
		    w = caps.currentExtent().width();
		    h = caps.currentExtent().height();
		} else {
		    w = clampi(want.x, caps.minImageExtent().width(), caps.maxImageExtent().width());
		    h = clampi(want.y, caps.minImageExtent().height(), caps.maxImageExtent().height());
		}
		this.w = w;
		this.h = h;
		this.want = want;
		this.vsync = vsync;
		if((w == 0) || (h == 0)) {
		    this.sc = 0;
		    this.images = this.done = new long[0];
		    this.format = VK_FORMAT_UNDEFINED;
		    return;
		}
		if((caps.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0)
		    throw(new Environment.UnavailableException("swapchain images cannot be blitted to"));
		IntBuffer n = st.mallocInt(1);
		vkGetPhysicalDeviceSurfaceFormatsKHR(env.pdev, surface, n, null);
		VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.malloc(n.get(0), st);
		vkGetPhysicalDeviceSurfaceFormatsKHR(env.pdev, surface, n, fmts);
		int fmt = -1, cs = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
		for(VkSurfaceFormatKHR f : fmts) {
		    if(((f.format() == VK_FORMAT_B8G8R8A8_UNORM) || (f.format() == VK_FORMAT_R8G8B8A8_UNORM)) && (f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)) {
			fmt = f.format();
			cs = f.colorSpace();
			break;
		    }
		}
		if(fmt < 0) {
		    fmt = fmts.get(0).format();
		    cs = fmts.get(0).colorSpace();
		}
		this.format = fmt;
		vkGetPhysicalDeviceSurfacePresentModesKHR(env.pdev, surface, n, null);
		IntBuffer modes = st.mallocInt(n.get(0));
		vkGetPhysicalDeviceSurfacePresentModesKHR(env.pdev, surface, n, modes);
		int mode = VK_PRESENT_MODE_FIFO_KHR;
		if(!vsync) {
		    for(int i = 0; i < modes.capacity(); i++) {
			if(modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR)
			    mode = VK_PRESENT_MODE_MAILBOX_KHR;
		    }
		    if(mode == VK_PRESENT_MODE_FIFO_KHR) {
			for(int i = 0; i < modes.capacity(); i++) {
			    if(modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR)
				mode = VK_PRESENT_MODE_IMMEDIATE_KHR;
			}
		    }
		}
		int nimg = caps.minImageCount() + 1;
		if((caps.maxImageCount() > 0) && (nimg > caps.maxImageCount()))
		    nimg = caps.maxImageCount();
		int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
		if((caps.supportedCompositeAlpha() & alpha) == 0)
		    alpha = Integer.lowestOneBit(caps.supportedCompositeAlpha());
		VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(st).sType$Default()
		    .surface(surface).minImageCount(nimg).imageFormat(fmt).imageColorSpace(cs)
		    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
		    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
		    .preTransform(caps.currentTransform()).compositeAlpha(alpha)
		    .presentMode(mode).clipped(true).oldSwapchain((old == null) ? VK_NULL_HANDLE : old.sc);
		sci.imageExtent().set(w, h);
		LongBuffer lp = st.mallocLong(1);
		VkEnvironment.check(vkCreateSwapchainKHR(dev, sci, null, lp), "vkCreateSwapchainKHR");
		this.sc = lp.get(0);
		vkGetSwapchainImagesKHR(dev, sc, n, null);
		LongBuffer imgs = st.mallocLong(n.get(0));
		vkGetSwapchainImagesKHR(dev, sc, n, imgs);
		this.images = new long[n.get(0)];
		this.done = new long[images.length];
		VkSemaphoreCreateInfo semi = VkSemaphoreCreateInfo.calloc(st).sType$Default();
		for(int i = 0; i < images.length; i++) {
		    images[i] = imgs.get(i);
		    VkEnvironment.check(vkCreateSemaphore(dev, semi, null, lp), "vkCreateSemaphore");
		    done[i] = lp.get(0);
		}
	    }
	}

	void destroy() {
	    for(long sem : done)
		vkDestroySemaphore(dev, sem, null);
	    if(sc != 0)
		vkDestroySwapchainKHR(dev, sc, null);
	}
    }

    private void idle() {
	vkDeviceWaitIdle(dev);
	retire(true);
    }

    private boolean ckswap(boolean vsync) {
	/* The surface is (re)created by the toolkit on the AWT
	 * thread; the render thread never touches AWT. */
	if(surface == 0)
	    return(false);
	Coord want = (bsize == null) ? Coord.of(1, 1) : bsize;
	if((swap == null) || swapdirty || (swap.vsync != vsync) || !swap.want.equals(want)) {
	    endpass();
	    Swapchain old = swap;
	    /* The current unit may still be recording; nothing it
	     * references belongs to the old swapchain until it is
	     * submitted, so waiting for the device is enough. */
	    vkDeviceWaitIdle(dev);
	    swap = new Swapchain(old, vsync, want);
	    if(old != null)
		old.destroy();
	    swapdirty = false;
	}
	return(swap.sc != 0);
    }

    private void lostsurface() {
	if(swap != null) {
	    vkDeviceWaitIdle(dev);
	    swap.destroy();
	    swap = null;
	}
	if(surface != 0) {
	    vkDestroySurfaceKHR(env.inst, surface, null);
	    surface = 0;
	}
    }

    void setsurface(long surface) {
	if(this.surface != 0)
	    lostsurface();
	this.surface = surface;
	swapdirty = true;
    }

    /* Called when the window's native surface goes away. */
    void surfacegone() {
	if(unit != null)
	    submit(false, 0);
	idle();
	lostsurface();
    }

    void swap(boolean vsync) {
	lastvsync = vsync;
	if((bcolor == null) || !ckswap(vsync)) {
	    submit(false, 0);
	    return;
	}
	Slot s = unit;
	int idx;
	try(MemoryStack st = stackPush()) {
	    IntBuffer ip = st.mallocInt(1);
	    int rv = vkAcquireNextImageKHR(dev, swap.sc, 1000000000L, s.acquire, VK_NULL_HANDLE, ip);
	    if(rv == VK_ERROR_OUT_OF_DATE_KHR) {
		swapdirty = true;
		submit(false, 0);
		return;
	    } else if(rv == VK_ERROR_SURFACE_LOST_KHR) {
		submit(false, 0);
		lostsurface();
		return;
	    } else if((rv != VK_SUCCESS) && (rv != VK_SUBOPTIMAL_KHR)) {
		if(rv == VK_TIMEOUT || rv == VK_NOT_READY) {
		    submit(false, 0);
		    return;
		}
		VkEnvironment.check(rv, "vkAcquireNextImageKHR");
	    }
	    if(rv == VK_SUBOPTIMAL_KHR)
		swapdirty = true;
	    idx = ip.get(0);
	    endpass();
	    fullbarrier(st, s.mcb);
	    VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, st).sType$Default()
		.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
		.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
		.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
		.image(swap.images[idx]);
	    ib.subresourceRange().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
	    vkCmdPipelineBarrier(s.mcb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, ib);
	    /* Flip vertically: the backbuffer has OpenGL's bottom-up
	     * row order. */
	    VkImageBlit.Buffer blit = VkImageBlit.calloc(1, st);
	    blit.get(0).srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
	    blit.get(0).srcOffsets(0).set(0, 0, 0);
	    blit.get(0).srcOffsets(1).set(bcolor.w, bcolor.h, 1);
	    blit.get(0).dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
	    blit.get(0).dstOffsets(0).set(0, swap.h, 0);
	    blit.get(0).dstOffsets(1).set(swap.w, 0, 1);
	    boolean same = (bcolor.w == swap.w) && (bcolor.h == swap.h);
	    vkCmdBlitImage(s.mcb, bcolor.image, VK_IMAGE_LAYOUT_GENERAL, swap.images[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
			   blit, same ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
	    ib.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(0)
		.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
	    vkCmdPipelineBarrier(s.mcb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, null, null, ib);
	    s.mused = true;
	}
	submit(true, idx);
    }

    boolean presented() {
	return(npresent > 0);
    }

    String stats() {
	int nring = 0;
	long ringsz = 0;
	for(Slot s : slots) {
	    for(Chunk c : s.ring.chunks) {
		nring++;
		ringsz += c.size;
	    }
	}
	return(String.format("units %,d, presents %,d, ring %d chunks / %,d kB, swap %s",
			     nunits, npresent, nring, ringsz / 1024,
			     (swap == null) ? "none" : String.format("%dx%d%s", swap.w, swap.h, swap.vsync ? " vsync" : "")));
    }

    void dispose() {
	if(unit != null)
	    submit(false, 0);
	idle();
	for(VkObject obj : env.reapable())
	    obj.destroy0();
	if(swap != null)
	    swap.destroy();
	swap = null;
	if(surface != 0)
	    vkDestroySurfaceKHR(env.inst, surface, null);
	surface = 0;
	if(bcolor != null) bcolor.destroy0();
	if(bdepth != null) bdepth.destroy0();
	dummytex.destroy0();
	dummyvtx.destroy0();
	for(Slot s : slots)
	    s.destroy();
    }
}
