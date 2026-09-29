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
import haven.*;
import haven.render.*;
import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.util.vma.Vma.*;

/* A device-local vertex or index buffer (STATIC and STREAM usage).
 * Updates are copied in by the transfer command buffer of the unit
 * that executes them. */
public class VkBuf extends VkObject {
    /* Extra space at the end, so that widened three-component vertex
     * formats never read past the buffer. */
    public static final int PAD = 16;
    public final int size;
    public final long buf, alloc;
    /* UINT8 index data is stored as UINT16. */
    boolean widen8 = false;

    public VkBuf(VkEnvironment env, int size, int usage) {
	super(env);
	this.size = size;
	try(MemoryStack st = stackPush()) {
	    VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(st).sType$Default()
		.size(size + PAD).usage(usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
		.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
	    VmaAllocationCreateInfo aci = VmaAllocationCreateInfo.calloc(st).usage(VMA_MEMORY_USAGE_AUTO);
	    LongBuffer bp = st.mallocLong(1);
	    PointerBuffer ap = st.mallocPointer(1);
	    VkEnvironment.check(vmaCreateBuffer(env.vma, bci, aci, bp, ap, null), "vmaCreateBuffer");
	    this.buf = bp.get(0);
	    this.alloc = ap.get(0);
	}
    }

    /* Returns the data as it should be stored, and the byte offset
     * it goes to. */
    ByteBuffer convert(ByteBuffer data) {
	if(!widen8)
	    return(data);
	ByteBuffer ret = Utils.mkbbuf(data.remaining() * 2);
	for(int i = data.position(); i < data.limit(); i++)
	    ret.putShort((short)(data.get(i) & 0xff));
	ret.flip();
	return(ret);
    }

    int convoff(int off) {
	return(widen8 ? (off * 2) : off);
    }

    void upload(int off, FillBuffer fill) {
	if(fill == null)
	    return;
	ByteBuffer data = convert(VkFillBuffer.data(fill));
	int doff = convoff(off);
	env.prep(ex -> {
		ex.upload(this, doff, data);
		fill.dispose();
	    });
    }

    protected void destroy() {
	vmaDestroyBuffer(env.vma, buf, alloc);
    }

    public String toString() {
	return(String.format("#<vk-buf %,d>", size));
    }
}
