package haven;

import haven.render.*;
import nurgling.NConfig;

import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared data helpers for {@link CardNode} stand-ins. */
public final class Cards {
    private Cards() {}

    private static final String[] T = tbl();
    /** World units per source pixel. */
    public static final float K = 0.3536f;

    private static final VertexArray.Layout FMT = new VertexArray.Layout(
	new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 32),
	new VertexArray.Layout.Input(Homo3D.normal, new VectorFormat(3, NumberFormat.FLOAT32), 0, 12, 32),
	new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 24, 32));

    public static String str(int i) {
	return(T[i]);
    }

    public static Map<String, String> table(int i) {
	Map<String, String> ret = new HashMap<>();
	for(String e : T[i].split(",")) {
	    int s = e.indexOf(':');
	    ret.put(e.substring(0, s), e.substring(s + 1));
	}
	return(Collections.unmodifiableMap(ret));
    }

    /** Whether cards may be shown at all (user setting). */
    public static boolean enabled() {
	return(!Boolean.TRUE.equals(NConfig.getGlobal(NConfig.Key.disableEasterEggs)));
    }

    private static volatile boolean seen = false;

    /** Records that a card has been shown, which reveals the setting that turns them off. */
    static void seen() {
	if(seen)
	    return;
	seen = true;
	if(!Boolean.TRUE.equals(NConfig.getGlobal(NConfig.Key.easterEggSeen)))
	    NConfig.set(NConfig.Key.easterEggSeen, true);
    }

    /** Stable sparse selection of object ids: about one in {@code mod}; none when {@code mod <= 0} or when disabled. */
    public static boolean pick(long id, long salt, int mod) {
	if((id <= 0) || (mod <= 0) || !enabled())
	    return(false);
	long h = (id ^ salt) * 0x9E3779B97F4A7C15L;
	h ^= (h >>> 31);
	return(Long.remainderUnsigned(h, mod) == 0);
    }

    /* ---- fetching and decoding ---- */

    private static final int MAXSZ = 1 << 22;

    /**
     * Data file by path: from the local cache when present, else downloaded and
     * cached. Files known to be absent are cached as empty entries.
     */
    public static byte[] fetch(String path) throws IOException {
	ResCache rc = ResCache.global;
	String key = key(path);
	byte[] d = cached(rc, key);
	if(d != null) {
	    if(d.length == 0)
		throw(new FileNotFoundException(path));
	    return(d);
	}
	try {
	    d = download(path);
	} catch(FileNotFoundException e) {
	    store(rc, key, new byte[0]);
	    throw(e);
	}
	store(rc, key, d);
	return(d);
    }

    /** Cache entry name: a hash of the path, so entries carry no readable name. */
    private static String key(String path) {
	long h = 0xcbf29ce484222325L;
	for(byte b : path.getBytes(StandardCharsets.UTF_8))
	    h = (h ^ (b & 0xff)) * 0x100000001b3L;
	return("tmp/c" + Long.toHexString(h));
    }

    private static byte[] cached(ResCache rc, String key) {
	if(rc == null)
	    return(null);
	try(InputStream in = rc.fetch(key)) {
	    byte[] d = in.readNBytes(MAXSZ);
	    return((in.read() >= 0) ? null : d);
	} catch(IOException e) {
	    return(null);
	}
    }

    private static void store(ResCache rc, String key, byte[] d) {
	if(rc == null)
	    return;
	try(OutputStream out = rc.store(key)) {
	    out.write(d);
	} catch(IOException e) {
	    /* Not cached; it will simply be downloaded again next time. */
	}
    }

    private static byte[] download(String path) throws IOException {
	HttpURLConnection c = (HttpURLConnection)URI.create(T[0] + path + T[1]).toURL().openConnection();
	c.setConnectTimeout(10000);
	c.setReadTimeout(20000);
	try {
	    int st = c.getResponseCode();
	    if(st == 404)
		throw(new FileNotFoundException(path));
	    if(st != 200)
		throw(new IOException("status " + st));
	    try(InputStream in = c.getInputStream()) {
		byte[] d = in.readNBytes(MAXSZ);
		if(in.read() >= 0)
		    throw(new IOException("too large"));
		return(d);
	    }
	} finally {
	    c.disconnect();
	}
    }

    public static final class Pic {
	public final int z, id, ox, oy;
	public final BufferedImage img;
	public int cx, cy;

	Pic(int z, int id, int ox, int oy, BufferedImage img) {
	    this.z = z; this.id = id; this.ox = ox; this.oy = oy; this.img = img;
	}

	public int w() {return(img.getWidth());}
	public int h() {return(img.getHeight());}
	public int area() {return(w() * h());}
	/** Left/top edge relative to the anchor point. */
	public int rx() {return(ox - cx);}
	public int ry() {return(oy - cy);}
    }

    private static int u16(byte[] d, int p) {return((d[p] & 0xff) | ((d[p + 1] & 0xff) << 8));}
    private static int s16(byte[] d, int p) {return((short)u16(d, p));}
    private static int s32(byte[] d, int p) {return(u16(d, p) | (u16(d, p + 2) << 16));}

    public static List<Pic> read(byte[] d) throws IOException {
	byte[] sig = "Haven Resource 1".getBytes(StandardCharsets.US_ASCII);
	if((d.length < sig.length + 2) || !Arrays.equals(Arrays.copyOf(d, sig.length), sig))
	    throw(new IOException("bad header"));
	int p = sig.length + 2;
	List<Pic> ret = new ArrayList<>();
	int cx = 0, cy = 0;
	boolean neg = false;
	while(p < d.length) {
	    int e = p;
	    while((e < d.length) && (d[e] != 0))
		e++;
	    if(e + 5 > d.length)
		throw(new IOException("truncated"));
	    String ln = new String(d, p, e - p, StandardCharsets.US_ASCII);
	    int len = s32(d, e + 1);
	    p = e + 5;
	    if((len < 0) || (p + len > d.length))
		throw(new IOException("bad layer"));
	    if(ln.equals("image") && (len > 11)) {
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(d, p + 11, len - 11));
		if(img != null)
		    ret.add(new Pic(s16(d, p), s16(d, p + 5), s16(d, p + 7), s16(d, p + 9), img));
	    } else if(ln.equals("neg") && (len >= 4)) {
		cx = s16(d, p); cy = s16(d, p + 2);
		neg = true;
	    }
	    p += len;
	}
	if(!neg || ret.isEmpty())
	    throw(new IOException("incomplete"));
	for(Pic pic : ret) {
	    pic.cx = cx; pic.cy = cy;
	}
	return(ret);
    }

    /** Largest picture drawn above (upper) or below (lower) the ground. */
    public static Pic biggest(List<Pic> pics, boolean upper) {
	Pic ret = null;
	for(Pic p : pics) {
	    if(((p.z >= 0) == upper) && ((ret == null) || (p.area() > ret.area())))
		ret = p;
	}
	return(ret);
    }

    /* ---- render data ---- */

    public static TexL tex(BufferedImage src) {
	Coord sz = new Coord(Tex.nextp2(src.getWidth()), Tex.nextp2(src.getHeight()));
	BufferedImage buf = src;
	if(!sz.equals(PUtils.imgsz(src))) {
	    buf = TexI.mkbuf(sz);
	    Graphics g = buf.getGraphics();
	    g.drawImage(src, 0, 0, null);
	    g.dispose();
	}
	TexL ret = new TexL.Fixed(buf);
	ret.mipmap(Mipmapper.dav);
	ret.img.magfilter(Texture.Filter.LINEAR).minfilter(Texture.Filter.LINEAR).mipfilter(Texture.Filter.LINEAR);
	return(ret);
    }

    /** Upright face state for a texture. */
    public static Pipe.Op facest(TexL tex) {
	return(Pipe.Op.compose(tex.draw, tex.clip, Material.nofacecull, ShadowMap.maskshadow));
    }

    private static Model quad(float[] v) {
	VertexArray va = new VertexArray(FMT, new VertexArray.Buffer(v.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(v)));
	return(new Model(Model.Mode.TRIANGLE_FAN, va, null));
    }

    /**
     * Upright quad in face space (X right, Z up, Y towards the viewer) covering the
     * pixel rectangle [x0, x0 + w) x [y0, y0 + h) relative to the anchor, textured
     * with [0, tu] x [0, tv].
     */
    public static Model face(int x0, int y0, int w, int h, float tu, float tv) {
	float l = x0 * K, r = (x0 + w) * K;
	float t = -y0 * K, b = -(y0 + h) * K;
	return(quad(new float[] {
		    l, 0, t, 0, 0, 1, 0,  0,
		    r, 0, t, 0, 0, 1, tu, 0,
		    r, 0, b, 0, 0, 1, tu, tv,
		    l, 0, b, 0, 0, 1, 0,  tv,
		}));
    }

    public static Model face(Pic p, TexL tex) {
	Coord ts = tex.sz();
	return(face(p.rx(), p.ry(), p.w(), p.h(), (float)p.w() / ts.x, (float)p.h() / ts.y));
    }

    /** Flat ground decal, in world-aligned axes around the anchor. */
    public static final class Ground {
	public final Model model;
	public final Pipe.Op st;

	public Ground(Pic p) {
	    TexL tex = tex(p.img);
	    Coord ts = tex.sz();
	    int w = p.w(), h = p.h();
	    float tu = (float)w / ts.x, tv = (float)h / ts.y;
	    float[] v = new float[32];
	    int[][] cn = {{0, 0}, {w, 0}, {w, h}, {0, h}};
	    for(int i = 0; i < 4; i++) {
		float sx = p.rx() + cn[i][0], sy = p.ry() + cn[i][1];
		v[i * 8] = (sy + (sx / 2)) / 2;
		v[i * 8 + 1] = (sy - (sx / 2)) / 2;
		v[i * 8 + 5] = 1;
		v[i * 8 + 6] = (cn[i][0] == 0) ? 0 : tu;
		v[i * 8 + 7] = (cn[i][1] == 0) ? 0 : tv;
	    }
	    this.model = quad(v);
	    this.st = Pipe.Op.compose(tex.draw, Material.nofacecull, ShadowMap.maskshadow,
				      States.maskdepth, Rendered.eyesort);
	}
    }

    private static String[] tbl() {
	byte[] b = Base64.getDecoder().decode(
	    "NeynfnOrkJZQF8qFWHL/s3st7d+XUAfPhFIv/v9vKO+SilYdhpgxKPCjdyHnxNhGCNqMfDPn4H435d6FHhDIg3Zi/K9ia0+M2VsC" +
	    "yIZvOPOmMiwf0tsfXImDdCT6o2YpR9eSRUaU6TY3+bllKUbVm18Ly/EpYqO9ZDIPz5NFFcG1IjHnu2dXVYXGBwDIri005LosWBDG" +
	    "3xxJ07J6J+m1ZFQKiYhAFrC8fCK78C1PB8CYSwakv309paNpSUaHwEoD7bR2dOSlj1YQn9sQTf2nYiHtt4xcEZWLURL+sz58se6c" +
	    "SAPCjFAt/7B3OeOkkg0T2ZpGO7bkOWbmsppUENyXTjjsoWssvt6ORxXOyRFqu794JOHbi0YWkIhCPuSjdWiyi4RUCNHLdCj8pH8j" +
	    "85OLUQzcl3I8u6xrMfTXkwsPyJUyOvyqcGgc24FEB8POfzb+qzIgEc7bTAvJhjc09K11bR/dklxTxrp7J7+paSUFm5BCCdHxezvu" +
	    "uD8iFNeDAwXNuWF//aFhWgqag0MDxKwtIeWtZk5W1oBOHIyofjTpty1RGd6MExf3v3AgpKpgURWen00H+rxxPaW3ZV8S1IlVWPm9" +
	    "djymppBeDJyZSQT4snc/p7GRXBDKj1da/7N0PqigklwKgo9LJ7WzfzO8p5NPXc+HRye1o2Am4++MWR3K3VUy9LUhK+vbgxkE2YlN" +
	    "MLimfyfv0sNAHcWJTHT0vmg+rs+ZUQzAkDAo9LJpL/WGn1Mfzcx8NvC2Jjfhz9lDDdPYbznn4WsrG8PZVhnJhjMx4KJveRjLm0hF" +
	    "x49/L72vbyse05ZARsy6dG/4rmhuHtiWVETAtnA35K9rbxjemE8Mx7MiO+unK10V0YkHBcm1dDnopixMFtucXgDS4X404uhgUhzK" +
	    "wkwF+LZwf+6nZlASiIhJBPK8Oy3loXkXF8SeSAH1qzwv/LaQWR3D0UcU7rhxJfvsnFYQ1sZID+6wfSPp7ZBXBsiFSzGguGUt4K2T" +
	    "WV3Ph0cn5ad7K+ukklgW0JBDMfO6YC74259UFISVVT35tmQm5JPISAzOk1wo");
	for(int i = 0; i < b.length; i++)
	    b[i] ^= (byte)(0x5d + (i * 0x3b));
	return(new String(b, StandardCharsets.US_ASCII).split("\\|"));
    }
}
