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
 *   ambient occlusion                      -150
 *   bloom                                  -120
 *   tone mapping and grading               -100 (PView.tonemap: HDR to LDR)
 *   clarity                                  5
 *   FXAA                                     10
 *   sharpening                               20
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

    /* Ambient occlusion */

    static final RawFunction aofn = new RawFunction(VEC4, "hv_ao", 6, DEPTHLIB +
	"vec4 hv_ao(vec4 col, vec2 tc, sampler2D dep, vec4 pp, vec2 pr, vec2 par)\n" +
	"{\n" +
	"    /* par = (strength, radius in world units) */\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    vec2 px = 1.0 / vec2(sz);\n" +
	"    if(texelFetch(dep, clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1), 0).r >= 0.99999)\n" +
	"        return(vec4(1.0));\n" +
	"    vec3 P = hv_vpos(dep, tc, pp, pr);\n" +
	"    vec3 pl = hv_vpos(dep, tc - vec2(px.x, 0.0), pp, pr), pr2 = hv_vpos(dep, tc + vec2(px.x, 0.0), pp, pr);\n" +
	"    vec3 pd = hv_vpos(dep, tc - vec2(0.0, px.y), pp, pr), pu = hv_vpos(dep, tc + vec2(0.0, px.y), pp, pr);\n" +
	"    vec3 dx = (abs(pr2.z - P.z) < abs(P.z - pl.z)) ? (pr2 - P) : (P - pl);\n" +
	"    vec3 dy = (abs(pu.z - P.z) < abs(P.z - pd.z)) ? (pu - P) : (P - pd);\n" +
	"    vec3 N = normalize(cross(dx, dy));\n" +
	"    float R = par.y;\n" +
	"    float dist = -P.z;\n" +
	"    float rtc = (pp.z > 0.5) ? (R * pr.x * 0.5) : (R * pr.x * 0.5 / dist);\n" +
	"    rtc = min(rtc, 0.035);\n" +
	"    float noise = fract(52.9829189 * fract(dot(tc / px, vec2(0.06711056, 0.00583715))));\n" +
	"    float occ = 0.0;\n" +
	"    for(int i = 0; i < 12; i++) {\n" +
	"        float a = (float(i) + noise) * 2.39996323;\n" +
	"        float r = rtc * sqrt((float(i) + 0.5) / 12.0);\n" +
	"        vec3 S = hv_vpos(dep, tc + vec2(cos(a), sin(a)) * r, pp, pr);\n" +
	"        vec3 v = S - P;\n" +
	"        float vv = dot(v, v);\n" +
	"        float fall = 1.0 - smoothstep(R * R, 4.0 * R * R, vv);\n" +
	"        /* Bias grows with distance: depth precision drops off, and flat ground at\n" +
	"         * grazing angles would otherwise shade itself. */\n" +
	"        float bias = 0.12 * R + 0.002 * dist;\n" +
	"        occ += fall * R * max(0.0, dot(v, N) - bias) / (vv + 0.1 * R * R);\n" +
	"    }\n" +
	"    float ao = clamp(1.0 - par.x * 2.5 * occ / 12.0, 0.0, 1.0);\n" +
	"    return(vec4(ao, ao, ao, 1.0));\n" +
	"}\n");
    static final Uniform ao_dep = u(SAMPLER2D, 0), ao_pp = u(VEC4, 1), ao_pr = u(VEC2, 2), ao_par = u(VEC2, 3);
    static final ShaderMacro ao_sh = shader(aofn, ao_dep, ao_pp, ao_pr, ao_par);

    static final RawFunction dcompfn = new RawFunction(VEC4, "hv_dcomp", 6, DEPTHLIB +
	"vec4 hv_dcomp(vec4 col, vec2 tc, sampler2D dep, sampler2D ao, vec4 pp, vec2 pr)\n" +
	"{\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    float d = texelFetch(dep, clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1), 0).r;\n" +
	"    vec3 c = col.rgb;\n" +
	"    {\n" +
	"        /* Depth-aware blur of the occlusion buffer. */\n" +
	"        vec2 apx = 1.0 / vec2(textureSize(ao, 0));\n" +
	"        float dc = hv_lindist(d, pp);\n" +
	"        float sum = 0.0, wsum = 0.0;\n" +
	"        for(int y = -2; y <= 2; y++) {\n" +
	"            for(int x = -2; x <= 2; x++) {\n" +
	"                vec2 o = vec2(float(x), float(y)) * apx;\n" +
	"                ivec2 dp = clamp(ivec2((tc + o) * vec2(sz)), ivec2(0), sz - 1);\n" +
	"                float ds = hv_lindist(texelFetch(dep, dp, 0).r, pp);\n" +
	"                float w = 1.0 / (0.001 + abs(ds - dc) / max(dc, 1.0) * 40.0);\n" +
	"                sum += texture(ao, tc + o).r * w;\n" +
	"                wsum += w;\n" +
	"            }\n" +
	"        }\n" +
	"        c *= sum / wsum;\n" +
	"    }\n" +
	"    return(vec4(c, col.a));\n" +
	"}\n");
    static final Uniform dc_dep = u(SAMPLER2D, 0), dc_ao = u(SAMPLER2D, 1), dc_pp = u(VEC4, 2), dc_pr = u(VEC2, 3);
    static final ShaderMacro dc_sh = shader(dcompfn, dc_dep, dc_ao, dc_pp, dc_pr);

    public static class DepthFX extends PostProcessor {
	final PView view;
	int aoq;
	float aostr;
	private Texture2D.Sampler2D aobuf, dsamp;
	private Texture dtex;

	public DepthFX(PView view) {
	    this.view = view;
	}

	public int order() {return(-150);}

	private float[][] projparams() {
	    Projection prj = view.basic.state().get(Homo3D.prj);
	    Matrix4f m = (prj == null) ? Matrix4f.id : prj.fin(Matrix4f.id);
	    boolean ortho = Math.abs(m.m[15] - 1.0f) < 0.001f;
	    return(new float[][] {
		    {m.m[10], m.m[14], ortho ? 1 : 0, 0},
		    {m.m[0], m.m[5]},
		});
	}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    if(!(view.depth instanceof Texture2D)) {
		g.image(new TexRaw(in, true), Coord.z, g.sz());
		return;
	    }
	    if(view.depth != dtex) {
		dtex = view.depth;
		dsamp = new Texture2D.Sampler2D((Texture2D)dtex);
	    }
	    float[][] pp = projparams();
	    {
		Coord sz = in.tex.sz();
		if(aoq == 0)
		    sz = Coord.of(Math.max(sz.x / 2, 1), Math.max(sz.y / 2, 1));
		if(!fits(aobuf, sz, NumberFormat.UNORM8)) {
		    if(aobuf != null)
			aobuf.dispose();
		    aobuf = mktarget(sz, NumberFormat.UNORM8);
		}
		blit(target(g, aobuf), in, new Pass(ao_sh, dsamp, pp[0], pp[1], new float[] {aostr, 7.0f}));
	    }
	    blit(g, in, new Pass(dc_sh, dsamp, aobuf, pp[0], pp[1]));
	}

	public void dispose() {
	    super.dispose();
	    if(aobuf != null)
		aobuf.dispose();
	}
    }

    /* Bloom */

    static final RawFunction bprefn = new RawFunction(VEC4, "hv_bpre", 3,
	"vec4 hv_bpre(vec4 col, vec2 tc, sampler2D src)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    vec3 c = (texture(src, tc + vec2(-px.x, -px.y)).rgb + texture(src, tc + vec2(px.x, -px.y)).rgb +\n" +
	"              texture(src, tc + vec2(-px.x, px.y)).rgb + texture(src, tc + vec2(px.x, px.y)).rgb) * 0.25;\n" +
	"    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
	"    float k = smoothstep(0.62, 1.0, l);\n" +
	"    return(vec4(c * k, 1.0));\n" +
	"}\n");
    static final Uniform bp_src = u(SAMPLER2D, 0);
    static final ShaderMacro bp_sh = shader(bprefn, bp_src);

    static final RawFunction bdownfn = new RawFunction(VEC4, "hv_bdown", 3,
	"vec4 hv_bdown(vec4 col, vec2 tc, sampler2D src)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    vec3 c = texture(src, tc).rgb * 0.25;\n" +
	"    c += (texture(src, tc + vec2(-px.x, -px.y)).rgb + texture(src, tc + vec2(px.x, -px.y)).rgb +\n" +
	"          texture(src, tc + vec2(-px.x, px.y)).rgb + texture(src, tc + vec2(px.x, px.y)).rgb) * 0.1875;\n" +
	"    return(vec4(c, 1.0));\n" +
	"}\n");
    static final Uniform bd_src = u(SAMPLER2D, 0);
    static final ShaderMacro bd_sh = shader(bdownfn, bd_src);

    static final RawFunction bupfn = new RawFunction(VEC4, "hv_bup", 4,
	"vec4 hv_bup(vec4 col, vec2 tc, sampler2D low, sampler2D cur)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(low, 0));\n" +
	"    vec3 c = texture(low, tc).rgb * 4.0;\n" +
	"    c += (texture(low, tc + vec2(px.x, 0.0)).rgb + texture(low, tc - vec2(px.x, 0.0)).rgb +\n" +
	"          texture(low, tc + vec2(0.0, px.y)).rgb + texture(low, tc - vec2(0.0, px.y)).rgb) * 2.0;\n" +
	"    c += texture(low, tc + px).rgb + texture(low, tc - px).rgb +\n" +
	"         texture(low, tc + vec2(px.x, -px.y)).rgb + texture(low, tc + vec2(-px.x, px.y)).rgb;\n" +
	"    return(vec4(c / 16.0 + texture(cur, tc).rgb, 1.0));\n" +
	"}\n");
    static final Uniform bu_low = u(SAMPLER2D, 0), bu_cur = u(SAMPLER2D, 1);
    static final ShaderMacro bu_sh = shader(bupfn, bu_low, bu_cur);

    static final RawFunction bcompfn = new RawFunction(VEC4, "hv_bcomp", 4,
	"vec4 hv_bcomp(vec4 col, vec2 tc, sampler2D bloom, float str)\n" +
	"{\n" +
	"    return(vec4(col.rgb + texture(bloom, tc).rgb * str, col.a));\n" +
	"}\n");
    static final Uniform bc_bloom = u(SAMPLER2D, 0), bc_str = u(FLOAT, 1);
    static final ShaderMacro bc_sh = shader(bcompfn, bc_bloom, bc_str);

    public static class Bloom extends PostProcessor {
	static final int LEVELS = 5;
	float strength;
	private final Texture2D.Sampler2D[] down = new Texture2D.Sampler2D[LEVELS], up = new Texture2D.Sampler2D[LEVELS];

	public int order() {return(-120);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Coord sz = in.tex.sz();
	    for(int i = 0; i < LEVELS; i++) {
		sz = Coord.of(Math.max(sz.x / 2, 1), Math.max(sz.y / 2, 1));
		if(!fits(down[i], sz, NumberFormat.FLOAT16)) {
		    if(down[i] != null) down[i].dispose();
		    if(up[i] != null) up[i].dispose();
		    down[i] = mktarget(sz, NumberFormat.FLOAT16);
		    up[i] = mktarget(sz, NumberFormat.FLOAT16);
		}
	    }
	    blit(target(g, down[0]), in, new Pass(bp_sh, in));
	    for(int i = 1; i < LEVELS; i++)
		blit(target(g, down[i]), down[i - 1], new Pass(bd_sh, down[i - 1]));
	    Texture2D.Sampler2D low = down[LEVELS - 1];
	    for(int i = LEVELS - 2; i >= 0; i--) {
		blit(target(g, up[i]), down[i], new Pass(bu_sh, low, down[i]));
		low = up[i];
	    }
	    blit(g, in, new Pass(bc_sh, low, strength * 0.6f));
	}

	public void dispose() {
	    super.dispose();
	    for(int i = 0; i < LEVELS; i++) {
		if(down[i] != null) down[i].dispose();
		if(up[i] != null) up[i].dispose();
	    }
	}
    }

    /* Keeps a view's effect chain in sync with the settings. */
    public static class Manager {
	private final PView view;
	private final Runnable rebasic;
	private int ver = -1;
	private boolean lastsup;
	private DepthFX dfx;
	private Bloom bloom;
	private Grade grade;
	private FXAA fxaa;
	private Sharpen sharp;
	private Clarity clar;

	/* rebasic re-applies the view's basic states, so that the
	 * scene switches between 8-bit and float color when HDR
	 * processing is turned on or off. */
	public Manager(PView view, Runnable rebasic) {
	    this.view = view;
	    this.rebasic = rebasic;
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
	    dfx = toggle(dfx, s.ssao, () -> new DepthFX(view));
	    if(dfx != null) {
		dfx.aoq = s.aoq; dfx.aostr = s.aostrength;
	    }
	    bloom = toggle(bloom, s.bloom, Bloom::new);
	    if(bloom != null)
		bloom.strength = s.bloomstrength;
	    boolean wantg = s.grade || s.bloom || s.vignette;
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
		grade.grade = s.grade; grade.vignette = s.vignette;
		grade.exposure = s.exposure; grade.contrast = s.contrast;
		grade.saturation = s.saturation; grade.warmth = s.warmth;
	    }
	    clar = toggle(clar, s.clarity, Clarity::new);
	    if(clar != null)
		clar.amount = s.claritystrength;
	    if(GroundRelief.set(s.relief, s.reliefstrength) && (view instanceof MapView))
		((MapView)view).glob.map.invalidateAll();
	    fxaa = toggle(fxaa, s.fxaa, FXAA::new);
	    sharp = toggle(sharp, s.sharpen, Sharpen::new);
	    if(sharp != null)
		sharp.amount = s.sharpness;
	    ShadowMap.softness = s.softshadow ? ((s.shadowq > 0) ? 2 : 1) : 0;
	    /* Applies to textures as they get samplers, i.e. newly
	     * loaded ones. */
	    Texture.defanisotropy = (s.aniso > 1) ? s.aniso : 0;
	}
    }

    /* Tone mapping and color grading */

    static final RawFunction gradefn = new RawFunction(VEC4, "hv_grade", 4,
	"vec4 hv_grade(vec4 col, vec2 tc, vec4 g, vec2 v)\n" +
	"{\n" +
	"    /* g = (exposure, contrast, saturation, warmth); v = (grade on, vignette) */\n" +
	"    vec3 x = col.rgb;\n" +
	"    if(v.x > 0.5) {\n" +
	"        x *= g.x;\n" +
	"        x *= vec3(1.0 + 0.07 * g.w, 1.0 + 0.015 * g.w, 1.0 - 0.07 * g.w);\n" +
	"        float l = dot(x, vec3(0.2126, 0.7152, 0.0722));\n" +
	"        x = mix(vec3(l), x, g.z);\n" +
	"        x = max(x, vec3(0.0));\n" +
	"        x = (x - 0.5) * g.y + 0.5;\n" +
	"    }\n" +
	"    /* Soft shoulder: values above 0.8 roll off towards 1 instead of clipping. */\n" +
	"    x = max(x, vec3(0.0));\n" +
	"    vec3 hi = 0.8 + 0.2 * (1.0 - exp(-(x - 0.8) / 0.2));\n" +
	"    x = mix(x, hi, step(vec3(0.8), x));\n" +
	"    if(v.y > 0.5) {\n" +
	"        vec2 d = tc - 0.5;\n" +
	"        x *= mix(0.72, 1.0, smoothstep(0.85, 0.3, length(d * vec2(1.0, 0.8)) * 1.2));\n" +
	"    }\n" +
	"    return(vec4(clamp(x, 0.0, 1.0), col.a));\n" +
	"}\n");
    static final Uniform gr_g = u(VEC4, 0), gr_v = u(VEC2, 1);
    static final ShaderMacro gr_sh = shader(gradefn, gr_g, gr_v);

    public static class Grade extends PostProcessor {
	boolean grade, vignette;
	float exposure, contrast, saturation, warmth;

	public int order() {return(ORDER_TONEMAP);}

	public FrameFormat outformat(FrameFormat in) {
	    FrameFormat ret = new FrameFormat(in);
	    ret.cfmt = new VectorFormat(in.cfmt.nc, NumberFormat.UNORM8);
	    return(ret);
	}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    blit(g, in, new Pass(gr_sh, new float[] {exposure, contrast, saturation, warmth},
				 new float[] {grade ? 1 : 0, vignette ? 1 : 0}));
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

    /* Clarity: local contrast, like a large-radius unsharp mask on
     * luminance, weighted towards midtones so it doesn't crush
     * shadows or clip highlights. */

    static final RawFunction clarfn = new RawFunction(VEC4, "hv_clarity", 4,
	"vec4 hv_clarity(vec4 col, vec2 tc, sampler2D tex, float amt)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(tex, 0));\n" +
	"    vec3 luma = vec3(0.2126, 0.7152, 0.0722);\n" +
	"    vec3 c = texture(tex, tc).rgb;\n" +
	"    float l = dot(c, luma);\n" +
	"    float sum = 0.0;\n" +
	"    for(int i = 0; i < 12; i++) {\n" +
	"        float a = float(i) * 0.5235988;\n" +
	"        float r = ((i % 2) == 0) ? 6.0 : 11.0;\n" +
	"        sum += dot(texture(tex, tc + vec2(cos(a), sin(a)) * r * px).rgb, luma);\n" +
	"    }\n" +
	"    float mean = sum / 12.0;\n" +
	"    float mid = 1.0 - pow(abs(l * 2.0 - 1.0), 2.0);\n" +
	"    float nl = l + (l - mean) * amt * 1.6 * mid;\n" +
	"    vec3 r = c * (nl / max(l, 0.001));\n" +
	"    return(vec4(clamp(r, 0.0, 1.0), col.a));\n" +
	"}\n");
    static final Uniform cl_tex = u(SAMPLER2D, 0), cl_amt = u(FLOAT, 1);
    static final ShaderMacro cl_sh = shader(clarfn, cl_tex, cl_amt);

    public static class Clarity extends PostProcessor {
	float amount;

	public int order() {return(5);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    blit(g, in, new Pass(cl_sh, in, amount));
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
