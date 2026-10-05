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
     *   heat shimmer (after temporal AA)           -88
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

    /* Keeps the last frame's picture and depth for soft smoke. Water has
     * its own immutable current-frame inputs, before this pass. */
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

    /* Heat shimmer follows flame geometry, not the point light's radius/offset. */
    static final int NHEAT = 6;
    static final RawFunction heatfn = new RawFunction(VEC4, "hv_heat", 17, FireFX.NOISE +
	"vec4 hv_heat(vec4 col, vec2 tc, sampler2D src, float t, sampler2D dep, vec4 s0, vec4 s1, vec4 s2, vec4 s3, vec4 s4, vec4 s5, vec4 p0, vec4 p1, vec4 p2, vec4 p3, vec4 p4, vec4 p5)\n" +
	"{\n" +
	"    vec4 s[6] = vec4[6](s0, s1, s2, s3, s4, s5);\n" +
	"    vec4 shape[6] = vec4[6](p0, p1, p2, p3, p4, p5);\n" +
	"    vec2 size = vec2(textureSize(src,0));\n" +
	"    float sceneDepth = texture(dep,tc).r;\n" +
	"    vec2 disp = vec2(0.0);\n" +
	"    for(int i = 0; i < 6; i++) {\n" +
	"        vec2 up = s[i].zw * size;\n" +
	"        float ul = dot(up, up);\n" +
	"        if(ul < 1e-8)\n" +
	"            continue;\n" +
	"        vec2 rel = (tc - s[i].xy) * size;\n" +
	"        float h = dot(rel, up) / ul;\n" +
	"        if(h <= 0.0 || h >= 1.0) continue;\n" +
	"        float plumeDepth = mix(shape[i].y,shape[i].z,h);\n" +
	"        if(sceneDepth + 0.00001 < plumeDepth) continue;\n" +
	"        vec2 perp = rel - up * h;\n" +
	"        float w = shape[i].x * (0.65 + 0.25*h);\n" +
	"        float fall = (1.0 - smoothstep(0.0, w, length(perp))) * smoothstep(0.0, 0.12, h) * (1.0 - smoothstep(0.45, 1.0, h));\n" +
	"        if(fall <= 0.0)\n" +
	"            continue;\n" +
	"        vec2 q = rel / max(shape[i].x,1.0) * 2.0 + shape[i].w;\n" +
	"        float nx = hv_fnoise(vec3(q.x, q.y + t * 3.5, t * 0.8)) - 0.5;\n" +
	"        float ny = hv_fnoise(vec3(q.x + 9.1, q.y + t * 3.5, t * 0.8 + 4.0)) - 0.5;\n" +
	"        float strength = min(shape[i].x * 0.45, clamp(shape[i].x * 0.28, 0.8, 3.5));\n" +
	"        vec2 delta = vec2(nx, ny) * fall * strength / size;\n" +
	"        if(texture(dep,tc+delta).r + 0.00001 >= plumeDepth) disp += delta;\n" +
	"    }\n" +
	"    return(vec4(texture(src, tc + disp).rgb, col.a));\n" +
	"}\n");
    static final Uniform ht_src = u(SAMPLER2D, 0), ht_t = u(FLOAT, 1), ht_dep=u(SAMPLER2D,2);
    static final Uniform[] ht_s = new Uniform[NHEAT];
    static final Uniform[] ht_p = new Uniform[NHEAT];
    static {
	for(int i = 0; i < NHEAT; i++)
	    ht_s[i] = u(VEC4, 3 + i);
        for(int i=0;i<NHEAT;i++) ht_p[i]=u(VEC4,3+NHEAT+i);
    }
    static final ShaderMacro ht_sh = shader(heatfn, ht_src, ht_t, ht_dep, ht_s[0], ht_s[1], ht_s[2], ht_s[3], ht_s[4], ht_s[5],ht_p[0],ht_p[1],ht_p[2],ht_p[3],ht_p[4],ht_p[5]);

    public static class Heat extends PostProcessor {
	final PView view;
        final Depth depth;
        final VolumeFire.Sources sources=new VolumeFire.Sources();

	public Heat(PView view) {this.view = view;depth=new Depth(view);sources.syncadd(view.tree,Rendered.class);}

        /* Refraction has no motion vectors: accumulating it in TAA erases small ripples. */
	public int order() {return(-88);}

	private static float[] project(Matrix4f pm, Coord3f p) {
	    float[] c = pm.mul4(new float[] {p.x, p.y, p.z, 1});
	    if(c[3] <= 0.01f)
		return(null);
	    return(new float[] {(c[0] / c[3]) * 0.5f + 0.5f, (c[1] / c[3]) * 0.5f + 0.5f,(c[2]/c[3])*.5f+.5f});
	}

        static float[][] plume(Matrix4f mvp,float[][] bounds,Coord size) {
            float[] lo=bounds[0],span=bounds[1];
            Coord3f origin=new Coord3f(lo[0]+span[0]*.5f,lo[1]+span[1]*.5f,lo[2]+span[2]*.35f);
            float[] a=project(mvp,origin),b=project(mvp,origin.add(0,0,span[2]*.85f));
            float[] x=project(mvp,origin.add(span[0]*.3f,0,0)),y=project(mvp,origin.add(0,span[1]*.3f,0));
            if(a==null || b==null || x==null || y==null) return null;
            float radius=(float)Math.max(Math.hypot((x[0]-a[0])*size.x,(x[1]-a[1])*size.y),Math.hypot((y[0]-a[0])*size.x,(y[1]-a[1])*size.y));
            if(radius<.25f) return null;
            float margin=radius/Math.min(size.x,size.y);
            if(Math.max(a[0],b[0])+margin<0 || Math.min(a[0],b[0])-margin>1 || Math.max(a[1],b[1])+margin<0 || Math.min(a[1],b[1])-margin>1) return null;
            return new float[][]{{a[0],a[1],b[0]-a[0],b[1]-a[1]},{radius,a[2],b[2],0}};
        }

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Object[] vals = new Object[3 + NHEAT*2];
	    vals[0] = in;
	    vals[1] = (float)(Utils.rtime() % 3000.0);
            vals[2]=depth.samp();
            for(int i=3;i<vals.length;i++) vals[i]=new float[4];
            List<RenderList.Slot<? extends Rendered>> copy;
            synchronized(sources.slots) {copy=new ArrayList<>(sources.slots);}
            List<float[][]> plumes=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            try(Locked lock=view.tree.lock()) {
                for(RenderList.Slot<? extends Rendered> source:copy) {
                    Pipe st=source.state();
                    if(!VolumeFire.candidate(source.obj(),st)) continue;
                    float[][] bounds=VolumeFire.bounds((FastMesh)source.obj());
                    Matrix4f loc=Homo3D.locxf(st);
                    if(!seen.add(Arrays.toString(loc.m)+Arrays.deepToString(bounds))) continue;
                    float[][] plume=plume(Homo3D.prjxf(st).mul(Homo3D.camxf(st)).mul(loc),bounds,in.tex.sz());
                    if(plume!=null) {plume[1][3]=loc.m[12]*.13f+loc.m[13]*.17f;plumes.add(plume);}
                }
            }
            plumes.sort((a,b)->Float.compare(b[1][0],a[1][0]));
            for(int i=0;i<Math.min(NHEAT,plumes.size());i++) {vals[3+i]=plumes.get(i)[0];vals[3+NHEAT+i]=plumes.get(i)[1];}
		if(plumes.isEmpty() || vals[2]==null) {
		    g.image(new TexRaw(in, true), Coord.z, g.sz());
		    return;
		}
	    blit(g, in, new Pass(ht_sh, vals));
	}
        public void dispose() {view.tree.remove(sources);synchronized(sources.slots){sources.slots.clear();}super.dispose();}
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
	    DirectionalShadows.Sun modern = view.basic.state().get(DirectionalShadows.slot);
	    if(modern != null) sm = modern.far;
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
