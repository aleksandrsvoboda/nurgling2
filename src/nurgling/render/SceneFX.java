package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.RenderContext.PostProcessor;
import java.util.*;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;
import static nurgling.render.NPostFX.*;

/*
 * More screen-space effects for the 3D view (graphics options, Vulkan
 * only), in PView's post-processing chain:
 *
 *   history (last frame's color and depth)   -200
 *   light shafts                              -140
 *   heat shimmer                              -130
 *   tilt-shift                                   3
 */
public class SceneFX {
    /* Linear view distance of the scene depth, far for the sky. */
    static final RawFunction linfn = new RawFunction(VEC4, "hv_lindepth", 4, DEPTHLIB +
	"vec4 hv_lindepth(vec4 col, vec2 tc, sampler2D dep, vec4 pp)\n" +
	"{\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    float d = texelFetch(dep, clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1), 0).r;\n" +
	"    if(d >= 0.99999)\n" +
	"        return(vec4(60000.0, 0.0, 0.0, 1.0));\n" +
	"    return(vec4(hv_lindist(d, pp), 0.0, 0.0, 1.0));\n" +
	"}\n");
    static final Uniform ld_dep = u(SAMPLER2D, 0), ld_pp = u(VEC4, 1);
    static final ShaderMacro ld_sh = shader(linfn, ld_dep, ld_pp);

    static final RawFunction copyfn = new RawFunction(VEC4, "hv_copy", 3,
	"vec4 hv_copy(vec4 col, vec2 tc, sampler2D src)\n" +
	"{\n" +
	"    return(vec4(texture(src, tc).rgb, 1.0));\n" +
	"}\n");
    static final Uniform cp_src = u(SAMPLER2D, 0);
    static final ShaderMacro cp_sh = shader(copyfn, cp_src);

    /* A depth sampler for the view, and its projection parameters. */
    static class Depth {
	final PView view;
	private Texture dtex;
	private Texture2D.Sampler2D dsamp;

	Depth(PView view) {this.view = view;}

	Texture2D.Sampler2D samp() {
	    if(!(view.depth instanceof Texture2D))
		return(null);
	    if(view.depth != dtex) {
		dtex = view.depth;
		dsamp = new Texture2D.Sampler2D((Texture2D)dtex);
	    }
	    return(dsamp);
	}

	float[][] projparams() {
	    Projection prj = view.basic.state().get(Homo3D.prj);
	    Matrix4f m = (prj == null) ? Matrix4f.id : prj.fin(Matrix4f.id);
	    boolean ortho = Math.abs(m.m[15] - 1.0f) < 0.001f;
	    return(new float[][] {
		    {m.m[10], m.m[14], ortho ? 1 : 0, 0},
		    {m.m[0], m.m[5]},
		});
	}
    }

    /* Keeps the last frame's picture and depth for effects drawn in
     * the scene (water reflections, soft smoke). */
    public static class History extends PostProcessor {
	final Depth depth;
	private Texture2D.Sampler2D col, dep;
	public volatile Atmos.History cur = null;

	public History(PView view) {this.depth = new Depth(view);}

	public int order() {return(-200);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Texture2D.Sampler2D ds = depth.samp();
	    Coord sz = in.tex.sz();
	    if(ds != null) {
		boolean re = false;
		if(!fits(col, sz, NumberFormat.FLOAT16)) {
		    if(col != null) col.dispose();
		    col = mktarget(sz, NumberFormat.FLOAT16);
		    re = true;
		}
		if(!fits(dep, sz, NumberFormat.FLOAT16)) {
		    if(dep != null) dep.dispose();
		    dep = mktarget(sz, NumberFormat.FLOAT16);
		    dep.minfilter(Texture.Filter.NEAREST).magfilter(Texture.Filter.NEAREST);
		    re = true;
		}
		float[][] pp = depth.projparams();
		blit(target(g, col), in, new Pass(cp_sh, in));
		blit(target(g, dep), in, new Pass(ld_sh, ds, pp[0]));
		Atmos.History h = cur;
		if(re || (h == null) || !Arrays.equals(h.pp, pp[0]) || !Arrays.equals(h.pr, pp[1]))
		    cur = new Atmos.History(col, dep, pp[0], pp[1]);
	    }
	    g.image(new TexRaw(in, true), Coord.z, g.sz());
	}

	public void dispose() {
	    super.dispose();
	    cur = null;
	    if(col != null) col.dispose();
	    if(dep != null) dep.dispose();
	}
    }

    /* Tilt-shift: the top and bottom of the view go soft, for a
     * miniature, diorama look. Two passes (across, then down). */
    static final RawFunction tiltfn = new RawFunction(VEC4, "hv_tilt", 4,
	"vec4 hv_tilt(vec4 col, vec2 tc, sampler2D src, vec3 par)\n" +
	"{\n" +
	"    /* par = (step x, step y, strength) */\n" +
	"    float d = abs(tc.y - 0.55);\n" +
	"    float r = par.z * smoothstep(0.08, 0.45, d) * 2.2;\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    vec2 dir = par.xy * px * r;\n" +
	"    vec3 sum = vec3(0.0);\n" +
	"    float ws = 0.0;\n" +
	"    for(int i = -6; i <= 6; i++) {\n" +
	"        float w = exp(-float(i * i) / 18.0);\n" +
	"        sum += texture(src, tc + dir * float(i)).rgb * w;\n" +
	"        ws += w;\n" +
	"    }\n" +
	"    return(vec4(sum / ws, 1.0));\n" +
	"}\n");
    static final Uniform ti_src = u(SAMPLER2D, 0), ti_par = u(VEC3, 1);
    static final ShaderMacro ti_sh = shader(tiltfn, ti_src, ti_par);

    public static class TiltShift extends PostProcessor {
	float strength = 1;
	private Texture2D.Sampler2D mid;

	public int order() {return(3);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Coord sz = in.tex.sz();
	    NumberFormat cf = in.tex.ifmt.cf;
	    if(!fits(mid, sz, cf)) {
		if(mid != null) mid.dispose();
		mid = mktarget(sz, cf);
	    }
	    blit(target(g, mid), in, new Pass(ti_sh, in, new float[] {1, 0, strength}));
	    blit(g, mid, new Pass(ti_sh, mid, new float[] {0, 1, strength}));
	}

	public void dispose() {
	    super.dispose();
	    if(mid != null) mid.dispose();
	}
    }

    /* Heat shimmer above fires. Each source is (x, y, ux, uy) in
     * screen coordinates: where the fire is, and the offset to a
     * point some way above it. */
    static final int NHEAT = 6;
    static final RawFunction heatfn = new RawFunction(VEC4, "hv_heat", 10, FireFX.NOISE +
	"vec4 hv_heat(vec4 col, vec2 tc, sampler2D src, float t, vec4 s0, vec4 s1, vec4 s2, vec4 s3, vec4 s4, vec4 s5)\n" +
	"{\n" +
	"    vec4 s[6] = vec4[6](s0, s1, s2, s3, s4, s5);\n" +
	"    vec2 disp = vec2(0.0);\n" +
	"    for(int i = 0; i < 6; i++) {\n" +
	"        vec2 up = s[i].zw;\n" +
	"        float ul = dot(up, up);\n" +
	"        if(ul < 1e-8)\n" +
	"            continue;\n" +
	"        vec2 rel = tc - s[i].xy;\n" +
	"        float h = dot(rel, up) / ul;\n" +
	"        vec2 perp = rel - up * h;\n" +
	"        float w = sqrt(ul) * (0.22 + 0.3 * clamp(h, 0.0, 1.5));\n" +
	"        float fall = (1.0 - smoothstep(0.0, w, length(perp))) * smoothstep(0.0, 0.2, h) * (1.0 - smoothstep(0.7, 1.6, h));\n" +
	"        if(fall <= 0.0)\n" +
	"            continue;\n" +
	"        vec2 q = rel / sqrt(ul) * 7.0;\n" +
	"        float nx = hv_fnoise(vec3(q.x, q.y + t * 3.5, t * 0.8)) - 0.5;\n" +
	"        float ny = hv_fnoise(vec3(q.x + 9.1, q.y + t * 3.5, t * 0.8 + 4.0)) - 0.5;\n" +
	"        disp += vec2(nx, ny) * fall * sqrt(ul) * 0.0175;\n" +
	"    }\n" +
	"    return(vec4(texture(src, tc + disp).rgb, col.a));\n" +
	"}\n");
    static final Uniform ht_src = u(SAMPLER2D, 0), ht_t = u(FLOAT, 1);
    static final Uniform[] ht_s = new Uniform[NHEAT];
    static {
	for(int i = 0; i < NHEAT; i++)
	    ht_s[i] = u(VEC4, 2 + i);
    }
    static final ShaderMacro ht_sh = shader(heatfn, ht_src, ht_t, ht_s[0], ht_s[1], ht_s[2], ht_s[3], ht_s[4], ht_s[5]);

    public static class Heat extends PostProcessor {
	final PView view;
	volatile List<Coord3f> fires = Collections.emptyList();

	public Heat(PView view) {this.view = view;}

	public int order() {return(-130);}

	private float[] project(Matrix4f pm, Coord3f p) {
	    float[] c = pm.mul4(new float[] {p.x, p.y, p.z, 1});
	    if(c[3] <= 0.01f)
		return(null);
	    return(new float[] {(c[0] / c[3]) * 0.5f + 0.5f, (c[1] / c[3]) * 0.5f + 0.5f});
	}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Object[] vals = new Object[2 + NHEAT];
	    vals[0] = in;
	    vals[1] = (float)(Utils.rtime() % 3000.0);
	    for(int i = 0; i < NHEAT; i++)
		vals[2 + i] = new float[4];
	    Camera cam = view.basic.state().get(Homo3D.cam);
	    Projection prj = view.basic.state().get(Homo3D.prj);
	    if((cam != null) && (prj != null)) {
		Matrix4f pm = prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id));
		int n = 0;
		for(Coord3f f : fires) {
		    if(n >= NHEAT)
			break;
		    float[] a = project(pm, f), b = project(pm, f.add(0, 0, 30));
		    if((a == null) || (b == null))
			continue;
		    if((a[0] < -0.2f) || (a[0] > 1.2f) || (a[1] < -0.2f) || (a[1] > 1.2f))
			continue;
		    vals[2 + n++] = new float[] {a[0], a[1], b[0] - a[0], b[1] - a[1]};
		}
		if(n == 0) {
		    g.image(new TexRaw(in, true), Coord.z, g.sz());
		    return;
		}
	    }
	    blit(g, in, new Pass(ht_sh, vals));
	}
    }

    /* Depth of field for photo mode: what is nearer or further than
     * the focus goes soft, gathered from a disk sized by the blur. */
    static final RawFunction doffn = new RawFunction(VEC4, "hv_dof", 6, DEPTHLIB +
	"float hv_coc(float d, vec2 fa)\n" +
	"{\n" +
	"    return(clamp(abs(d - fa.x) / max(fa.x * 0.55, 1.0), 0.0, 1.0));\n" +
	"}\n" +
	"vec4 hv_dof(vec4 col, vec2 tc, sampler2D src, sampler2D dep, vec4 pp, vec2 fa)\n" +
	"{\n" +
	"    /* fa = (focus distance, blur radius in pixels) */\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    float d = hv_lindist(texelFetch(dep, clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1), 0).r, pp);\n" +
	"    float coc = hv_coc(d, fa);\n" +
	"    vec3 sum = texture(src, tc).rgb;\n" +
	"    float ws = 1.0;\n" +
	"    for(int i = 0; i < 32; i++) {\n" +
	"        float a = float(i) * 2.39996323;\n" +
	"        float r = sqrt((float(i) + 0.5) / 32.0);\n" +
	"        vec2 o = vec2(cos(a), sin(a)) * r * fa.y;\n" +
	"        vec2 st = tc + o * px;\n" +
	"        float sd = hv_lindist(texelFetch(dep, clamp(ivec2(st * vec2(sz)), ivec2(0), sz - 1), 0).r, pp);\n" +
	"        float sc = hv_coc(sd, fa);\n" +
	"        /* A tap counts if its own blur reaches this far; nearer\n" +
	"         * sharp things do not bleed onto the blurred background. */\n" +
	"        float reach = ((sd < d) ? sc : max(sc, coc)) * fa.y;\n" +
	"        float w = clamp(reach - r * fa.y + 1.0, 0.0, 1.0);\n" +
	"        sum += texture(src, st).rgb * w;\n" +
	"        ws += w;\n" +
	"    }\n" +
	"    return(vec4(sum / ws, 1.0));\n" +
	"}\n");
    static final Uniform df_src = u(SAMPLER2D, 0), df_dep = u(SAMPLER2D, 1), df_pp = u(VEC4, 2), df_fa = u(VEC2, 3);
    static final ShaderMacro df_sh = shader(doffn, df_src, df_dep, df_pp, df_fa);

    public static class DoF extends PostProcessor {
	final Depth depth;

	public DoF(PView view) {this.depth = new Depth(view);}

	public int order() {return(-85);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Texture2D.Sampler2D ds = depth.samp();
	    float f = Photo.focus;
	    if(!Photo.on || (f <= 0) || (ds == null)) {
		g.image(new TexRaw(in, true), Coord.z, g.sz());
		return;
	    }
	    float r = 9f * Photo.aperture * (in.tex.sz().y / 1080f + 0.4f);
	    blit(g, in, new Pass(df_sh, in, ds, depth.projparams()[0], new float[] {f, r}));
	}
    }

    /* Light shafts: sunlight scattered in the air, marched through
     * the sun's shadow map, so it streams between trees and past
     * buildings when the sun is low. */
    static final RawFunction shaftfn = new RawFunction(VEC4, "hv_shafts", 9, DEPTHLIB +
	"vec4 hv_shafts(vec4 col, vec2 tc, sampler2D src, sampler2D dep, sampler2D smap, mat4 txf, vec4 pp, vec2 pr, vec4 sun)\n" +
	"{\n" +
	"    /* sun = (view-space direction to the sun, strength); color below */\n" +
	"    vec3 c = texture(src, tc).rgb;\n" +
	"    ivec2 sz = textureSize(dep, 0);\n" +
	"    float d = texelFetch(dep, clamp(ivec2(tc * vec2(sz)), ivec2(0), sz - 1), 0).r;\n" +
	"    vec3 P = hv_vpos(dep, tc, pp, pr);\n" +
	"    if(d >= 0.99999)\n" +
	"        P = normalize(P) * 400.0;\n" +
	"    float len = min(length(P), 600.0);\n" +
	"    vec3 dir = normalize(P);\n" +
	"    float jit = fract(52.9829189 * fract(dot(tc * vec2(sz), vec2(0.06711056, 0.00583715))));\n" +
	"    float lit = 0.0;\n" +
	"    for(int i = 0; i < 20; i++) {\n" +
	"        vec3 s = dir * len * ((float(i) + jit) / 20.0);\n" +
	"        vec4 sc = txf * vec4(s, 1.0);\n" +
	"        sc.xyz /= sc.w;\n" +
	"        if((sc.x < 0.0) || (sc.x > 1.0) || (sc.y < 0.0) || (sc.y > 1.0))\n" +
	"            lit += 1.0;\n" +
	"        else if(texture(smap, sc.xy).r + 0.002 > sc.z)\n" +
	"            lit += 1.0;\n" +
	"    }\n" +
	"    lit /= 20.0;\n" +
	"    float ph = 0.25 + 1.2 * pow(max(dot(dir, sun.xyz), 0.0), 6.0);\n" +
	"    float amt = lit * (1.0 - exp(-len * 0.0009)) * ph * sun.w;\n" +
	"    return(vec4(c + amt * col.rgb, 1.0));\n" +
	"}\n");
    static final Uniform sf_src = u(SAMPLER2D, 0), sf_dep = u(SAMPLER2D, 1), sf_smap = u(SAMPLER2D, 2), sf_txf = u(MAT4, 3),
	sf_pp = u(VEC4, 4), sf_pr = u(VEC2, 5), sf_sun = u(VEC4, 6), sf_col = u(VEC3, 7);
    static final ShaderMacro sf_sh = prog -> {
	shaftfn.define(prog.fctx);
	FragColor.fragcol(prog.fctx).mod(in -> shaftfn.call(vec4(sf_col.ref(), l(1.0)), Tex2D.rtexcoord.ref(), sf_src.ref(), sf_dep.ref(), sf_smap.ref(),
							   sf_txf.ref(), sf_pp.ref(), sf_pr.ref(), sf_sun.ref()), 100);
    };

    public static class Shafts extends PostProcessor {
	final PView view;
	final Depth depth;
	volatile float[] sun = null, suncol = null;

	public Shafts(PView view) {this.view = view; this.depth = new Depth(view);}

	public int order() {return(-140);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    ShadowMap sm = view.basic.state().get(ShadowMap.smap);
	    Camera cam = view.basic.state().get(Homo3D.cam);
	    Texture2D.Sampler2D ds = depth.samp();
	    float[] sun = this.sun, col = this.suncol;
	    Matrix4f txf = ((sm == null) || (cam == null)) ? null : sm.eyetotex(cam);
	    if((txf == null) || (ds == null) || (sun == null) || (sun[3] <= 0)) {
		g.image(new TexRaw(in, true), Coord.z, g.sz());
		return;
	    }
	    float[][] pp = depth.projparams();
	    blit(g, in, new Pass(sf_sh, in, ds, sm.lsamp, txf, pp[0], pp[1], sun, col));
	}
    }
}
