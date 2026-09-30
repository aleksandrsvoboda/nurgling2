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
import haven.render.vk.VkRender.*;

/*
 * A persistent, sorted draw list. As in GLDrawList, uniform values,
 * render targets and fixed-function state are held in settings
 * shared between slots and keyed by the state groups they depend on,
 * so a RenderTree update only recomputes what actually changed (a
 * moving camera updates one camera setting, not every slot). Each
 * slot then keeps its packed uniform block and pipeline key cached,
 * refreshing them only when one of its settings has a new version.
 */
public class VkDrawList implements DrawList {
    public final VkEnvironment env;
    public Object desc;
    private final Map<SettingKey, DepSetting> settings = new HashMap<>();
    private final Map<Slot<? extends Rendered>, DrawSlot> slotmap = new IdentityHashMap<>();
    private final Map<Pipe, Object> psettings = new IdentityHashMap<>();
    private final Map<Pipe, Object> orderidx = new IdentityHashMap<>();
    private final TreeSet<DrawSlot> order;
    private boolean disposed = false;
    private static final AtomicLong uniqid = new AtomicLong();
    /* Slots whose programs should be rebuilt; see refresh(). */
    private final Set<Slot<? extends Rendered>> stale = new LinkedHashSet<>();
    private static final int REBUILD_PER_FRAME = 300;

    VkDrawList(VkEnvironment env) {
	this.env = env;
	this.order = new TreeSet<>(this::compare);
    }

    private int compare(DrawSlot a, DrawSlot b) {
	int c;
	if((c = Rendered.Order.cmp.compare(a.gorder, b.gorder)) != 0)
	    return(c);
	if((c = Utils.sidcmp(a.prog, b.prog)) != 0)
	    return(c);
	if((c = Utils.sidcmp(a.tgt, b.tgt)) != 0)
	    return(c);
	if((c = Utils.sidcmp(a.geo, b.geo)) != 0)
	    return(c);
	return(Long.compare(a.sortid, b.sortid));
    }

    /* Settings (as in GLDrawList) */

    static class SettingKey {
	final VkProgram prog;
	final Object vid;
	final Pipe depid_1;
	final Pipe[] depid_v;

	SettingKey(VkProgram prog, Object vid, Pipe... depid) {
	    this.prog = prog;
	    this.vid = vid;
	    if(depid.length == 1) {
		this.depid_1 = depid[0];
		this.depid_v = null;
	    } else {
		this.depid_1 = null;
		this.depid_v = depid;
	    }
	}

	public int ndeps() {
	    return((depid_v == null) ? 1 : depid_v.length);
	}

	public int hashCode() {
	    int rv = System.identityHashCode(prog);
	    rv = (rv * 31) + System.identityHashCode(vid);
	    if(depid_v == null) {
		rv = (rv * 31) + System.identityHashCode(depid_1);
	    } else {
		for(int i = 0; i < depid_v.length; i++)
		    rv = (rv * 31) + System.identityHashCode(depid_v[i]);
	    }
	    return(rv);
	}

	public boolean equals(Object o) {
	    if(!(o instanceof SettingKey))
		return(false);
	    SettingKey that = (SettingKey)o;
	    if((this.prog != that.prog) || (this.vid != that.vid))
		return(false);
	    if(depid_v == null) {
		if(this.depid_1 != that.depid_1)
		    return(false);
	    } else {
		if((that.depid_v == null) || (this.depid_v.length != that.depid_v.length))
		    return(false);
		for(int i = 0; i < depid_v.length; i++) {
		    if(this.depid_v[i] != that.depid_v[i])
			return(false);
		}
	    }
	    return(true);
	}
    }

    private static Pipe nidx(GroupPipe st, int idx) {
	return((idx < 0) ? Pipe.nil : st.group(idx));
    }

    static Pipe[] makedepid(GroupPipe state, Collection<State.Slot<?>> deps) {
	Iterator<State.Slot<?>> it = deps.iterator();
	if(!it.hasNext())
	    return(new Pipe[0]);
	int one = state.gstate(it.next().id);
	int ni = 1;
	while(it.hasNext()) {
	    int cid = it.next().id;
	    int grp = state.gstate(cid);
	    if(grp != one) {
		Pipe[] ret = new Pipe[deps.size()];
		for(int i = 0; i < ni; i++)
		    ret[i] = nidx(state, one);
		ret[ni++] = nidx(state, grp);
		while(it.hasNext())
		    ret[ni++] = nidx(state, state.gstate(it.next().id));
		return(ret);
	    }
	    ni++;
	}
	return(new Pipe[] {nidx(state, one)});
    }

    abstract class DepSetting {
	final SettingKey key;
	int rc = 0;
	int ver = 0;

	DepSetting(SettingKey key) {
	    this.key = key;
	}

	abstract State.Slot<?>[] depslots();
	abstract void compute();

	void update() {
	    compute();
	    ver++;
	}

	private int depmask_1 = -1;
	private int[] depmask_v = null;
	void ckupdate(int[] mask) {
	    if((depmask_v == null) && (depmask_1 < 0)) {
		State.Slot<?>[] slots = depslots();
		if(slots.length == 1) {
		    depmask_1 = slots[0].id;
		} else {
		    int[] depmask = new int[slots.length];
		    for(int i = 0; i < slots.length; i++)
			depmask[i] = slots[i].id;
		    depmask_v = depmask;
		}
	    }
	    if(depmask_v == null) {
		for(int i = 0; i < mask.length; i++) {
		    if(mask[i] == depmask_1) {
			update();
			return;
		    }
		}
	    } else {
		for(int i = 0; i < mask.length; i++) {
		    for(int o = 0; o < depmask_v.length; o++) {
			if(mask[i] == depmask_v[o]) {
			    update();
			    return;
			}
		    }
		}
	    }
	}

	Pipe compstate() {
	    if(key.depid_v == null)
		return(key.depid_1);
	    State.Slot<?>[] depslots = depslots();
	    return(new Pipe() {
		    public <T extends State> T get(State.Slot<T> slot) {
			for(int i = 0; i < depslots.length; i++) {
			    if(depslots[i] == slot)
				return(key.depid_v[i].get(slot));
			}
			throw(new RuntimeException("reading non-dependent slot"));
		    }

		    public Pipe copy() {throw(new UnsupportedOperationException());}
		    public State[] states() {throw(new UnsupportedOperationException());}
		});
	}

	void put() {
	    if(--rc <= 0) {
		if(rc < 0)
		    throw(new RuntimeException());
		release();
		delsetting(this);
	    }
	}

	void release() {}
    }

    private void delsettingp(DepSetting set, Pipe dp) {
	Object cur = psettings.get(dp);
	if(cur == null) {
	} else if(cur == set) {
	    psettings.remove(dp);
	} else if(cur instanceof DepSetting[]) {
	    DepSetting[] sl = (DepSetting[])cur;
	    int n = -1;
	    find: for(int i = 0; i < sl.length; i++) {
		if(sl[i] == set) {
		    if((i == sl.length - 1) || (sl[i + 1] == null)) {
			sl[i] = null;
			n = i;
			break find;
		    } else {
			for(int o = sl.length - 1; o > i; o--) {
			    if(sl[o] != null) {
				sl[i] = sl[o];
				sl[o] = null;
				n = o;
				break find;
			    }
			}
			throw(new RuntimeException());
		    }
		}
	    }
	    if(n < 0) {
	    } else if(n == 0) {
		throw(new RuntimeException());
	    } else if(n == 1) {
		psettings.put(dp, sl[0]);
	    }
	} else {
	    throw(new RuntimeException());
	}
    }

    private void delsetting(DepSetting set) {
	settings.remove(set.key);
	if(set.key.depid_v != null) {
	    Pipe[] deps = set.key.depid_v;
	    intern: for(int i = 0; i < deps.length; i++) {
		for(int o = 0; o < i; o++) {
		    if(deps[i] == deps[o])
			continue intern;
		}
		delsettingp(set, deps[i]);
	    }
	} else if(set.key.depid_1 != null) {
	    delsettingp(set, set.key.depid_1);
	}
    }

    private void addsettingp(DepSetting set, Pipe dp) {
	Object cur = psettings.get(dp);
	if(cur == null) {
	    psettings.put(dp, set);
	} else if(cur instanceof DepSetting) {
	    if(cur == set)
		throw(new RuntimeException());
	    psettings.put(dp, new DepSetting[] {(DepSetting)cur, set});
	} else if(cur instanceof DepSetting[]) {
	    DepSetting[] sl = (DepSetting[])cur;
	    for(int i = 0; i < sl.length; i++) {
		if(sl[i] == set)
		    throw(new RuntimeException());
	    }
	    if(sl[sl.length - 1] == null) {
		for(int i = sl.length - 1; i > 0; i--) {
		    if(sl[i - 1] != null) {
			sl[i] = set;
			return;
		    }
		}
		throw(new RuntimeException());
	    } else {
		DepSetting[] nsl = Arrays.copyOf(sl, sl.length + 1);
		nsl[sl.length] = set;
		psettings.put(dp, nsl);
	    }
	} else {
	    throw(new RuntimeException());
	}
    }

    private void addsetting(DepSetting set) {
	settings.put(set.key, set);
	if(set.key.depid_v != null) {
	    Pipe[] deps = set.key.depid_v;
	    intern: for(int i = 0; i < deps.length; i++) {
		for(int o = 0; o < i; o++) {
		    if(deps[i] == deps[o])
			continue intern;
		}
		addsettingp(set, deps[i]);
	    }
	} else if(set.key.depid_1 != null) {
	    addsettingp(set, set.key.depid_1);
	}
    }

    private static void getrefs(Object val) {
	if(val instanceof TexBind) {
	    ((TexBind)val).tex.get();
	} else if(val instanceof TexBind[]) {
	    for(TexBind tb : (TexBind[])val) {
		if(tb != null)
		    tb.tex.get();
	    }
	}
    }

    private static void putrefs(Object val) {
	if(val instanceof TexBind) {
	    ((TexBind)val).tex.put();
	} else if(val instanceof TexBind[]) {
	    for(TexBind tb : (TexBind[])val) {
		if(tb != null)
		    tb.tex.put();
	    }
	}
    }

    class UniformSetting extends DepSetting {
	final VkProgram prog;
	final Uniform var;
	Object val;
	/* std140 bytes of the value, packed once for all slots
	 * sharing this setting; null for samplers. */
	final byte[] packed;
	private final ByteBuffer pbuf;

	UniformSetting(SettingKey key) {
	    super(key);
	    this.prog = key.prog;
	    this.var = (Uniform)key.vid;
	    if(VkProgram.samplerp(var.type)) {
		this.packed = null;
		this.pbuf = null;
	    } else {
		this.packed = new byte[VkProgram.std140(var.type)[0]];
		this.pbuf = ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
	    }
	    update();
	}

	void compute() {
	    Object nval = var.value.apply(compstate());
	    if(nval == null)
		throw(new NullPointerException("tried to set null for uniform " + var));
	    nval = env.prepuval(nval);
	    getrefs(nval);
	    Object pval = this.val;
	    this.val = nval;
	    putrefs(pval);
	    if(packed != null) {
		Arrays.fill(packed, (byte)0);
		VkProgram.pack(pbuf, 0, var.type, nval, var);
	    }
	}

	State.Slot<?>[] depslots() {
	    return(var.deps.toArray(new State.Slot<?>[0]));
	}

	void release() {
	    putrefs(val);
	    val = null;
	}
    }

    DepSetting getuniform(VkProgram prog, Uniform var, GroupPipe state) {
	SettingKey key = new SettingKey(prog, var, makedepid(state, var.deps));
	DepSetting ret = settings.get(key);
	if(ret == null)
	    addsetting(ret = new UniformSetting(key));
	ret.rc++;
	return(ret);
    }

    private static State.Slot<?>[] progfslots(VkProgram prog) {
	int n = 1;
	for(FragData var : prog.fragdata)
	    n += var.deps.size();
	State.Slot<?>[] ret = new State.Slot<?>[n];
	n = 0;
	ret[n++] = DepthBuffer.slot;
	for(FragData var : prog.fragdata) {
	    for(State.Slot<?> dep : var.deps)
		ret[n++] = dep;
	}
	return(ret);
    }

    private static void attrefs(Targets t, boolean get) {
	if(t == null)
	    return;
	for(Object c : t.color) {
	    if(c instanceof Attach) {
		if(get) ((Attach)c).tex.get(); else ((Attach)c).tex.put();
	    }
	}
	if(t.depth instanceof Attach) {
	    if(get) ((Attach)t.depth).tex.get(); else ((Attach)t.depth).tex.put();
	}
    }

    class TargetSetting extends DepSetting {
	final VkProgram prog;
	Targets val;

	TargetSetting(SettingKey key) {
	    super(key);
	    this.prog = key.prog;
	    update();
	}

	void compute() {
	    Targets nval = VkRender.mktargets(env, prog, compstate());
	    attrefs(nval, true);
	    Targets pval = this.val;
	    this.val = nval;
	    attrefs(pval, false);
	}

	State.Slot<?>[] depslots() {
	    return(progfslots(prog));
	}

	void release() {
	    attrefs(val, false);
	    val = null;
	}
    }

    DepSetting gettarget(VkProgram prog, GroupPipe state) {
	SettingKey key = new SettingKey(prog, TargetSetting.class, makedepid(state, Arrays.asList(progfslots(prog))));
	DepSetting ret = settings.get(key);
	if(ret == null)
	    addsetting(ret = new TargetSetting(key));
	ret.rc++;
	return(ret);
    }

    private static final List<State.Slot<?>> dynslots = Arrays.asList(
	States.viewport, States.scissor, States.facecull, States.depthtest,
	States.maskdepth.slot, States.linewidth, States.depthbias);

    class DynSetting extends DepSetting {
	Dyn val;

	DynSetting(SettingKey key) {
	    super(key);
	    update();
	}

	void compute() {
	    val = new Dyn(compstate());
	}

	State.Slot<?>[] depslots() {
	    return(dynslots.toArray(new State.Slot<?>[0]));
	}
    }

    DepSetting getdyn(GroupPipe state) {
	SettingKey key = new SettingKey(null, DynSetting.class, makedepid(state, dynslots));
	DepSetting ret = settings.get(key);
	if(ret == null)
	    addsetting(ret = new DynSetting(key));
	ret.rc++;
	return(ret);
    }

    /* Slots */

    /* A slot's program is still being built (see getprogasync). */
    static class NotReady extends RuntimeException {
	public Throwable fillInStackTrace() {return(this);}
    }

    private class DrawSlot {
	final long sortid = uniqid.getAndIncrement();
	final Slot<? extends Rendered> bk;
	final VkProgram prog;
	final UniformSetting[] unis;
	TargetSetting tgt;
	DynSetting dyn;
	Geometry geo;
	VkProgram.VertexKey vk;
	int topo;
	Rendered.Order gorder = Rendered.deflt;
	final Pipe ordersrc;
	/* Caches */
	PipeKey key;
	int keyver = -1;
	final ByteBuffer ubo;
	final byte[] uboa;
	final int[] uver;
	Object[] tex;
	final int[] tver;
	private boolean disposed = false;

	/* A placeholder, drawing nothing, while the program builds. */
	DrawSlot(Slot<? extends Rendered> bk, boolean pending) {
	    this.bk = bk;
	    this.prog = null;
	    this.unis = new UniformSetting[0];
	    this.uboa = null;
	    this.ubo = null;
	    this.uver = new int[0];
	    this.tver = new int[0];
	    this.ordersrc = null;
	}

	DrawSlot(Slot<? extends Rendered> bk) {
	    this.bk = bk;
	    GroupPipe bst = bk.state();
	    State[] st = bst.states();
	    ShaderMacro[] shaders = new ShaderMacro[st.length];
	    int shash = 0;
	    for(int i = 0; i < st.length; i++) {
		shaders[i] = (st[i] == null) ? null : st[i].shader();
		shash ^= System.identityHashCode(shaders[i]);
	    }
	    VkProgram prog = env.getprogasync(shash, shaders);
	    if(prog == null)
		throw(new NotReady());
	    this.prog = prog;
	    prog.lock();
	    this.unis = new UniformSetting[prog.uniforms.length];
	    this.uboa = (prog.ubosize > 0) ? new byte[prog.ubosize] : null;
	    this.ubo = (uboa != null) ? ByteBuffer.wrap(uboa).order(ByteOrder.nativeOrder()) : null;
	    this.uver = new int[prog.uniforms.length];
	    this.tver = new int[prog.samplers.length];
	    Arrays.fill(uver, -1);
	    Arrays.fill(tver, -1);
	    Pipe ordersrc = null;
	    try {
		this.tgt = (TargetSetting)gettarget(prog, bst);
		this.dyn = (DynSetting)getdyn(bst);
		for(int i = 0; i < unis.length; i++)
		    unis[i] = (UniformSetting)getuniform(prog, prog.uniforms[i], bst);
		int grp = bst.gstate(Rendered.order.id);
		if(grp >= 0) {
		    ordersrc = bst.group(grp);
		    gorder = ordersrc.get(Rendered.order);
		}
		/* A Rendered may draw nothing (as GLDrawList allows);
		 * such slots stay in the list but are skipped. */
		SlotRender g = new SlotRender(this);
		bk.obj().draw(bst, g);
	    } catch(RuntimeException exc) {
		this.ordersrc = null;
		release();
		throw(exc);
	    }
	    this.ordersrc = ordersrc;
	    if(ordersrc != null)
		orderreg();
	}

	void refresh() {
	    Targets tv = tgt.val;
	    if(keyver != tgt.ver) {
		key = prog.pipekey(vk, topo, tv.cfmt, tv.dfmt, tv.blend, tv.cmask);
		keyver = tgt.ver;
	    }
	    boolean tch = false;
	    for(int i = 0; i < prog.samplers.length; i++) {
		UniformSetting u = unis[prog.samplers[i]];
		if(tver[i] != u.ver) {
		    tch = true;
		    tver[i] = u.ver;
		}
	    }
	    if(tch || (tex == null)) {
		Object[] nt = new Object[prog.samplers.length];
		for(int i = 0; i < nt.length; i++)
		    nt[i] = unis[prog.samplers[i]].val;
		tex = nt;
	    }
	    if(ubo != null) {
		for(int i = 0; i < unis.length; i++) {
		    UniformSetting u = unis[i];
		    if((prog.uoff[i] >= 0) && (uver[i] != u.ver)) {
			System.arraycopy(u.packed, 0, uboa, prog.uoff[i], u.packed.length);
			uver[i] = u.ver;
		    }
		}
	    }
	}

	@SuppressWarnings("unchecked")
	private void orderreg() {
	    Object cur = orderidx.get(ordersrc);
	    if(cur == null) {
		orderidx.put(ordersrc, this);
	    } else if(cur instanceof DrawSlot) {
		List<DrawSlot> nl = new ArrayList<>(2);
		nl.add((DrawSlot)cur);
		nl.add(this);
		orderidx.put(ordersrc, nl);
	    } else {
		((List<DrawSlot>)cur).add(this);
	    }
	}

	@SuppressWarnings("unchecked")
	private void orderunreg() {
	    Object cur = orderidx.get(ordersrc);
	    if(cur == this) {
		orderidx.remove(ordersrc);
	    } else if(cur instanceof List) {
		List<DrawSlot> ls = (List<DrawSlot>)cur;
		ls.remove(this);
		if(ls.size() < 2)
		    orderidx.put(ordersrc, ls.get(0));
	    }
	}

	void orderupdate() {
	    Rendered.Order norder = ordersrc.get(Rendered.order);
	    if(Rendered.Order.cmp.compare(gorder, norder) == 0) {
		gorder = norder;
		return;
	    }
	    order.remove(this);
	    gorder = norder;
	    order.add(this);
	}

	private void release() {
	    if(tgt != null) tgt.put();
	    if(dyn != null) dyn.put();
	    for(UniformSetting u : unis) {
		if(u != null)
		    u.put();
	    }
	    if(geo != null)
		geo.put();
	    if(prog != null)
		prog.unlock();
	}

	void dispose() {
	    if(disposed)
		throw(new IllegalStateException());
	    disposed = true;
	    if(ordersrc != null)
		orderunreg();
	    release();
	}
    }

    class SlotRender implements Render {
	final DrawSlot slot;
	private boolean done;

	SlotRender(DrawSlot slot) {
	    this.slot = slot;
	}

	public Environment env() {return(env);}

	public void draw(Pipe st, Model mod) {
	    if(done)
		throw(new IllegalStateException("Can only render once in drawlist"));
	    if(st != slot.bk.state())
		throw(new IllegalArgumentException("Must render with state from rendertree"));
	    for(VertexArray.Buffer buf : mod.va.bufs) {
		if(buf.usage == DataBuffer.Usage.EPHEMERAL)
		    throw(new IllegalArgumentException("ephemeral models in drawlist"));
	    }
	    if((mod.ind != null) && (mod.ind.usage == DataBuffer.Usage.EPHEMERAL))
		throw(new IllegalArgumentException("ephemeral models in drawlist"));
	    Geometry geo = VkRender.geometry(env, mod);
	    geo.get();
	    slot.geo = geo;
	    slot.vk = slot.prog.vkey(mod.va.fmt);
	    slot.topo = VkRender.topology(mod.mode);
	    done = true;
	}

	public void submit(Render sub) {throw(new UnsupportedOperationException());}
	public void clear(Pipe pipe, FragData buf, FColor val) {throw(new UnsupportedOperationException());}
	public void clear(Pipe pipe, double val) {throw(new UnsupportedOperationException());}
	public void pget(Pipe pipe, FragData buf, Area area, VectorFormat fmt, ByteBuffer dstbuf, Consumer<ByteBuffer> callback) {throw(new UnsupportedOperationException());}
	public void pget(Texture.Image img, VectorFormat fmt, ByteBuffer dst, Consumer<ByteBuffer> callback) {throw(new UnsupportedOperationException());}
	public void timestamp(Consumer<Long> callback) {throw(new UnsupportedOperationException());}
	public void fence(Runnable callback) {throw(new UnsupportedOperationException());}
	public <T extends DataBuffer> void update(T buf, DataBuffer.PartFiller<? super T> data, int from, int to) {throw(new UnsupportedOperationException());}
	public <T extends DataBuffer> void update(T buf, DataBuffer.Filler<? super T> data) {throw(new UnsupportedOperationException());}
	public void dispose() {}
    }

    /* DrawList interface */

    public void draw(Render r) {
	if(!(r instanceof VkRender) || (((VkRender)r).env != env))
	    throw(new IllegalArgumentException());
	VkRender g = (VkRender)r;
	synchronized(this) {
	    if(!stale.isEmpty())
		rebuild(REBUILD_PER_FRAME);
	    for(DrawSlot s : order) {
		if(s.geo == null)
		    continue;
		s.refresh();
		/* A new pipeline is made in the background; the slot
		 * shows up once it is ready. */
		if(!s.prog.pipeready(s.key))
		    continue;
		g.draw(s.prog, s.key, s.tgt.val, s.dyn.val, s.tex, s.ubo, s.geo);
	    }
	}
    }

    /* Rebuilds every slot's program, a few per frame, e.g. after a
     * state's shader changed with a graphics option. */
    public void refresh() {
	synchronized(this) {
	    stale.addAll(slotmap.keySet());
	}
    }

    private void rebuild(int max) {
	List<Slot<? extends Rendered>> retry = new ArrayList<>();
	Iterator<Slot<? extends Rendered>> it = stale.iterator();
	for(int n = 0; it.hasNext() && (n < max); n++) {
	    Slot<? extends Rendered> slot = it.next();
	    it.remove();
	    if(!slotmap.containsKey(slot))
		continue;
	    try {
		if(!tryupdate(slot))
		    retry.add(slot);
	    } catch(Loading l) {
		/* Keep the current slot until what it waits for is loaded. */
		retry.add(slot);
	    }
	}
	stale.addAll(retry);
    }

    public void add(Slot<? extends Rendered> slot) {
	synchronized(this) {
	    if(disposed)
		throw(new IllegalStateException());
	    /* A slot that cannot be built yet (its program is being
	     * built, or a texture it uses is still loading) is kept as
	     * a placeholder drawing nothing and retried each frame; the
	     * list never throws Loading at those adding to it. */
	    DrawSlot dslot;
	    try {
		dslot = new DrawSlot(slot);
	    } catch(NotReady | Loading e) {
		dslot = new DrawSlot(slot, true);
		stale.add(slot);
	    }
	    order.add(dslot);
	    if(slotmap.put(slot, dslot) != null)
		throw(new AssertionError());
	}
    }

    public void remove(Slot<? extends Rendered> slot) {
	synchronized(this) {
	    DrawSlot dslot = slotmap.remove(slot);
	    if(dslot == null)
		throw(new IllegalStateException(String.format("removing non-present slot (%s)", slot.obj())));
	    stale.remove(slot);
	    order.remove(dslot);
	    dslot.dispose();
	}
    }

    public void update(Slot<? extends Rendered> slot) {
	synchronized(this) {
	    /* Until the new program is built, or what the slot's
	     * uniforms wait for has loaded, it keeps drawing as before
	     * and is retried each frame. */
	    try {
		if(!tryupdate(slot))
		    stale.add(slot);
	    } catch(Loading l) {
		stale.add(slot);
	    }
	}
    }

    /* Replaces the slot's drawing; false (keeping the old one) while
     * its new program is still being built. */
    private boolean tryupdate(Slot<? extends Rendered> slot) {
	DrawSlot dslot;
	try {
	    dslot = new DrawSlot(slot);
	} catch(NotReady e) {
	    return(false);
	}
	DrawSlot old = slotmap.remove(slot);
	if(old == null)
	    throw(new IllegalStateException(String.format("updating non-present slot (%s)", slot.obj())));
	order.remove(old);
	old.dispose();
	order.add(dslot);
	slotmap.put(slot, dslot);
	return(true);
    }

    @SuppressWarnings("unchecked")
    private void orderupdate(Pipe group) {
	Object reg = orderidx.get(group);
	if(reg == null) {
	} else if(reg instanceof DrawSlot) {
	    ((DrawSlot)reg).orderupdate();
	} else if(reg instanceof List) {
	    for(DrawSlot slot : new ArrayList<>((List<DrawSlot>)reg))
		slot.orderupdate();
	}
    }

    public void update(Pipe group, int[] mask) {
	synchronized(this) {
	    Object reg = psettings.get(group);
	    if(reg == null) {
	    } else if(reg instanceof DepSetting) {
		((DepSetting)reg).ckupdate(mask);
	    } else if(reg instanceof DepSetting[]) {
		for(DepSetting set : (DepSetting[])reg) {
		    if(set == null)
			break;
		    set.ckupdate(mask);
		}
	    }
	    for(int i = 0; i < mask.length; i++) {
		if(mask[i] == Rendered.order.id)
		    orderupdate(group);
	    }
	}
    }

    private final Disposable lck = Finalizer.leakcheck(this);
    public void dispose() {
	lck.dispose();
	synchronized(this) {
	    for(DrawSlot slot : new ArrayList<>(order))
		slot.dispose();
	    order.clear();
	    slotmap.clear();
	    stale.clear();
	    disposed = true;
	}
    }

    public String stats() {
	return(String.format("%,d", order.size()));
    }

    public String toString() {
	return(String.format("#<vk-drawlist %s>%s", env, (desc == null) ? "" : " (" + desc + ")"));
    }

    public VkDrawList desc(Object desc) {
	this.desc = desc;
	return(this);
    }
}
