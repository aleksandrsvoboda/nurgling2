package nurgling.render.remembered;

import haven.*;
import haven.render.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;

/**
 * Remembered ground past the full-detail ring, drawn out of the map file's
 * zoom grids. A zoom grid of level {@code L} covers {@code 2^L x 2^L} grids
 * in one 100 x 100 sample array (the majority tile and lowest height of each
 * block), so every cell costs the same to build and to draw while each level
 * covers four times the ground of the one below. A cell is one height mesh
 * of {@link #QUADS}^2 quads, textured with the tiles' average colours and
 * skirted along its edges so neighbouring cells of other levels show no
 * crack. Where the segment has no grid under a sample, nothing is drawn.
 */
public class FarGround implements RenderTree.Node {
    static final int QUADS = 50;
    /** Cell builds in flight at most. */
    static final int MAXBUILD = 6;
    /** Built cells kept besides the ones drawn and wanted. */
    static final int CACHE = 192;

    static final VertexArray.Layout fmt = new VertexArray.Layout(
	new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 32),
	new VertexArray.Layout.Input(Homo3D.normal, new VectorFormat(3, NumberFormat.FLOAT32), 0, 12, 32),
	new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 24, 32));
    static final Pipe.Op light = Pipe.Op.compose(new Light.PhongLight(true), new States.Facecull(States.Facecull.Mode.NONE));
    /** Average colours of tilesets' map images, by resource name. */
    private static final Map<String, Integer> tilecolors = new ConcurrentHashMap<>();

    public static final class Key {
	public final int lvl;
	public final Coord sc;

	public Key(int lvl, Coord sc) {this.lvl = lvl; this.sc = sc;}

	public int size() {return(1 << lvl);}

	/** Whether this cell's ground and {@code o}'s overlap. */
	public boolean overlaps(Key o) {
	    int a = size(), b = o.size();
	    return((sc.x < o.sc.x + b) && (o.sc.x < sc.x + a) && (sc.y < o.sc.y + b) && (o.sc.y < sc.y + a));
	}

	/** Whether this cell holds all of {@code o}'s ground. */
	public boolean contains(Key o) {
	    int a = size(), b = o.size();
	    return((lvl >= o.lvl) && (o.sc.x >= sc.x) && (o.sc.y >= sc.y) && (o.sc.x + b <= sc.x + a) && (o.sc.y + b <= sc.y + a));
	}

	public boolean equals(Object o) {
	    return((o instanceof Key) && (((Key)o).lvl == lvl) && ((Key)o).sc.equals(sc));
	}

	public int hashCode() {
	    return((sc.hashCode() * 31) + lvl);
	}

	public String toString() {
	    return(String.format("%d@%s", lvl, sc));
	}
    }

    private static final class Built implements Disposable {
	final Model model;
	final TexI tex;
	final RenderTree.Node node;

	Built(Model model, TexI tex) {
	    this.model = model;
	    this.tex = tex;
	    Pipe.Op st = Pipe.Op.compose(tex.st(), light);
	    this.node = new RenderTree.Node() {
		    public void added(RenderTree.Slot slot) {
			slot.add(model, st);
		    }
		};
	}

	public void dispose() {
	    model.dispose();
	    model.ind.dispose();
	    model.va.dispose();
	    tex.dispose();
	}
    }

    private final class Cell {
	final Key key;
	Indir<? extends MapFile.DataGrid> src;
	MapFile.DataGrid from;
	Defer.Future<Built> job;
	MapFile.DataGrid jobfrom;
	Built built;
	boolean empty = false;
	RenderTree.Slot drawn;

	Cell(Key key) {this.key = key;}

	boolean ready() {
	    return(empty || (built != null));
	}

	/* Takes up a finished build, and starts one when the zoom grid under
	 * it is new. Returns whether a build is in flight. */
	boolean poll(boolean maystart) {
	    if(job != null) {
		if(!job.done(0))
		    return(true);
		Defer.Future<Built> j = job;
		job = null;
		Built b;
		try {
		    b = j.get(0);
		} catch(Defer.DeferredException e) {
		    new Warning(e, "remembered ground: cannot build far cell " + key).issue();
		    b = null;
		}
		from = jobfrom;
		swap(b);
	    }
	    if(src == null)
		src = seg.grid(key.lvl, key.sc);
	    MapFile.DataGrid d;
	    try {
		d = src.get();
	    } catch(Loading l) {
		return(false);
	    } catch(Defer.DeferredException e) {
		/* The map file could not make this zoom grid: draw none. */
		d = null;
	    }
	    if(d == null) {
		swap(null);
		empty = true;
		from = null;
		return(false);
	    }
	    if((d != from) && maystart) {
		MapFile.DataGrid fd = d;
		Key k = key;
		Predicate<Coord> kn = known;
		boolean flat = (Boolean)nurgling.NConfig.get(nurgling.NConfig.Key.flatsurface);
		jobfrom = d;
		job = Defer.later(() -> build(k, fd, kn, flat));
		return(true);
	    }
	    return(false);
	}

	/* A new build replaces the old one in the scene before the old one
	 * is disposed. */
	void swap(Built b) {
	    Built prev = built;
	    built = b;
	    empty = (b == null);
	    if(drawn != null) {
		RenderTree.Slot ps = drawn;
		drawn = null;
		if((b != null) && (slot != null))
		    drawn = place(this);
		ps.remove();
	    }
	    if(prev != null)
		prev.dispose();
	}
    }

    private final MapFile.Segment seg;
    private final Coord off;
    private volatile Predicate<Coord> known;
    private RenderTree.Slot slot;
    private final LinkedHashMap<Key, Cell> cells = new LinkedHashMap<>(64, 0.75f, true);

    public FarGround(MapFile.Segment seg, Coord off, Predicate<Coord> known) {
	this.seg = seg;
	this.off = off;
	this.known = known;
    }

    public void known(Predicate<Coord> known) {
	this.known = known;
    }

    public void added(RenderTree.Slot slot) {
	this.slot = slot;
    }

    public void removed(RenderTree.Slot slot) {
	this.slot = null;
	for(Cell c : cells.values())
	    c.drawn = null;
    }

    private RenderTree.Slot place(Cell c) {
	Coord gc = c.key.sc.sub(off);
	Coord2d pc = gc.mul(cmaps).mul(tilesz);
	return(slot.add(c.built.node, Location.xlate(new Coord3f((float)pc.x, -(float)pc.y, 0))));
    }

    public int drawn() {
	int n = 0;
	for(Cell c : cells.values()) {
	    if(c.drawn != null)
		n++;
	}
	return(n);
    }

    /** Builds what {@code want} needs (most wanted first) and answers the
     * cells that are ready to draw. */
    public Set<Key> prepare(List<Key> want) {
	Set<Key> ready = new HashSet<>();
	int inflight = 0;
	for(Cell c : cells.values()) {
	    if(c.job != null)
		inflight++;
	}
	for(Key k : want) {
	    Cell c = cells.get(k);
	    if(c == null)
		cells.put(k, c = new Cell(k));
	    boolean had = (c.job != null);
	    if(c.poll(inflight < MAXBUILD) && !had)
		inflight++;
	    if(c.ready())
		ready.add(k);
	}
	return(ready);
    }

    /** Puts {@code shown} in the scene, takes every other cell out of it,
     * and lets go of cells past the cache that nothing asked for. */
    public void show(Set<Key> shown, Set<Key> keep) {
	if(slot == null)
	    return;
	for(Key k : shown) {
	    Cell c = cells.get(k);
	    if((c != null) && (c.built != null) && (c.drawn == null))
		c.drawn = place(c);
	}
	for(Cell c : cells.values()) {
	    if((c.drawn != null) && !shown.contains(c.key)) {
		c.drawn.remove();
		c.drawn = null;
	    }
	}
	int extra = 0;
	for(Key k : cells.keySet()) {
	    if(!keep.contains(k))
		extra++;
	}
	for(Iterator<Cell> i = cells.values().iterator(); i.hasNext() && (extra > CACHE);) {
	    Cell c = i.next();
	    if(keep.contains(c.key) || (c.drawn != null))
		continue;
	    i.remove();
	    if(c.job != null)
		c.job.cancel();
	    if(c.built != null)
		c.built.dispose();
	    extra--;
	}
    }

    public void dispose() {
	for(Cell c : cells.values()) {
	    if(c.drawn != null)
		c.drawn.remove();
	    if(c.job != null)
		c.job.cancel();
	    if(c.built != null)
		c.built.dispose();
	}
	cells.clear();
    }

    private static int tilecolor(MapFile.TileInfo ti) {
	Integer c = tilecolors.get(ti.res.name);
	if(c != null)
	    return(c);
	Resource res = ti.res.get();   /* Loading goes back to Defer, which runs the build again */
	Resource.Image img = res.layer(Resource.imgc);
	int col = (img == null) ? 0xff808080 : nurgling.tools.TileColors.average(img.img);
	tilecolors.put(ti.res.name, col);
	return(col);
    }

    static Built build(Key key, MapFile.DataGrid d, Predicate<Coord> known, boolean flat) {
	final int Q = QUADS, N = cmaps.x, nv1 = Q + 1;
	int step = key.size();
	/* Whether the segment has the grid under each sample. */
	boolean[] has = new boolean[N * N];
	boolean any = false;
	for(int y = 0; y < N; y++) {
	    for(int x = 0; x < N; x++) {
		Coord sc = key.sc.add((x * step) / N, (y * step) / N);
		any |= (has[x + (y * N)] = known.test(sc));
	    }
	}
	if(!any)
	    return(null);

	int[] colors = new int[d.tilesets.length];
	for(int i = 0; i < colors.length; i++) {
	    try {
		colors[i] = tilecolor(d.tilesets[i]);
	    } catch(Loading l) {
		throw(l);
	    } catch(RuntimeException e) {
		/* A tileset that no longer exists: grey, as the map draws nothing better. */
		colors[i] = 0xff808080;
	    }
	}
	BufferedImage img = TexI.mkbuf(Coord.of(N, N));
	for(int y = 0; y < N; y++) {
	    for(int x = 0; x < N; x++)
		img.setRGB(x, y, has[x + (y * N)] ? colors[d.tiles[x + (y * N)]] : 0);
	}

	float qs = (float)(step * tilesz.x * N / Q);
	float[] z = new float[nv1 * nv1];
	for(int j = 0; j < nv1; j++) {
	    for(int i = 0; i < nv1; i++) {
		int si = Math.min(i * N / Q, N - 1), sj = Math.min(j * N / Q, N - 1);
		z[i + (j * nv1)] = flat ? 0 : d.zmap[si + (sj * N)];
	    }
	}
	float skirt = step * 40f;
	int nskirt = 4 * nv1;
	int nv = (nv1 * nv1) + nskirt;
	float[] vd = new float[nv * 8];
	int vi = 0;
	for(int j = 0; j < nv1; j++) {
	    for(int i = 0; i < nv1; i++) {
		float zl = z[Math.max(i - 1, 0) + (j * nv1)], zr = z[Math.min(i + 1, Q) + (j * nv1)];
		float zu = z[i + (Math.max(j - 1, 0) * nv1)], zd = z[i + (Math.min(j + 1, Q) * nv1)];
		/* Render y runs opposite to j. */
		float nx = -(zr - zl) / (2 * qs), ny = (zd - zu) / (2 * qs);
		float nl = (float)Math.sqrt((nx * nx) + (ny * ny) + 1);
		vi = vertex(vd, vi, i * qs, -j * qs, z[i + (j * nv1)], nx / nl, ny / nl, 1 / nl, (float)i / Q, (float)j / Q);
	    }
	}
	/* Skirt vertices: below each edge vertex, edges in order top, bottom, left, right. */
	int sbase = nv1 * nv1;
	for(int e = 0; e < 4; e++) {
	    for(int t = 0; t < nv1; t++) {
		int i = (e == 2) ? 0 : (e == 3) ? Q : t;
		int j = (e == 0) ? 0 : (e == 1) ? Q : t;
		int src = (i + (j * nv1)) * 8;
		vi = vertex(vd, vi, vd[src], vd[src + 1], vd[src + 2] - skirt, vd[src + 3], vd[src + 4], vd[src + 5], vd[src + 6], vd[src + 7]);
	    }
	}

	short[] idx = new short[(Q * Q * 6) + (4 * Q * 6)];
	int ni = 0;
	for(int j = 0; j < Q; j++) {
	    for(int i = 0; i < Q; i++) {
		if(!has[(i * N / Q) + ((j * N / Q) * N)])
		    continue;
		int a = i + (j * nv1), b = a + 1, c = a + nv1, dd = c + 1;
		ni = quad(idx, ni, a, c, b, dd);
	    }
	}
	for(int e = 0; e < 4; e++) {
	    for(int t = 0; t < Q; t++) {
		int i = (e == 2) ? 0 : (e == 3) ? Q - 1 : t;
		int j = (e == 0) ? 0 : (e == 1) ? Q - 1 : t;
		if(!has[(i * N / Q) + ((j * N / Q) * N)])
		    continue;
		int ta, tb;
		if(e < 2) {
		    int row = (e == 0) ? 0 : Q;
		    ta = t + (row * nv1); tb = ta + 1;
		} else {
		    int col = (e == 2) ? 0 : Q;
		    ta = col + (t * nv1); tb = ta + nv1;
		}
		int sa = sbase + (e * nv1) + t, sb = sa + 1;
		ni = quad(idx, ni, ta, sa, tb, sb);
	    }
	}
	if(ni == 0)
	    return(null);
	short[] fidx = Arrays.copyOf(idx, ni);
	VertexArray va = new VertexArray(fmt, new VertexArray.Buffer(vd.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(vd)));
	Model.Indices ind = new Model.Indices(ni, NumberFormat.UINT16, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(fidx));
	Model model = new Model(Model.Mode.TRIANGLES, va, ind);
	TexI tex = new TexI(img, false);
	tex.filter(Texture.Filter.LINEAR);
	tex.wrapmode(Texture.Wrapping.CLAMP);
	return(new Built(model, tex));
    }

    private static int vertex(float[] vd, int vi, float x, float y, float z, float nx, float ny, float nz, float u, float v) {
	vd[vi++] = x; vd[vi++] = y; vd[vi++] = z;
	vd[vi++] = nx; vd[vi++] = ny; vd[vi++] = nz;
	vd[vi++] = u; vd[vi++] = v;
	return(vi);
    }

    private static int quad(short[] idx, int ni, int a, int b, int c, int d) {
	idx[ni++] = (short)a; idx[ni++] = (short)b; idx[ni++] = (short)c;
	idx[ni++] = (short)c; idx[ni++] = (short)b; idx[ni++] = (short)d;
	return(ni);
    }
}
