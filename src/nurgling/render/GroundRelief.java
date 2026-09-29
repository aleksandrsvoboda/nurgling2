package nurgling.render;

import haven.*;
import haven.render.*;
import haven.render.sl.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.util.*;
import static haven.render.sl.Type.*;

/*
 * Relief for terrain. Haven's ground is flat geometry with flat
 * textures; paving, cobbles and rock are painted on.
 *
 * For each ground texture this derives, once, a smooth height map at
 * the scale of the stones: bright regions separated by dark veins
 * become rounded domes whose edges fall off into the veins. That
 * height bends the normal the game's lighting uses, so the sun, moon,
 * fires and torches shade the stones. Because Haven's night light is
 * mostly ambient, a little extra shading from the sun or moon
 * direction (and less light in the deep veins) keeps the relief
 * readable in any lighting.
 *
 * GroundTile and TerrainTile add the state to every ground material;
 * its shader is only present while the effect is on. Toggling
 * rebuilds the map meshes, since draw lists pick programs when meshes
 * are added.
 */
public class GroundRelief {
    public static volatile boolean enabled = false;
    private static volatile float strength = 1.0f;

    /* The stone normal, from the smooth height map. */
    static final String BUMP =
	"vec3 hv_rbump(vec3 n, vec3 p, sampler2D hm, vec2 tc, float k)\n" +
	"{\n" +
	"    /* Height in world units; the derivatives of a smooth height\n" +
	"     * give a smooth, stone-scale slope. */\n" +
	"    float hw = texture(hm, tc).r * k * 0.8;\n" +
	"    vec3 dpx = dFdx(p), dpy = dFdy(p);\n" +
	"    float dhx = dFdx(hw), dhy = dFdy(hw);\n" +
	"    vec3 r1 = cross(dpy, n), r2 = cross(n, dpx);\n" +
	"    float det = dot(dpx, r1);\n" +
	"    vec3 grad = sign(det) * (dhx * r1 + dhy * r2);\n" +
	"    vec3 m = abs(det) * n - grad;\n" +
	"    float l = length(m);\n" +
	"    return((l > 0.0) ? (m / l) : n);\n" +
	"}\n";

    /* Bends the normal the game's own lighting uses, so the sun,
     * moon, fires and torches all shade the stones. */
    static final RawFunction nfn = new RawFunction(VEC3, "hv_rnorm", 5, BUMP +
	"vec3 hv_rnorm(vec3 n, vec3 p, sampler2D hm, vec2 tc, float k)\n" +
	"{\n" +
	"    return(hv_rbump(n, p, hm, tc, k));\n" +
	"}\n");

    /* Keeps the relief readable when the light is mostly ambient
     * (night, night vision): a little extra shading from the sun or
     * moon direction, and deep veins get less light. */
    static final RawFunction cfn = new RawFunction(VEC4, "hv_rcol", 6,
	"vec4 hv_rcol(vec4 col, vec3 p, sampler2D hm, vec2 tc, vec3 L, float k)\n" +
	"{\n" +
	"    vec3 n = normalize(cross(dFdx(p), dFdy(p)));\n" +
	"    if(dot(n, p) > 0.0)\n" +
	"        n = -n;\n" +
	"    float hw = texture(hm, tc).r;\n" +
	"    float hs = hw * k * 0.8;\n" +
	"    vec3 dpx = dFdx(p), dpy = dFdy(p);\n" +
	"    float dhx = dFdx(hs), dhy = dFdy(hs);\n" +
	"    vec3 r1 = cross(dpy, n), r2 = cross(n, dpx);\n" +
	"    float det = dot(dpx, r1);\n" +
	"    vec3 m = abs(det) * n - sign(det) * (dhx * r1 + dhy * r2);\n" +
	"    vec3 bn = (length(m) > 0.0) ? normalize(m) : n;\n" +
	"    float s = 1.0 + 0.55 * (dot(bn, L) - dot(n, L));\n" +
	"    s *= mix(1.0 - 0.22 * min(k, 1.5), 1.04, smoothstep(0.0, 0.6, hw));\n" +
	"    return(vec4(col.rgb * clamp(s, 0.5, 1.35), col.a));\n" +
	"}\n");

    /* Direction towards the sun or moon, in view space. */
    static final Uniform usun = new Uniform(VEC3, p -> {
	    float[] dir = null;
	    Light.LightList ll = p.get(Light.lights);
	    if(ll != null) {
		synchronized(ll.ll) {
		    for(RenderList.Slot<Light> sl : ll.ll) {
			if(sl.obj() instanceof DirLight) {
			    dir = ((DirLight)sl.obj()).dir;
			    break;
			}
		    }
		}
	    }
	    Camera cam = p.get(Homo3D.cam);
	    if((dir == null) || (cam == null))
		return(new float[] {-0.55f, 0.65f, 0.5f});
	    float[] e = cam.fin(Matrix4f.id).mul4(new float[] {dir[0], dir[1], dir[2], 0});
	    float l = (float)Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
	    if(l < 1e-6f)
		return(new float[] {-0.55f, 0.65f, 0.5f});
	    return(new float[] {e[0] / l, e[1] / l, e[2] / l});
	}, Light.lights, Homo3D.cam);

    /* Height maps */

    private static final Map<TexRender, Texture2D.Sampler2D> heights = new WeakHashMap<>();
    private static Texture2D.Sampler2D flat = null;

    private static Texture2D.Sampler2D sampler(Texture2D tex) {
	Texture2D.Sampler2D ret = tex.sampler();
	ret.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR);
	if(tex.images().size() > 1)
	    ret.mipfilter(Texture.Filter.LINEAR);
	ret.wrapmode(Texture.Wrapping.REPEAT);
	return(ret);
    }

    private static synchronized Texture2D.Sampler2D flat() {
	if(flat == null) {
	    byte[] px = {(byte)255, (byte)255, (byte)255, (byte)255};
	    flat = sampler(new Texture2D(1, 1, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), DataBuffer.Filler.of(px)));
	}
	return(flat);
    }

    /* Box blur with wrap-around, in place, radius r. */
    private static void blur(float[] a, int w, int h, int r) {
	float[] tmp = new float[Math.max(w, h)];
	float n = 2 * r + 1;
	for(int y = 0; y < h; y++) {
	    float sum = 0;
	    for(int i = -r; i <= r; i++)
		sum += a[y * w + Math.floorMod(i, w)];
	    for(int x = 0; x < w; x++) {
		tmp[x] = sum / n;
		sum += a[y * w + Math.floorMod(x + r + 1, w)] - a[y * w + Math.floorMod(x - r, w)];
	    }
	    System.arraycopy(tmp, 0, a, y * w, w);
	}
	for(int x = 0; x < w; x++) {
	    float sum = 0;
	    for(int i = -r; i <= r; i++)
		sum += a[Math.floorMod(i, h) * w + x];
	    for(int y = 0; y < h; y++) {
		tmp[y] = sum / n;
		sum += a[Math.floorMod(y + r + 1, h) * w + x] - a[Math.floorMod(y - r, h) * w + x];
	    }
	    for(int y = 0; y < h; y++)
		a[y * w + x] = tmp[y];
	}
    }

    private static float smoothstep(float e0, float e1, float x) {
	float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
	return(t * t * (3 - 2 * t));
    }

    static float[] heightmap(BufferedImage img) {
	int w = img.getWidth(), h = img.getHeight();
	float[] lum = new float[w * h];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		int c = img.getRGB(x, y);
		lum[y * w + x] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
	    }
	}
	int sz = Math.min(w, h);
	/* Stones are brighter than the local average, veins darker. */
	float[] mean = lum.clone();
	int rm = Math.max(3, sz / 20);
	blur(mean, w, h, rm);
	blur(mean, w, h, rm);
	float[] s = new float[w * h];
	for(int i = 0; i < s.length; i++)
	    s[i] = smoothstep(-0.035f, 0.05f, lum[i] - mean[i]);
	/* Round the stone plateaus into domes: repeated blurs of the
	 * stone mask approximate distance from the veins. */
	float[] a = s.clone(), b = s.clone();
	int r1 = Math.max(1, sz / 128), r2 = Math.max(2, sz / 64);
	blur(a, w, h, r1); blur(a, w, h, r1);
	blur(b, w, h, r2); blur(b, w, h, r2);
	float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
	for(int i = 0; i < s.length; i++) {
	    float v = (0.35f * a[i]) + (0.65f * b[i]);
	    s[i] = v;
	    lo = Math.min(lo, v);
	    hi = Math.max(hi, v);
	}
	float range = Math.max(hi - lo, 0.0001f);
	for(int i = 0; i < s.length; i++)
	    s[i] = (s[i] - lo) / range;
	return(s);
    }

    private static Texture2D.Sampler2D mkheight(BufferedImage img) {
	int w = img.getWidth(), h = img.getHeight();
	float[] hm = heightmap(img);
	boolean pot = ((w & (w - 1)) == 0) && ((h & (h - 1)) == 0);
	List<byte[]> levels = new ArrayList<>();
	float[] cur = hm;
	int cw = w, ch = h;
	while(true) {
	    byte[] px = new byte[cw * ch * 4];
	    for(int i = 0; i < cw * ch; i++) {
		byte v = (byte)Math.round(Math.max(0, Math.min(1, cur[i])) * 255);
		px[i * 4] = px[i * 4 + 1] = px[i * 4 + 2] = v;
		px[i * 4 + 3] = (byte)255;
	    }
	    levels.add(px);
	    if(!pot || ((cw == 1) && (ch == 1)))
		break;
	    int nw = Math.max(cw / 2, 1), nh = Math.max(ch / 2, 1);
	    float[] nxt = new float[nw * nh];
	    for(int y = 0; y < nh; y++) {
		for(int x = 0; x < nw; x++) {
		    int x0 = Math.min(x * 2, cw - 1), x1 = Math.min(x * 2 + 1, cw - 1);
		    int y0 = Math.min(y * 2, ch - 1), y1 = Math.min(y * 2 + 1, ch - 1);
		    nxt[y * nw + x] = (cur[y0 * cw + x0] + cur[y0 * cw + x1] + cur[y1 * cw + x0] + cur[y1 * cw + x1]) * 0.25f;
		}
	    }
	    cur = nxt; cw = nw; ch = nh;
	}
	Texture2D tex = new Texture2D(w, h, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8),
				      (DataBuffer.Filler<Texture.Image>)(img2, env) -> {
					  if(img2.level >= levels.size())
					      return(null);
					  FillBuffer buf = env.fillbuf(img2);
					  buf.pull(ByteBuffer.wrap(levels.get(img2.level)));
					  return(buf);
				      });
	return(sampler(tex));
    }

    static Texture2D.Sampler2D heightfor(TexRender.TexDraw draw) {
	if((draw == null) || !(draw.tex instanceof TexL))
	    return(flat());
	TexL tex = (TexL)draw.tex;
	synchronized(heights) {
	    Texture2D.Sampler2D ret = heights.get(tex);
	    if(ret != null)
		return(ret);
	}
	Texture2D.Sampler2D ret;
	try {
	    BufferedImage img = tex.fill();
	    ret = (img == null) ? flat() : mkheight(img);
	} catch(Loading l) {
	    throw(l);
	} catch(RuntimeException e) {
	    new Warning(e, "could not derive ground relief for " + tex).issue();
	    ret = flat();
	}
	synchronized(heights) {
	    heights.put(tex, ret);
	}
	return(ret);
    }

    static final Uniform uheight = new Uniform(SAMPLER2D, p -> heightfor(p.get(TexRender.TexDraw.slot)), TexRender.TexDraw.slot);

    private static final Map<Float, ShaderMacro> macros = new HashMap<>();

    private static ShaderMacro macro(float k) {
	synchronized(macros) {
	    return(macros.computeIfAbsent(k, key -> prog -> {
			nfn.define(prog.fctx);
			cfn.define(prog.fctx);
			Homo3D.frageyen(prog.fctx).mod(in -> nfn.call(in, Homo3D.frageyev.ref(), uheight.ref(), Tex2D.rtexcoord.ref(), Cons.l(key)), 10);
			FragColor.fragcol(prog.fctx).mod(in -> cfn.call(in, Homo3D.frageyev.ref(), uheight.ref(), Tex2D.rtexcoord.ref(), usun.ref(), Cons.l(key)), 1000);
		    }));
	}
    }

    public static final State.Slot<State> slot = new State.Slot<>(State.Slot.Type.DRAW, State.class);
    public static final State state = new State() {
	    public ShaderMacro shader() {
		return(enabled ? macro(strength) : null);
	    }

	    public void apply(Pipe p) {
		p.put(slot, this);
	    }

	    public String toString() {return("#<ground-relief>");}
	};

    /* Returns whether anything changed (and the map must be rebuilt). */
    public static boolean set(boolean on, float k) {
	k = Math.round(k * 20) / 20.0f;
	boolean ch = (on != enabled) || (on && (k != strength));
	enabled = on;
	strength = k;
	return(ch);
    }
}
