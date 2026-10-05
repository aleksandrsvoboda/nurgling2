package nurgling.render;

import java.util.*;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import haven.RenderContext.PostProcessor;
import static haven.render.sl.Cons.*;
import static haven.render.sl.Type.*;
import static nurgling.render.NPostFX.*;

/*
 * Image-quality passes (graphics options, Vulkan only):
 *
 *  - Temporal anti-aliasing: while the camera moves, subpixel sample
 *    positions advance; at rest the raster offset stays fixed. Frames
 *    blend with reprojected history without shaking stationary detail.
 *  - Auto-exposure: the picture adapts, over a second or two, when
 *    going from daylight into a dark cave or house and back.
 *  - Upscaling: with a render scale below 100%, the picture is scaled
 *    up with an edge-aware filter and re-sharpened (like FSR 1),
 *    instead of plainly stretched.
 */
public class Temporal {
    public static volatile boolean taa = false, upscale = false;

    /* Each view's jitter this frame, in clip space: {dx, dy, frame}.
     * Per view, since with several sessions another session's camera
     * update would otherwise overwrite it between this view's jitter
     * and its TAA pass. */
    private static final class JitterState {
        float dx, dy;
        int frame;
        Matrix4f pose;
        Coord size;
    }
    private static final Map<PView, JitterState> jitters = new WeakHashMap<>();

    private static float halton(int i, int b) {
	float f = 1, r = 0;
	while(i > 0) {
	    f /= b;
	    r += f * (i % b);
	    i /= b;
	}
	return(r);
    }

    /* A state op that nudges the scene's projection for this frame;
     * composed onto the map camera's state. */
    public static Pipe.Op jitter(PView view, Coord rsz) {
	float dx, dy;
	synchronized(jitters) {
	    JitterState j = jitters.computeIfAbsent(view, v -> new JitterState());
	    int i = (j.frame++ & 7) + 1;
	    dx = (2 * (halton(i, 2) - 0.5f)) / Math.max(rsz.x, 1);
	    dy = (2 * (halton(i, 3) - 0.5f)) / Math.max(rsz.y, 1);
	    j.dx = dx; j.dy = dy;
	}
	return(new Jitter(dx, dy));
    }

    static float[] jitterof(PView view) {
	synchronized(jitters) {
	    JitterState j = jitters.get(view);
	    return((j == null) ? new float[2] : new float[] {j.dx, j.dy});
	}
    }

    /** Snapshot once per rendered view, after settings sync. A stationary camera
     * must not keep cycling raster coverage on fine textures and silhouettes. */
    public static Pipe.Op cameraFrame(PView view, Coord size, Pipe.Op camera, boolean enabled) {
        Pipe state = new BufPipe();
        state.prep(camera);
        Projection projection = state.get(Homo3D.prj);
        Camera transform = state.get(Homo3D.cam);
        if(projection == null || transform == null) {
            synchronized(jitters) {jitters.remove(view);}
            return camera;
        }
        Matrix4f pm = new Matrix4f(projection.fin(Matrix4f.id));
        Matrix4f cm = new Matrix4f(transform.fin(Matrix4f.id));
        Pipe.Op snapshot = Pipe.Op.compose(new Projection(pm), new Camera(cm));
        synchronized(jitters) {
            if(!enabled) {
                jitters.remove(view);
                return snapshot;
            }
            JitterState j = jitters.computeIfAbsent(view, v -> new JitterState());
            Matrix4f pose = pm.mul(cm);
            if(j.pose == null || !size.equals(j.size)) {
                j.dx = j.dy = 0;
                j.frame = 0;
            } else if(!Arrays.equals(j.pose.m, pose.m)) {
                jitter(view, size);
            }
            j.pose = pose;
            j.size = new Coord(size);
            return Pipe.Op.compose(snapshot, new Jitter(j.dx, j.dy));
        }
    }

    static class Jitter implements Pipe.Op {
	final float dx, dy;
	Jitter(float dx, float dy) {this.dx = dx; this.dy = dy;}

	public void apply(Pipe p) {
	    Projection pr = p.get(Homo3D.prj);
	    if(pr == null)
		return;
	    Matrix4f j = Transform.makexlate(new Matrix4f(), Coord3f.of(dx, dy, 0));
	    p.put(Homo3D.prj, new Projection(j.mul(pr.fin(Matrix4f.id))));
	}
    }

    /* Unjittered reconstruction, also used when temporal history is unavailable. */
    static final RawFunction presentfn = new RawFunction(VEC4, "hv_taapresent", 4,
	"vec4 hv_taapresent(vec4 col, vec2 tc, sampler2D src, vec2 jitter)\n" +
	"{\n" +
	"    ivec2 sz = textureSize(src, 0);\n" +
	"    vec2 p = (tc + jitter * 0.5) * vec2(sz) - 0.5;\n" +
	"    ivec2 base = ivec2(floor(p));\n" +
	"    vec2 f = fract(p);\n" +
	"    vec4 a = texelFetch(src, clamp(base, ivec2(0), sz - 1), 0);\n" +
	"    vec4 b = texelFetch(src, clamp(base + ivec2(1, 0), ivec2(0), sz - 1), 0);\n" +
	"    vec4 c = texelFetch(src, clamp(base + ivec2(0, 1), ivec2(0), sz - 1), 0);\n" +
	"    vec4 d = texelFetch(src, clamp(base + ivec2(1, 1), ivec2(0), sz - 1), 0);\n" +
	"    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);\n" +
	"}\n");
    static final Uniform present_src = u(SAMPLER2D, 0), present_jitter = u(VEC2, 1);
    static final ShaderMacro present_sh = shader(presentfn, present_src, present_jitter);

    /* Temporal anti-aliasing */

    static final RawFunction taafn = new RawFunction(VEC4, "hv_taa", 9, DEPTHLIB +
	"vec3 hv_ycocg(vec3 c) {return(vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b));}\n" +
	"vec3 hv_rgb(vec3 c) {return(vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z));}\n" +
	"vec4 hv_taa(vec4 col, vec2 tc, sampler2D cur, sampler2D hist, sampler2D dep, mat4 rep, sampler2D olddep, vec4 jitter, vec4 oldpp)\n" +
	"{\n" +
	"    ivec2 sz = textureSize(cur, 0);\n" +
	"    vec2 px = 1.0 / vec2(sz);\n" +
	"    /* Output and color history stay on the stable display grid. Only current\n" +
	"     * color and depth are sampled on the jittered raster grid. */\n" +
	"    vec2 ctc = tc + jitter.xy * 0.5;\n" +
	"    vec3 c = texture(cur, ctc).rgb;\n" +
	"    if(rep[3][3] < -0.5) return vec4(c, 1.0);\n" +
	"    /* Where this point was on screen last frame. */\n" +
	"    ivec2 dsz = textureSize(dep, 0);\n" +
	"    float d = texelFetch(dep, clamp(ivec2(ctc * vec2(dsz)), ivec2(0), dsz - 1), 0).r;\n" +
	"    vec4 pc = rep * vec4(ctc * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);\n" +
	"    if(pc.w <= 0.000001 || d >= 0.99999) return vec4(c, 1.0);\n" +
	"    vec2 oldtc = (pc.xy / pc.w) * 0.5 + 0.5;\n" +
	"    vec2 ptc = oldtc - jitter.zw * 0.5;\n" +
	"    if(any(lessThan(ptc, px * 0.5)) || any(greaterThan(ptc, 1.0 - px * 0.5)))\n" +
	"        return(vec4(c, 1.0));\n" +
	"    float expected = pc.z / pc.w * 0.5 + 0.5;\n" +
	"    ivec2 hsz = textureSize(olddep, 0);\n" +
	"    float oldz = texelFetch(olddep, clamp(ivec2(oldtc * vec2(hsz)), ivec2(0), hsz - 1), 0).r;\n" +
	"    if(expected <= 0.0 || expected >= 1.0 || oldz >= 0.99999) return vec4(c, 1.0);\n" +
	"    float distance = abs(hv_lindist(expected, oldpp));\n" +
	"    if(abs(hv_lindist(oldz, oldpp) - distance) > max(0.25, distance * 0.001)) return vec4(c, 1.0);\n" +
	"    float motion = length((ptc - tc) * vec2(sz));\n" +
	"    float moving = smoothstep(0.5, 6.0, motion);\n" +
	"    /* Keep the history within the colors around this pixel now,\n" +
	"     * so things that moved do not leave trails. */\n" +
	"    vec3 mn = vec3(1e9), mx = vec3(-1e9), m1 = vec3(0.0), m2 = vec3(0.0);\n" +
	"    for(int y = -1; y <= 1; y++) {\n" +
	"        for(int x = -1; x <= 1; x++) {\n" +
	"            vec3 s = hv_ycocg(texture(cur, ctc + vec2(float(x), float(y)) * px).rgb);\n" +
	"            mn = min(mn, s); mx = max(mx, s);\n" +
	"            m1 += s; m2 += s * s;\n" +
	"        }\n" +
	"    }\n" +
	"    m1 /= 9.0; m2 /= 9.0;\n" +
	"    vec3 sd = sqrt(max(m2 - m1 * m1, vec3(0.0)));\n" +
	"    float extent = mix(1.25, 0.75, moving);\n" +
	"    mn = max(mn, m1 - sd * extent); mx = min(mx, m1 + sd * extent);\n" +
	"    vec3 h = hv_ycocg(texture(hist, ptc).rgb);\n" +
	"    h = clamp(h, mn, mx);\n" +
	"    /* Static high-contrast coverage varies with jitter; do not mistake it for\n" +
	"     * motion and discard the accumulated subpixel samples every cycle. */\n" +
	"    float fresh = mix(0.08, 0.80, moving);\n" +
	"    vec3 r = mix(hv_rgb(h), c, fresh);\n" +
	"    return(vec4(r, 1.0));\n" +
	"}\n");
    static final Uniform ta_cur = u(SAMPLER2D, 0), ta_hist = u(SAMPLER2D, 1), ta_dep = u(SAMPLER2D, 2), ta_rep = u(MAT4, 3);
    static final Uniform ta_olddep = u(SAMPLER2D,4), ta_jitter = u(VEC4,5), ta_oldpp = u(VEC4,6);
    static final ShaderMacro ta_sh = shader(taafn, ta_cur, ta_hist, ta_dep, ta_rep, ta_olddep, ta_jitter, ta_oldpp);
    static final RawFunction tadepthfn = new RawFunction(VEC4,"hv_taadepth",3,
        "vec4 hv_taadepth(vec4 col, vec2 tc, sampler2D dep) {\n" +
        " ivec2 sz=textureSize(dep,0);\n" +
        " return vec4(texelFetch(dep,clamp(ivec2(tc*vec2(sz)),ivec2(0),sz-1),0).r,0.0,0.0,1.0);\n" +
        "}\n");
    static final ShaderMacro ta_depth_sh = shader(tadepthfn,ta_cur);

    public static class TAA extends PostProcessor {
	final SceneFX.Depth depth;
	private final Texture2D.Sampler2D[] hist = new Texture2D.Sampler2D[2];
	private Texture2D.Sampler2D histDepth;
	private float[] prevJitter = {0,0}, prevProjection = {0,0,0,0};
	private double lastFrame;
	private int cur = 0;
	private Matrix4f prevvp = null;
	private boolean valid = false;

	public TAA(PView view) {this.depth = new SceneFX.Depth(view);}

	/* All effects which use scene depth run first on the raster grid. */
	public int order() {return(90);}

	/* Match the actual color/depth sample positions, including projection jitter. */
	private Matrix4f vp() {
	    Projection prj = depth.view.basic.state().get(Homo3D.prj);
	    Camera cam = depth.view.basic.state().get(Homo3D.cam);
	    if((prj == null) || (cam == null))
		return(null);
	    return(prj.fin(Matrix4f.id).mul(cam.fin(Matrix4f.id)));
	}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Texture2D.Sampler2D ds = depth.samp();
	    Coord sz = in.tex.sz();
	    NumberFormat cf = in.tex.ifmt.cf;
	    double now = Utils.rtime();
	    if(now - lastFrame > .25) valid = false;
	    lastFrame = now;
	    for(int i = 0; i < 2; i++) {
		if(!fits(hist[i], sz, cf)) {
		    if(hist[i] != null)
			hist[i].dispose();
		    hist[i] = mktarget(sz, cf);
		    valid = false;
		}
	    }
	    if(histDepth == null || !histDepth.tex.sz().equals(sz)) {
	        if(histDepth != null) histDepth.dispose();
	        histDepth = new Texture2D(sz,DataBuffer.Usage.STATIC,new VectorFormat(1,NumberFormat.FLOAT32),null).sampler();
	        histDepth.minfilter(Texture.Filter.NEAREST).magfilter(Texture.Filter.NEAREST).wrapmode(Texture.Wrapping.CLAMP);
	        valid = false;
	    }
	    Matrix4f vp = vp();
	    if((ds == null) || (vp == null)) {
		blit(g, in, new Pass(present_sh, in, jitterof(depth.view)));
		valid = false;
		return;
	    }
	    /* From this frame's screen and depth to last frame's screen. */
	    Matrix4f rep;
	    if(valid && (prevvp != null)) {
		rep = prevvp.mul(vp.invert());
	    } else {
		rep = new Matrix4f(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1);
	    }
	    Texture2D.Sampler2D prev = hist[cur], next = hist[cur ^ 1];
	    float[] jitter = jitterof(depth.view);
	    float[] offsets = {jitter[0], jitter[1], prevJitter[0], prevJitter[1]};
	    blit(target(g, next), in, new Pass(ta_sh, in, prev, ds, rep, histDepth, offsets, prevProjection));
	    // Copy only after the resolve has read the preceding frame's depth.
	    blit(target(g, histDepth), in, new Pass(ta_depth_sh, ds));
	    g.image(new TexRaw(next, true), Coord.z, g.sz());
	    cur ^= 1;
	    prevvp = vp;
	    prevJitter = jitter;
	    prevProjection = depth.projparams()[0];
	    valid = true;
	}

	public void dispose() {
	    super.dispose();
	    synchronized(jitters) {jitters.remove(depth.view);}
	    for(Texture2D.Sampler2D h : hist) {
		if(h != null)
		    h.dispose();
	    }
	    if(histDepth != null) histDepth.dispose();
	}
    }

    /* Auto-exposure */

    static final RawFunction lumfn = new RawFunction(VEC4, "hv_loglum", 4,
	"vec4 hv_loglum(vec4 col, vec2 tc, sampler2D src, sampler2D dep)\n" +
	"{\n" +
	"    vec3 s = vec3(0.0);\n" +
	"    for(int y = 0; y < 4; y++) {\n" +
	"        for(int x = 0; x < 4; x++) {\n" +
	"            vec2 o = (vec2(float(x), float(y)) - 1.5) / 64.0;\n" +
	"            vec2 uv = tc + o * 0.25;\n" +
	"            ivec2 sz = textureSize(dep, 0);\n" +
	"            ivec2 p = clamp(ivec2(uv * vec2(sz)), ivec2(0), sz - 1);\n" +
	"            if(texelFetch(dep, p, 0).r >= 1.0) continue;\n" +
	"            vec3 c = max(texture(src, uv).rgb, vec3(0.0));\n" +
	"            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
	"            /* Keep dark geometry, but exclude the unrendered void around interiors. */\n" +
	"            s += vec3(log(max(l, 0.002)), 1.0, min(l * l, 64.0));\n" +
	"        }\n" +
	"    }\n" +
	"    return(vec4(s / 16.0, 1.0));\n" +
	"}\n");
    static final Uniform lu_src = u(SAMPLER2D, 0), lu_dep = u(SAMPLER2D, 1);
    static final ShaderMacro lu_sh = shader(lumfn, lu_src, lu_dep);

    static final RawFunction halffn = new RawFunction(VEC4, "hv_half", 3,
	"vec4 hv_half(vec4 col, vec2 tc, sampler2D src)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    vec3 s = texture(src, tc + vec2(-0.5, -0.5) * px).rgb + texture(src, tc + vec2(0.5, -0.5) * px).rgb +\n" +
	"             texture(src, tc + vec2(-0.5, 0.5) * px).rgb + texture(src, tc + vec2(0.5, 0.5) * px).rgb;\n" +
	"    return(vec4(s * 0.25, 1.0));\n" +
	"}\n");
	static final Uniform hf_src = u(SAMPLER2D, 0);
    static final ShaderMacro hf_sh = shader(halffn, hf_src);

    static final RawFunction adaptfn = new RawFunction(VEC4, "hv_adapt", 5,
	"vec4 hv_adapt(vec4 col, vec2 tc, sampler2D avg, sampler2D prev, vec3 par)\n" +
	"{\n" +
	"    /* par = (adaptation this frame 0..1, first frame, minimum exposure) */\n" +
	"    vec3 meter = texture(avg, vec2(0.5)).rgb;\n" +
	"    float weight = max(meter.g, 0.000001);\n" +
	"    float lum = exp(meter.r / weight);\n" +
	"    /* Brighten dark places (caves, houses) and tame glare, but\n" +
	"     * only so far: the game's own day and night stay visible. */\n" +
	"    float target = clamp(0.28 / max(lum, 0.001), par.z, 2.2);\n" +
	"    /* Lit floors and objects must not wash out just because shadows dominate. */\n" +
	"    float rms = sqrt(max(meter.b / weight, 0.000001));\n" +
	"    target = min(target, max(par.z, 0.65 / rms));\n" +
	"    if(meter.g < 0.000001) target = 1.0;\n" +
	"    float p = (par.y > 0.5) ? target : texture(prev, vec2(0.5)).r;\n" +
	"    /* Do not carry outdoor dimming into an interior during adaptation. */\n" +
	"    return(vec4(max(par.z, mix(p, target, par.x)), 0.0, 0.0, 1.0));\n" +
	"}\n");
    static final Uniform ad_avg = u(SAMPLER2D, 0), ad_prev = u(SAMPLER2D, 1), ad_par = u(VEC3, 2);
    static final ShaderMacro ad_sh = shader(adaptfn, ad_avg, ad_prev, ad_par);

    public static class AutoExposure extends PostProcessor {
	private final SceneFX.Depth depth;
	private final Texture2D.Sampler2D[] lum = new Texture2D.Sampler2D[7];
	private final Texture2D.Sampler2D[] adapt = new Texture2D.Sampler2D[2];
	private int cur = 0;
	private double last = 0;
	private boolean first = true;
	/* The exposure for this frame, a 1x1 texture. */
	public volatile Texture2D.Sampler2D exposure = null;
	/* Indoors this control is night vision: it may lift darkness, never dim the baseline. */
	public volatile boolean outdoors = false;

	public AutoExposure(PView view) {this.depth = new SceneFX.Depth(view);}

	public int order() {return(-110);}

	public void run(GOut g, Texture2D.Sampler2D in) {
	    Texture2D.Sampler2D ds = depth.samp();
	    if(ds == null) {
		exposure = null;
		first = true;
		g.image(new TexRaw(in, true), Coord.z, g.sz());
		return;
	    }
	    if(lum[0] == null) {
		for(int i = 0; i < 7; i++) {
		    int s = 64 >> i;
		    lum[i] = mktarget(Coord.of(s, s), NumberFormat.FLOAT16);
		}
		for(int i = 0; i < 2; i++)
		    adapt[i] = mktarget(Coord.of(1, 1), NumberFormat.FLOAT16);
	    }
	    blit(target(g, lum[0]), in, new Pass(lu_sh, in, ds));
	    for(int i = 1; i < 7; i++)
		blit(target(g, lum[i]), lum[i - 1], new Pass(hf_sh, lum[i - 1]));
	    double now = Utils.rtime();
	    float dt = (float)Math.min(Math.max(now - last, 0), 0.5);
	    last = now;
	    float a = 1 - (float)Math.exp(-dt * 1.4);
	    Texture2D.Sampler2D prev = adapt[cur], next = adapt[cur ^ 1];
	    blit(target(g, next), lum[6], new Pass(ad_sh, lum[6], prev, new float[] {a, first ? 1 : 0, outdoors ? .75f : 1f}));
	    first = false;
	    cur ^= 1;
	    exposure = next;
	    g.image(new TexRaw(in, true), Coord.z, g.sz());
	}

	public void dispose() {
	    super.dispose();
	    exposure = null;
	    for(Texture2D.Sampler2D s : lum) {
		if(s != null) s.dispose();
	    }
	    for(Texture2D.Sampler2D s : adapt) {
		if(s != null) s.dispose();
	    }
	}
    }

    private static Texture2D.Sampler2D one = null;
    /* Exposure 1, for when auto-exposure is off. */
    public static synchronized Texture2D.Sampler2D one() {
	if(one == null) {
	    byte[] px = {(byte)255, (byte)255, (byte)255, (byte)255};
	    one = new Texture2D(1, 1, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), DataBuffer.Filler.of(px)).sampler();
	}
	return(one);
    }

    /* Upscaling: edge-aware (Lanczos, with ringing held to the local
     * range) then contrast-adaptive sharpening. */

    static final RawFunction easufn = new RawFunction(VEC4, "hv_easu", 3,
	"float hv_lz(float x)\n" +
	"{\n" +
	"    x = abs(x);\n" +
	"    if(x < 1e-4)\n" +
	"        return(1.0);\n" +
	"    if(x >= 2.0)\n" +
	"        return(0.0);\n" +
	"    float px = 3.14159265 * x;\n" +
	"    return(2.0 * sin(px) * sin(px * 0.5) / (px * px));\n" +
	"}\n" +
	"vec4 hv_easu(vec4 col, vec2 tc, sampler2D src)\n" +
	"{\n" +
	"    vec2 sz = vec2(textureSize(src, 0));\n" +
	"    vec2 p = tc * sz - 0.5;\n" +
	"    vec2 f = fract(p);\n" +
	"    vec2 b = (floor(p) + 0.5) / sz;\n" +
	"    vec3 sum = vec3(0.0);\n" +
	"    float ws = 0.0;\n" +
	"    vec3 mn = vec3(1e9), mx = vec3(-1e9);\n" +
	"    for(int y = -1; y <= 2; y++) {\n" +
	"        for(int x = -1; x <= 2; x++) {\n" +
	"            vec3 c = texture(src, b + vec2(float(x), float(y)) / sz).rgb;\n" +
	"            float w = hv_lz(float(x) - f.x) * hv_lz(float(y) - f.y);\n" +
	"            sum += c * w;\n" +
	"            ws += w;\n" +
	"            if((x >= 0) && (x <= 1) && (y >= 0) && (y <= 1)) {\n" +
	"                mn = min(mn, c); mx = max(mx, c);\n" +
	"            }\n" +
	"        }\n" +
	"    }\n" +
	"    return(vec4(clamp(sum / ws, mn, mx), 1.0));\n" +
	"}\n");
    static final Uniform ea_src = u(SAMPLER2D, 0);
    static final ShaderMacro ea_sh = shader(easufn, ea_src);

    static final RawFunction rcasfn = new RawFunction(VEC4, "hv_rcas", 4,
	"vec4 hv_rcas(vec4 col, vec2 tc, sampler2D src, float sharp)\n" +
	"{\n" +
	"    vec2 px = 1.0 / vec2(textureSize(src, 0));\n" +
	"    vec3 e = texture(src, tc).rgb;\n" +
	"    vec3 b = texture(src, tc - vec2(0.0, px.y)).rgb, h = texture(src, tc + vec2(0.0, px.y)).rgb;\n" +
	"    vec3 d = texture(src, tc - vec2(px.x, 0.0)).rgb, f = texture(src, tc + vec2(px.x, 0.0)).rgb;\n" +
	"    vec3 mn = min(e, min(min(b, h), min(d, f))), mx = max(e, max(max(b, h), max(d, f)));\n" +
	"    vec3 hitmin = mn / (4.0 * mx + 1e-4);\n" +
	"    vec3 hitmax = (1.0 - mx) / (4.0 * mn - 4.0 - 1e-4);\n" +
	"    vec3 lobe3 = max(-hitmin, hitmax);\n" +
	"    float lobe = max(-0.1875, min(max(lobe3.r, max(lobe3.g, lobe3.b)), 0.0)) * sharp;\n" +
	"    vec3 r = (lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0);\n" +
	"    return(vec4(clamp(r, 0.0, 1.0), 1.0));\n" +
	"}\n");
    static final Uniform rc_src = u(SAMPLER2D, 0), rc_sharp = u(FLOAT, 1);
    static final ShaderMacro rc_sh = shader(rcasfn, rc_src, rc_sharp);

    private static Texture2D.Sampler2D upbuf = null;

    /* PView's resampling, when upscaling is on. */
    public static void upscale(GOut g, Texture2D.Sampler2D in) {
	Coord osz = g.sz();
	if(!fits(upbuf, osz, NumberFormat.UNORM8)) {
	    if(upbuf != null)
		upbuf.dispose();
	    upbuf = mktarget(osz, NumberFormat.UNORM8);
	}
	blit(target(g, upbuf), in, new Pass(ea_sh, in));
	blit(g, upbuf, new Pass(rc_sh, upbuf, 0.85f));
    }
}
