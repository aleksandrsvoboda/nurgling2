package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Realistic fire and smoke (graphics options, Vulkan only).
 *
 * Fire: every flame in the game (campfires, braziers, kilns, smelters,
 * crucibles, torches) is an animated mesh with an unlit, scrolling
 * fire texture (the "texrot" material, TexAnim). The flame shader
 * below replaces the flat scroll with rising 3D turbulence, a hot
 * white-yellow core cooling to the texture's own hue at the tips, and
 * soft see-through edges. With an HDR scene the flame adds its light
 * on top of what is behind it, so bloom makes it glow.
 *
 * Embers rise from fire lights (see Embers), and smoke puffs grow,
 * turn and break up (see the local ISmoke).
 */
public class FireFX {
    public static volatile boolean fire = false, smoke = false, hdr = false;

    /* Soft smoke: puffs fade out where they meet the ground and
     * walls (needs the frame history, see SceneFX.History). */
    public static volatile boolean soft = false;

    public static boolean setsoft(boolean soft) {
	boolean ch = (soft != FireFX.soft);
	FireFX.soft = soft;
	return(ch);
    }

    /* Returns whether anything changed (and programs must be rebuilt). */
    public static boolean set(boolean fire, boolean smoke, boolean hdr) {
	boolean ch = (fire != FireFX.fire) || (smoke != FireFX.smoke) || (fire && (hdr != FireFX.hdr));
	FireFX.fire = fire;
	FireFX.smoke = smoke;
	FireFX.hdr = hdr;
	return(ch);
    }

    /* Pretend wind for smoke and embers: long lulls, gusts that build
     * over a couple of seconds and die away, and a direction that
     * slowly wanders. Every fire shares it, so all smoke in view
     * leans the same way, but a gust sweeps across the land, reaching
     * a fire a little later the further downwind it stands. Same
     * space as Environ.wind(); added to the game's own wind. */
    static final double FRONT = 70;
    /* The wind's clock (replaceable for offline rendering). It wraps
     * like the shaders' frame time, so trees and water (see WIND)
     * feel the same gusts as smoke and embers. */
    public static volatile java.util.function.DoubleSupplier clock = () -> Utils.rtime() % 3000.0;

    /* The same wind for shaders: hv_gust(p, t) is the wind at map
     * position p (render space, y flipped as in mapv) at frame time t,
     * in world units per second, render space; hv_gusty(p, t) is the
     * gust strength 0..1 there. */
    public static final String WIND =
	"float hv_whash(float i)\n" +
	"{\n" +
	"    return(fract(sin(i * 127.1 + 311.7) * 43758.5453));\n" +
	"}\n" +
	"float hv_wnoise(float x)\n" +
	"{\n" +
	"    float i = floor(x), f = x - i;\n" +
	"    f = f * f * (3.0 - 2.0 * f);\n" +
	"    return(mix(hv_whash(i), hv_whash(i + 1.0), f));\n" +
	"}\n" +
	"float hv_wdir(float t)\n" +
	"{\n" +
	"    return(0.9 + 0.55 * sin(t * 0.021) + 0.3 * sin(t * 0.057 + 2.0));\n" +
	"}\n" +
	"float hv_wgusts(float t)\n" +
	"{\n" +
	"    float n = 0.65 * hv_wnoise(t / 2.6) + 0.35 * hv_wnoise(t / 0.9 + 17.0);\n" +
	"    return(pow(clamp((n - 0.15) / 0.85, 0.0, 1.0), 1.5));\n" +
	"}\n" +
	"float hv_gusty(vec2 p, float t)\n" +
	"{\n" +
	"    float a = hv_wdir(t);\n" +
	"    vec2 d = vec2(cos(a), sin(a));\n" +
	"    return(hv_wgusts(t - dot(vec2(p.x, -p.y), d) / 70.0));\n" +
	"}\n" +
	"vec2 hv_gust(vec2 p, float t)\n" +
	"{\n" +
	"    float a = hv_wdir(t);\n" +
	"    vec2 d = vec2(cos(a), sin(a));\n" +
	"    float tt = t - dot(vec2(p.x, -p.y), d) / 70.0;\n" +
	"    float s = 3.5 + 12.0 * hv_wgusts(tt) + sin(tt * 1.9 + 0.7);\n" +
	"    return(vec2(d.x, -d.y) * s);\n" +
	"}\n";
    public static final RawFunction winddef = new RawFunction(VEC2, "hv_gust", 2, WIND);

    private static double vnoise(double x) {
	double i = Math.floor(x), f = x - i;
	double a = hash(i), b = hash(i + 1);
	f = f * f * (3 - 2 * f);
	return(a + ((b - a) * f));
    }

    private static double hash(double i) {
	double v = Math.sin(i * 127.1 + 311.7) * 43758.5453;
	return(v - Math.floor(v));
    }

    public static double winddir(double t) {
	return(0.9 + (0.55 * Math.sin(t * 0.021)) + (0.3 * Math.sin((t * 0.057) + 2.0)));
    }

    /* Gust strength, 0..1, mostly low with occasional peaks. */
    public static double gustiness(double t) {
	double n = (0.65 * vnoise(t / 2.6)) + (0.35 * vnoise((t / 0.9) + 17.0));
	return(Math.pow(Utils.clip((n - 0.15) / 0.85, 0, 1), 1.5));
    }

    /* The wind at a map position (or anywhere, if null). */
    public static Coord3f gust(Coord2d at) {
	double t = clock.getAsDouble();
	double dir = winddir(t);
	double dx = Math.cos(dir), dy = Math.sin(dir);
	if(at != null)
	    t -= ((at.x * dx) + (at.y * dy)) / FRONT;
	/* A steady breeze under the gusts. */
	double s = 3.5 + (12.0 * gustiness(t)) + (1.0 * Math.sin((t * 1.9) + 0.7));
	return(Coord3f.of((float)(dx * s), (float)(dy * s), 0));
    }

    /* How much of the wind reaches a puff at height z above its
     * source: sheltered low down, full force higher up. */
    public static float windheight(float z) {
	return(Math.max(0.3f, Math.min(1.6f, 0.3f + (z / 22f))));
    }

    /* Value noise and a few octaves of it, in 3D. */
    public static final String NOISE =
	"float hv_fhash(vec3 p)\n" +
	"{\n" +
	"    p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));\n" +
	"    p *= 17.0;\n" +
	"    return(fract(p.x * p.y * p.z * (p.x + p.y + p.z)));\n" +
	"}\n" +
	"float hv_fnoise(vec3 x)\n" +
	"{\n" +
	"    vec3 i = floor(x), f = fract(x);\n" +
	"    f = f * f * (3.0 - 2.0 * f);\n" +
	"    return(mix(mix(mix(hv_fhash(i + vec3(0, 0, 0)), hv_fhash(i + vec3(1, 0, 0)), f.x),\n" +
	"                   mix(hv_fhash(i + vec3(0, 1, 0)), hv_fhash(i + vec3(1, 1, 0)), f.x), f.y),\n" +
	"               mix(mix(hv_fhash(i + vec3(0, 0, 1)), hv_fhash(i + vec3(1, 0, 1)), f.x),\n" +
	"                   mix(hv_fhash(i + vec3(0, 1, 1)), hv_fhash(i + vec3(1, 1, 1)), f.x), f.y), f.z));\n" +
	"}\n" +
	"float hv_ffbm(vec3 p)\n" +
	"{\n" +
	"    float v = 0.0, a = 0.5;\n" +
	"    for(int i = 0; i < 4; i++) {\n" +
	"        v += a * hv_fnoise(p);\n" +
	"        p = p * 2.03 + vec3(1.7, 9.2, 3.1);\n" +
	"        a *= 0.5;\n" +
	"    }\n" +
	"    return(v);\n" +
	"}\n";
    public static final RawFunction noisedef = new RawFunction(FLOAT, "hv_ffbm", 1, NOISE);

    /* col: the flame's own color (scrolling texture times vertex
     * color); op: model-space position; ep, en: eye-space position
     * and normal; t: time. */
    static final RawFunction flame = new RawFunction(VEC4, "hv_flame", 6,
	"vec4 hv_flame(vec4 col, vec3 op, vec3 ep, vec3 en, float t, float hdr)\n" +
	"{\n" +
	"    /* Turbulence rising through the flame, warped sideways. */\n" +
	"    vec3 q = op * 0.22;\n" +
	"    float w = hv_ffbm(q * 0.7 + vec3(0.0, 0.0, -t * 0.9));\n" +
	"    float n = hv_ffbm(q + vec3(w * 1.3, w * 0.9, -t * 2.1));\n" +
	"    /* Soft edges: the flame thins out where its surface turns away. */\n" +
	"    float el = length(en);\n" +
	"    float facing = (el > 0.01) ? abs(dot(en / el, normalize(-ep))) : 1.0;\n" +
	"    float edge = smoothstep(0.02, 0.6, facing);\n" +
	"    float lum = dot(col.rgb, vec3(0.3, 0.59, 0.11));\n" +
	"    float heat = clamp((0.35 + lum) * (0.35 + 1.25 * n), 0.0, 1.6) * edge;\n" +
	"    /* The texture's own hue, cooling to dark red at the tips and\n" +
	"     * burning white-yellow in the core. */\n" +
	"    float mx = max(max(col.r, col.g), col.b);\n" +
	"    vec3 hue = (mx > 0.001) ? (col.rgb / mx) : vec3(1.0, 0.5, 0.15);\n" +
	"    vec3 c = hue * hue * smoothstep(0.05, 0.6, heat);\n" +
	"    c = mix(c, hue, smoothstep(0.35, 0.8, heat));\n" +
	"    c = mix(c, vec3(1.0, 0.95, 0.8), smoothstep(0.8, 1.3, heat));\n" +
	"    float a = clamp(smoothstep(0.12, 0.55, heat), 0.0, 1.0) * col.a;\n" +
	"    if(hdr > 0.5) {\n" +
	"        /* Emitted light added over the scene: out = dst * (1 - a) + c. */\n" +
	"        c *= 0.9 + 2.4 * heat * heat;\n" +
	"        return(vec4(min(c / max(a, 0.03), vec3(40.0)), a));\n" +
	"    }\n" +
	"    return(vec4(c * (0.8 + 0.4 * heat), a));\n" +
	"}\n");

    static final AutoVarying objv = new AutoVarying(VEC3, "s_fireobjv") {
	    protected Expression root(VertexContext vctx) {
		return(pick(Homo3D.vertex.ref(), "xyz"));
	    }
	};

    private static ShaderMacro mkflame(boolean hdr) {
	return(prog -> {
		noisedef.define(prog.fctx);
		flame.define(prog.fctx);
		/* Values must exist before the program is assembled. */
		ValBlock.Value en = Homo3D.frageyen(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> {
			/* Flames are unlit; lit scrolling materials (pipe
			 * smoke, water) are left alone. Checked here, once
			 * every macro of the program has run. */
			if(prog.getmod(Phong.class) != null)
			    return(in);
			Tex2D tex = prog.getmod(Tex2D.class);
			if((tex == null) || (tex.tex2d == null))
			    return(in);
			return(flame.call(in, objv.ref(), Homo3D.frageyev.ref(), en.depref(),
					  FrameInfo.time(), l(hdr ? 1.0 : 0.0)));
		    }, 2000);
	    });
    }

    private static final Map<List<Object>, ShaderMacro> flames = new HashMap<>();

    /* Hook for TexAnim, the scrolling-texture state all flames use. */
    public static ShaderMacro flame(ShaderMacro base) {
	if(!fire)
	    return(base);
	boolean h = hdr;
	synchronized(flames) {
	    return(flames.computeIfAbsent(Arrays.asList(base, h), k -> ShaderMacro.compose(base, mkflame(h))));
	}
    }
}
