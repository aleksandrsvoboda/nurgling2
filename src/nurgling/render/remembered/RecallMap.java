package nurgling.render.remembered;

import haven.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.function.Predicate;

import static haven.MCache.cmaps;

/**
 * The remembered ground's own map cache: an {@link MCache} on the live
 * session whose grids come from the map file instead of the server. Nothing
 * ticks it and nothing calls {@code sendreqs()} on it, so a grid it lacks is
 * asked for and never sent; the live {@code glob.map}, which bots, the
 * pathfinder and clicks read, never sees a recorded grid.
 *
 * <p>Grids are keyed in the live session's grid coordinates, so a cut drawn
 * from here lines up with the live terrain. {@code off} turns them into the
 * segment's grid coordinates: segment = session + off.
 *
 * <p>A cut's mesh reads one tile into each neighbouring grid. Where the map
 * file has no neighbour (the edge of the explored world) a pad stands in:
 * a grid that repeats the nearest edge tiles and heights of the grids beside
 * it, so edge cuts build instead of waiting for ground that does not exist.
 */
public class RecallMap {
    /** Loads read from the map file at once. */
    static final int MAXREAD = 6;
    /** Grids held past the ones asked for, for the view coming back. */
    static final int RETAIN = 8;

    public final MCache map;
    final MapFile file;
    final MapFile.Segment seg;
    final Coord off;

    private final Map<Coord, Defer.Future<Loaded>> loading = new HashMap<>();
    /** Recorded grids in the cache, least recently wanted first. */
    private final LinkedHashMap<Coord, Long> real = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Coord> pads = new HashSet<>();
    /** Grids the segment says it has but whose record would not load. */
    private final Set<Coord> unreadable = new HashSet<>();
    private final Map<String, Integer> tileids = new HashMap<>();
    private int ntiles = 0;
    /** Grids read from the map file, for :recall. */
    public int read = 0;

    private static final class Loaded {
	static final Loaded RETRY = new Loaded(null), MISSING = new Loaded(null);
	final MapFile.Grid grid;
	Loaded(MapFile.Grid grid) {this.grid = grid;}
    }

    public RecallMap(Session sess, MapFile file, MapFile.Segment seg, Coord off) {
	this.map = new MCache(sess);
	this.file = file;
	this.seg = seg;
	this.off = off;
    }

    public boolean has(Coord gc) {
	return(real.containsKey(gc));
    }

    public int grids() {
	return(real.size());
    }

    /**
     * Reads the grids in {@code want} (session grid coords, most wanted
     * first) that the segment has, a few at a time, and keeps pads beside
     * them where it has none. {@code known} is the segment's own set of
     * grids (segment coords).
     */
    public void want(List<Coord> want, Predicate<Coord> known) {
	poll(known);
	for(Coord gc : want) {
	    if(real.containsKey(gc)) {
		real.get(gc);   /* touch, for the retain order */
		continue;
	    }
	    if(loading.containsKey(gc) || unreadable.contains(gc) || !known.test(gc.add(off)))
		continue;
	    if(loading.size() >= MAXREAD)
		break;
	    loading.put(gc, load(gc.add(off)));
	}
    }

    private Defer.Future<Loaded> load(Coord sc) {
	MapFile file = this.file;
	MapFile.Segment seg = this.seg;
	return(Defer.later(() -> {
		    /* The map file's writer holds its lock across disk I/O; never
		     * wait on it, come back next tick instead. */
		    Lock rl = file.lock.readLock();
		    if(!rl.tryLock())
			return(Loaded.RETRY);
		    try {
			Long id = seg.map.get(sc);
			if(id == null)
			    return(Loaded.MISSING);
			MapFile.Grid g = MapFile.Grid.load(file, id);
			return((g == null) ? Loaded.MISSING : new Loaded(g));
		    } finally {
			rl.unlock();
		    }
		}));
    }

    private void poll(Predicate<Coord> known) {
	for(Iterator<Map.Entry<Coord, Defer.Future<Loaded>>> i = loading.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Coord, Defer.Future<Loaded>> e = i.next();
	    if(!e.getValue().done(0))
		continue;
	    i.remove();
	    Coord gc = e.getKey();
	    Loaded l;
	    try {
		l = e.getValue().get(0);
	    } catch(Defer.DeferredException ex) {
		/* A record that would not decode: leave that grid out. */
		new Warning(ex, "remembered ground: could not read grid at " + gc).issue();
		unreadable.add(gc);
		continue;
	    }
	    if(l == Loaded.RETRY)
		continue;
	    if(l == Loaded.MISSING) {
		unreadable.add(gc);
		continue;
	    }
	    put(gc, l.grid);
	    read++;
	    for(Coord n : neighbours(gc)) {
		if(!real.containsKey(n) && !known.test(n.add(off)))
		    mkpad(n);
	    }
	}
    }

    @SuppressWarnings("unchecked")
    private MCache.Grid blank(Coord gc, long id) {
	MCache.Grid g = map.new Grid(gc);
	g.id = id;
	g.ols = new Indir[0];
	g.ol = new boolean[0][];
	g.seq = 0;
	return(g);
    }

    private void put(Coord gc, MapFile.Grid src) {
	MCache.Grid g = blank(gc, src.id);
	int[] tmap = new int[src.tilesets.length];
	for(int i = 0; i < src.tilesets.length; i++) {
	    Resource.Saved res = src.tilesets[i].res;
	    String key = res.name + "\0" + res.ver;
	    Integer id = tileids.get(key);
	    if(id == null) {
		tileids.put(key, id = ntiles++);
		map.settileset(id, res);
	    }
	    tmap[i] = id;
	}
	for(int i = 0; i < g.tiles.length; i++) {
	    g.tiles[i] = tmap[src.tiles[i]];
	    g.z[i] = src.zmap[i];
	}
	pads.remove(gc);
	real.put(gc, src.id);
	map.putgrid(g);
    }

    /** A stand-in where the segment has no grid: every tile is the nearest
     * tile of the recorded grids beside it. */
    private void mkpad(Coord gc) {
	List<MCache.Grid> srcs = new ArrayList<>();
	synchronized(map.grids) {
	    for(Coord n : neighbours(gc)) {
		if(real.containsKey(n)) {
		    MCache.Grid g = map.grids.get(n);
		    if(g != null)
			srcs.add(g);
		}
	    }
	}
	if(srcs.isEmpty())
	    return;
	MCache.Grid pad = blank(gc, 0);
	Coord ul = gc.mul(cmaps);
	for(int y = 0; y < cmaps.y; y++) {
	    for(int x = 0; x < cmaps.x; x++) {
		int tx = ul.x + x, ty = ul.y + y;
		MCache.Grid best = null;
		int bd = Integer.MAX_VALUE, bx = 0, by = 0;
		for(MCache.Grid s : srcs) {
		    int cx = Utils.clip(tx, s.ul.x, s.ul.x + cmaps.x - 1);
		    int cy = Utils.clip(ty, s.ul.y, s.ul.y + cmaps.y - 1);
		    int d = Math.max(Math.abs(cx - tx), Math.abs(cy - ty));
		    if(d < bd) {
			bd = d; best = s; bx = cx - s.ul.x; by = cy - s.ul.y;
		    }
		}
		int i = x + (y * cmaps.x);
		pad.tiles[i] = best.gettile(bx, by);
		pad.z[i] = (float)best.getz(bx, by);
	    }
	}
	pads.add(gc);
	map.putgrid(pad);
    }

    /**
     * Lets go of the recorded grids not in {@code keep} past the
     * {@link #RETAIN} most recently wanted, and of pads no kept grid borders.
     * The caller has taken every cut of a grid that goes out of the scene.
     */
    public void retain(Set<Coord> keep) {
	int extra = 0;
	for(Coord gc : real.keySet()) {
	    if(!keep.contains(gc))
		extra++;
	}
	for(Iterator<Coord> i = real.keySet().iterator(); i.hasNext() && (extra > RETAIN);) {
	    Coord gc = i.next();
	    if(keep.contains(gc))
		continue;
	    i.remove();
	    map.dropgrid(gc);
	    extra--;
	}
	for(Iterator<Coord> i = pads.iterator(); i.hasNext();) {
	    Coord gc = i.next();
	    boolean used = false;
	    for(Coord n : neighbours(gc)) {
		if(real.containsKey(n)) {
		    used = true;
		    break;
		}
	    }
	    if(!used) {
		i.remove();
		map.dropgrid(gc);
	    }
	}
	/* Nothing sends what the cache asks for; don't let it pile up. */
	map.clearreqs();
    }

    /** Forgets that a grid would not load, so the next want reads it again
     * (it may have been recorded since). */
    public void forgetmissing() {
	unreadable.clear();
    }

    public void dispose() {
	for(Defer.Future<Loaded> f : loading.values())
	    f.cancel();
	loading.clear();
	real.clear();
	pads.clear();
	map.trimall();
    }

    static Coord[] neighbours(Coord gc) {
	Coord[] ret = new Coord[8];
	int n = 0;
	for(int y = -1; y <= 1; y++) {
	    for(int x = -1; x <= 1; x++) {
		if((x != 0) || (y != 0))
		    ret[n++] = gc.add(x, y);
	    }
	}
	return(ret);
    }
}
