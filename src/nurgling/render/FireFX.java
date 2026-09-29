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

    /* Returns whether anything changed (and programs must be rebuilt). */
    public static boolean set(boolean fire, boolean smoke, boolean hdr) {
	boolean ch = (fire != FireFX.fire) || (smoke != FireFX.smoke) || (fire && (hdr != FireFX.hdr));
	FireFX.fire = fire;
	FireFX.smoke = smoke;
	FireFX.hdr = hdr;
	return(ch);
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
