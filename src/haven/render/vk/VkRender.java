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
import java.util.concurrent.atomic.*;
import java.util.function.*;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.render.vk.VkEnvironment.Attach;
import haven.render.vk.VkEnvironment.TexBind;
import haven.render.vk.VkProgram.PipeKey;
import static haven.Utils.eq;
import static org.lwjgl.vulkan.VK10.*;

/*
 * Records rendering commands. Everything a draw needs is resolved
 * here, on the recording thread: the program, packed uniform data,
 * render targets and fixed-function state. The render thread only
 * translates the resulting commands into Vulkan calls.
 *
 * Per-draw data (uniform blocks, ephemeral vertex data) goes into
 * an arena that is copied to GPU-visible memory in one piece when
 * the render is executed.
 */
public class VkRender implements Render, Disposable {
    public final VkEnvironment env;
    final List<Cmd> cmds = new ArrayList<>();
    ByteBuffer arena = null;
    int apos = 0;
    private final VkEnvironment.Sequence seq;
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    VkRender(VkEnvironment env) {
	this.env = env;
	this.seq = env.new Sequence(this);
    }

    public VkEnvironment env() {return(env);}

    boolean empty() {return(cmds.isEmpty());}

    /* Arena */

    int alloc(int sz, int align) {
	int off = (apos + align - 1) & ~(align - 1);
	int end = off + sz;
	if(arena == null)
	    arena = env.getarena(end);
	if(end > arena.capacity()) {
	    int ncap = arena.capacity();
	    while(ncap < end)
		ncap *= 2;
	    ByteBuffer narena = env.getarena(ncap);
	    ByteBuffer old = arena.duplicate();
	    old.position(0).limit(apos);
	    narena.put(old);
	    env.putarena(arena);
	    arena = narena;
	}
	apos = end;
	return(off);
    }

    int put(ByteBuffer data, int align, int pad) {
	int n = data.remaining();
	int off = alloc(n + pad, align);
	ByteBuffer dst = arena.duplicate();
	dst.position(off);
	dst.put(data.duplicate());
	return(off);
    }

    /* Resolved state */

    static class Targets {
	/* Per fragment output location: Attach, DEFCOLOR or null. */
	final Object[] color;
	final Object depth;
	final BlendMode[] blend;
	final int[] cmask, cfmt;
	final int dfmt;
	final FColor bconst;

	Targets(Object[] color, Object depth, BlendMode[] blend, int[] cmask, int[] cfmt, int dfmt) {
	    this.color = color;
	    this.depth = depth;
	    this.blend = blend;
	    this.cmask = cmask;
	    this.cfmt = cfmt;
	    this.dfmt = dfmt;
	    FColor bconst = null;
	    for(BlendMode b : blend) {
		if((b != null) && (b.color != null))
		    bconst = b.color;
	    }
	    this.bconst = bconst;
	}

	boolean sameatt(Targets that) {
	    return((this == that) || (Arrays.equals(this.color, that.color) && eq(this.depth, that.depth)));
	}
    }

    static Targets mktargets(VkEnvironment env, VkProgram prog, Pipe pipe) {
	int n = prog.fragdata.length;
	Object[] color = new Object[n];
	BlendMode[] blend = new BlendMode[n];
	int[] cmask = new int[n], cfmt = new int[n];
	boolean any = false;
	for(int i = 0; i < n; i++) {
	    Object fval = prog.fragdata[i].value.apply(pipe);
	    FragTarget ft = null;
	    if(fval instanceof FragTarget)
		fval = (ft = (FragTarget)fval).buf;
	    Object att = env.prepfval(fval);
	    color[i] = att;
	    if(att == null) {
		cfmt[i] = VK_FORMAT_UNDEFINED;
		continue;
	    }
	    any = true;
	    cfmt[i] = (att == VkEnvironment.DEFCOLOR) ? VkEnvironment.COLOR_FORMAT : ((Attach)att).tex.fmt.vk;
	    int mask = 0xf;
	    if(ft != null) {
		blend[i] = ft.blend;
		for(int c = 0; c < 4; c++) {
		    if(ft.mask[c])
			mask &= ~(1 << c);
		}
	    }
	    cmask[i] = mask;
	}
	DepthBuffer<?> db = pipe.get(DepthBuffer.slot);
	Object depth = env.prepfval((db == null) ? null : db.image);
	int dfmt = VK_FORMAT_UNDEFINED;
	if(depth != null) {
	    any = true;
	    dfmt = (depth == VkEnvironment.DEFDEPTH) ? VkEnvironment.DEPTH_FORMAT : ((Attach)depth).tex.fmt.vk;
	}
	if(!any)
	    throw(new IllegalArgumentException("empty framebuffer"));
	return(new Targets(color, depth, blend, cmask, cfmt, dfmt));
    }

    static class Dyn {
	final Area vp, sc;
	final int cull;
	final boolean dtest, dwrite, bias;
	final int dop;
	final float bfac, bunits, lw;

	Dyn(Pipe pipe) {
	    States.Viewport vp = pipe.get(States.viewport);
	    this.vp = (vp == null) ? null : vp.area;
	    States.Scissor sc = pipe.get(States.scissor);
	    this.sc = (sc == null) ? null : sc.area;
	    States.Facecull fc = pipe.get(States.facecull);
	    if(fc == null) {
		cull = VK_CULL_MODE_NONE;
	    } else {
		switch(fc.mode) {
		case FRONT: cull = VK_CULL_MODE_FRONT_BIT; break;
		case BACK:  cull = VK_CULL_MODE_BACK_BIT; break;
		case BOTH:  cull = VK_CULL_MODE_FRONT_AND_BACK; break;
		default:    cull = VK_CULL_MODE_NONE; break;
		}
	    }
	    States.Depthtest dt = pipe.get(States.depthtest);
	    if(dt == null) {
		dtest = false;
		dop = VK_COMPARE_OP_ALWAYS;
	    } else {
		dtest = true;
		switch(dt.test) {
		case FALSE: dop = VK_COMPARE_OP_NEVER; break;
		case TRUE:  dop = VK_COMPARE_OP_ALWAYS; break;
		case EQ:    dop = VK_COMPARE_OP_EQUAL; break;
		case NEQ:   dop = VK_COMPARE_OP_NOT_EQUAL; break;
		case LT:    dop = VK_COMPARE_OP_LESS; break;
		case GT:    dop = VK_COMPARE_OP_GREATER; break;
		case LE:    dop = VK_COMPARE_OP_LESS_OR_EQUAL; break;
		case GE:    dop = VK_COMPARE_OP_GREATER_OR_EQUAL; break;
		default:    dop = VK_COMPARE_OP_LESS; break;
		}
	    }
	    dwrite = (pipe.get(States.maskdepth.slot) == null);
	    States.DepthBias db = pipe.get(States.depthbias);
	    bias = (db != null);
	    bfac = bias ? db.factor : 0;
	    bunits = bias ? db.units : 0;
	    States.LineWidth lw = pipe.get(States.linewidth);
	    this.lw = (lw == null) ? 1 : lw.w;
	}
    }

    static final int[] dynslots = {
	States.viewport.id, States.scissor.id, States.facecull.id, States.depthtest.id,
	States.maskdepth.slot.id, States.linewidth.id, States.depthbias.id,
    };

    static int topology(Model.Mode mode) {
	switch(mode) {
	case POINTS:         return(VK_PRIMITIVE_TOPOLOGY_POINT_LIST);
	case LINES:          return(VK_PRIMITIVE_TOPOLOGY_LINE_LIST);
	case LINE_STRIP:     return(VK_PRIMITIVE_TOPOLOGY_LINE_STRIP);
	case TRIANGLES:      return(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
	case TRIANGLE_STRIP: return(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
	case TRIANGLE_FAN:   return(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN);
	default: throw(new RuntimeException("unimplemented draw mode " + mode));
	}
    }

    /* Vertex/index sources: a VkBuf or an arena offset. */
    static class Geometry {
	final Object[] vsrc;
	final Object isrc;
	final int itype, first, count, ninst;

	Geometry(Object[] vsrc, Object isrc, int itype, int first, int count, int ninst) {
	    this.vsrc = vsrc;
	    this.isrc = isrc;
	    this.itype = itype;
	    this.first = first;
	    this.count = count;
	    this.ninst = ninst;
	}

	void get() {
	    for(Object src : vsrc) {
		if(src instanceof VkBuf)
		    ((VkBuf)src).get();
	    }
	    if(isrc instanceof VkBuf)
		((VkBuf)isrc).get();
	}

	void put() {
	    for(Object src : vsrc) {
		if(src instanceof VkBuf)
		    ((VkBuf)src).put();
	    }
	    if(isrc instanceof VkBuf)
		((VkBuf)isrc).put();
	}
    }

    static int itype(Model.Indices ind) {
	return((ind.fmt == NumberFormat.UINT32) ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16);
    }

    /* Geometry of a model whose buffers all have device objects. */
    static Geometry geometry(VkEnvironment env, Model mod) {
	Object[] vsrc = new Object[mod.va.bufs.length];
	for(int i = 0; i < vsrc.length; i++)
	    vsrc[i] = env.prepare(mod.va.bufs[i]);
	Object isrc = (mod.ind == null) ? null : env.prepare(mod.ind);
	return(new Geometry(vsrc, isrc, (mod.ind == null) ? 0 : itype(mod.ind), mod.f, mod.n, mod.ninst));
    }

    private Geometry ephgeometry(Model mod) {
	Object[] vsrc = new Object[mod.va.bufs.length];
	for(int i = 0; i < vsrc.length; i++) {
	    VertexArray.Buffer buf = mod.va.bufs[i];
	    if(buf.usage == DataBuffer.Usage.EPHEMERAL) {
		if(buf.init == null)
		    throw(new IllegalArgumentException("ephemeral vertex buffer without data"));
		FillBuffer fill = buf.init.fill(buf, env);
		vsrc[i] = put(VkFillBuffer.data(fill), 16, VkBuf.PAD);
		fill.dispose();
	    } else {
		vsrc[i] = env.prepare(buf);
	    }
	}
	Object isrc = null;
	if(mod.ind != null) {
	    if(mod.ind.usage == DataBuffer.Usage.EPHEMERAL) {
		FillBuffer fill = mod.ind.init.fill(mod.ind, env);
		ByteBuffer data = VkFillBuffer.data(fill);
		if(mod.ind.fmt == NumberFormat.UINT8) {
		    ByteBuffer w = Utils.mkbbuf(data.remaining() * 2);
		    for(int i = data.position(); i < data.limit(); i++)
			w.putShort((short)(data.get(i) & 0xff));
		    w.flip();
		    data = w;
		}
		isrc = put(data, 16, 0);
		fill.dispose();
	    } else {
		isrc = env.prepare(mod.ind);
	    }
	}
	return(new Geometry(vsrc, isrc, (mod.ind == null) ? 0 : itype(mod.ind), mod.f, mod.n, mod.ninst));
    }

    /* Commands */

    static abstract class Cmd {
	abstract void exec(VkExec ex, VkRender r);
	void abort() {}
    }

    static class DrawCmd extends Cmd {
	final VkProgram prog;
	final PipeKey key;
	final Targets tgt;
	final Dyn dyn;
	final Object[] tex;
	final int ubo;
	final Geometry geo;

	DrawCmd(VkProgram prog, PipeKey key, Targets tgt, Dyn dyn, Object[] tex, int ubo, Geometry geo) {
	    this.prog = prog;
	    this.key = key;
	    this.tgt = tgt;
	    this.dyn = dyn;
	    this.tex = tex;
	    this.ubo = ubo;
	    this.geo = geo;
	}

	void exec(VkExec ex, VkRender r) {ex.draw(this);}
    }

    static class ClearCmd extends Cmd {
	final Targets tgt;
	final int loc;
	final FColor color;
	final double depth;
	final Area area;

	ClearCmd(Targets tgt, int loc, FColor color, double depth, Area area) {
	    this.tgt = tgt;
	    this.loc = loc;
	    this.color = color;
	    this.depth = depth;
	    this.area = area;
	}

	void exec(VkExec ex, VkRender r) {ex.clear(this);}
    }

    static class SubCmd extends Cmd {
	final VkRender sub;
	SubCmd(VkRender sub) {this.sub = sub;}
	void exec(VkExec ex, VkRender r) {
	    ex.run(sub);
	    sub.dispose();
	}
	void abort() {
	    sub.abort();
	    sub.dispose();
	}
    }

    static class BufUpdateCmd extends Cmd {
	final VkBuf buf;
	final int off;
	final ByteBuffer data;
	final FillBuffer fill;

	BufUpdateCmd(VkBuf buf, int off, ByteBuffer data, FillBuffer fill) {
	    this.buf = buf;
	    this.off = off;
	    this.data = data;
	    this.fill = fill;
	}

	void exec(VkExec ex, VkRender r) {
	    ex.upload(buf, off, data);
	    fill.dispose();
	}
	void abort() {fill.dispose();}
    }

    static class TexUpdateCmd extends Cmd {
	final VkTexture tex;
	final int level, layer, w, h, d;
	final ByteBuffer data;
	final FillBuffer fill;

	TexUpdateCmd(VkTexture tex, int level, int layer, int w, int h, int d, ByteBuffer data, FillBuffer fill) {
	    this.tex = tex;
	    this.level = level; this.layer = layer;
	    this.w = w; this.h = h; this.d = d;
	    this.data = data;
	    this.fill = fill;
	}

	void exec(VkExec ex, VkRender r) {
	    if(level < tex.levels)
		ex.uploadtex(tex, level, layer, w, h, d, data);
	    fill.dispose();
	}
	void abort() {fill.dispose();}
    }

    static class PGetCmd extends Cmd {
	final Object img;
	final int level;
	final Area area;
	final VectorFormat fmt;
	final ByteBuffer dst;
	final Consumer<ByteBuffer> cb;

	PGetCmd(Object img, int level, Area area, VectorFormat fmt, ByteBuffer dst, Consumer<ByteBuffer> cb) {
	    this.img = img;
	    this.level = level;
	    this.area = area;
	    this.fmt = fmt;
	    this.dst = dst;
	    this.cb = cb;
	}

	void exec(VkExec ex, VkRender r) {ex.pget(this);}
	void abort() {
	    if(cb instanceof Abortable)
		((Abortable)cb).abort();
	}
    }

    static class TimestampCmd extends Cmd {
	final Consumer<Long> cb;
	TimestampCmd(Consumer<Long> cb) {this.cb = cb;}
	void exec(VkExec ex, VkRender r) {ex.timestamp(cb);}
    }

    static class FenceCmd extends Cmd {
	final Runnable cb;
	FenceCmd(Runnable cb) {this.cb = cb;}
	void exec(VkExec ex, VkRender r) {r.env.callback(cb);}
	void abort() {
	    if(cb instanceof Abortable)
		((Abortable)cb).abort();
	}
    }

    static class SwapCmd extends Cmd {
	final boolean vsync;
	SwapCmd(boolean vsync) {this.vsync = vsync;}
	void exec(VkExec ex, VkRender r) {ex.swap(vsync);}
    }

    /* State application, as in the GL Applier */

    private State[] cur = new State[0];
    private ShaderMacro[] shaders = new ShaderMacro[0];
    private int shash = 0;
    private VkProgram prog = null;
    private Object[] uvals = new Object[0];
    private Object[] tex = new Object[0];
    private boolean udirty = true, tdirty = true;
    private int lastubo = -1;
    private Targets tgt = null;
    private Dyn dyn = null;

    private void apply(Pipe to) {
	State[] ns = to.states();
	if(cur.length < ns.length) {
	    cur = Arrays.copyOf(cur, ns.length);
	    shaders = Arrays.copyOf(shaders, ns.length);
	}
	int[] pdirty = new int[cur.length];
	int pn = 0;
	{
	    int i = 0;
	    for(; i < ns.length; i++) {
		if(!eq(ns[i], cur[i]))
		    pdirty[pn++] = i;
	    }
	    for(; i < cur.length; i++) {
		if(cur[i] != null)
		    pdirty[pn++] = i;
	    }
	}
	if((pn == 0) && (prog != null))
	    return;
	int shash = this.shash;
	boolean schanged = false;
	ShaderMacro[] nshaders = shaders;
	for(int i = 0; i < pn; i++) {
	    int slot = pdirty[i];
	    State s = (slot < ns.length) ? ns[slot] : null;
	    ShaderMacro nm = (s == null) ? null : s.shader();
	    if(nm != nshaders[slot]) {
		if(!schanged) {
		    nshaders = Arrays.copyOf(shaders, shaders.length);
		    schanged = true;
		}
		shash ^= System.identityHashCode(nshaders[slot]) ^ System.identityHashCode(nm);
		nshaders[slot] = nm;
	    }
	}
	VkProgram prog = this.prog;
	if(schanged || (prog == null))
	    prog = env.getprog(shash, nshaders);
	boolean pchanged = (prog != this.prog);
	Object[] nuvals = pchanged ? new Object[prog.uniforms.length] : uvals;
	boolean fdirty = pchanged, ddirty = pchanged;
	if(pchanged) {
	    for(int i = 0; i < prog.uniforms.length; i++)
		nuvals[i] = getuval(prog, i, to);
	} else {
	    boolean[] ch = new boolean[prog.uniforms.length];
	    for(int i = 0; i < pn; i++) {
		int slot = pdirty[i];
		if((prog.umap.length > slot) && (prog.umap[slot] != null)) {
		    for(int ui : prog.umap[slot]) {
			if(!ch[ui]) {
			    ch[ui] = true;
			    Object nv = getuval(prog, ui, to);
			    if(nv != nuvals[ui]) {
				if(prog.uoff[ui] >= 0)
				    udirty = true;
				else
				    tdirty = true;
				nuvals[ui] = nv;
			    }
			}
		    }
		}
		if((prog.fmap.length > slot) && prog.fmap[slot])
		    fdirty = true;
	    }
	}
	for(int i = 0; i < pn; i++) {
	    int slot = pdirty[i];
	    for(int ds : dynslots) {
		if(ds == slot)
		    ddirty = true;
	    }
	}
	Targets ntgt = fdirty ? mktargets(env, prog, to) : tgt;
	Dyn ndyn = ddirty ? new Dyn(to) : dyn;

	for(int i = 0; i < pn; i++) {
	    int slot = pdirty[i];
	    cur[slot] = (slot < ns.length) ? ns[slot] : null;
	}
	this.shaders = nshaders;
	this.shash = shash;
	if(pchanged) {
	    this.prog = prog;
	    udirty = tdirty = true;
	}
	this.uvals = nuvals;
	this.tgt = ntgt;
	this.dyn = ndyn;
    }

    private Object getuval(VkProgram prog, int ui, Pipe pipe) {
	Object val = prog.uniforms[ui].value.apply(pipe);
	if(val == null)
	    throw(new NullPointerException(String.format("tried to set null for uniform %s on %s", prog.uniforms[ui], pipe)));
	return(env.prepuval(val));
    }

    private int ubo() {
	if(prog.ubosize == 0)
	    return(-1);
	if(!udirty && (lastubo >= 0))
	    return(lastubo);
	int off = alloc(prog.ubosize, 256);
	for(int i = 0; i < prog.ubosize; i += 8)
	    arena.putLong(off + i, 0);
	prog.pack(arena, off, uvals);
	lastubo = off;
	udirty = false;
	return(off);
    }

    private Object[] tex() {
	if(!tdirty)
	    return(tex);
	Object[] ret = new Object[prog.samplers.length];
	for(int i = 0; i < ret.length; i++)
	    ret[i] = uvals[prog.samplers[i]];
	tex = ret;
	tdirty = false;
	return(ret);
    }

    /* Render interface */

    public void draw(Pipe pipe, Model data) {
	apply(pipe);
	Geometry geo = ephgeometry(data);
	PipeKey key = prog.pipekey(prog.vkey(data.va.fmt), topology(data.mode), tgt.cfmt, tgt.dfmt, tgt.blend, tgt.cmask);
	cmds.add(new DrawCmd(prog, key, tgt, dyn, tex(), ubo(), geo));
    }

    /* Used by draw lists, which resolve their state themselves. */
    void draw(VkProgram prog, PipeKey key, Targets tgt, Dyn dyn, Object[] tex, ByteBuffer ubo, Geometry geo) {
	int uoff = -1;
	if(ubo != null) {
	    uoff = alloc(prog.ubosize, 256);
	    ByteBuffer dst = arena.duplicate();
	    dst.position(uoff);
	    dst.put(ubo.duplicate());
	}
	cmds.add(new DrawCmd(prog, key, tgt, dyn, tex, uoff, geo));
    }

    public void clear(Pipe pipe, FragData buf, FColor val) {
	apply(pipe);
	int loc = prog.fragidx(buf);
	if(loc < 0)
	    throw(new IllegalArgumentException(String.format("%s is not on current framebuffer", buf)));
	if((tgt.color[loc] == null) || (tgt.cmask[loc] == 0))
	    return;
	cmds.add(new ClearCmd(tgt, loc, val, 0, dyn.sc));
    }

    public void clear(Pipe pipe, double val) {
	apply(pipe);
	if(tgt.depth == null)
	    throw(new IllegalArgumentException("current framebuffer has no depthbuffer"));
	if(!dyn.dwrite)
	    return;
	cmds.add(new ClearCmd(tgt, -1, null, val, dyn.sc));
    }

    public <T extends DataBuffer> void update(T buf, DataBuffer.Filler<? super T> fill) {
	if(buf instanceof Texture.Image) {
	    Texture.Image<?> img = (Texture.Image<?>)buf;
	    VkTexture tex = env.prepare(img.tex);
	    FillBuffer fb = fill.fill(buf, env);
	    ByteBuffer data = VkTexture.texels(img.tex, tex.fmt, fb, img.w * img.h * img.d);
	    int layer = 0;
	    if(img instanceof TextureArray.ArrayImage)
		layer = ((TextureArray.ArrayImage<?>)img).layer;
	    else if(img instanceof TextureCube.CubeImage)
		layer = ((TextureCube.CubeImage)img).face.ordinal();
	    cmds.add(new TexUpdateCmd(tex, img.level, layer, img.w, img.h, img.d, data, fb));
	    return;
	}
	update(buf, fill, 0, buf.size());
    }

    public <T extends DataBuffer> void update(T buf, DataBuffer.PartFiller<? super T> fill, int from, int to) {
	update(buf, (DataBuffer.Filler<? super T>)fill, from, to);
    }

    @SuppressWarnings("unchecked")
    private <T extends DataBuffer> void update(T buf, DataBuffer.Filler<? super T> fill, int from, int to) {
	VkBuf vb;
	if(buf instanceof Model.Indices)
	    vb = env.prepare((Model.Indices)buf);
	else if(buf instanceof VertexArray.Buffer)
	    vb = env.prepare((VertexArray.Buffer)buf);
	else if(buf instanceof Texture.Image)
	    throw(new IllegalArgumentException("partial texture updates are not supported"));
	else
	    throw(new IllegalArgumentException("updating buffer of type: " + buf.getClass().getName()));
	FillBuffer fb;
	if((from == 0) && (to == buf.size()))
	    fb = fill.fill(buf, env);
	else
	    fb = ((DataBuffer.PartFiller<? super T>)fill).fill(buf, env, from, to);
	cmds.add(new BufUpdateCmd(vb, vb.convoff(from), vb.convert(VkFillBuffer.data(fb)), fb));
    }

    public void pget(Pipe pipe, FragData buf, Area area, VectorFormat fmt, ByteBuffer dstbuf, Consumer<ByteBuffer> callback) {
	if(dstbuf.remaining() < fmt.size() * area.area())
	    throw(new IllegalArgumentException("destination buffer needs at least " + fmt.size() * area.area() + " bytes, has only " + dstbuf.remaining()));
	apply(pipe);
	int loc = prog.fragidx(buf);
	if((loc < 0) || (tgt.color[loc] == null))
	    throw(new IllegalArgumentException(String.format("%s is not on current framebuffer", buf)));
	Object img = tgt.color[loc];
	cmds.add(new PGetCmd(img, (img instanceof Attach) ? ((Attach)img).level : 0, area, fmt, dstbuf, callback));
    }

    public void pget(Texture.Image img, VectorFormat fmt, ByteBuffer dstbuf, Consumer<ByteBuffer> callback) {
	int dsz = fmt.size() * img.w * img.h * img.d;
	if(dstbuf.remaining() < dsz)
	    throw(new IllegalArgumentException("destination buffer needs at least " + dsz + " bytes, has only " + dstbuf.remaining()));
	if(!(img.tex instanceof Texture2D))
	    throw(new IllegalArgumentException("texture-get for " + img.tex.getClass()));
	VkTexture tex = env.prepare(img.tex);
	cmds.add(new PGetCmd(new Attach(tex, img.level), img.level, Area.sized(Coord.z, Coord.of(img.w, img.h)), fmt, dstbuf, callback));
    }

    public void submit(Render sub) {
	if(!(sub instanceof VkRender) || (((VkRender)sub).env != env))
	    throw(new IllegalArgumentException("environment mismatch"));
	VkRender vsub = (VkRender)sub;
	if(vsub.empty()) {
	    vsub.dispose();
	    return;
	}
	cmds.add(new SubCmd(vsub));
    }

    public void timestamp(Consumer<Long> callback) {
	cmds.add(new TimestampCmd(callback));
    }

    public void fence(Runnable callback) {
	cmds.add(new FenceCmd(callback));
    }

    public void swapbuffers(boolean vsync) {
	cmds.add(new SwapCmd(vsync));
    }

    void abort() {
	for(Cmd cmd : cmds)
	    cmd.abort();
    }

    public void dispose() {
	if(disposed.getAndSet(true))
	    return;
	seq.dispose();
	if(arena != null) {
	    env.putarena(arena);
	    arena = null;
	}
    }

    public String toString() {
	return(String.format("#<vk-render %d cmds>", cmds.size()));
    }
}
