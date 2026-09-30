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
 * Terrain: GroundTile and TerrainTile add the state to every ground
 * material. World objects (houses, ovens, barrels; resources under
 * gfx/terobjs/): the per-pixel Phong lighting state adds the same
 * shader through phong(), as do the tile textures (brick, stone)
 * objects are built from. Metal is kept smooth and given a sheen
 * instead; cel-shaded materials get a stronger sun term (cel()), and
 * damage cracks are cut in (CrackTex). Characters, animals and items
 * are left alone, since painted faces and clothing would turn lumpy.
 *
 * Height maps are built on the background loader; materials wait for
 * theirs the same way they wait for their textures. The shaders are
 * only present while the effects are on; toggling rebuilds the draw
 * lists' programs.
 */
public class GroundRelief {
    public static volatile boolean enabled = false, objects = false;
    private static volatile float strength = 1.0f, ostrength = 0.6f;

    /* The stone normal, from a smooth height (in world units): the
     * screen-space derivatives of a smooth height give a smooth,
     * stone-scale slope, with no tangents needed. */
    static final String BUMP =
	"vec3 hv_rbump(vec3 n, vec3 p, float hw)\n" +
	"{\n" +
	"    vec3 dpx = dFdx(p), dpy = dFdy(p);\n" +
	"    float dhx = dFdx(hw), dhy = dFdy(hw);\n" +
	"    vec3 r1 = cross(dpy, n), r2 = cross(n, dpx);\n" +
	"    float det = dot(dpx, r1);\n" +
	"    vec3 grad = sign(det) * (dhx * r1 + dhy * r2);\n" +
	"    vec3 m = abs(det) * n - grad;\n" +
	"    float l = length(m);\n" +
	"    return((l > 0.0) ? (m / l) : n);\n" +
	"}\n" +
	/* Height in world units. Ground (sc < 0) uses a fixed depth.
	 * Objects measure depth in texels of their own texture (sc,
	 * calibrated per texture), so a texture squeezed onto a small
	 * barrel and one stretched over a roof get the same slopes. */
	"float hv_rdepth(sampler2D hm, vec2 tc, vec3 p, float h, float k, float sc)\n" +
	"{\n" +
	"    if(sc < 0.0)\n" +
	"        return(h * k * 0.8);\n" +
	"    vec2 dx = dFdx(tc), dy = dFdy(tc);\n" +
	"    vec2 ts = vec2(textureSize(hm, 0));\n" +
	"    float uva = abs(dx.x * dy.y - dx.y * dy.x) * ts.x * ts.y;\n" +
	"    float wa = length(cross(dFdx(p), dFdy(p)));\n" +
	"    float wpt = sqrt(wa / max(uva, 1e-12));\n" +
	"    return(h * k * sc * wpt);\n" +
	"}\n" +
	"vec3 hv_rfacen(vec3 p)\n" +
	"{\n" +
	"    vec3 n = normalize(cross(dFdx(p), dFdy(p)));\n" +
	"    return((dot(n, p) > 0.0) ? -n : n);\n" +
	"}\n";
    static final RawFunction bumpdef = new RawFunction(VEC3, "hv_rbump", 3, BUMP);

    /* Defines a relief function and the helpers it uses. */
    static void define(Context ctx, RawFunction fn) {
	bumpdef.define(ctx);
	fn.define(ctx);
    }

    /* Bends the normal the game's own lighting uses, so the sun,
     * moon, fires and torches all shade the stones. */
    static final RawFunction nfn = new RawFunction(VEC3, "hv_rnorm", 6,
	"vec3 hv_rnorm(vec3 n, vec3 p, sampler2D hm, vec2 tc, float k, float sc)\n" +
	"{\n" +
	"    return(hv_rbump(n, p, hv_rdepth(hm, tc, p, texture(hm, tc).r, k, sc)));\n" +
	"}\n");

    /* Keeps the relief readable when the light is mostly ambient
     * (night, night vision): a little extra shading from the sun or
     * moon direction (gain g), and deep veins get less light. */
    static final RawFunction cfn = new RawFunction(VEC4, "hv_rcol", 8,
	"vec4 hv_rcol(vec4 col, vec3 p, sampler2D hm, vec2 tc, vec3 L, float k, float g, float sc)\n" +
	"{\n" +
	"    vec3 n = hv_rfacen(p);\n" +
	"    float hw = texture(hm, tc).r;\n" +
	"    vec3 bn = hv_rbump(n, p, hv_rdepth(hm, tc, p, hw, k, sc));\n" +
	"    float s = 1.0 + g * (dot(bn, L) - dot(n, L));\n" +
	"    s *= mix(1.0 - 0.22 * min(k, 1.5), 1.0, smoothstep(0.0, 0.6, hw));\n" +
	"    return(vec4(col.rgb * clamp(s, 0.5, 1.35), col.a));\n" +
	"}\n");

    /* Metal sheen: a sharp highlight from the sun or moon, a sky
     * reflection that is brighter facing up, and a bright rim at
     * grazing angles, all tinted by the metal's own color. */
    static final RawFunction mfn = new RawFunction(VEC4, "hv_rmetal", 5,
	"vec4 hv_rmetal(vec4 col, vec3 p, vec3 n, vec3 L, float m)\n" +
	"{\n" +
	"    if(m <= 0.0)\n" +
	"        return(col);\n" +
	"    vec3 v = normalize(-p);\n" +
	"    n = normalize(n);\n" +
	"    if(dot(n, v) < 0.0)\n" +
	"        n = -n;\n" +
	"    vec3 r = reflect(-v, n);\n" +
	"    float sp = pow(max(dot(r, L), 0.0), 24.0);\n" +
	"    float sky = 0.5 + 0.5 * r.y;\n" +
	"    float fr = pow(1.0 - max(dot(n, v), 0.0), 3.0);\n" +
	"    vec3 c = col.rgb * (0.75 + 0.6 * sky * sky) + col.rgb * (sp * 1.8 + fr * 0.6) + vec3(sp * 0.3);\n" +
	"    return(vec4(mix(col.rgb, c, m), col.a));\n" +
	"}\n");

    /* Damage cracks are cut into the surface: a softened copy of
     * the crack pattern is a groove whose edges catch the light on
     * one side and fall into shadow on the other. */
    public static final RawFunction crackn = new RawFunction(VEC3, "hv_crackn", 5,
	"vec3 hv_crackn(vec3 n, vec3 p, sampler3D t, vec3 c, float k)\n" +
	"{\n" +
	"    float a = 0.6 * texture(t, c, 2.0).r + 0.4 * texture(t, c).r;\n" +
	"    return(hv_rbump(n, p, -a * k));\n" +
	"}\n");
    public static final RawFunction crackc = new RawFunction(VEC4, "hv_crackc", 6,
	"vec4 hv_crackc(vec4 col, vec3 p, sampler3D t, vec3 c, vec3 L, float k)\n" +
	"{\n" +
	"    vec3 n = hv_rfacen(p);\n" +
	"    float a = 0.6 * texture(t, c, 2.0).r + 0.4 * texture(t, c).r;\n" +
	"    vec3 bn = hv_rbump(n, p, -a * k);\n" +
	"    float s = 1.0 + 1.3 * (dot(bn, L) - dot(n, L));\n" +
	"    s *= 1.0 - 0.45 * smoothstep(0.1, 0.7, a);\n" +
	"    return(vec4(col.rgb * clamp(s, 0.3, 1.5), col.a));\n" +
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

    /* A texture's height map and its object calibration. */
    static class Relief {
	final Texture2D.Sampler2D map;
	final float scale;
	Relief(Texture2D.Sampler2D map, float scale) {this.map = map; this.scale = scale;}
    }

    private static final Map<TexRender, Relief> heights = new WeakHashMap<>();
    private static Texture2D.Sampler2D flat = null;
    private static Relief flatr = null;

    private static synchronized Relief flatr() {
	if(flatr == null)
	    flatr = new Relief(flat(), 0);
	return(flatr);
    }

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
    static void blur(float[] a, int w, int h, int r) {
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

    static float smoothstep(float e0, float e1, float x) {
	float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
	return(t * t * (3 - 2 * t));
    }

    /* Target mean slope of object relief at strength 1. */
    static final float SLOPE = 1.0f;

    static float[] heightmap(BufferedImage img) {
	return(heightmap(img, null));
    }

    /* stats, if given, receives the calibration for object relief:
     * the depth (in texels) giving the target mean slope, less for
     * faint textures whose painted detail is subtle. */
    static float[] heightmap(BufferedImage img, float[] stats) {
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
	/* Which side is the network of grooves: on cobbles the thin
	 * lines are dark, but on brick the mortar between is light.
	 * The thin lines are the minority that strays far from the
	 * local average, so they show as the long tail (skew) of the
	 * difference. */
	double m2 = 0, m3 = 0;
	for(int i = 0; i < lum.length; i++) {
	    double d = lum[i] - mean[i];
	    m2 += d * d;
	    m3 += d * d * d;
	}
	m2 /= lum.length; m3 /= lum.length;
	float pol = ((m2 > 0) && ((m3 / Math.pow(m2, 1.5)) > 0.35)) ? -1 : 1;
	float[] s = new float[w * h];
	for(int i = 0; i < s.length; i++)
	    s[i] = smoothstep(-0.035f, 0.05f, pol * (lum[i] - mean[i]));
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
	if(stats != null) {
	    double g = 0;
	    for(int y = 0; y < h; y++) {
		for(int x = 0; x < w; x++) {
		    float c = s[y * w + x];
		    float dx = s[y * w + ((x + 1) % w)] - c, dy = s[((y + 1) % h) * w + x] - c;
		    g += Math.sqrt((dx * dx) + (dy * dy));
		}
	    }
	    g /= (w * h);
	    float depth = (g > 1e-5) ? (float)Math.min(SLOPE / g, 80) : 0;
	    float contrast = (float)Math.sqrt(m2);
	    stats[0] = depth * Math.max(0.35f, Math.min(1.0f, contrast / 0.07f));
	}
	return(s);
    }

    static float[] luminance(BufferedImage img) {
	int w = img.getWidth(), h = img.getHeight();
	float[] lum = new float[w * h];
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		int c = img.getRGB(x, y);
		lum[y * w + x] = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
	    }
	}
	return(lum);
    }

    /* The depth (in texels) giving a height map the target mean slope. */
    static float calibrate(float[] s, int w, int h) {
	double g = 0;
	for(int y = 0; y < h; y++) {
	    for(int x = 0; x < w; x++) {
		float c = s[y * w + x];
		float dx = s[y * w + ((x + 1) % w)] - c, dy = s[((y + 1) % h) * w + x] - c;
		g += Math.sqrt((dx * dx) + (dy * dy));
	    }
	}
	g /= (w * h);
	return((g > 1e-5) ? (float)Math.min(SLOPE / g, 80) : 0);
    }

    /* name: the texture's resource, to tell what it is made of (see
     * Materials); objects only, the ground is all stone and soil. */
    private static Relief mkheight(BufferedImage img, String name, boolean objs) {
	int w = img.getWidth(), h = img.getHeight();
	float[] stats = new float[1];
	Materials.Kind kind = objs ? Materials.classify(name, img, luminance(img)) : Materials.Kind.STONE;
	if(kind == Materials.Kind.FLAT)
	    return(flatr());
	float[] hm;
	if(kind == Materials.Kind.STONE) {
	    hm = heightmap(img, stats);
	} else {
	    float[] lum = luminance(img);
	    hm = (kind == Materials.Kind.WOOD) ? Materials.wood(lum, w, h) : Materials.fine(lum, w, h);
	    stats[0] = calibrate(hm, w, h);
	}
	stats[0] *= kind.depth;
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
	return(new Relief(sampler(tex), stats[0]));
    }

    private static final Map<TexRender, Defer.Future<Relief>> pending = new WeakHashMap<>();

    /* objs: only world-object textures get a height map. Throws
     * Loading until the map is built. */
    static Relief heightfor(TexRender.TexDraw draw, boolean objs) {
	if((draw == null) || !(draw.tex instanceof TexL))
	    return(flatr());
	TexL tex = (TexL)draw.tex;
	if(objs) {
	    /* World objects, and the tile textures (brick, stone)
	     * that ovens and smelters are built from. Metal is kept
	     * smooth and made shiny instead. */
	    String nm = tex.loadname();
	    if((nm == null) || !(nm.contains("gfx/terobjs/") || nm.contains("gfx/tiles/")) || metal(nm))
		return(flatr());
	}
	synchronized(heights) {
	    Relief ret = heights.get(tex);
	    if(ret != null)
		return(ret);
	}
	Defer.Future<Relief> f;
	synchronized(pending) {
	    f = pending.get(tex);
	    if(f == null) {
		pending.put(tex, f = Defer.later(() -> {
			    try {
				BufferedImage img = tex.fill();
				return((img == null) ? flatr() : mkheight(img, tex.loadname(), objs));
			    } catch(Loading l) {
				throw(l);
			    } catch(RuntimeException e) {
				new Warning(e, "could not derive relief for " + tex).issue();
				return(flatr());
			    }
			}));
	    }
	}
	Relief ret = f.get();
	synchronized(heights) {
	    heights.put(tex, ret);
	}
	synchronized(pending) {
	    pending.remove(tex);
	}
	return(ret);
    }

    private static final String[] metals = {
	"iron", "bronze", "copper", "gold", "silver", "steel", "tin", "lead", "metal", "zinc", "brass",
    };

    static boolean metal(String nm) {
	if((nm == null) || !nm.contains("gfx/terobjs/subst/"))
	    return(false);
	String sub = nm.substring(nm.lastIndexOf('/') + 1);
	for(String m : metals) {
	    if(sub.contains(m))
		return(true);
	}
	return(false);
    }

    private static float metalfor(TexRender.TexDraw draw) {
	if((draw == null) || !(draw.tex instanceof TexL))
	    return(0);
	return(metal(((TexL)draw.tex).loadname()) ? 1 : 0);
    }

    static final Uniform uheight = new Uniform(SAMPLER2D, p -> heightfor(p.get(TexRender.TexDraw.slot), false).map, TexRender.TexDraw.slot);
    static final Uniform uoheight = new Uniform(SAMPLER2D, p -> heightfor(p.get(TexRender.TexDraw.slot), true).map, TexRender.TexDraw.slot);
    static final Uniform uoscale = new Uniform(FLOAT, p -> heightfor(p.get(TexRender.TexDraw.slot), true).scale, TexRender.TexDraw.slot);
    static final Uniform umetal = new Uniform(FLOAT, p -> metalfor(p.get(TexRender.TexDraw.slot)), TexRender.TexDraw.slot);

    private static final Map<Float, ShaderMacro> macros = new HashMap<>();

    private static ShaderMacro mkmacro(Uniform hm, float k, boolean objs) {
	java.util.function.Supplier<Expression> sc = () -> objs ? uoscale.ref() : Cons.l(-1.0);
	return(prog -> {
		define(prog.fctx, nfn);
		define(prog.fctx, cfn);
		Homo3D.frageyen(prog.fctx).mod(in -> {
			if(!textured(prog))
			    return(in);
			return(nfn.call(in, Homo3D.frageyev.ref(), hm.ref(), Tex2D.rtexcoord.ref(), Cons.l(k), sc.get()));
		    }, 10);
		FragColor.fragcol(prog.fctx).mod(in -> {
			if(!textured(prog))
			    return(in);
			return(cfn.call(in, Homo3D.frageyev.ref(), hm.ref(), Tex2D.rtexcoord.ref(), usun.ref(), Cons.l(k), Cons.l(0.55), sc.get()));
		    }, 1000);
		if(objs) {
		    mfn.define(prog.fctx);
		    FragColor.fragcol(prog.fctx).mod(in -> {
			    if(!textured(prog))
				return(in);
			    return(mfn.call(in, Homo3D.frageyev.ref(), Homo3D.frageyen(prog.fctx).depref(), usun.ref(), umetal.ref()));
			}, 1010);
		}
	    });
    }

    /* Cel-shaded materials (barrels and the like) flatten their
     * light into bands, which leaves no room for the bent normal;
     * they get their relief from the sun-direction shading alone,
     * at a higher gain. */
    private static ShaderMacro mkcel(float k) {
	return(prog -> {
		define(prog.fctx, cfn);
		FragColor.fragcol(prog.fctx).mod(in -> {
			if(!textured(prog))
			    return(in);
			return(cfn.call(in, Homo3D.frageyev.ref(), uoheight.ref(), Tex2D.rtexcoord.ref(), usun.ref(), Cons.l(k), Cons.l(1.3), uoscale.ref()));
		    }, 1001);
	    });
    }

    private static final Map<List<Object>, ShaderMacro> cels = new HashMap<>();

    /* Hook for Light.CelShade. */
    public static ShaderMacro cel(ShaderMacro base) {
	if(!objects)
	    return(base);
	float k = ostrength;
	synchronized(cels) {
	    return(cels.computeIfAbsent(Arrays.asList(base, k), key -> ShaderMacro.compose(base, mkcel(k))));
	}
    }

    /* Hook for CrackTex: whether damage cracks are cut in. */
    public static boolean cracks() {
	return(objects);
    }

    public static void defcracks(Context ctx) {
	define(ctx, crackn);
	define(ctx, crackc);
    }

    public static float crackdepth() {
	return(0.8f * ostrength / 0.6f);
    }

    public static Uniform sun() {
	return(usun);
    }

    private static boolean textured(ProgramContext prog) {
	Tex2D t = prog.getmod(Tex2D.class);
	return((t != null) && (t.tex2d != null));
    }

    /* Parallax ground (a graphics option): the ground texture is
     * looked up where the line of sight actually meets the relief, so
     * stones hide the gaps behind them when seen at an angle. A short
     * march through the height map; the shift is held to a few texels,
     * as the ground textures sit side by side in an atlas. */
    public static volatile boolean parallax = false;
    static final RawFunction pomfn = new RawFunction(VEC2, "hv_pom", 4,
	"vec2 hv_pom(vec2 tc, vec3 p, sampler2D hm, float k)\n" +
	"{\n" +
	"    vec3 dp1 = dFdx(p), dp2 = dFdy(p);\n" +
	"    vec2 duv1 = dFdx(tc), duv2 = dFdy(tc);\n" +
	"    /* The ground's own (face) normal: the texture coordinate is\n" +
	"     * worked out before the shading normal is. */\n" +
	"    vec3 n = cross(dp1, dp2);\n" +
	"    float nl = length(n);\n" +
	"    if(nl < 1e-12)\n" +
	"        return(tc);\n" +
	"    n /= nl;\n" +
	"    if(dot(n, p) > 0.0)\n" +
	"        n = -n;\n" +
	"    vec3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);\n" +
	"    vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;\n" +
	"    vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;\n" +
	"    float im = inversesqrt(max(max(dot(T, T), dot(B, B)), 1e-20));\n" +
	"    T *= im; B *= im;\n" +
	"    vec3 v = normalize(-p);\n" +
	"    vec3 vt = vec3(dot(v, T), dot(v, B), dot(v, n));\n" +
	"    if(vt.z < 0.05)\n" +
	"        return(tc);\n" +
	"    /* Depth in texture units: the relief's world depth over the\n" +
	"     * texture's world scale. */\n" +
	"    float uvpw = length(duv1) / max(length(dp1), 1e-6);\n" +
	"    vec2 dir = -(vt.xy / max(vt.z, 0.3)) * (k * 0.8 * uvpw);\n" +
	"    vec2 ts = vec2(textureSize(hm, 0));\n" +
	"    float lim = 4.0 / max(ts.x, 1.0);\n" +
	"    float dl = length(dir);\n" +
	"    if(dl > lim)\n" +
	"        dir *= lim / dl;\n" +
	"    const int N = 10;\n" +
	"    vec2 step = dir / float(N);\n" +
	"    vec2 uv = tc;\n" +
	"    float depth = 0.0, pdepth = 0.0;\n" +
	"    float h = 1.0 - textureGrad(hm, uv, duv1, duv2).r, ph = h;\n" +
	"    for(int i = 0; i < N; i++) {\n" +
	"        if(depth >= h)\n" +
	"            break;\n" +
	"        pdepth = depth; ph = h;\n" +
	"        uv += step;\n" +
	"        depth += 1.0 / float(N);\n" +
	"        h = 1.0 - textureGrad(hm, uv, duv1, duv2).r;\n" +
	"    }\n" +
	"    /* Between the last two steps, where the ray crossed. */\n" +
	"    float a = h - depth, b = ph - pdepth;\n" +
	"    float w = (abs(a - b) > 1e-5) ? clamp(a / (a - b), 0.0, 1.0) : 0.0;\n" +
	"    return(mix(uv, uv - step, w));\n" +
	"}\n");

    private static ShaderMacro mkpom(float k) {
	return(prog -> {
		Tex2D tex = prog.getmod(Tex2D.class);
		if((tex == null) || (tex.tex2d == null))
		    return;
		pomfn.define(prog.fctx);
		tex.texcoord().mod(in -> pomfn.call(in, Homo3D.frageyev.ref(), uheight.ref(), Cons.l(k)), 10);
	    });
    }

    private static final Map<Float, ShaderMacro> pmacros = new HashMap<>();

    private static ShaderMacro macro(float k) {
	if(parallax) {
	    synchronized(pmacros) {
		return(pmacros.computeIfAbsent(k, key -> ShaderMacro.compose(mkmacro(uheight, key, false), mkpom(key))));
	    }
	}
	synchronized(macros) {
	    return(macros.computeIfAbsent(k, key -> mkmacro(uheight, key, false)));
	}
    }

    private static final Map<List<Object>, ShaderMacro> phongs = new HashMap<>();

    /* Hook for Light.PhongLight: per-pixel lit materials get object
     * relief while it is on. */
    public static ShaderMacro phong(ShaderMacro base) {
	if(!objects || (base != Light.PhongLight.flight))
	    return(base);
	float k = ostrength;
	PhongCache c = lastphong;
	if((c != null) && (c.k == k))
	    return(c.macro);
	ShaderMacro ret;
	synchronized(phongs) {
	    ret = phongs.computeIfAbsent(Arrays.asList(base, k), key -> ShaderMacro.compose(base, mkmacro(uoheight, k, true)));
	}
	lastphong = new PhongCache(k, ret);
	return(ret);
    }

    private static class PhongCache {
	final float k;
	final ShaderMacro macro;
	PhongCache(float k, ShaderMacro macro) {this.k = k; this.macro = macro;}
    }
    private static volatile PhongCache lastphong = null;

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

    /* Returns whether anything changed (and programs must be rebuilt). */
    public static boolean set(boolean ground, float k, boolean objs, float ok, boolean pom) {
	k = Math.round(k * 20) / 20.0f;
	ok = Math.round(ok * 20) / 20.0f;
	boolean ch = (ground != enabled) || (ground && (k != strength)) ||
	    (objs != objects) || (objs && (ok != ostrength)) || (ground && (pom != parallax));
	parallax = pom;
	enabled = ground;
	strength = k;
	objects = objs;
	ostrength = ok;
	return(ch);
    }
}
