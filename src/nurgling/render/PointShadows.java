package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Shadows from point lights: torches, campfires, lamps. The game only
 * gives the sun and moon a shadow map; this renders one for each of
 * the few point lights nearest the view.
 *
 * Each light gets a strip of six depth maps, covering every direction.
 * Casters are the same lit objects that cast sun shadows, culled
 * to those within the light's reach (instanced batches, such as a
 * forest of one tree kind, are always drawn). Geometry right around
 * the light, such as the torch itself or the logs of a fire, is left
 * out so it does not black out its own light.
 *
 * The lighting shader already has every light's current position, so
 * the scene's state only changes when the set of shadowed lights does,
 * not as a torch moves.
 */
public class PointShadows implements Disposable {
    /* Settings: how many lights cast shadows (0 = off), and the size
     * of each side's depth map. */
    static final int FACES = 6;
    static final float NEAR = 1.0f, EXCL = 3.0f, MAXREACH = 330f;
    /* About how far from the view's center the screen reaches. */
    static final float VIEW = 180f;
    /* Lights set at ground level (braziers, fires) are placed at
     * the flame for their shadows, and their fixture is left out. */
    static final float LOWLIGHT = 4f, LIFT = 7f, FIXTURE = 9f;

    /* Ground height at a world-space point. */
    public interface Ground {
	float z(float x, float y);
    }

    /* Forward and up of each side, in world space (z up). */
    static final Coord3f[] FWD = {Coord3f.of(1, 0, 0), Coord3f.of(-1, 0, 0), Coord3f.of(0, 1, 0), Coord3f.of(0, -1, 0), Coord3f.of(0, 0, -1), Coord3f.of(0, 0, 1)};
    static final Coord3f[] UP = {Coord3f.of(0, 0, 1), Coord3f.of(0, 0, 1), Coord3f.of(0, 0, 1), Coord3f.of(0, 0, 1), Coord3f.of(0, 1, 0), Coord3f.of(0, -1, 0)};

    static Matrix4f faceview(int f, Coord3f l) {
	Coord3f F = FWD[f], U = UP[f], R = F.cmul(U);
	return(new Matrix4f( R.x,  R.y,  R.z, -R.dmul(l),
			     U.x,  U.y,  U.z, -U.dmul(l),
			    -F.x, -F.y, -F.z,  F.dmul(l),
			       0,    0,    0,  1));
    }

    /* How far a light reaches, from its attenuation and cut-off. */
    public static float reach(PosLight l) {
	float at = (PosLight.atoverride != 0) ? PosLight.atoverride : l.at;
	if(at <= 0)
	    return(MAXREACH);
	float k = (1.0f / at) - l.ac;
	float d;
	if(l.aq > 0)
	    d = (float)((-l.al + Math.sqrt(l.al * l.al + 4 * l.aq * k)) / (2 * l.aq));
	else if(l.al > 0)
	    d = k / l.al;
	else
	    d = MAXREACH;
	return(Math.max(10, Math.min(d, MAXREACH)));
    }

    /* The shadow lookup. v is the vector from the light to the
     * point, in world space; the side is picked by its major axis. */
    static final RawFunction look = new RawFunction(FLOAT, "hv_pshadow", 5,
	"float hv_pcompare(sampler2D map, ivec2 at, ivec2 low, ivec2 high, vec3 receiver, vec2 grad, vec2 ts) {\n" +
	"    ivec2 tap = clamp(at,low,high);\n" +
	"    float z = receiver.z + dot(grad, (vec2(tap) + 0.5) * ts - receiver.xy);\n" +
	"    return step(z, texelFetch(map, tap, 0).r);\n" +
	"}\n" +
	"float hv_pshadow(sampler2D map, vec3 v, float n, float f, vec2 ts)\n" +
	"{\n" +
	"    vec3 a = abs(v);\n" +
	"    float m, face;\n" +
	"    vec2 q;\n" +
	"    if((a.z >= a.x) && (a.z >= a.y)) {\n" +
	"        if(v.z > 0.0) {m = v.z; q = vec2(v.x, -v.y); face = 5.0;}\n" +
	"        else {m = -v.z; q = vec2(v.x, v.y); face = 4.0;}\n" +
	"    } else if(a.x >= a.y) {\n" +
	"        if(v.x > 0.0) {m = v.x; q = vec2(-v.y, v.z); face = 0.0;}\n" +
	"        else {m = -v.x; q = vec2(v.y, v.z); face = 1.0;}\n" +
	"    } else {\n" +
	"        if(v.y > 0.0) {m = v.y; q = vec2(v.x, v.z); face = 2.0;}\n" +
	"        else {m = -v.y; q = vec2(-v.x, v.z); face = 3.0;}\n" +
	"    }\n" +
	"    q /= max(m, n);\n" +
	"    vec2 base = vec2((face + 0.5 + 0.5 * q.x) / 6.0, 0.5 + 0.5 * q.y);\n" +
	"    int size = textureSize(map,0).y;\n" +
	"    ivec2 low=ivec2(int(face)*size,0), high=low+ivec2(size-1);\n" +
	"    float bias = 0.08 + 0.003 * m;\n" +
	"    float receiver = 0.5 + 0.5 * ((f+n)/(f-n) - (2.0*f*n)/((f-n)*max(n,m-bias)));\n" +
	"    float plane = 0.5 + 0.5 * ((f+n)/(f-n) - (2.0*f*n)/((f-n)*max(n,m)));\n" +
	"    vec3 p = vec3(base, plane), dx = dFdx(p), dy = dFdy(p);\n" +
	"    float det = dx.x * dy.y - dx.y * dy.x;\n" +
	"    vec2 grad = vec2(0.0);\n" +
	"    /* Depth is planar in each perspective face. Correct every PCF texel\n" +
	"     * separately, including bilinear taps, to avoid striped self-shadowing. */\n" +
	"    if(abs(det) > 1e-12 && abs(dFdx(face)) < 0.5 && abs(dFdy(face)) < 0.5)\n" +
	"        grad = clamp(vec2(dy.y * dx.z - dx.y * dy.z, dx.x * dy.z - dy.x * dx.z) / det, vec2(-8.0), vec2(8.0));\n" +
	"    if((m >= f) || (m <= n)) return 1.0;\n" +
	"    vec3 ref = vec3(base, receiver);\n" +
	"    const vec2 pd[8] = vec2[8](vec2(-0.613, 0.354), vec2(0.170, -0.713), vec2(0.747, 0.271), vec2(-0.259, -0.281),\n" +
	"        vec2(0.320, 0.815), vec2(-0.859, -0.338), vec2(0.536, -0.183), vec2(-0.110, 0.290));\n" +
	"    float r = clamp(0.75 + 0.008 * m, 0.75, 2.5);\n" +
	"    float lit = 0.0;\n" +
	"    for(int i = 0; i < 8; i++) {\n" +
	"        vec2 uv = base + pd[i] * ts * r;\n" +
	"        vec2 grid=uv/ts-0.5, w=fract(grid); ivec2 at=ivec2(floor(grid));\n" +
	"        lit += mix(mix(hv_pcompare(map,at,low,high,ref,grad,ts),hv_pcompare(map,at+ivec2(1,0),low,high,ref,grad,ts),w.x),\n" +
	"                   mix(hv_pcompare(map,at+ivec2(0,1),low,high,ref,grad,ts),hv_pcompare(map,at+ivec2(1),low,high,ref,grad,ts),w.x),w.y);\n" +
	"    }\n" +
	"    return(lit / 8.0);\n" +
	"}\n");

    /* The scene state: which lights have shadows, and their maps. */
    public static final State.Slot<Lit> slot = new State.Slot<>(State.Slot.Type.DRAW, Lit.class);
    public static class Lit extends State {
	final int[] idx;
	final float[] far, lift;
	final Texture2D.Sampler2D[] maps;
	final int n, fres;

	Lit(int n, int fres, int[] idx, float[] far, float[] lift, Texture2D.Sampler2D[] maps) {
	    this.n = n; this.fres = fres;
	    this.idx = idx; this.far = far; this.lift = lift; this.maps = maps;
	}

	public ShaderMacro shader() {return(Shader.get(n, fres));}
	public void apply(Pipe p) {p.put(slot, this);}

	public String toString() {return("#<point-shadows " + Arrays.toString(idx) + ">");}
    }

    static final Uniform uview = new Uniform(MAT3, p -> {
	    Matrix4f cm = Transform.rxinvert(p.get(Homo3D.cam).fin(Matrix4f.id));
	    return(new float[] {cm.m[0], cm.m[1], cm.m[2], cm.m[4], cm.m[5], cm.m[6], cm.m[8], cm.m[9], cm.m[10]});
	}, Homo3D.cam);

    static class Shader implements ShaderMacro {
	final int n, fres;
	final Uniform[] uidx, ufar, ulift, umap;

	Shader(int n, int fres) {
	    this.n = n; this.fres = fres;
	    uidx = new Uniform[n]; ufar = new Uniform[n]; ulift = new Uniform[n]; umap = new Uniform[n];
	    /* While the number of shadowed lights changes, programs
	     * built for more lights can briefly see the new, shorter
	     * state; the extra ones then read as unshadowed. */
	    for(int i = 0; i < n; i++) {
		final int k = i;
		uidx[i] = new Uniform(INT, p -> {Lit c = p.get(slot); return((k < c.n) ? c.idx[k] : -1);}, slot);
		ufar[i] = new Uniform(FLOAT, p -> {Lit c = p.get(slot); return((k < c.n) ? c.far[k] : 1.0f);}, slot);
		ulift[i] = new Uniform(FLOAT, p -> {Lit c = p.get(slot); return((k < c.n) ? c.lift[k] : 0.0f);}, slot);
		umap[i] = new Uniform(SAMPLER2D, p -> {Lit c = p.get(slot); return(c.maps[Math.min(k, c.n - 1)]);}, slot);
	    }
	}

	public void modify(ProgramContext prog) {
	    Phong ph = prog.getmod(Phong.class);
	    if((ph == null) || !ph.pfrag)
		return;
	    look.define(prog.fctx);
	    ph.dolight.mod(() -> {
		    Expression lp = pick(fref(ph.dolight.ls, "pos"), "xyz");
		    Expression v = mul(uview.ref(), sub(ph.dolight.vert, lp));
		    Expression ts = vec2(l(1.0 / (FACES * fres)), l(1.0 / fres));
		    /* The whole light is shadowed, ambient included: lights
		     * at ground level reach the ground by their ambient
		     * term alone. */
		    for(int k = 0; k < n; k++) {
			Expression lv = sub(v, vec3(l(0.0), l(0.0), ulift[k].ref()));
			ph.dolight.lmod(new If(eq(uidx[k].ref(), ph.dolight.i),
					       stmt(amul(ph.dolight.lvl.tgt, look.call(umap[k].ref(), lv, l(NEAR), ufar[k].ref(), ts)))));
		    }
		}, 10);
	}

	public int hashCode() {return((n * 31) + fres);}
	public boolean equals(Object o) {
	    return((o instanceof Shader) && (((Shader)o).n == n) && (((Shader)o).fres == fres));
	}

	private static final Map<List<Integer>, Shader> interned = new HashMap<>();
	static Shader get(int n, int fres) {
	    synchronized(interned) {
		return(interned.computeIfAbsent(Arrays.asList(n, fres), k -> new Shader(n, fres)));
	    }
	}
    }

    /* Leaves out geometry right around the light (the light sits at
     * the origin of each side's camera). */
    static final State.Slot<Excl> exclslot = new State.Slot<>(State.Slot.Type.DRAW, Excl.class);
    static class Excl extends State {
	static final Map<Float, Excl> interned = new HashMap<>();
	final float r;
	final ShaderMacro sh;

	private Excl(float r) {
	    this.r = r;
	    sh = prog -> prog.fctx.mainmod(blk -> blk.add(new If(lt(length(Homo3D.frageyev.ref()), l(r)), new Discard())), -100);
	}

	static Excl get(float r) {
	    synchronized(interned) {
		return(interned.computeIfAbsent(r, Excl::new));
	    }
	}

	public ShaderMacro shader() {return(sh);}
	public void apply(Pipe p) {p.put(exclslot, this);}
    }

    /* One side's caster list. */
    class Face implements RenderList.Adapter {
	final int f;
	final ProxyPipe basic = new ProxyPipe();
	DefPipe curbasic = null;
	DrawList back = null;
	final Map<RenderList.Slot<? extends Rendered>, FSlot> slots = new HashMap<>();

	Face(int f) {this.f = f;}

	class FSlot implements RenderList.Slot<Rendered>, GroupPipe {
	    final RenderList.Slot<? extends Rendered> bk;

	    FSlot(RenderList.Slot<? extends Rendered> bk) {this.bk = bk;}

	    public Rendered obj() {return(bk.obj());}
	    public GroupPipe state() {return(this);}

	    public Pipe group(int idx) {
		return((idx == 0) ? basic : bk.state().group(idx - 1));
	    }

	    public int gstate(int id) {
		if(State.Slot.byid(id).type == State.Slot.Type.GEOM) {
		    int ret = bk.state().gstate(id);
		    if(ret >= 0)
			return(ret + 1);
		}
		if((id < curbasic.mask.length) && curbasic.mask[id])
		    return(0);
		return(-1);
	    }

	    public int nstates() {
		return(Math.max(bk.state().nstates(), curbasic.mask.length));
	    }
	}

	void add(RenderList.Slot<? extends Rendered> s) {
	    FSlot fs = new FSlot(s);
	    if(slots.put(s, fs) != null)
		throw(new AssertionError());
	    if(back != null)
		back.add(fs);
	}

	void remove(RenderList.Slot<? extends Rendered> s) {
	    FSlot fs = slots.remove(s);
	    if((fs != null) && (back != null))
		back.remove(fs);
	}

	void update(RenderList.Slot<? extends Rendered> s) {
	    FSlot fs = slots.get(s);
	    if((fs != null) && (back != null))
		back.update(fs);
	}

	void basic(Pipe.Op st) {
	    DefPipe buf = new DefPipe();
	    buf.prep(st);
	    int[] mask = basic.dupdate(buf);
	    curbasic = buf;
	    if(back != null)
		back.update(basic, mask);
	}

	void draw(Render out) {
	    if((back == null) || !out.env().compatible(back)) {
		if(back != null)
		    back.dispose();
		back = out.env().drawlist().desc("point-shadow: " + f);
		back.asyncadd(this, Rendered.class);
	    }
	    back.draw(out);
	}

	public Locked lock() {return(casters.lock());}
	public Iterable<? extends RenderList.Slot<?>> slots() {return(slots.values());}
	public <R> void add(RenderList<R> list, Class<? extends R> type) {}
	public void remove(RenderList<?> list) {}

	void dispose() {
	    if(back != null)
		back.dispose();
	    back = null;
	}
    }

    /* The shadow of one light. */
    class Shadow {
	final int fres;
	final Texture2D atlas;
	final Texture2D.Sampler2D samp;
	final Face[] faces = new Face[FACES];
	final Set<RenderList.Slot<? extends Rendered>> active = new HashSet<>();
	final Projection[] proj = {null};
	Light light = null;
	Coord3f pos = null, cpos = null;
	float far = 0, excl = EXCL;

	Shadow(int fres) {
	    this.fres = fres;
	    atlas = new Texture2D(new Coord(fres * FACES, fres), DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
	    samp = new Texture2D.Sampler2D(atlas);
	    samp.magfilter(Texture.Filter.NEAREST).minfilter(Texture.Filter.NEAREST).wrapmode(Texture.Wrapping.CLAMP);
	    for(int f = 0; f < FACES; f++)
		faces[f] = new Face(f);
	}

	boolean near(RenderList.Slot<? extends Rendered> s) {
	    if(pos == null)
		return(false);
	    if(s instanceof InstanceBatch)
		return(true);
	    Matrix4f xf = Homo3D.locxf(s.state());
	    float dx = xf.m[12] - pos.x, dy = xf.m[13] - pos.y, dz = xf.m[14] - pos.z;
	    float r = far + 40;
	    return(((dx * dx) + (dy * dy) + (dz * dz)) < (r * r));
	}

	void enter(RenderList.Slot<? extends Rendered> s) {
	    if(active.add(s)) {
		for(Face f : faces)
		    f.add(s);
	    }
	}

	void leave(RenderList.Slot<? extends Rendered> s) {
	    if(active.remove(s)) {
		for(Face f : faces)
		    f.remove(s);
	    }
	}

	void cull() {
	    for(RenderList.Slot<? extends Rendered> s : casters.all) {
		if(near(s))
		    enter(s);
		else
		    leave(s);
	    }
	}

	/* Points the sides at the light's current position. */
	void place(Light light, Coord3f pos, float far, float excl) {
	    boolean relit = (light != this.light) || (far != this.far) || (excl != this.excl);
	    this.light = light;
	    this.far = far;
	    this.excl = excl;
	    if(!relit && (this.pos != null) && (this.pos.dist(pos) < 0.01f))
		return;
	    this.pos = pos;
	    if(relit || (cpos == null) || (cpos.dist(pos) > 8)) {
		cpos = pos;
		cull();
	    }
	    Projection pj = new Projection(Projection.makefrustum(new Matrix4f(), -NEAR, NEAR, -NEAR, NEAR, NEAR, far));
	    for(int f = 0; f < FACES; f++) {
		faces[f].basic(Pipe.Op.compose(ShadowMap.ShadowList.shadowbasic,
					       new DepthBuffer<>(atlas.image(0)),
					       new States.Viewport(Area.sized(Coord.of(f * fres, 0), Coord.of(fres, fres))),
					       pj, new Camera(faceview(f, pos)), Excl.get(excl), new FrameInfo()));
	    }
	}

	void draw(Render out) {
	    if(pos == null)
		return;
	    Pipe cl = new BufPipe().prep(Pipe.Op.compose(ShadowMap.ShadowList.shadowbasic, new DepthBuffer<>(atlas.image(0)), new States.Viewport(Area.sized(Coord.of(fres * FACES, fres)))));
	    out.clear(cl, 1.0);
	    for(Face f : faces)
		f.draw(out);
	}

	void dispose() {
	    for(Face f : faces)
		f.dispose();
	    atlas.dispose();
	}
    }

    /* All lit objects that could cast a shadow. */
    class Casters implements RenderList<Rendered> {
	final Set<RenderList.Slot<? extends Rendered>> all = new HashSet<>();

	Locked lock() {return(master.lock());}

	boolean casts(RenderList.Slot<? extends Rendered> s) {
	    GroupPipe st = s.state();
	    return((st.get(Light.lighting) != null) && (st.get(ShadowMap.maskshadow.slot) == null));
	}

	public void add(RenderList.Slot<? extends Rendered> s) {
	    if(!casts(s))
		return;
	    all.add(s);
	    for(Shadow sh : shadows) {
		if(sh.near(s))
		    sh.enter(s);
	    }
	}

	public void remove(RenderList.Slot<? extends Rendered> s) {
	    if(all.remove(s)) {
		for(Shadow sh : shadows)
		    sh.leave(s);
	    }
	}

	public void update(RenderList.Slot<? extends Rendered> s) {
	    if(!all.contains(s)) {
		add(s);
		return;
	    }
	    if(!casts(s)) {
		remove(s);
		return;
	    }
	    for(Shadow sh : shadows) {
		if(sh.near(s)) {
		    if(sh.active.contains(s)) {
			for(Face f : sh.faces)
			    f.update(s);
		    } else {
			sh.enter(s);
		    }
		} else {
		    sh.leave(s);
		}
	    }
	}

	public void update(Pipe group, int[] statemask) {
	    for(Shadow sh : shadows) {
		for(Face f : sh.faces) {
		    if(f.back != null)
			f.back.update(group, statemask);
		}
	    }
	}
    }

    private final RenderList.Adapter master;
    private final Casters casters = new Casters();
    private final List<Shadow> shadows = new ArrayList<>();
    private Lit cur = null;

    public PointShadows(RenderList.Adapter master) {
	this.master = master;
	casters.asyncadd(master, Rendered.class);
    }

    public RenderList.Adapter master() {return(master);}

    private static class Cand {
	final Light light;
	final int idx;
	final Coord3f pos;
	/* dist: the light's score (higher is shadowed first). */
	final float far, dist, lift;
	Cand(Light light, int idx, Coord3f pos, float far, float dist, float lift) {
	    this.light = light; this.idx = idx; this.pos = pos; this.far = far; this.dist = dist; this.lift = lift;
	}
    }

    /* Picks the lights nearest to cc (world space) and places their
     * shadows. Returns the scene state, or null when none. */
    public Lit update(Light.LightList lights, Coord3f cc, int n, int fres, Ground ground) {
	List<Cand> cands = new ArrayList<>();
	synchronized(lights.ll) {
	    for(int i = 0; i < lights.ll.size(); i++) {
		RenderList.Slot<Light> ls = lights.ll.get(i);
		if(!(ls.obj() instanceof PosLight))
		    continue;
		PosLight pl = (PosLight)ls.obj();
		float[] p = Homo3D.locxf(ls.state()).mul4(pl.pos);
		Coord3f pos = Coord3f.of(p[0], p[1], p[2]);
		float far = reach(pl);
		float d = pos.dist(cc);
		/* How much of the light's lit circle reaches into the view
		 * around cc, times how bright it is. */
		float reachin = (far + VIEW) - d;
		if(reachin <= 0)
		    continue;
		float lift = 0;
		if(ground != null) {
		    float gz = ground.z(pos.x, pos.y);
		    if(pos.z - gz < LOWLIGHT)
			lift = (gz + LIFT) - pos.z;
		}
		float lum = Math.max(0.05f, (pl.dif[0] + pl.dif[1] + pl.dif[2]) / 3);
		float score = Math.min(reachin, far) * lum;
		/* A light already shadowed keeps it unless another is
		 * clearly better, so shadows do not flick between lights. */
		for(Shadow sh : shadows) {
		    if(sh.light == pl)
			score *= 1.4f;
		}
		cands.add(new Cand(pl, i, pos, far, score, lift));
	    }
	}
	cands.sort(Comparator.comparingDouble(c -> -c.dist));
	try(Locked lk = casters.lock()) {
	    while(shadows.size() > n)
		shadows.remove(shadows.size() - 1).dispose();
	    if(!shadows.isEmpty() && (shadows.get(0).fres != fres)) {
		for(Shadow sh : shadows)
		    sh.dispose();
		shadows.clear();
	    }
	    while(shadows.size() < Math.min(n, cands.size()))
		shadows.add(new Shadow(fres));
	    /* Keep a light on the shadow it already has, so its map and
	     * caster lists need not be rebuilt. */
	    Shadow[] assign = new Shadow[Math.min(n, cands.size())];
	    List<Shadow> free = new ArrayList<>(shadows);
	    for(int i = 0; i < assign.length; i++) {
		for(Shadow sh : free) {
		    if(sh.light == cands.get(i).light) {
			assign[i] = sh;
			free.remove(sh);
			break;
		    }
		}
	    }
	    for(int i = 0; i < assign.length; i++) {
		if(assign[i] == null)
		    assign[i] = free.remove(0);
		Cand c = cands.get(i);
		assign[i].place(c.light, c.pos.add(0, 0, c.lift), c.far, (c.lift > 0) ? FIXTURE : EXCL);
	    }
	    for(Shadow sh : free) {
		sh.light = null;
		sh.pos = null;
		for(RenderList.Slot<? extends Rendered> s : new ArrayList<>(sh.active))
		    sh.leave(s);
	    }
	    /* With no light to shadow, the state stays, shadowing no
	     * light: taking it away would change every lit object's
	     * shader, which then has to be built anew. */
	    int[] idx = new int[n];
	    float[] far = new float[n], lift = new float[n];
	    Texture2D.Sampler2D[] maps = new Texture2D.Sampler2D[n];
	    Arrays.fill(idx, -1);
	    Arrays.fill(far, 1);
	    for(int i = 0; i < n; i++)
		maps[i] = (assign.length > 0) ? assign[i % assign.length].samp : Atmos.dummy();
	    for(int i = 0; i < assign.length; i++) {
		idx[i] = cands.get(i).idx;
		far[i] = cands.get(i).far;
		lift[i] = cands.get(i).lift;
		maps[i] = assign[i].samp;
	    }
	    if((cur == null) || (cur.n != n) || (cur.fres != fres) || !Arrays.equals(cur.idx, idx) ||
	       !Arrays.equals(cur.far, far) || !Arrays.equals(cur.lift, lift) || !Arrays.equals(cur.maps, maps))
		cur = new Lit(n, fres, idx, far, lift, maps);
	    return(cur);
	}
    }

    public void draw(Render out) {
	try(Locked lk = casters.lock()) {
	    for(Shadow sh : shadows)
		sh.draw(out);
	}
    }

    public void dispose() {
	master.remove(casters);
	try(Locked lk = casters.lock()) {
	    for(Shadow sh : shadows)
		sh.dispose();
	    shadows.clear();
	}
    }
}
