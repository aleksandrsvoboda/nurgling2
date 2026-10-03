package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Atmosphere effects in the scene (graphics options, Vulkan only):
 *
 *  - Wet ground: surfaces darken in rain, and ground facing the sky
 *    gets a soft material-dependent sheen. Grass stays matte;
 *    drying takes a while after the rain stops.
 *  - Fire glow: warm lights (fires, torches, braziers) cast a soft
 *    pool of light around themselves, flickering with the fire.
 *  - Water's lighting inputs (the transparent surface itself lives in
 *    WaterSurface and reads this frame's color/depth).
 *
 * One state (Env) in the map view's basic state carries the slowly
 * changing values; anything animated runs off the shaders' clock. A
 * new Env is made only when those values change noticeably.
 */
public class Atmos {
    /* Settings. */
    public static volatile boolean wet = false, glow = false, water = false;
    /* Settled snow (a setting, like the above). */
    public static volatile boolean snow = false;

    public static boolean set(boolean wet, boolean glow, boolean water) {
	boolean ch = (wet != Atmos.wet) || (glow != Atmos.glow) || (water != Atmos.water);
	Atmos.wet = wet;
	Atmos.glow = glow;
	Atmos.water = water;
	return(ch);
    }

    public static final State.Slot<Env> slot = new State.Slot<>(State.Slot.Type.DRAW, Env.class);

    /* History: last frame's color and linear view distance. */
    public static class History {
	public final Texture2D.Sampler2D col, dep;
	/* Projection parameters, as NPostFX.DEPTHLIB. */
	public final float[] pp, pr;

	public History(Texture2D.Sampler2D col, Texture2D.Sampler2D dep, float[] pp, float[] pr) {
	    this.col = col; this.dep = dep; this.pp = pp; this.pr = pr;
	}
    }

    public static class Env extends State {
	/* Wetness and settled snow: 0..1. */
	public final float wet;
	public float snow = 0;
	public final boolean glow, water;
	public final float[] sundir, suncol, skycol;
	public final History hist;
	/* The sun's (or moon's) index in the scene's light list. */
	public final int sunidx;

	public Env(float wet, boolean glow, boolean water, int sunidx, float[] sundir, float[] suncol, float[] skycol, History hist) {
	    this.wet = wet; this.glow = glow; this.water = water; this.sunidx = sunidx;
	    this.sundir = sundir; this.suncol = suncol; this.skycol = skycol;
	    this.hist = hist;
	}

	public boolean on() {
	    return(Atmos.wet || Atmos.snow || glow || water);
	}

	/* The shader follows the settings, not the weather: a shower
	 * starting only changes values, and never
	 * makes every lit object's shader be built again. */
	public ShaderMacro shader() {return(Shader.get(Atmos.wet, glow, Atmos.snow));}
	public void apply(Pipe p) {p.put(slot, this);}

	/* Whether this differs enough from that to be worth a new state. */
	public boolean differs(Env that) {
	    return((that == null) || (Math.abs(wet - that.wet) > 0.03f) ||
		   (glow != that.glow) || (water != that.water) || (hist != that.hist) || (sunidx != that.sunidx) ||
		   (Math.abs(snow - that.snow) > 0.03f) ||
		   diff(sundir, that.sundir, 0.02f) || diff(suncol, that.suncol, 0.03f) || diff(skycol, that.skycol, 0.03f));
	}

	private static boolean diff(float[] a, float[] b, float e) {
	    for(int i = 0; i < a.length; i++) {
		if(Math.abs(a[i] - b[i]) > e)
		    return(true);
	    }
	    return(false);
	}
    }

    static final Env none = new Env(0, false, false, -1, new float[] {0, 0, 1}, new float[] {1, 1, 1}, new float[] {1, 1, 1}, null);
    private static Env env(Pipe p) {
	Env e = p.get(slot);
	return((e == null) ? none : e);
    }

    static final Uniform uwet = new Uniform(FLOAT, p -> env(p).wet, slot);
    static final Uniform usnow = new Uniform(FLOAT, p -> env(p).snow, slot);
    static final Uniform usuncol = new Uniform(VEC3, p -> env(p).suncol, slot);
    static final Uniform uskycol = new Uniform(VEC3, p -> env(p).skycol, slot);
    /* The sky's up direction, in view space. */
    static final Uniform uup = new Uniform(VEC3, p -> {
	    Camera cam = p.get(Homo3D.cam);
	    if(cam == null)
		return(new float[] {0, 1, 0});
	    float[] e = cam.fin(Matrix4f.id).mul4(new float[] {0, 0, 1, 0});
	    float l = (float)Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
	    return(new float[] {e[0] / l, e[1] / l, e[2] / l});
	}, Homo3D.cam);

    /** Porous terrain absorbs rain; only paving has a continuous reflective film. */
    public static class WetSurface extends State {
        public static final Slot<WetSurface> slot = new Slot<>(Slot.Type.DRAW, WetSurface.class);
        public static final WetSurface VEGETATION = new WetSurface(.22f, 0, 6);
        public static final WetSurface SOIL = new WetSurface(.32f, .14f, 7);
        public static final WetSurface PAVING = new WetSurface(.28f, 1.10f, 24);
        private static final WetSurface OBJECT = new WetSurface(.22f, .28f, 10);
        final float[] response;
        private WetSurface(float darken, float sheen, float exponent) {
            response = new float[]{darken, sheen, exponent};
        }
        public static WetSurface ground(String name) {
            return terrain(name);
        }
        public static WetSurface terrain(String name) {
            if(name.startsWith("gfx/tiles/paving/")) return PAVING;
            if(name.contains("dirt") || name.contains("plow") || name.contains("sand") || name.contains("rock")) return SOIL;
            return VEGETATION;
        }
        public ShaderMacro shader() {return null;}
        public void apply(Pipe p) {p.put(slot, this);}
    }
    static final Uniform uwetsurface = new Uniform(VEC3, p -> {
        WetSurface surface = p.get(WetSurface.slot);
        return (surface == null ? WetSurface.OBJECT : surface).response;
    }, WetSurface.slot);

    /* Follow stone relief, fading unresolved normal detail instead of painting glossy patches. */
    static final RawFunction wetfn = new RawFunction(VEC4, "hv_wet", 9,
	"vec4 hv_wet(vec4 col, vec3 ep, vec3 en, vec3 up, vec3 L, vec3 sc, vec3 sky, float wet, vec3 surface)\n" +
	"{\n" +
	"    float w = clamp(wet, 0.0, 1.0);\n" +
	"    float nl = length(en);\n" +
	"    if(nl < 0.01)\n" +
	"        return(col);\n" +
	"    vec3 detail = en / nl;\n" +
	"    vec3 n = detail;\n" +
	"    vec3 gn = cross(dFdx(ep), dFdy(ep));\n" +
	"    if(length(gn) > 0.000001) {\n" +
	"        gn = normalize(gn);\n" +
	"        n = dot(gn, n) < 0.0 ? -gn : gn;\n" +
	"    }\n" +
	"    float face = dot(n, up);\n" +
	"    float up1 = smoothstep(0.55, 0.95, face);\n" +
	"    vec3 c = col.rgb * (1.0 - surface.x * w * (0.4 + 0.6 * up1));\n" +
	"    float paving = smoothstep(0.3, 0.5, surface.y);\n" +
	"    vec3 dx = dFdx(detail), dy = dFdy(detail);\n" +
	"    float variance = max(dot(dx,dx), dot(dy,dy));\n" +
	"    float resolved = 1.0 - smoothstep(0.03, 0.30, variance);\n" +
	"    n = normalize(mix(n, detail, 0.75 * paving * resolved));\n" +
	"    vec3 v = normalize(-ep);\n" +
	"    vec3 h = (v + L) / max(length(v + L), 0.0001);\n" +
	"    float spec = pow(max(dot(n, h), 0.0), surface.z) * max(dot(n, L), 0.0);\n" +
	"    float fres = 0.25 + 0.75 * pow(1.0 - max(dot(n, v), 0.0), 3.0);\n" +
	"    c += w * up1 * surface.y * (spec * sc * mix(0.35, 0.65, paving) + sky * mix(0.16 + 0.32 * fres, 0.12 + 0.18 * fres, paving));\n" +
	"    return(vec4(c, col.a));\n" +
	"}\n");

    /* Settled snow: upward faces turn white, patchy at the edges,
     * before lighting so it is lit and shadowed like the ground. */
    static final RawFunction snowfn = new RawFunction(VEC4, "hv_snow", 5,
	"vec4 hv_snow(vec4 col, vec3 en, vec3 up, vec3 mp, float s)\n" +
	"{\n" +
	"    float nl = length(en);\n" +
	"    if(nl < 0.01)\n" +
	"        return(col);\n" +
	"    float face = dot(en / nl, up);\n" +
	"    float nz = hv_ffbm(vec3(mp.xy * 0.18, 3.7));\n" +
	"    float cov = smoothstep(0.3, 0.75, face + (nz - 0.5) * 0.5 - (1.0 - s) * 0.9) * min(s * 1.5, 1.0);\n" +
	"    vec3 sc = vec3(0.9, 0.93, 1.0) * (0.92 + 0.12 * nz);\n" +
	"    return(vec4(mix(col.rgb, sc, cov * 0.93), col.a));\n" +
	"}\n");

    static class Shader implements ShaderMacro {
	final boolean wet, glow, snow;

	Shader(boolean wet, boolean glow, boolean snow) {
	    this.wet = wet; this.glow = glow; this.snow = snow;
	}

	public void modify(ProgramContext prog) {
	    Phong ph = prog.getmod(Phong.class);
	    if((ph == null) || !ph.pfrag)
		return;
	    FireFX.noisedef.define(prog.fctx);
	    if(glow) {
		/* Warm point lights (fires) add a soft pool of their own
		 * color, shaped by their reach and point shadows. */
		ph.dolight.mod(() -> {
			Expression ls = ph.dolight.ls;
			Expression warm = and(gt(pick(fref(ls, "pos"), "w"), l(0.5)),
					      gt(pick(fref(ls, "dif"), "r"), mul(pick(fref(ls, "dif"), "b"), l(1.4))));
			ph.dolight.lmod(new If(warm, stmt(aadd(ph.dolight.diff, mul(pick(fref(ph.dolight.mat, "amb"), "rgb"),
										    pick(fref(ls, "dif"), "rgb"),
										    ph.dolight.lvl.ref(), l(0.45))))));
		    }, 20);
	    }
	    if(snow) {
		snowfn.define(prog.fctx);
		ValBlock.Value sen = Homo3D.frageyen(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> snowfn.call(in, sen.depref(), uup.ref(), Homo3D.fragmapv.ref(), usnow.ref()), 200);
	    }
	    if(wet) {
		wetfn.define(prog.fctx);
		ValBlock.Value en = Homo3D.frageyen(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> wetfn.call(in, Homo3D.frageyev.ref(), en.depref(), uup.ref(),
								 GroundRelief.usun.ref(), usuncol.ref(), uskycol.ref(),
								 uwet.ref(), uwetsurface.ref()), 1100);
	    }
	}

	public int hashCode() {return((wet ? 1 : 0) | (glow ? 2 : 0) | (snow ? 4 : 0));}
	public boolean equals(Object o) {
	    return((o instanceof Shader) && (((Shader)o).hashCode() == hashCode()));
	}

	private static final Shader[] cache = new Shader[8];
	static synchronized ShaderMacro get(boolean wet, boolean glow, boolean snow) {
	    int i = (wet ? 1 : 0) | (glow ? 2 : 0) | (snow ? 4 : 0);
	    if(i == 0)
		return(null);
	    if(cache[i] == null)
		cache[i] = new Shader(wet, glow, snow);
	    return(cache[i]);
	}
    }

    /* Water */

    private static Texture2D.Sampler2D dummy = null;
    /* Stands in for history textures that are not there yet. */
    static synchronized Texture2D.Sampler2D dummy() {
	if(dummy == null) {
	    byte[] px = new byte[4];
	    dummy = new Texture2D(1, 1, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), DataBuffer.Filler.of(px)).sampler();
	}
	return(dummy);
    }

    private static History hist(Pipe p) {
	Env e = p.get(slot);
	return((e == null) ? null : e.hist);
    }

    static final Uniform uhcol = new Uniform(SAMPLER2D, p -> {History h = hist(p); return((h == null) ? dummy() : h.col);}, slot);
    static final Uniform uhdep = new Uniform(SAMPLER2D, p -> {History h = hist(p); return((h == null) ? dummy() : h.dep);}, slot);
    static final Uniform uhpp = new Uniform(VEC4, p -> {History h = hist(p); return((h == null) ? new float[4] : h.pp);}, slot);
    static final Uniform uhpr = new Uniform(VEC3, p -> {
	    History h = hist(p);
	    return((h == null) ? new float[3] : new float[] {h.pr[0], h.pr[1], 1});
	}, slot);

    /* Soft particles: how far (0..1) a particle at eye position e is
     * in front of what the last frame showed behind it, over len. */
    static final RawFunction softfn = new RawFunction(FLOAT, "hv_soft", 5,
	"float hv_soft(vec3 e, sampler2D hd, vec4 pp, vec3 pr, float len)\n" +
	"{\n" +
	"    if(pr.z < 0.5)\n" +
	"        return(1.0);\n" +
	"    float dist = -e.z;\n" +
	"    if(dist <= 0.1)\n" +
	"        return(1.0);\n" +
	"    vec2 ndc = (pp.z > 0.5) ? vec2(e.x * pr.x, e.y * pr.y) : vec2(e.x * pr.x / dist, e.y * pr.y / dist);\n" +
	"    vec2 uv = ndc * 0.5 + 0.5;\n" +
	"    if((uv.x < 0.0) || (uv.x > 1.0) || (uv.y < 0.0) || (uv.y > 1.0))\n" +
	"        return(1.0);\n" +
	"    return(clamp((texture(hd, uv).r - dist) / len, 0.0, 1.0));\n" +
	"}\n");

    public static Expression soft(ProgramContext prog, Expression eye, Expression len) {
	softfn.define(prog.fctx);
	return(softfn.call(eye, uhdep.ref(), uhpp.ref(), uhpr.ref(), len));
    }

}
