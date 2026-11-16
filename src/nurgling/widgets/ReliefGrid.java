package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.leveling.LevelPlan;
import nurgling.leveling.LevelPlans;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static haven.MCache.cmaps;

/**
 * A minimap grid drawn as shaded relief ({@link ReliefRenderer}). Detail follows the zoom: up
 * close a grid is drawn at 2 or 4 pixels per tile so tile boundaries are thin lines. While a
 * new image renders the previous one stays on screen.
 *
 * Grids under a leveling plan also show where to dig and where to fill, and the plan's outline.
 *
 * With the relief option off it draws exactly as the stock minimap does, except up close on
 * grids a plan covers: those draw flat, without shading, contours or tile edges, but with the plan.
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

    /** Grid ids of this grid and its eight neighbours, row by row; 0 = not in the segment. Level 0 only. */
    private long[] gids;
    private MapFile.DataGrid gidgrid;
    private long pk = Long.MIN_VALUE;
    private boolean cr;

    @Override
    public Tex img() {
	MapFile.DataGrid grid = gref.get();
	boolean zoom = grid instanceof MapFile.ZoomGrid;
	boolean relief = enabled();
	LevelPlans plans = map.levelPlans();
	/* With shading off a grid still draws through here when a plan covers it, so the plan shows. */
	boolean planned = (plans != null) && (grid != null) && !zoom && touched(plans, grid);
	if(!relief && !planned)
	    return(super.img());
	int k = zoom ? 1 : map.reliefScale();
	long pkey = (plans == null) ? 0 : zoom ? plans.revision() : planrev(plans);
	if((grid != cgrid) || (k != ck) || (pkey != pk) || (relief != cr)) {
	    if(next != null)
		next.cancel();
	    List<LevelPlan> snap = (plans == null) ? Collections.emptyList() : plans.plans();
	    next = (grid == null) ? null : Defer.later(zoom ? zoomjob(grid, k, snap) : viewjob(k, relief, snap));
	    cgrid = grid;
	    ck = k;
	    pk = pkey;
	    cr = relief;
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

    /**
     * Looked up once per grid image. Called on the UI thread, and the map file holds its write
     * lock while it saves grids to disk, so this never waits for the lock: it tries again next frame.
     */
    private long[] gids(MapFile.DataGrid grid) {
	if((gids == null) || (gidgrid != grid)) {
	    java.util.concurrent.locks.Lock lk = file.lock.readLock();
	    if(!lk.tryLock())
		return((gidgrid == grid) ? gids : null);
	    long[] ret = new long[9];
	    try {
		for(int y = -1; y <= 1; y++) {
		    for(int x = -1; x <= 1; x++) {
			Long id = seg.map.get(sc.add(x, y));
			ret[((y + 1) * 3) + x + 1] = (id == null) ? 0 : id;
		    }
		}
	    } finally {
		lk.unlock();
	    }
	    gids = ret;
	    gidgrid = grid;
	}
	return(gids);
    }

    private boolean touched(LevelPlans plans, MapFile.DataGrid grid) {
	if(plans.plans().isEmpty())
	    return(false);
	long[] ids = gids(grid);
	if(ids == null)
	    return(false);
	for(long id : ids) {
	    if((id != 0) && plans.touches(id))
		return(true);
	}
	return(false);
    }

    /** Changes whenever a plan changes on this grid or a neighbour (the outline reads the border ring). */
    private long planrev(LevelPlans plans) {
	if(gids == null)
	    return(0);
	long r = 0;
	for(long id : gids) {
	    if(id != 0)
		r = (r * 31) + plans.gridRevision(id);
	}
	return(r);
    }

    /** Level 0: read through a view of this grid and its neighbours, so slopes, lines and cliffs cross grid edges. */
    private Defer.Callable<Tex> viewjob(int k, boolean relief, List<LevelPlan> plans) {
	double str = relief ? strength() : 0;
	double cont = relief ? CONTOURS : 0;
	boolean uniform = Boolean.TRUE.equals(NConfig.get(NConfig.Key.uniformBiomeColors));
	long[] ids = (gids == null) ? new long[9] : gids.clone();
	return(() -> {
	    MapFile.View view = new MapFile.View(seg);
	    try(Locked lk = new Locked(file.lock.readLock())) {
		for(int y = -1; y <= 1; y++) {
		    for(int x = -1; x <= 1; x++)
			view.addgrid(sc.add(x, y));
		}
		view.fin();
		ReliefRenderer.Cells c = fromview(view, sc.mul(cmaps), uniform);
		c.edges = relief;
		planview(c, plans, ids);
		return(new TexI(image(c, k, 0, str, cont)));
	    }
	});
    }

    /** Zoomed out: the zoom grid alone; its border tiles use one-sided slopes. */
    private Defer.Callable<Tex> zoomjob(MapFile.DataGrid grid, int k, List<LevelPlan> plans) {
	double str = strength();
	boolean uniform = Boolean.TRUE.equals(NConfig.get(NConfig.Key.uniformBiomeColors));
	int lvl = ((MapFile.ZoomGrid)grid).lvl;
	return(() -> {
	    ReliefRenderer.Cells c = fromgrid(grid, sc.mul(cmaps), uniform);
	    if(!plans.isEmpty())
		planzoom(c, plans, lvl);
	    return(new TexI(image(c, k, lvl, str, CONTOURS)));
	});
    }

    /** The plan of every cell, border ring included, from the 3x3 grid ids; level 0. */
    static void planview(ReliefRenderer.Cells c, List<LevelPlan> plans, long[] ids) {
	if(plans.isEmpty())
	    return;
	int n = c.n;
	for(int y = -1; y <= n; y++) {
	    for(int x = -1; x <= n; x++) {
		int gx = Math.floorDiv(x, n), gy = Math.floorDiv(y, n);
		long id = ids[((gy + 1) * 3) + gx + 1];
		if(id != 0)
		    setplan(c, c.at(x, y), plans, id, x - (gx * n), y - (gy * n));
	    }
	}
    }

    /**
     * Zoomed out a cell stands for a block of tiles; it takes the plan of the block's first tile.
     * The border ring is filled too, so no outline is drawn along the grid's own edge.
     */
    private void planzoom(ReliefRenderer.Cells c, List<LevelPlan> plans, int lvl) {
	int n = c.n;
	Map<Coord, Long> ids = new HashMap<>();
	try(Locked lk = new Locked(file.lock.readLock())) {
	    for(int y = -1; y <= n; y++) {
		for(int x = -1; x <= n; x++) {
		    Coord tc = sc.mul(cmaps).add(x, y).mul(1 << lvl);
		    Coord gc = tc.div(cmaps);
		    Long id = ids.computeIfAbsent(gc, g -> {
			    Long v = seg.map.get(g);
			    return((v == null) ? 0L : v);
			});
		    if(id != 0)
			setplan(c, c.at(x, y), plans, id, tc.x - (gc.x * cmaps.x), tc.y - (gc.y * cmaps.y));
		}
	    }
	}
    }

    private static void setplan(ReliefRenderer.Cells c, int i, List<LevelPlan> plans, long id, int lx, int ly) {
	for(int p = 0; p < plans.size(); p++) {
	    LevelPlan plan = plans.get(p);
	    if(plan.contains(id, lx, ly)) {
		c.plan[i] = p;
		c.target[i] = (float)plan.target;
		return;
	    }
	}
    }

    static BufferedImage image(ReliefRenderer.Cells c, int k, int lvl, double str, double cont) {
	BufferedImage img = TexI.mkbuf(Coord.of(c.n * k, c.n * k));
	ReliefRenderer.render(c, k, lvl, str, cont, ((DataBufferByte)img.getRaster().getDataBuffer()).getData());
	return(img);
    }

    public static boolean waterp(String resnm) {
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

    public static boolean cliffp(MapSource m, Coord tc, int t) {
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
