package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.nio.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Life around the view (graphics options, Vulkan only):
 *
 *  - Ambient particles: dust motes drifting in daylight, fireflies
 *    blinking low over the ground on warm nights, and leaves blown off
 *    nearby trees in gusts, colored by the season.
 *  - Footsteps: dust kicked up by those running over dry ground, snow
 *    over snow, and splashes in water.
 *  - Wildlife: butterflies over grassland on spring and summer days.
 *  - Lightning in heavy rain: the whole scene flashes (see flash).
 *
 * They follow the camera and ride the same wind as smoke and embers.
 */
public class AmbientFX implements RenderTree.Node, Rendered, TickList.TickNode, TickList.Ticking {
    public static volatile boolean enabled = false, steps = false, wildlife = false, lightning = false;
    /* The rain's rate (0 when dry), and the lightning's flash now (0..1). */
    public static volatile float rainrate = 0, flash = 0;
    static final float R = 150, SIZEMAX = 6f;
    static final int DUST = 0, FLY = 1, LEAF = 2, PUFF = 3, SPLASH = 4, BFLY = 5;
    public static boolean any() {return(enabled || steps || wildlife || lightning);}
    static final Attribute ainfo = new Attribute(VEC4, "ambinfo");
    static final VertexArray.Layout fmt =
	new VertexArray.Layout(new VertexArray.Layout.Input(Homo3D.vertex,     new VectorFormat(3, NumberFormat.FLOAT32), 0,  0, 20),
			       new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.UNORM8),  0, 12, 20),
			       new VertexArray.Layout.Input(ainfo,             new VectorFormat(4, NumberFormat.UNORM8),  0, 16, 20));
    final MapView mv;
    final Random rnd = new Random();
    final List<P> ps = new ArrayList<>();
    final List<RenderTree.Slot> slots = new ArrayList<>(1);
    final List<Coord3f> trees = new ArrayList<>();
    VertexArray va = null;
    Model model = null;
    double treet = 0;
    float leafacc = 0;
    double strike = -10, nextstrike = 0;
    final Map<Long, float[]> stepacc = new HashMap<>();
    /* New particles made while the list is being updated. */
    final List<P> spawned = new ArrayList<>();
    float[] light = {1, 1, 1};

    public AmbientFX(MapView mv) {
	this.mv = mv;
    }

    class P {
	final int kind;
	float x, y, z, xv, yv, zv, t, life, ph, rot, rotv, size;
	float r, g, b, a = 1, z0;

	P(int kind, float x, float y, float z) {
	    this.kind = kind;
	    this.x = x; this.y = y; this.z = z;
	    ph = rnd.nextFloat() * 20;
	    rot = rnd.nextFloat();
	    rotv = (rnd.nextFloat() - 0.5f) * 1.5f;
	}
    }

    private float groundz(float x, float y) {
	try {
	    return((float)mv.glob.map.getcz(x, -y));
	} catch(Loading l) {
	    return(Float.NaN);
	}
    }

    private static final float[][] leafcols = {
	{0.45f, 0.62f, 0.22f}, /* spring */
	{0.30f, 0.48f, 0.16f}, /* summer */
	{0.85f, 0.45f, 0.10f}, /* autumn */
    };

    private void spawn(Coord3f cc, boolean day, boolean night, int season) {
	int ndust = 0, nfly = 0;
	for(P p : ps) {
	    if(p.kind == DUST) ndust++;
	    else if(p.kind == FLY) nfly++;
	}
	if(day) {
	    for(int i = ndust; i < 70; i++) {
		float x = cc.x + (rnd.nextFloat() * 2 - 1) * R, y = cc.y + (rnd.nextFloat() * 2 - 1) * R;
		float gz = groundz(x, y);
		if(Float.isNaN(gz))
		    break;
		P p = new P(DUST, x, y, gz + 2 + rnd.nextFloat() * 35);
		p.life = 6 + rnd.nextFloat() * 8;
		p.size = 0.21f + rnd.nextFloat() * 0.18f;
		p.r = 1.0f; p.g = 0.95f; p.b = 0.85f;
		ps.add(p);
	    }
	}
	if(night && (season != 3)) {
	    for(int i = nfly; i < 45; i++) {
		float x = cc.x + (rnd.nextFloat() * 2 - 1) * R, y = cc.y + (rnd.nextFloat() * 2 - 1) * R;
		float gz = groundz(x, y);
		if(Float.isNaN(gz))
		    break;
		P p = new P(FLY, x, y, gz + 1 + rnd.nextFloat() * 9);
		p.life = 8 + rnd.nextFloat() * 10;
		p.size = 0.33f;
		p.r = 0.85f; p.g = 1.0f; p.b = 0.35f;
		ps.add(p);
	    }
	}
    }

    private void findtrees(Coord3f cc) {
	trees.clear();
	synchronized(mv.glob.oc) {
	    for(Gob gob : mv.glob.oc) {
		try {
		    String nm = (gob.ngob == null) ? null : gob.ngob.name;
		    if((nm == null) || !nm.startsWith("gfx/terobjs/trees/"))
			continue;
		    Coord3f c = gob.getc();
		    Coord3f rc = Coord3f.of(c.x, -c.y, c.z);
		    if(Math.hypot(rc.x - cc.x, rc.y - cc.y) < R)
			trees.add(rc);
		} catch(Loading l) {
		}
	    }
	}
    }

    public TickList.Ticking ticker() {return(this);}

    public void autotick(double ddt) {
	float dt = (float)Math.min(ddt, 0.25);
	Coord3f cc;
	try {
	    cc = mv.getcc().invy();
	} catch(Loading l) {
	    return;
	}
	Astronomy ast = mv.glob.ast;
	boolean night = (ast != null) && ast.night;
	int season = (ast == null) ? 1 : ast.is;
	DirLight sun = mv.amblight;
	float lum = 1;
	if(sun != null) {
	    light = new float[] {Math.min(1.3f, sun.amb[0] + sun.dif[0]), Math.min(1.3f, sun.amb[1] + sun.dif[1]), Math.min(1.3f, sun.amb[2] + sun.dif[2])};
	    lum = (sun.dif[0] + sun.dif[1] + sun.dif[2]) / 3;
	}
	boolean day = !night && (lum > 0.25f);
	if(enabled)
	    spawn(cc, day, night, season);
	if(steps)
	    steps(cc, dt);
	if(wildlife)
	    wildlife(cc, day, season);
	flash = lightning ? lightning(now()) : 0;
	double now = now();
	if(now - treet > 3) {
	    treet = now;
	    findtrees(cc);
	}
	Coord2d at = new Coord2d(cc.x, -cc.y);
	Coord3f wm = FireFX.gust(at);
	/* Map-space wind to render space. */
	Coord3f wind = Coord3f.of(wm.x, -wm.y, 0);
	float gusty = (float)Math.max(0, (Math.hypot(wm.x, wm.y) - 4) / 10);
	if(enabled && day && (season != 3) && !trees.isEmpty()) {
	    leafacc += dt * gusty * Math.min(trees.size(), 20) * 0.9f;
	    while(leafacc >= 1) {
		leafacc -= 1;
		Coord3f t = trees.get(rnd.nextInt(trees.size()));
		float a = rnd.nextFloat() * (float)Math.PI * 2, r = rnd.nextFloat() * 14;
		P p = new P(LEAF, t.x + (float)Math.cos(a) * r, t.y + (float)Math.sin(a) * r, t.z + 25 + rnd.nextFloat() * 20);
		p.life = 12;
		p.size = 0.54f + rnd.nextFloat() * 0.24f;
		float[] c = leafcols[Math.min(season, 2)];
		float v = 0.8f + rnd.nextFloat() * 0.4f;
		p.r = c[0] * v; p.g = c[1] * v; p.b = c[2] * v;
		if((season == 2) && rnd.nextBoolean()) {
		    p.r = 0.9f * v; p.g = 0.7f * v; p.b = 0.15f;
		}
		ps.add(p);
	    }
	}
	for(Iterator<P> i = ps.iterator(); i.hasNext();) {
	    P p = i.next();
	    p.t += dt;
	    switch(p.kind) {
	    case DUST:
		p.xv += dt * (((wind.x * 0.25f) - p.xv) * 0.5f + (float)Utils.fgrandoom(rnd) * 0.8f);
		p.yv += dt * (((wind.y * 0.25f) - p.yv) * 0.5f + (float)Utils.fgrandoom(rnd) * 0.8f);
		p.zv += dt * ((-p.zv * 0.5f) + (float)Utils.fgrandoom(rnd) * 0.6f);
		break;
	    case FLY:
		p.xv += dt * ((-p.xv * 0.8f) + (float)Math.sin((p.t * 1.3f) + p.ph) * 4f);
		p.yv += dt * ((-p.yv * 0.8f) + (float)Math.cos((p.t * 1.1f) + p.ph) * 4f);
		p.zv += dt * ((-p.zv * 0.8f) + (float)Math.sin((p.t * 0.7f) + p.ph * 2) * 2f);
		break;
	    case PUFF:
		p.xv += dt * ((wind.x * 0.25f - p.xv) * 1.5f);
		p.yv += dt * ((wind.y * 0.25f - p.yv) * 1.5f);
		p.zv += dt * (-p.zv * 2.0f);
		break;
	    case SPLASH:
		p.zv -= dt * 28f;
		if(p.z < p.z0 - 0.3f)
		    p.t = p.life;
		break;
	    case BFLY:
		/* A butterfly's wandering, bobbing flight. */
		p.rot += dt * (float)Utils.fgrandoom(rnd) * 2.5f;
		p.xv = (float)Math.cos(p.rot * 6.28f) * 4f + wind.x * 0.15f;
		p.yv = (float)Math.sin(p.rot * 6.28f) * 4f + wind.y * 0.15f;
		p.zv = (float)Math.sin((p.t * 2.2f) + p.ph) * 2.5f;
		break;
	    case LEAF:
		/* Tumbling down, carried by the wind, with a flutter. */
		p.xv += dt * ((wind.x - p.xv) * 0.9f + (float)Math.sin((p.t * 3.1f) + p.ph) * 6f);
		p.yv += dt * ((wind.y - p.yv) * 0.9f + (float)Math.cos((p.t * 2.7f) + p.ph) * 6f);
		p.zv += dt * ((-3.5f - p.zv) * 1.2f + (float)Math.sin((p.t * 4.3f) + p.ph) * 5f);
		p.rot += p.rotv * dt;
		break;
	    }
	    p.x += p.xv * dt;
	    p.y += p.yv * dt;
	    p.z += p.zv * dt;
	    boolean dead = p.t > p.life;
	    if(Math.hypot(p.x - cc.x, p.y - cc.y) > R * 1.3f)
		dead = true;
	    if(p.kind == LEAF) {
		float gz = groundz(p.x, p.y);
		if(!Float.isNaN(gz) && (p.z < gz + 0.3f)) {
		    p.z = gz + 0.3f;
		    if(p.life > p.t + 1.5f)
			p.life = p.t + 1.5f;
		    p.xv = p.yv = p.zv = 0;
		}
	    }
	    if(dead)
		i.remove();
	}
	/* Splashes made during the update, added once it is done. */
	ps.addAll(spawned);
	spawned.clear();
    }

    private double now() {
	return(Utils.rtime());
    }

    private void splash(float x, float y, float z, int n) {
	for(int i = 0; i < n; i++) {
	    P p = new P(SPLASH, x, y, z);
	    float a = rnd.nextFloat() * 6.28f, v = 1.5f + rnd.nextFloat() * 3.5f;
	    p.xv = (float)Math.cos(a) * v; p.yv = (float)Math.sin(a) * v; p.zv = 5f + rnd.nextFloat() * 6f;
	    p.z0 = z; p.life = 1.2f; p.t = 0.6f;
	    p.size = 0.22f + rnd.nextFloat() * 0.15f;
	    p.r = 0.85f * light[0]; p.g = 0.92f * light[1]; p.b = 1.0f * light[2];
	    spawned.add(p);
	}
    }

    /* Dust, snow and splashes kicked up by those on the move. */
    private void steps(Coord3f cc, float dt) {
	Set<Long> seen = new HashSet<>();
	synchronized(mv.glob.oc) {
	    for(Gob gob : mv.glob.oc) {
		String nm = (gob.ngob == null) ? null : gob.ngob.name;
		if((nm == null) || !(nm.startsWith("gfx/borka/") || nm.startsWith("gfx/kritter/")))
		    continue;
		Moving m = gob.getattr(Moving.class);
		if(m == null)
		    continue;
		Coord2d rc = gob.rc;
		if((rc == null) || (Math.hypot(rc.x - cc.x, -rc.y - cc.y) > R * 0.8))
		    continue;
		double v;
		Coord3f c;
		try {
		    v = m.getv();
		    c = gob.getc();
		} catch(Loading l) {
		    continue;
		}
		if(v < 2)
		    continue;
		int kind = TileKinds.kind(TileKinds.at(mv.glob.map, rc));
		if(kind == TileKinds.OTHER)
		    continue;
		seen.add(gob.id);
		float[] acc = stepacc.computeIfAbsent(gob.id, k -> new float[1]);
		acc[0] += dt * (float)Math.min(v, 30) * ((kind == TileKinds.WATER) ? 0.25f : 0.12f);
		while(acc[0] >= 1) {
		    acc[0] -= 1;
		    float x = c.x + (rnd.nextFloat() - 0.5f) * 2, y = -c.y + (rnd.nextFloat() - 0.5f) * 2;
		    if(kind == TileKinds.WATER) {
			splash(x, y, c.z + 0.3f, 3);
		    } else {
			P p = new P(PUFF, x, y, c.z + 0.5f);
			p.xv = (rnd.nextFloat() - 0.5f) * 3; p.yv = (rnd.nextFloat() - 0.5f) * 3; p.zv = 1.5f + rnd.nextFloat() * 1.5f;
			p.life = 1.0f + rnd.nextFloat() * 0.6f;
			p.size = 1.4f + rnd.nextFloat() * 0.8f;
			if(kind == TileKinds.SNOW) {
			    p.r = 0.95f; p.g = 0.97f; p.b = 1.0f; p.a = 0.7f;
			} else {
			    p.r = 0.62f; p.g = 0.52f; p.b = 0.40f; p.a = 0.45f;
			}
			p.r *= light[0]; p.g *= light[1]; p.b *= light[2];
			ps.add(p);
		    }
		}
	    }
	}
	stepacc.keySet().retainAll(seen);
    }

    /* Butterflies. */
    private void wildlife(Coord3f cc, boolean day, int season) {
	int nb = 0;
	for(P p : ps) {
	    if(p.kind == BFLY) nb++;
	}
	if(day && (season <= 1)) {
	    for(int i = nb; i < 10; i++) {
		float x = cc.x + (rnd.nextFloat() * 2 - 1) * R * 0.8f, y = cc.y + (rnd.nextFloat() * 2 - 1) * R * 0.8f;
		if(TileKinds.grass(TileKinds.at(mv.glob.map, new Coord2d(x, -y))) == null)
		    break;
		float gz = groundz(x, y);
		if(Float.isNaN(gz))
		    break;
		P p = new P(BFLY, x, y, gz + 2 + rnd.nextFloat() * 4);
		p.life = 25 + rnd.nextFloat() * 20;
		p.size = 1.1f;
		float[][] cols = {{1.0f, 0.95f, 0.9f}, {0.95f, 0.8f, 0.2f}, {0.9f, 0.45f, 0.15f}, {0.55f, 0.65f, 1.0f}};
		float[] c = cols[rnd.nextInt(cols.length)];
		p.r = c[0]; p.g = c[1]; p.b = c[2];
		ps.add(p);
	    }
	}
    }

    /* Lightning: now and then in heavy rain, a double flash. */
    private float lightning(double now) {
	if(rainrate > 0) {
	    if(nextstrike == 0)
		nextstrike = now + 5 + rnd.nextFloat() * 20;
	    if(now > nextstrike) {
		float heavy = Math.min(rainrate / 300f, 1f);
		strike = now;
		nextstrike = now + (12 + rnd.nextFloat() * 40) / Math.max(heavy, 0.15f);
	    }
	} else {
	    nextstrike = 0;
	}
	double t = now - strike;
	if((t < 0) || (t > 0.7))
	    return(0);
	float f = (float)(Math.exp(-t * 18) + ((t > 0.2) ? 0.8 * Math.exp(-(t - 0.2) * 9) : 0));
	return(Math.min(f, 1));
    }

    public void autogtick(Render g) {
	int n = ps.size();
	if(n < 1) {
	    dispose();
	    return;
	}
	if((va == null) || (va.bufs[0].size() < n * fmt.inputs[0].stride)) {
	    if(va != null)
		va.dispose();
	    va = new VertexArray(fmt, new VertexArray.Buffer(Math.max(64, n * 2) * fmt.inputs[0].stride, DataBuffer.Usage.STREAM, null)).shared();
	}
	g.update(va.bufs[0], this::fill);
	if((model == null) || (model.n != n)) {
	    if(model != null)
		model.dispose();
	    model = new Model(Model.Mode.POINTS, va, null, 0, n);
	    for(RenderTree.Slot slot : slots)
		slot.update();
	}
    }

    private static byte ub(float v) {
	return((byte)Math.round(Math.max(0, Math.min(1, v)) * 255));
    }

    private FillBuffer fill(VertexArray.Buffer dst, Environment env) {
	FillBuffer ret = env.fillbuf(dst);
	ByteBuffer buf = ret.push();
	for(P p : ps) {
	    buf.putFloat(p.x).putFloat(p.y).putFloat(p.z);
	    float fin = ((p.kind == PUFF) || (p.kind == SPLASH)) ? 0.1f : 1.2f;
	    float fade = Math.min(1, Math.min(p.t / fin, (p.life - p.t) / 1.2f)) * p.a;
	    float r = p.r, g = p.g, b = p.b, a = fade;
	    float size = p.size, turn = p.rot;
	    if(p.kind == PUFF) {
		size = p.size * (1 + p.t * 1.6f);
		a *= 1 - Math.min(1, p.t / p.life);
	    } else if(p.kind == BFLY) {
		turn = (p.t * 9f) + p.ph;
	    }
	    if(p.kind == DUST) {
		/* Faint specks, seen where sunlight catches them. */
		r *= light[0]; g *= light[1]; b *= light[2];
		a *= 0.35f + 0.25f * (float)Math.sin((p.t * 2.3f) + p.ph);
	    } else if(p.kind == FLY) {
		a *= Math.max(0, (float)Math.sin((p.t * 1.7f) + p.ph)) * 0.9f + 0.1f;
	    } else {
		r *= light[0]; g *= light[1]; b *= light[2];
	    }
	    buf.put(ub(r)).put(ub(g)).put(ub(b)).put(ub(a));
	    buf.put(ub(p.kind / 5f)).put(ub(turn - (float)Math.floor(turn))).put(ub(size / SIZEMAX)).put((byte)0);
	}
	return(ret);
    }

    public void draw(Pipe state, Render out) {
	if(model != null)
	    out.draw(state, model);
    }

    public void dispose() {
	if(model != null) {
	    model.dispose();
	    model = null;
	}
	if(va != null) {
	    va.dispose();
	    va = null;
	}
    }

    static final AutoVarying vinfo = new AutoVarying(VEC4, "s_ambinfo") {
	    protected Expression root(VertexContext vctx) {
		return(ainfo.ref());
	    }
	};

    /* info = (kind / 5, turn or flap, size / SIZEMAX, 0) */
    static final RawFunction shape = new RawFunction(VEC4, "hv_ambient", 4,
	"vec4 hv_ambient(vec4 col, vec2 pc, vec4 info, float hdr)\n" +
	"{\n" +
	"    vec2 d = pc - vec2(0.5);\n" +
	"    float kind = floor(info.x * 5.0 + 0.5);\n" +
	"    float a;\n" +
	"    vec3 c = col.rgb;\n" +
	"    if(kind > 4.5) {\n" +
	"        /* A butterfly: two wings opening and closing. */\n" +
	"        float w = 0.2 + 0.8 * abs(sin(info.y * 6.2831853));\n" +
	"        vec2 q = vec2(abs(d.x) / max(w, 0.05) - 0.2, d.y);\n" +
	"        float wing = 1.0 - smoothstep(0.8, 1.0, dot(q, q) / 0.05);\n" +
	"        float body = (1.0 - smoothstep(0.02, 0.04, abs(d.x))) * (1.0 - smoothstep(0.18, 0.22, abs(d.y)));\n" +
	"        a = max(wing, body);\n" +
	"        c = mix(c, vec3(0.15), body * (1.0 - wing));\n" +
	"    } else if(kind > 3.5) {\n" +
	"        /* A water droplet. */\n" +
	"        float r2 = dot(d, d) * 4.0;\n" +
	"        a = exp(-r2 * 6.0);\n" +
	"    } else if(kind > 2.5) {\n" +
	"        /* A puff of dust or snow, soft and uneven. */\n" +
	"        float r = length(d) * 2.0;\n" +
	"        float ang = atan(d.y, d.x);\n" +
	"        a = (1.0 - smoothstep(0.4, 1.0, r + 0.12 * sin(ang * 5.0 + info.y * 30.0))) * 0.8;\n" +
	"    } else if(kind > 1.5) {\n" +
	"        /* A leaf: a pointed oval, turning as it tumbles. */\n" +
	"        float ang = info.y * 6.2831853, s = sin(ang), cs = cos(ang);\n" +
	"        vec2 q = vec2(d.x * cs - d.y * s, d.x * s + d.y * cs) * 2.0;\n" +
	"        float e = q.x * q.x / 0.9 + q.y * q.y / 0.22 * (1.0 + 0.8 * abs(q.x));\n" +
	"        a = 1.0 - smoothstep(0.75, 1.0, e);\n" +
	"        c *= 0.85 + 0.3 * q.y;\n" +
	"    } else if(kind > 0.5) {\n" +
	"        /* A firefly: a small glowing point with a halo. */\n" +
	"        float r2 = dot(d, d) * 4.0;\n" +
	"        a = exp(-r2 * 10.0) + exp(-r2 * 2.5) * 0.3;\n" +
	"        if(hdr > 0.5)\n" +
	"            c *= 1.0 + 4.0 * exp(-r2 * 10.0);\n" +
	"    } else {\n" +
	"        float r2 = dot(d, d) * 4.0;\n" +
	"        a = exp(-r2 * 5.0);\n" +
	"    }\n" +
	"    a *= col.a;\n" +
	"    if(a < 0.004)\n" +
	"        discard;\n" +
	"    return(vec4(c, a));\n" +
	"}\n");

    private static ShaderMacro mkprog(boolean hdr) {
	return(prog -> {
		Function pdiv = new Function.Def(FLOAT) {{
		    Expression vec = param(Function.PDir.IN, VEC4).ref();
		    code.add(new Return(div(pick(vec, "x"), pick(vec, "w"))));
		}};
		Homo3D homo = Homo3D.get(prog);
		prog.vctx.ptsz.mod(in -> mul(sub(pdiv.call(homo.pprjxf(add(homo.eyev.depref(), vec4(l(SIZEMAX), l(0.0), l(0.0), l(0.0))))),
						 pdiv.call(prog.vctx.posv.depref())),
					     pick(FrameConfig.u_screensize.ref(), "x"), pick(ainfo.ref(), "z")), 0);
		prog.vctx.ptsz.force();
		shape.define(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> shape.call(in, FragmentContext.ptc, vinfo.ref(), l(hdr ? 1.0 : 0.0)), 100);
	    });
    }
    private static final ShaderMacro[] progs = {mkprog(false), mkprog(true)};

    static class DrawState extends State {
	public static final State.Slot<DrawState> slot = new State.Slot<>(State.Slot.Type.DRAW, DrawState.class);
	public ShaderMacro shader() {return(progs[FireFX.hdr ? 1 : 0]);}
	public void apply(Pipe p) {p.put(slot, this);}
    }
    private static final DrawState draw = new DrawState();

    public void added(RenderTree.Slot slot) {
	slot.ostate(Pipe.Op.compose(States.maskdepth, Rendered.eyesort, VertexColor.instance, draw));
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }
}
