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
 *  - Fair-weather clouds: soft cloud shadows drifting over the land on
 *    days the server sends no clouds of its own.
 *  - Wet ground: surfaces darken in rain, and ground facing the sky
 *    gets a sheen reflecting the sky, sun and moon, broken up by
 *    rain ripples. Drying takes a while after the rain stops.
 *  - Fire glow: warm lights (fires, torches, braziers) cast a soft
 *    pool of light around themselves, flickering with the fire.
 *  - Water: reflections that depend on the viewing angle, tinted to
 *    the time of day, a glint from the sun or moon, ripples driven by
 *    the wind, and reflections of what stands at the shore (from the
 *    previous frame's picture, see NPostFX.History).
 *
 * One state (Env) in the map view's basic state carries the slowly
 * changing values; anything animated runs off the shaders' clock. A
 * new Env is made only when those values change noticeably.
 */
public class Atmos {
    /* Settings. */
    public static volatile boolean clouds = false, wet = false, glow = false, water = false, sway = false;

    public static boolean set(boolean clouds, boolean wet, boolean glow, boolean water, boolean sway) {
	boolean ch = (clouds != Atmos.clouds) || (wet != Atmos.wet) || (glow != Atmos.glow) || (water != Atmos.water) || (sway != Atmos.sway);
	Atmos.clouds = clouds;
	Atmos.wet = wet;
	Atmos.glow = glow;
	Atmos.water = water;
	Atmos.sway = sway;
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
	/* cover: fair-weather cloud cover, 0 for none. wet: 0..1. */
	public final float cover, wet;
	public final boolean glow, water;
	public final float[] sundir, suncol, skycol;
	public final History hist;
	/* The sun's (or moon's) index in the scene's light list. */
	public final int sunidx;

	public Env(float cover, float wet, boolean glow, boolean water, int sunidx, float[] sundir, float[] suncol, float[] skycol, History hist) {
	    this.cover = cover; this.wet = wet; this.glow = glow; this.water = water; this.sunidx = sunidx;
	    this.sundir = sundir; this.suncol = suncol; this.skycol = skycol;
	    this.hist = hist;
	}

	public boolean on() {
	    return((cover > 0) || (wet > 0) || glow || water);
	}

	public ShaderMacro shader() {return(Shader.get(cover > 0, wet > 0, glow));}
	public void apply(Pipe p) {p.put(slot, this);}

	/* Whether this differs enough from that to be worth a new state. */
	public boolean differs(Env that) {
	    return((that == null) || (Math.abs(cover - that.cover) > 0.02f) || (Math.abs(wet - that.wet) > 0.03f) ||
		   (glow != that.glow) || (water != that.water) || (hist != that.hist) || (sunidx != that.sunidx) ||
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

    static final Env none = new Env(0, 0, false, false, -1, new float[] {0, 0, 1}, new float[] {1, 1, 1}, new float[] {1, 1, 1}, null);
    private static Env env(Pipe p) {
	Env e = p.get(slot);
	return((e == null) ? none : e);
    }

    static final Uniform ucover = new Uniform(FLOAT, p -> env(p).cover, slot);
    static final Uniform usunidx = new Uniform(INT, p -> env(p).sunidx, slot);
    static final Uniform uwet = new Uniform(FLOAT, p -> env(p).wet, slot);
    static final Uniform ucdir = new Uniform(VEC2, p -> {
	    float[] d = env(p).sundir;
	    float zf = 1.0f / (d[2] + 1.1f);
	    return(new float[] {-d[0] * zf, -d[1] * zf});
	}, slot);
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

    /* Fair-weather clouds: how lit (0..1) a point on the map is. */
    static final RawFunction cloudfn = new RawFunction(FLOAT, "hv_cloudlit", 4,
	"float hv_cloudlit(vec3 mp, vec2 cdir, float t, float cover)\n" +
	"{\n" +
	"    vec2 tc = (mp.xy + mp.z * cdir) / 600.0 + vec2(0.007, -0.004) * t;\n" +
	"    float c = hv_ffbm(vec3(tc, t * 0.002));\n" +
	"    c = c * 0.75 + hv_ffbm(vec3(tc * 2.7 + vec2(3.1, 7.7), t * 0.004)) * 0.25;\n" +
	"    /* Spread the noise's narrow range over 0..1, so cover is\n" +
	"     * roughly the share of the sky that is cloud. */\n" +
	"    c = clamp((c - 0.47) * 4.0 + 0.5, 0.0, 1.0);\n" +
	"    float th = 1.0 - cover;\n" +
	"    return(mix(1.0, 0.45, smoothstep(th - 0.08, th + 0.1, c)));\n" +
	"}\n");

    /* Wet ground: darker, with a sky and sun sheen where it faces up. */
    static final RawFunction wetfn = new RawFunction(VEC4, "hv_wet", 9,
	"vec4 hv_wet(vec4 col, vec3 ep, vec3 en, vec3 up, vec3 L, vec3 sc, vec3 sky, vec3 mp, vec2 tw)\n" +
	"{\n" +
	"    float w = tw.y;\n" +
	"    float nl = length(en);\n" +
	"    if(nl < 0.01)\n" +
	"        return(col);\n" +
	"    vec3 n = en / nl;\n" +
	"    float face = dot(n, up);\n" +
	"    float up1 = smoothstep(0.55, 0.95, face);\n" +
	"    vec3 c = col.rgb * (1.0 - 0.3 * w * (0.4 + 0.6 * up1));\n" +
	"    /* Rain ripples break up the sheen. */\n" +
	"    float r = hv_ffbm(vec3(mp.xy * 0.35, tw.x * 2.5));\n" +
	"    vec3 v = normalize(-ep);\n" +
	"    vec3 R = reflect(-v, n);\n" +
	"    float spec = pow(max(dot(R, L), 0.0), 70.0) * (0.4 + 1.2 * r);\n" +
	"    float fres = 0.04 + 0.96 * pow(1.0 - max(dot(n, v), 0.0), 5.0);\n" +
	"    c += w * up1 * (spec * sc * 1.4 + (0.12 + fres) * sky * 0.35 * (0.7 + 0.6 * r));\n" +
	"    return(vec4(c, col.a));\n" +
	"}\n");

    static class Shader implements ShaderMacro {
	final boolean clouds, wet, glow;

	Shader(boolean clouds, boolean wet, boolean glow) {
	    this.clouds = clouds; this.wet = wet; this.glow = glow;
	}

	public void modify(ProgramContext prog) {
	    Phong ph = prog.getmod(Phong.class);
	    if((ph == null) || !ph.pfrag)
		return;
	    FireFX.noisedef.define(prog.fctx);
	    if(clouds) {
		cloudfn.define(prog.fctx);
		/* Computed once per fragment, like CloudShadow. */
		ValBlock.Value lit = prog.fctx.uniform.new Value(FLOAT) {
			public Expression root() {
			    return(cloudfn.call(Homo3D.fragmapv.ref(), ucdir.ref(), FrameInfo.time(), ucover.ref()));
			}

			protected void cons2(Block blk) {
			    tgt = new Variable.Global(FLOAT).ref();
			    blk.add(ass(tgt, init));
			}
		    };
		lit.force();
		ph.dolight.mod(() -> {
			ph.dolight.dcalc.add(new If(eq(usunidx.ref(), ph.dolight.i),
						    stmt(amul(ph.dolight.dl.tgt, lit.ref()))),
					     ph.dolight.dcurs);
		    }, 0);
	    }
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
	    if(wet) {
		wetfn.define(prog.fctx);
		ValBlock.Value en = Homo3D.frageyen(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> wetfn.call(in, Homo3D.frageyev.ref(), en.depref(), uup.ref(),
								 GroundRelief.usun.ref(), usuncol.ref(), uskycol.ref(),
								 Homo3D.fragmapv.ref(), vec2(FrameInfo.time(), uwet.ref())), 1100);
	    }
	}

	public int hashCode() {return((clouds ? 1 : 0) | (wet ? 2 : 0) | (glow ? 4 : 0));}
	public boolean equals(Object o) {
	    return((o instanceof Shader) && (((Shader)o).clouds == clouds) && (((Shader)o).wet == wet) && (((Shader)o).glow == glow));
	}

	private static final Shader[] cache = new Shader[8];
	static synchronized ShaderMacro get(boolean clouds, boolean wet, boolean glow) {
	    int i = (clouds ? 1 : 0) | (wet ? 2 : 0) | (glow ? 4 : 0);
	    if(i == 0)
		return(null);
	    if(cache[i] == null)
		cache[i] = new Shader(clouds, wet, glow);
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

    /* Wind ripples on the water, stronger in gusts. */
    static final RawFunction ripfn = new RawFunction(VEC3, "hv_ripple", 4,
	"vec3 hv_ripple(vec3 n, vec3 mp, vec3 up, float t)\n" +
	"{\n" +
	"    float g = hv_gusty(mp.xy, t);\n" +
	"    /* Animated through the noise's time axis with a slight steady\n" +
	"     * drift; gusts only make the ripples stronger. */\n" +
	"    vec2 q = mp.xy * 0.45 + vec2(0.13, 0.07) * t;\n" +
	"    float e = 0.35;\n" +
	"    float h0 = hv_ffbm(vec3(q, t * 0.7));\n" +
	"    float hx = hv_ffbm(vec3(q + vec2(e, 0.0), t * 0.7));\n" +
	"    float hy = hv_ffbm(vec3(q + vec2(0.0, e), t * 0.7));\n" +
	"    float a = 0.04 + 0.12 * g;\n" +
	"    vec3 tx = normalize(cross(up, vec3(0.0, 0.0, 1.0)) + vec3(1e-4));\n" +
	"    vec3 ty = cross(up, tx);\n" +
	"    return(normalize(n - (tx * (hx - h0) + ty * (hy - h0)) / e * a));\n" +
	"}\n");

    /* The water's surface color: sky reflection by viewing angle,
     * tinted to the time of day, a sun or moon glint, and the shore
     * reflected from the last frame's picture. in holds the game's own
     * sky reflection (times 0.4). Blended additively over the bottom. */
    static final RawFunction waterfn = new RawFunction(VEC4, "hv_water", 12,
	"vec4 hv_water(vec4 in0, vec3 ep, vec3 en, vec3 L, vec3 sc, vec3 sky, sampler2D hcol, sampler2D hdep, vec4 pp, vec3 pr, float hon, vec3 up)\n" +
	"{\n" +
	"    vec3 n = normalize(en);\n" +
	"    vec3 v = normalize(-ep);\n" +
	"    float cosv = max(dot(n, v), 0.0);\n" +
	"    float fres = 0.2 + 0.8 * pow(1.0 - cosv, 3.0);\n" +
	"    vec3 refl = in0.rgb * 2.5 * sky;\n" +
	"    vec3 R = reflect(-v, n);\n" +
	"    /* Only rays that leave the water upwards can reflect the shore. */\n" +
	"    if((hon > 0.5) && (dot(R, up) > 0.08)) {\n" +
	"        /* March the reflected ray against the last frame's depth. */\n" +
	"        vec3 p = ep;\n" +
	"        float stp = 2.0;\n" +
	"        for(int i = 0; i < 24; i++) {\n" +
	"            p += R * stp;\n" +
	"            stp *= 1.18;\n" +
	"            float dist = -p.z;\n" +
	"            if(dist <= 0.5)\n" +
	"                break;\n" +
	"            vec2 ndc = (pp.z > 0.5) ? vec2(p.x * pr.x, p.y * pr.y) : vec2(p.x * pr.x / dist, p.y * pr.y / dist);\n" +
	"            vec2 uv = ndc * 0.5 + 0.5;\n" +
	"            if((uv.x < 0.0) || (uv.x > 1.0) || (uv.y < 0.0) || (uv.y > 1.0))\n" +
	"                break;\n" +
	"            float sd = texture(hdep, uv).r;\n" +
	"            if((i > 1) && (dist > sd + 0.5) && (dist < sd + 3.0 + stp * 2.0)) {\n" +
	"                vec2 ed = min(uv, 1.0 - uv);\n" +
	"                float fade = smoothstep(0.0, 0.08, min(ed.x, ed.y)) * (1.0 - float(i) / 24.0);\n" +
	"                refl = mix(refl, texture(hcol, uv).rgb, fade);\n" +
	"                break;\n" +
	"            }\n" +
	"        }\n" +
	"    }\n" +
	"    vec3 c = refl * fres;\n" +
	"    c += sc * (pow(max(dot(R, L), 0.0), 350.0) * 5.0 + pow(max(dot(R, L), 0.0), 40.0) * 0.15);\n" +
	"    return(vec4(c, in0.a));\n" +
	"}\n");

    private static final ShaderMacro watersh = prog -> {
	FireFX.noisedef.define(prog.fctx);
	FireFX.winddef.define(prog.fctx);
	ripfn.define(prog.fctx);
	waterfn.define(prog.fctx);
	Homo3D.frageyen(prog.fctx).mod(in -> ripfn.call(in, Homo3D.fragmapv.ref(), uup.ref(), FrameInfo.time()), -5);
	ValBlock.Value en = Homo3D.frageyen(prog.fctx);
	FragColor.fragcol(prog.fctx).mod(in -> waterfn.call(in, Homo3D.frageyev.ref(), en.depref(), GroundRelief.usun.ref(),
							   usuncol.ref(), uskycol.ref(), uhcol.ref(), uhdep.ref(), uhpp.ref(), uhpr.ref(),
							   pick(uhpr.ref(), "z"), uup.ref()), 10);
    };
    private static final Map<ShaderMacro, ShaderMacro> waters = new HashMap<>();

    /* Hook for WaterTile's surface. */
    public static ShaderMacro water(ShaderMacro base) {
	if(!water)
	    return(base);
	synchronized(waters) {
	    return(waters.computeIfAbsent(base, b -> ShaderMacro.compose(b, watersh)));
	}
    }
}
