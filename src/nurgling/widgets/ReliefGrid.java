package nurgling.widgets;

import haven.*;
import nurgling.NConfig;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import static haven.MCache.cmaps;

/**
 * A minimap grid drawn as shaded relief ({@link ReliefRenderer}). Detail follows the zoom: up
 * close a grid is drawn at 2 or 4 pixels per tile so tile boundaries are thin lines. While a
 * new image renders the previous one stays on screen.
 *
 * With the relief option off it draws exactly as the stock minimap does.
 */
public class ReliefGrid extends MiniMap.DisplayGrid {
    private final NMiniMap map;
    private MapFile.DataGrid cgrid;
    private int ck = -1;
    private Defer.Future<Tex> next;
    private Tex img;

    public ReliefGrid(NMiniMap map, MapFile.Segment seg, Coord sc, int lvl, Indir<? extends MapFile.DataGrid> gref) {
	super(map, seg, sc, lvl, gref);
	this.map = map;
    }

    public static boolean enabled() {
	return(Boolean.TRUE.equals(NConfig.get(NConfig.Key.reliefMap)));
    }

    /** World units between contour lines at full detail. */
    static final double CONTOURS = 10;

    public static double strength() {
	Object v = NConfig.get(NConfig.Key.reliefStrength);
	return((v instanceof Number) ? ((Number)v).doubleValue() / 100.0 : 1.0);
    }

    @Override
    public Tex img() {
	if(!enabled())
	    return(super.img());
	MapFile.DataGrid grid = gref.get();
	boolean zoom = grid instanceof MapFile.ZoomGrid;
	int k = zoom ? 1 : map.reliefScale();
	if((grid != cgrid) || (k != ck)) {
	    if(next != null)
		next.cancel();
	    next = (grid == null) ? null : Defer.later(zoom ? zoomjob(grid, k) : viewjob(k));
	    cgrid = grid;
	    ck = k;
	}
	if(next != null) {
	    try {
		img = next.get();
		next = null;
	    } catch(Loading l) {
		/* Still rendering: keep showing the previous image. */
	    }
	}
	return(img);
    }

    /** Level 0: read through a view of this grid and its neighbours, so slopes, lines and cliffs cross grid edges. */
    private Defer.Callable<Tex> viewjob(int k) {
	double str = strength();
	boolean uniform = Boolean.TRUE.equals(NConfig.get(NConfig.Key.uniformBiomeColors));
	return(() -> {
	    MapFile.View view = new MapFile.View(seg);
	    try(Locked lk = new Locked(file.lock.readLock())) {
		for(int y = -1; y <= 1; y++) {
		    for(int x = -1; x <= 1; x++)
			view.addgrid(sc.add(x, y));
		}
		view.fin();
		return(new TexI(image(fromview(view, sc.mul(cmaps), uniform), k, 0, str, CONTOURS)));
	    }
	});
    }

    /** Zoomed out: the zoom grid alone; its border tiles use one-sided slopes. */
    private Defer.Callable<Tex> zoomjob(MapFile.DataGrid grid, int k) {
	double str = strength();
	boolean uniform = Boolean.TRUE.equals(NConfig.get(NConfig.Key.uniformBiomeColors));
	int lvl = ((MapFile.ZoomGrid)grid).lvl;
	return(() -> new TexI(image(fromgrid(grid, sc.mul(cmaps), uniform), k, lvl, str, CONTOURS)));
    }

    static BufferedImage image(ReliefRenderer.Cells c, int k, int lvl, double str, double cont) {
	BufferedImage img = TexI.mkbuf(Coord.of(c.n * k, c.n * k));
	ReliefRenderer.render(c, k, lvl, str, cont, ((DataBufferByte)img.getRaster().getDataBuffer()).getData());
	return(img);
    }

    static boolean waterp(String resnm) {
	return(resnm.contains("water") || resnm.endsWith("/deep") || resnm.endsWith("/odeep"));
    }

    private static int texrgb(BufferedImage tex, Coord tc, boolean uniform) {
	if(tex == null)
	    return(0);
	if(uniform)
	    return(nurgling.tools.TileColors.average(tex) & 0xffffff);
	return(tex.getRGB(Utils.floormod(tc.x, tex.getWidth()), Utils.floormod(tc.y, tex.getHeight())) & 0xffffff);
    }

    static ReliefRenderer.Cells fromview(MapFile.View m, Coord ul, boolean uniform) {
	int n = cmaps.x;
	ReliefRenderer.Cells c = new ReliefRenderer.Cells(n);
	BufferedImage[] texes = new BufferedImage[256];
	Boolean[] water = new Boolean[256];
	for(int y = -1; y <= n; y++) {
	    for(int x = -1; x <= n; x++) {
		Coord tc = ul.add(x, y);
		int t = m.gettile(tc);
		int i = c.at(x, y);
		c.tile[i] = t;
		if(t < 0)
		    continue;
		/* The view orders its tilesets by priority, so the id is the rank. */
		c.rank[i] = t;
		c.z[i] = (float)m.getfz(tc);
		c.cliff[i] = cliffp(m, tc, t);
		if((x < 0) || (y < 0) || (x >= n) || (y >= n))
		    continue;
		if(t >= texes.length) {
		    texes = java.util.Arrays.copyOf(texes, t + 1);
		    water = java.util.Arrays.copyOf(water, t + 1);
		}
		c.rgb[i] = texrgb(MapSource.tileimg(m, texes, t), tc, uniform);
		if(water[t] == null) {
		    Tileset set = m.tileset(t);
		    water[t] = (set != null) && waterp(set.getres().name);
		}
		c.water[i] = water[t];
	    }
	}
	return(c);
    }

    private static boolean cliffp(MapSource m, Coord tc, int t) {
	try {
	    return((m.tiler(t) instanceof haven.resutil.Ridges.RidgeTile) && haven.resutil.Ridges.brokenp(m, tc));
	} catch(Loading l) {
	    throw(l);
	} catch(RuntimeException e) {
	    /* As the stock minimap: cached tilesets can hold outdated references; such a tile shows no cliff. */
	    return(false);
	}
    }

    static ReliefRenderer.Cells fromgrid(MapFile.DataGrid g, Coord off, boolean uniform) {
	int n = cmaps.x;
	ReliefRenderer.Cells c = new ReliefRenderer.Cells(n);
	int nt = g.tilesets.length;
	BufferedImage[] texes = new BufferedImage[nt];
	boolean[] loaded = new boolean[nt];
	boolean[] water = new boolean[nt];
	for(int t = 0; t < nt; t++)
	    water[t] = waterp(g.tilesets[t].res.name);
	Coord tc = new Coord();
	for(tc.y = 0; tc.y < n; tc.y++) {
	    for(tc.x = 0; tc.x < n; tc.x++) {
		int t = g.gettile(tc);
		int i = c.at(tc.x, tc.y);
		c.tile[i] = t;
		c.rank[i] = g.tilesets[t].prio;
		c.z[i] = (float)g.getfz(tc);
		c.water[i] = water[t];
		if(!loaded[t]) {
		    texes[t] = tiletex(g.tilesets[t]);
		    loaded[t] = true;
		}
		c.rgb[i] = texrgb(texes[t], tc.add(off), uniform);
	    }
	}
	return(c);
    }

    private static BufferedImage tiletex(MapFile.TileInfo info) {
	try {
	    Resource.Image ir = info.res.get().layer(Resource.imgc);
	    return((ir == null) ? null : ir.img);
	} catch(Loading l) {
	    throw(l);
	} catch(RuntimeException e) {
	    /* A tileset that will not load draws black, as on the stock minimap. */
	    return(null);
	}
    }
}
