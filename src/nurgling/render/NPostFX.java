package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.RenderContext.FrameFormat;
import haven.RenderContext.PostProcessor;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;

/*
 * Screen-space effects for the 3D view, as post-processors in
 * PView's chain. They only use the generic render API, so they work
 * with both the OpenGL and the Vulkan renderer.
 *
 * Chain order (lower runs first):
 *   tone mapping and grading               -100 (PView.tonemap: HDR to LDR)
 *   FXAA                                     10
 *   sharpening                               20
 *   TAA resolve (stable display and history)  90
 *   PView's own resampling                  100
 */
public class NPostFX {
    /* A full-screen pass: a shader plus the values its uniforms read. */
    static class Pass extends RUtils.AdHoc {
	final Object[] vals;

	Pass(ShaderMacro sh, Object... vals) {
	    super(sh);
	    this.vals = vals;
	}
    }

    static Uniform u(Type type, int idx) {
	return(new Uniform(type, p -> ((Pass)p.get(RUtils.adhoc)).vals[idx], RUtils.adhoc));
    }

    /* A shader that replaces the drawn color with fn(in, texcoord, uniforms...). */
    static ShaderMacro shader(RawFunction fn, Uniform... us) {
	return(prog -> {
		fn.define(prog.fctx);
		FragColor.fragcol(prog.fctx).mod(in -> {
			Expression[] args = new Expression[2 + us.length];
			args[0] = in;
			args[1] = Tex2D.rtexcoord.ref();
			for(int i = 0; i < us.length; i++)
			    args[2 + i] = us[i].ref();
			return(fn.call(args));
		    }, 100);
	    });
    }

    static Texture2D.Sampler2D mktarget(Coord sz, NumberFormat cf) {
	Texture2D tex = new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, cf), null);
	Texture2D.Sampler2D ret = tex.sampler();
	ret.minfilter(Texture.Filter.LINEAR).magfilter(Texture.Filter.LINEAR);
	ret.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
	return(ret);
    }

    static boolean fits(Texture2D.Sampler2D s, Coord sz, NumberFormat cf) {
	return((s != null) && s.tex.sz().equals(sz) && (s.tex.ifmt.cf == cf));
    }

    /* A GOut that renders into a texture, like PView.resolveout. */
    static GOut target(GOut g, Texture2D.Sampler2D tgt) {
	Pipe st = new BufPipe();
	Area area = Area.sized(Coord.z, tgt.tex.sz());
	st.prep(new FrameInfo()).prep(new States.Viewport(area)).prep(new Ortho2D(area));
	st.prep(new FragColor<>(tgt.tex.image(0)));
	return(new GOut(g.out, st, area.sz()));
    }

    static void blit(GOut g, Texture2D.Sampler2D src, Pass pass) {
	g.usestate(pass);
	g.image(new TexRaw(src, true), Coord.z, g.sz());
	g.defstate();
    }

    /* Linear view distance from a depth-buffer value; pp holds
     * (m10, m14, ortho, 0) of the projection matrix. */
    static final String DEPTHLIB =
	"float hv_lindist(float d, vec4 pp)\n" +
	"{\n" +
	"    float zn = d * 2.0 - 1.0;\n" +
	"    if(pp.z > 0.5)\n" +
	"        return(-(zn - pp.y) / pp.x);\n" +
	"    return(pp.y / (zn + pp.x));\n" +
	"}\n" +
	"vec3 hv_vpos(sampler2D dep, vec2 tc, vec4 pp, vec2 pr)\n" +
	"{\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    ivec2 px = clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1);\n" +
	"    float dist = hv_lindist(texelFetch(dep, px, 0).r, pp);\n" +
	"    vec2 ndc = tc * 2.0 - 1.0;\n" +
	"    if(pp.z > 0.5)\n" +
	"        return(vec3(ndc.x / pr.x, ndc.y / pr.y, -dist));\n" +
	"    return(vec3(ndc.x * dist / pr.x, ndc.y * dist / pr.y, -dist));\n" +
	"}\n";

    /* Keeps a view's effect chain in sync with the settings. */
    public static class Manager {
	private final PView view;
	private final Runnable rebasic, reprog;
	private int ver = -1;
	private boolean lastsup;
	private Grade grade;
	private FXAA fxaa;
	private Sharpen sharp;
	private SceneFX.History hist;
	private SceneFX.Heat heat;
	private VolumeFire volumeFire;
	private WaterSurface waterSurface;
	private Lightning lightning;
	private Grass grass;
	private SceneFX.Shafts shafts;
	private Temporal.TAA taa;
	private Temporal.AutoExposure autoexp;
	private SceneFX.DoF dof;
	private NGfx.Settings cur = null;

	/* rebasic re-applies the view's basic states, so that the
	 * scene switches between 8-bit and float color when HDR
	 * processing is turned on or off. */
	/* reprog rebuilds the view's draw-list programs, for shader
	 * changes (relief) that only apply when a slot is added. */
	public Manager(PView view, Runnable rebasic, Runnable reprog) {
	    this.view = view;
	    this.rebasic = rebasic;
	    this.reprog = reprog;
	}

	private <T extends PostProcessor> T toggle(T cur, boolean want, java.util.function.Supplier<T> mk) {
	    if(want && (cur == null)) {
		cur = mk.get();
		view.add(cur);
	    } else if(!want && (cur != null)) {
		view.remove(cur);
		cur.dispose();
		cur = null;
	    }
	    return(cur);
	}

	public void sync(Environment env) {
	    int v = NGfx.version();
	    boolean sup = NGfx.supported(env);
	    if((v == ver) && (sup == lastsup))
		return;
	    ver = v;
	    lastsup = sup;
	    NGfx.Settings s = NGfx.effective(env);
	    boolean wantg = s.colorpass();
	    if(wantg && (grade == null)) {
		grade = new Grade();
		view.tonemap(grade);
		rebasic.run();
	    } else if(!wantg && (grade != null)) {
		view.tonemap(null);
		grade.dispose();
		grade = null;
		rebasic.run();
	    }
	    if(grade != null) {
		grade.grade = s.grade;
		grade.tonemap = s.grade || s.autoexp;
		grade.exposure = s.exposure; grade.contrast = s.contrast;
		grade.saturation = s.saturation; grade.warmth = s.warmth;
	    }
	    boolean rp = GroundRelief.set(s.relief, s.reliefstrength, s.parallax, s.reliefpavingonly);
	    volumeFire = toggle(volumeFire, s.fire, () -> new VolumeFire(view));
	    rp |= FireFX.set(s.fire, s.smoke, s.hdr());
	    rp |= Atmos.set(s.wet, s.glow, s.water);
	    waterSurface = toggle(waterSurface, s.water, () -> new WaterSurface(view));
	    if(waterSurface != null) waterSurface.reflections = s.waterreflections;
	    if(waterSurface != null) waterSurface.rainRipples = s.rainripples;
	    lightning = toggle(lightning, s.lightning, () -> new Lightning(view));
	    grass = toggle(grass, s.grass, () -> new Grass(view));
	    if(grass != null) grass.configure(s.grassdensity);
	    rp |= (s.snow != Atmos.snow);
	    Atmos.snow = s.snow;
	    hist = toggle(hist, s.smoke, () -> new SceneFX.History(view));
	    rp |= FireFX.setsoft(s.smoke && (hist != null));
	    heat = toggle(heat, s.heat, () -> new SceneFX.Heat(view));
	    shafts = toggle(shafts, s.shafts, () -> new SceneFX.Shafts(view));
	    cur = s;
	    if(rp)
		reprog.run();
	    /* TAA smooths edges itself; FXAA on top would only blur. */
	    fxaa = toggle(fxaa, s.fxaa && !s.taa, FXAA::new);
	    taa = toggle(taa, s.taa, () -> new Temporal.TAA(view));
	    Temporal.taa = s.taa;
	    Temporal.upscale = s.upscale;
	    autoexp = toggle(autoexp, s.autoexp, () -> new Temporal.AutoExposure(view));
	    sharp = toggle(sharp, s.sharpen, Sharpen::new);
	    if(sharp != null)
		sharp.amount = s.sharpness;
	    /* Applies to textures as they get samplers, i.e. newly
	     * loaded ones. */
	    Texture.defanisotropy = (s.aniso > 1) ? s.aniso : 0;
	}

	public void dispose() {
	    for(PostProcessor effect : new PostProcessor[]{grass,lightning,waterSurface,volumeFire,hist,heat,shafts,taa,autoexp,sharp,fxaa,dof}) {
	        if(effect != null) {view.remove(effect);effect.dispose();}
	    }
	    if(grade != null) {view.tonemap(null);grade.dispose();}
	}

	private Atmos.Env env = null;
	private float wetness = 0;
	private double last = 0;

	/* Per frame: the values that follow the game world (weather and sunlight). */
	private float snowcover = 0;

	public void tick(MapView mv, int sunidx) {
	    NGfx.Settings s = cur;
	    if(s == null)
		return;
	    dof = toggle(dof, Photo.on, () -> new SceneFX.DoF(view));
	    double now = Utils.rtime();
	    if(waterSurface != null) waterSurface.wakes.tick(mv,now);
	    if(lightning != null) lightning.tick(mv,now);
	    if(grass != null) grass.tick(mv,now);
	    float dt = (float)Math.min(Math.max(now - last, 0), 1.0);
	    last = now;
	    DirLight sun = mv.amblight;
	    boolean outdoors = mv.outdoorLighting();
	    if(autoexp != null) autoexp.outdoors = outdoors;
	    if(waterSurface != null) {
		waterSurface.sheltered = !outdoors;
		float rainTarget = outdoors ? RainLighting.intensity(mv.weather()) : 0;
		waterSurface.rainIntensity += (rainTarget - waterSurface.rainIntensity) * (1 - (float)Math.exp(-dt / .6f));
		if(!outdoors) waterSurface.rainIntensity = 0;
	    }
	    boolean raining = false, snowing = false;
	    for(Glob.Weather w : mv.weather()) {
		if((w instanceof haven.res.gfx.fx.rain.Rain) && (((haven.res.gfx.fx.rain.Rain)w).rate > 0)) {
		    raining = true;
		}
		if((w instanceof haven.res.gfx.fx.snow.Snow) && (((haven.res.gfx.fx.snow.Snow)w).rate > 0))
		    snowing = true;
	    }
	    /* Wet in about half a minute of rain, dry over a few. */
	    float tgt = (s.wet && raining) ? 1 : 0;
	    wetness += (tgt - wetness) * Math.min(1, dt / ((tgt > wetness) ? 25f : 150f));
	    if(!s.wet || !outdoors)
		wetness = 0;
	    /* Snow settles over about a minute of snowfall and melts
	     * over several. */
	    float stgt = (s.snow && snowing) ? 1 : 0;
	    snowcover += (stgt - snowcover) * Math.min(1, dt / ((stgt > snowcover) ? 50f : 400f));
	    if(!s.snow || !outdoors)
		snowcover = 0;
	    float[] sdir = {0, 0, 1}, scol = {0, 0, 0}, sky = {0.5f, 0.5f, 0.5f};
	    if(sun != null) {
		sdir = sun.dir.clone();
		scol = new float[] {sun.dif[0], sun.dif[1], sun.dif[2]};
		float[] sk = {(sun.amb[0] + sun.dif[0]) * 0.6f, (sun.amb[1] + sun.dif[1]) * 0.6f, (sun.amb[2] + sun.dif[2]) * 0.65f};
		sky = sk;
	    }
	    Atmos.History h = (hist == null) ? null : hist.cur;
	    Atmos.Env ne = new Atmos.Env((wetness > 0.01f) ? wetness : 0, s.glow, s.water,
					 sunidx, sdir, scol, sky, h);
	    ne.snow = (snowcover > 0.01f) ? snowcover : 0;
	    if(!ne.on() && (h == null)) {
		if(env != null)
		    view.basic(Atmos.class, null);
		env = null;
	    } else if(ne.differs(env)) {
		env = ne;
		view.basic(Atmos.class, ne);
	    }
	    if(grade != null) {
		grade.expo = (autoexp == null) ? null : autoexp.exposure;
		grade.nightvision = autoexp != null && !outdoors;
	    }
	    if(shafts != null) {
		Camera cam = view.basic.state().get(Homo3D.cam);
		if(!outdoors || (sun == null) || (cam == null)) {
		    shafts.sun = null;
		} else {
		    float[] e = cam.fin(Matrix4f.id).mul4(new float[] {sun.dir[0], sun.dir[1], sun.dir[2], 0});
		    float l = (float)Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
		    /* Strongest with a low sun; faint by moonlight. */
		    float elev = sun.dir[2] / (float)Math.sqrt(sun.dir[0] * sun.dir[0] + sun.dir[1] * sun.dir[1] + sun.dir[2] * sun.dir[2]);
		    float low = 0.35f + 0.65f * (1 - Math.max(0, Math.min(1, elev / 0.7f)));
		    float lum = (sun.dif[0] + sun.dif[1] + sun.dif[2]) / 3;
		    shafts.sun = new float[] {e[0] / l, e[1] / l, e[2] / l, 0.4f * low * Math.min(1, lum)};
		    shafts.suncol = new float[] {sun.dif[0], sun.dif[1] * 0.95f, sun.dif[2] * 0.85f};
		}
	    }
	}


    }

    /* Tone mapping and color grading */

    static final RawFunction gradefn = new RawFunction(VEC4, "hv_grade", 6,
	"vec4 hv_grade(vec4 col, vec2 tc, vec4 g, vec4 v, vec4 tod, sampler2D expo)\n" +
	"{\n" +
	"    /* g = (exposure, contrast, saturation, warmth); v = (grade on, tonemap, indoor exposure, brightness);\n" +
	"     * tod = time-of-day tint and saturation */\n" +
	"    vec3 x = col.rgb * tod.rgb;\n" +
	"    float gain = texture(expo, vec2(0.5)).r;\n" +
	"    if(v.z > 0.5 && gain > 1.0) {\n" +
	"        /* Lift darkness without multiplying already bright lamps/floors into white.\n" +
	"         * One RGB gain preserves hue; the quadratic curve stays monotonic at gain <= 2.2. */\n" +
	"        float room = max(0.0, 1.0 - max(x.r, max(x.g, x.b)));\n" +
	"        gain = 1.0 + (gain - 1.0) * room * room;\n" +
	"    }\n" +
	"    x *= gain;\n" +
	"    x = mix(vec3(dot(x, vec3(0.2126, 0.7152, 0.0722))), x, tod.w);\n" +
	"    if(v.x > 0.5) {\n" +
	"        x *= g.x;\n" +
	"        x *= vec3(1.0 + 0.07 * g.w, 1.0 + 0.015 * g.w, 1.0 - 0.07 * g.w);\n" +
	"        float l = dot(x, vec3(0.2126, 0.7152, 0.0722));\n" +
	"        x = mix(vec3(l), x, g.z);\n" +
	"        x = max(x, vec3(0.0));\n" +
	"        x = (x - 0.5) * g.y + 0.5;\n" +
	"    }\n" +
	"    /* Soft shoulder: values above 0.8 roll off towards 1 instead of clipping. */\n" +
	"    x = max(x, vec3(0.0)) * v.w;\n" +
	"    if(v.y > 0.5) {\n" +
	"        vec3 hi = 0.8 + 0.2 * (1.0 - exp(-(x - 0.8) / 0.2));\n" +
	"        x = mix(x, hi, step(vec3(0.8), x));\n" +
	"    }\n" +
	"    return(vec4(clamp(x, 0.0, 1.0), col.a));\n" +
	"}\n");
    static final Uniform gr_g = u(VEC4, 0), gr_v = u(VEC4, 1), gr_tod = u(VEC4, 2), gr_expo = u(SAMPLER2D, 3);
    static final ShaderMacro gr_sh = shader(gradefn, gr_g, gr_v, gr_tod, gr_expo);

    public static class Grade extends PostProcessor {
	boolean grade, tonemap, nightvision;
	float exposure, contrast, saturation, warmth;
	volatile float[] tod = {1, 1, 1, 1};
	volatile Texture2D.Sampler2D expo = null;

	public int order() {return(ORDER_TONEMAP);}

	public FrameFormat outformat(FrameFormat in) {
	    FrameFormat ret = new FrameFormat(in);
	    ret.cfmt = new VectorFormat(in.cfmt.nc, NumberFormat.UNORM8);
	    return(ret);
	}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    blit(g, in, new Pass(gr_sh, new float[] {exposure, contrast, saturation, warmth},
				 new float[] {grade ? 1 : 0, tonemap ? 1 : 0, nightvision ? 1 : 0, expo == null ? 1f : 1.15f}, tod,
				 (expo == null) ? Temporal.one() : expo));
	}
    }

    /* Anti-aliasing (FXAA) */

    static final RawFunction fxaafn = new RawFunction(VEC4, "hv_fxaa", 3,
	"vec4 hv_fxaa(vec4 col, vec2 tc, sampler2D tex)\n" +
	"{\n" +
	"    vec2 rcp = 1.0 / vec2(textureSize(tex, 0));\n" +
	"    vec3 luma = vec3(0.299, 0.587, 0.114);\n" +
	"    vec3 rgbNW = texture(tex, tc + vec2(-1.0, -1.0) * rcp).rgb;\n" +
	"    vec3 rgbNE = texture(tex, tc + vec2(1.0, -1.0) * rcp).rgb;\n" +
	"    vec3 rgbSW = texture(tex, tc + vec2(-1.0, 1.0) * rcp).rgb;\n" +
	"    vec3 rgbSE = texture(tex, tc + vec2(1.0, 1.0) * rcp).rgb;\n" +
	"    vec4 cM = texture(tex, tc);\n" +
	"    float lNW = dot(rgbNW, luma), lNE = dot(rgbNE, luma), lSW = dot(rgbSW, luma), lSE = dot(rgbSE, luma);\n" +
	"    float lM = dot(cM.rgb, luma);\n" +
	"    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));\n" +
	"    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));\n" +
	"    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), ((lNW + lSW) - (lNE + lSE)));\n" +
	"    float dirReduce = max((lNW + lNE + lSW + lSE) * (0.25 * (1.0 / 8.0)), 1.0 / 128.0);\n" +
	"    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);\n" +
	"    dir = clamp(dir * rcpDirMin, vec2(-8.0), vec2(8.0)) * rcp;\n" +
	"    vec3 rgbA = 0.5 * (texture(tex, tc + dir * (1.0 / 3.0 - 0.5)).rgb + texture(tex, tc + dir * (2.0 / 3.0 - 0.5)).rgb);\n" +
	"    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture(tex, tc + dir * -0.5).rgb + texture(tex, tc + dir * 0.5).rgb);\n" +
	"    float lB = dot(rgbB, luma);\n" +
	"    if((lB < lMin) || (lB > lMax))\n" +
	"        return(vec4(rgbA, cM.a));\n" +
	"    return(vec4(rgbB, cM.a));\n" +
	"}\n");
    static final Uniform fx_tex = u(SAMPLER2D, 0);
    static final ShaderMacro fx_sh = shader(fxaafn, fx_tex);

    public static class FXAA extends PostProcessor {
	public int order() {return(10);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    blit(g, in, new Pass(fx_sh, in));
	}
    }

    /* Sharpening */

    static final RawFunction sharpfn = new RawFunction(VEC4, "hv_sharpen", 4,
	"vec4 hv_sharpen(vec4 col, vec2 tc, sampler2D tex, float amt)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(tex, 0));\n" +
	"    vec3 c = texture(tex, tc).rgb;\n" +
	"    vec3 n = texture(tex, tc + vec2(0.0, px.y)).rgb, s = texture(tex, tc - vec2(0.0, px.y)).rgb;\n" +
	"    vec3 e = texture(tex, tc + vec2(px.x, 0.0)).rgb, w = texture(tex, tc - vec2(px.x, 0.0)).rgb;\n" +
	"    vec3 mn = min(c, min(min(n, s), min(e, w))), mx = max(c, max(max(n, s), max(e, w)));\n" +
	"    /* Contrast-adaptive: sharpen less where local contrast is already high. */\n" +
	"    vec3 a = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.001)), 0.0, 1.0);\n" +
	"    vec3 k = sqrt(a) * amt * 0.25;\n" +
	"    vec3 r = c + (4.0 * c - n - s - e - w) * k;\n" +
	"    return(vec4(clamp(r, mn, mx), col.a));\n" +
	"}\n");
    static final Uniform sh_tex = u(SAMPLER2D, 0), sh_amt = u(FLOAT, 1);
    static final ShaderMacro sh_sh = shader(sharpfn, sh_tex, sh_amt);

    public static class Sharpen extends PostProcessor {
	float amount;

	public int order() {return(20);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    blit(g, in, new Pass(sh_sh, in, amount));
	}
    }
}
