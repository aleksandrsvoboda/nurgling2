package nurgling.render.remembered;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import nurgling.NConfig;

import static haven.MCache.cmaps;
import static haven.MCache.cutn;
import static haven.MCache.tilesz;

/**
 * Remembered ground: the ground of every grid the map file has recorded,
 * drawn past the area the server streams, for one MapView. It reads the
 * map file and nothing else, so it costs the server nothing and shows no
 * objects: the bare ground is what is remembered, not what is there now.
 *
 * <p>Each tick a quadtree over the segment's grids, around where the
 * character is, decides what is drawn: grids close enough to the camera
 * for their tiles to show at full detail ({@link NearGround}), coarser
 * zoom-grid cells further out ({@link FarGround}). Live terrain always
 * wins where both have ground. Nothing is taken out of the scene before
 * what replaces it is built, so a camera moving about shows no holes.
 *
 * <p>Only a session whose terrain is drawn has it: a background session
 * keeps no meshes ({@link MCache#meshes()}) and holds none of this either.
 */
public class RememberedGround {
    public static final int RANGEMIN = 2, RANGEMAX = 128, RANGEDEF = 8;
    public static final int DETAILMIN = 25, DETAILMAX = 200, DETAILDEF = 100;
    /** Grids drawn at full detail at most, past the ones the live terrain stands on. */
    static final int FULLCAP = 16;
    /* Commander mode draws few grids (live areas and marked ones): all of them at full detail, never as the blurry
     * zoom cells, so ground coming into view is the ground it will stay. */
    static final int COMMANDER_FULLCAP = 40;

    private static int fullcap() {
	return((filter != null) ? COMMANDER_FULLCAP : FULLCAP);
    }
    static final int MAXLVL = 7;
    static final double LOCATEWAIT = 1.0, SETTINGSWAIT = 0.5, UPDATEWAIT = 0.1;
    static final float DEFFAR = 5000;

    private static volatile boolean on = false;

    /**
     * Commander mode: which recorded grids to draw. While one is set, remembered ground is on whatever the option
     * says, and only these grids show. {@link #version()} changes whenever its answer may have.
     */
    public interface Filter {
	boolean show(long seg, Coord sc, long gridid);
	int version();
    }
    private static volatile Filter filter = null;

    public static void filter(Filter f) {
	if(filter != f) {
	    filter = f;
	    reload();
	}
    }
    private static volatile int range = RANGEDEF, wash = 50, detail = DETAILDEF;
    private static double setread = 0;

    private static void readsettings() {
	double now = Utils.rtime();
	if(now - setread < SETTINGSWAIT)
	    return;
	setread = now;
	on = (filter != null) || Boolean.TRUE.equals(NConfig.get(NConfig.Key.rememberedGround));
	range = Utils.clip(intval(NConfig.Key.rememberedGroundRange, RANGEDEF), RANGEMIN, RANGEMAX);
	/* Commander mode shows remembered ground as it is: greyed, it would change colour under the camera each time
	 * a session that sees it live takes the view. */
	wash = (filter != null) ? 0 : Utils.clip(intval(NConfig.Key.rememberedGroundWash, 50), 0, 100);
	detail = Utils.clip(intval(NConfig.Key.rememberedGroundDetail, DETAILDEF), DETAILMIN, DETAILMAX);
    }

    private static int intval(NConfig.Key key, int def) {
	Object v = NConfig.get(key);
	return((v instanceof Number) ? ((Number)v).intValue() : def);
    }

    /** Settings changed (Options): read them on the next tick. */
    public static void reload() {
	setread = 0;
    }

    /** The far clipping plane a camera needs: past the remembered ground's
     * reach while it is on. */
    public static float zfar(float def) {
	if(!on)
	    return(def);
	return(def + (float)((range + 1) * cmaps.x * tilesz.x * Math.sqrt(2)));
    }

    private static final class Located {
	static final Located NONE = new Located(null, null, null, null), RETRY = new Located(null, null, null, null);
	final MapFile.Segment seg;
	final Coord off;
	/* The segment's grids and, per level, the zoom cells that hold any:
	 * null when unchanged since the last look. */
	final Set<Coord> known;
	final Set<Coord>[] occ;
	/* The segment's grid count and the filter version the known set was made at. */
	int srcsize = -1, fv = -1;
	Located(MapFile.Segment seg, Coord off, Set<Coord> known, Set<Coord>[] occ) {
	    this.seg = seg; this.off = off; this.known = known; this.occ = occ;
	}
    }

    @SuppressWarnings("unchecked")
    private static Set<Coord>[] occupancy(Set<Coord> known) {
	Set<Coord>[] occ = new Set[MAXLVL + 1];
	for(int l = 1; l <= MAXLVL; l++) {
	    Set<Coord> o = new HashSet<>();
	    for(Coord c : known)
		o.add(Coord.of((c.x >> l) << l, (c.y >> l) << l));
	    occ[l] = o;
	}
	return(occ);
    }

    private final MapView mv;
    private MapFile.Segment seg;
    private Coord off;
    private RecallMap src;
    private NearGround near;
    private FarGround far;
    private RenderTree.Slot snear, sfar;
    private float slotwash = -1;
    private Set<Coord> known = Collections.emptySet();
    private int knownsrc = -1, knownfv = -1;
    @SuppressWarnings("unchecked")
    private Set<Coord>[] occ = new Set[MAXLVL + 1];
    private Defer.Future<Located> locating;
    private double lastloc = 0, lastupdate = 0;
    private Set<FarGround.Key> drawn = new HashSet<>();
    private float appliedfar = -1;
    private int lastwant0 = 0, lastwantfar = 0;

    public RememberedGround(MapView mv) {
	this.mv = mv;
    }

    private MapFile mapfile() {
	GameUI gui = mv.getparent(GameUI.class);
	if(gui == null)
	    return(null);
	if(gui.mapfile != null)
	    return(gui.mapfile.file);
	if(gui.mmap != null)
	    return(gui.mmap.file);
	return(null);
    }

    public void tick() {
	readsettings();
	float zf = zfar(DEFFAR);
	if(zf != appliedfar) {
	    appliedfar = zf;
	    mv.camera.resized();
	}
	MapFile file = on ? mapfile() : null;
	if((file == null) || !mv.glob.map.meshes()) {
	    clear();
	    return;
	}
	locate(file);
	if(seg == null)
	    return;
	Coord3f cc;
	try {
	    cc = mv.getcc();
	} catch(Loading l) {
	    return;
	}
	if(src == null) {
	    src = new RecallMap(mv.glob.sess, file, seg, off);
	    near = new NearGround();
	    far = new FarGround(seg, off, known::contains);
	}
	slots();
	double now = Utils.rtime();
	if(now - lastupdate >= UPDATEWAIT) {
	    lastupdate = now;
	    update(cc);
	} else {
	    near.yield(mv::livecut);
	}
    }

    private void slots() {
	float w = wash / 100f;
	Pipe.Op st = (w > 0) ? Pipe.Op.compose(ShadowMap.maskshadow, new GroundWash(w)) : ShadowMap.maskshadow;
	if(snear == null) {
	    /* maskshadow: the shadow lists mirror every lit slot but these,
	     * and remembered ground lies outside every shadow box anyway. */
	    snear = mv.basic.add(near, st);
	    sfar = mv.basic.add(far, st);
	    slotwash = w;
	} else if(w != slotwash) {
	    snear.ostate(st);
	    sfar.ostate(st);
	    slotwash = w;
	}
    }

    /* Where the session's grids are in the map file, and which grids the
     * segment has; asked of a Defer thread, under a read lock it never waits for. */
    private void locate(MapFile file) {
	if(locating != null) {
	    if(!locating.done(0))
		return;
	    Located l;
	    try {
		l = locating.get(0);
	    } catch(Defer.DeferredException e) {
		new Warning(e, "remembered ground: cannot locate the session in the map").issue();
		l = Located.RETRY;
	    }
	    locating = null;
	    if(l == Located.NONE) {
		/* No live grid is recorded (yet): there is nothing to place
		 * remembered ground by. */
		clear();
	    } else if(l != Located.RETRY) {
		if((seg == null) || (l.seg.id != seg.id) || !l.off.equals(off)) {
		    /* A new place, or the same place re-based (a cave, a house,
		     * a segment merge): everything read through the old offset
		     * would be drawn where it never was. */
		    clear();
		    seg = l.seg;
		    off = l.off;
		}
		if(l.known != null) {
		    setknown(l.known, l.occ);
		    knownsrc = l.srcsize;
		    knownfv = l.fv;
		}
	    }
	}
	double now = Utils.rtime();
	if(now - lastloc < ((seg == null) ? 0.25 : LOCATEWAIT))
	    return;
	lastloc = now;
	List<long[]> live = new ArrayList<>();
	synchronized(mv.glob.map.grids) {
	    for(MCache.Grid g : mv.glob.map.grids.values())
		live.add(new long[] {g.id, g.gc.x, g.gc.y});
	}
	if(live.isEmpty())
	    return;
	final long curseg = (seg == null) ? 0 : seg.id;
	final int cursize = knownsrc, curfv = knownfv;
	final Filter f = filter;
	final int fv = (f == null) ? -1 : f.version();
	locating = Defer.later(() -> {
		Lock rl = file.lock.readLock();
		if(!rl.tryLock())
		    return(Located.RETRY);
		boolean locked = true;
		try {
		    for(long[] g : live) {
			MapFile.GridInfo info = file.gridinfo.get(g[0]);
			if(info == null)
			    continue;
			MapFile.Segment s = file.segments.get(info.seg);
			if(s == null)
			    continue;
			Coord o = info.sc.sub((int)g[1], (int)g[2]);
			/* Copied only when it changed: the segment's grids are
			 * only ever added to, so the count tells. */
			int size = s.map.size();
			if((s.id == curseg) && (size == cursize) && (fv == curfv))
			    return(new Located(s, o, null, null));
			Set<Coord> k;
			if(f == null) {
			    k = new HashSet<>(s.map.keySet());
			} else {
			    k = new HashSet<>();
			    for(Map.Entry<Coord, Long> e : s.map.entrySet()) {
				if(f.show(s.id, e.getKey(), e.getValue()))
				    k.add(e.getKey());
			    }
			}
			rl.unlock();
			locked = false;
			Located ret = new Located(s, o, k, occupancy(k));
			ret.srcsize = size;
			ret.fv = fv;
			return(ret);
		    }
		    return(Located.NONE);
		} finally {
		    if(locked)
			rl.unlock();
		}
	    });
    }

    private void setknown(Set<Coord> k, Set<Coord>[] occ) {
	known = k;
	this.occ = occ;
	if(far != null)
	    far.known(k::contains);
	if(src != null)
	    src.forgetmissing();
    }

    private boolean exists(int lvl, Coord sc) {
	if(lvl == 0)
	    return(known.contains(sc));
	Set<Coord> o = occ[lvl];
	return((o != null) && o.contains(sc));
    }

    /* Selection state for one tick. */
    private double ex, ey, ez, gz, split;
    private Set<Coord> forced;
    private Coord cs;
    private int rng;
    private final Map<FarGround.Key, Double> kdist = new HashMap<>();

    private double dist(FarGround.Key k) {
	Double d = kdist.get(k);
	if(d != null)
	    return(d);
	double sz = k.size() * cmaps.x * tilesz.x;
	Coord gc = k.sc.sub(off);
	double x0 = gc.x * cmaps.x * tilesz.x, y0 = gc.y * cmaps.y * tilesz.y;
	double dx = Math.max(0, Math.max(x0 - ex, ex - (x0 + sz)));
	double dy = Math.max(0, Math.max(y0 - ey, ey - (y0 + sz)));
	double dz = ez - gz;
	double ret = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
	kdist.put(k, ret);
	return(ret);
    }

    private boolean hasforced(FarGround.Key k) {
	for(Coord f : forced) {
	    if((f.x >= k.sc.x) && (f.y >= k.sc.y) && (f.x < k.sc.x + k.size()) && (f.y < k.sc.y + k.size()))
		return(true);
	}
	return(false);
    }

    private void visit(int lvl, Coord sc, List<FarGround.Key> out) {
	int sz = 1 << lvl;
	if((sc.x + sz <= cs.x - rng) || (sc.x > cs.x + rng) || (sc.y + sz <= cs.y - rng) || (sc.y > cs.y + rng))
	    return;
	if(!exists(lvl, sc))
	    return;
	FarGround.Key k = new FarGround.Key(lvl, sc);
	if(lvl == 0) {
	    out.add(k);
	    return;
	}
	boolean sp = hasforced(k) || ((sz * cmaps.x * tilesz.x) > (split * dist(k)));
	if(!sp) {
	    out.add(k);
	    return;
	}
	int h = sz >> 1;
	visit(lvl - 1, sc, out);
	visit(lvl - 1, sc.add(h, 0), out);
	visit(lvl - 1, sc.add(0, h), out);
	visit(lvl - 1, sc.add(h, h), out);
    }

    /* The cells to draw: a quadtree from the top level down over the range,
     * split while a cell is large for its distance to the camera. */
    private List<FarGround.Key> select() {
	List<FarGround.Key> out = new ArrayList<>();
	int top = Utils.clip(32 - Integer.numberOfLeadingZeros(rng), 1, MAXLVL);
	int sz = 1 << top;
	int x0 = ((cs.x - rng) >> top) << top, y0 = ((cs.y - rng) >> top) << top;
	for(int y = y0; y <= cs.y + rng; y += sz) {
	    for(int x = x0; x <= cs.x + rng; x += sz)
		visit(top, Coord.of(x, y), out);
	}
	/* Too many grids at full detail: the farthest pairs of rows go back
	 * to their level-one cell, which never holds live ground. */
	List<FarGround.Key> full = new ArrayList<>();
	for(FarGround.Key k : out) {
	    if((k.lvl == 0) && !forced.contains(k.sc))
		full.add(k);
	}
	if(full.size() > fullcap()) {
	    Map<Coord, List<FarGround.Key>> parents = new HashMap<>();
	    for(FarGround.Key k : out) {
		if(k.lvl == 0)
		    parents.computeIfAbsent(Coord.of((k.sc.x >> 1) << 1, (k.sc.y >> 1) << 1), c -> new ArrayList<>()).add(k);
	    }
	    List<FarGround.Key> ps = new ArrayList<>();
	    for(Coord p : parents.keySet()) {
		FarGround.Key pk = new FarGround.Key(1, p);
		if(!hasforced(pk))
		    ps.add(pk);
	    }
	    ps.sort(Comparator.comparingDouble(this::dist).reversed());
	    int nfull = full.size();
	    Set<FarGround.Key> gone = new HashSet<>();
	    List<FarGround.Key> add = new ArrayList<>();
	    for(FarGround.Key pk : ps) {
		if(nfull <= fullcap())
		    break;
		List<FarGround.Key> ch = parents.get(pk.sc);
		gone.addAll(ch);
		nfull -= ch.size();
		add.add(pk);
	    }
	    out.removeAll(gone);
	    out.addAll(add);
	}
	out.sort(Comparator.comparingDouble(this::dist));
	return(out);
    }

    private void update(Coord3f cc) {
	Coord3f eye = mv.cameye();
	ex = eye.x; ey = -eye.y; ez = eye.z;
	gz = cc.z;
	/* A cell splits while it is larger than split x its distance; more
	 * detail splits sooner. */
	split = (filter != null) ? 0 : (50.0 / detail);
	rng = range;
	kdist.clear();
	cs = Coord2d.of(cc.x, cc.y).floor(tilesz).div(cmaps).add(off);
	forced = new HashSet<>();
	Area la = mv.terrainarea();
	if(la != null) {
	    for(Coord c : la)
		forced.add(c.div(cutn).add(off));
	}

	List<FarGround.Key> want = select();
	/* What the near ground builds and draws: the grids wanted whole, then
	 * the ones still drawn whole while what replaces them is built. */
	List<Coord> want0 = new ArrayList<>();
	List<FarGround.Key> wantfar = new ArrayList<>();
	for(FarGround.Key k : want) {
	    if(k.lvl == 0)
		want0.add(k.sc.sub(off));
	    else
		wantfar.add(k);
	}
	Set<FarGround.Key> wantset = new HashSet<>(want);
	for(FarGround.Key k : drawn) {
	    if(wantset.contains(k))
		continue;
	    if(k.lvl == 0)
		want0.add(k.sc.sub(off));
	    else
		wantfar.add(k);
	}
	List<Coord> load = new ArrayList<>(want0);
	Set<Coord> loadset = new HashSet<>(want0);
	for(Coord gc : want0) {
	    for(Coord n : RecallMap.neighbours(gc)) {
		if(loadset.add(n))
		    load.add(n);
	    }
	}
	src.want(load, known::contains);
	Set<Coord> ready0 = near.prepare(src.map, want0, mv::livecut);
	Set<FarGround.Key> readyfar = far.prepare(wantfar);

	/* What is drawn: every wanted cell that is ready, and where one is
	 * not, whatever was drawn over its ground last tick. */
	Set<FarGround.Key> shown = new HashSet<>();
	List<FarGround.Key> hold = new ArrayList<>();
	for(FarGround.Key k : want) {
	    boolean ready = (k.lvl == 0) ? ready0.contains(k.sc.sub(off)) : readyfar.contains(k);
	    if(ready) {
		shown.add(k);
		continue;
	    }
	    for(FarGround.Key p : drawn) {
		if(p.overlaps(k))
		    hold.add(p);
	    }
	}
	for(FarGround.Key h : hold) {
	    boolean ready = (h.lvl == 0) ? ready0.contains(h.sc.sub(off)) : readyfar.contains(h);
	    if(!ready)
		continue;
	    shown.removeIf(s -> (h.lvl > s.lvl) && h.contains(s));
	    shown.add(h);
	}
	Set<Coord> shown0 = new HashSet<>();
	Set<FarGround.Key> shownfar = new HashSet<>();
	for(FarGround.Key k : shown) {
	    if(k.lvl == 0)
		shown0.add(k.sc.sub(off));
	    else
		shownfar.add(k);
	}
	near.show(shown0, mv::livecut);
	far.show(shownfar, new HashSet<>(wantfar));
	src.retain(loadset);
	drawn = shown;
	lastwant0 = want0.size();
	lastwantfar = wantfar.size();
    }

    /** Takes it all out of the scene, then lets go of what it held. The
     * segment's grid set stays: it is the same segment's until the next
     * look says otherwise. */
    public void clear() {
	if((snear == null) && (src == null) && (seg == null) && (locating == null))
	    return;
	if(snear != null) {
	    snear.remove();
	    sfar.remove();
	    snear = sfar = null;
	}
	if(near != null)
	    near.clear();
	if(far != null)
	    far.dispose();
	if(src != null)
	    src.dispose();
	near = null;
	far = null;
	src = null;
	seg = null;
	off = null;
	drawn = new HashSet<>();
	if(locating != null) {
	    locating.cancel();
	    locating = null;
	}
	lastloc = lastupdate = 0;
    }

    /** The recorded ground's height at a point of the session, or NaN where nothing recorded is held. */
    public double height(Coord2d pc) {
	RecallMap s = src;
	if(s == null)
	    return(Double.NaN);
	try {
	    return(s.map.getcz(pc));
	} catch(Loading l) {
	    return(Double.NaN);
	}
    }

    /** The segment this is placed in, or 0. */
    public long segment() {
	MapFile.Segment s = seg;
	return((s == null) ? 0 : s.id);
    }

    public String stats() {
	if(!on)
	    return("remembered ground: off");
	if(seg == null)
	    return("remembered ground: not placed (no recorded grid under the session yet)");
	return(String.format("remembered ground: segment %x, range %d, %d known grids; full detail %d wanted, %d cuts drawn; " +
			     "far %d wanted, %d drawn; %d grids read, %d held, %d asked and never sent",
			     seg.id, range, known.size(), lastwant0, near.drawn(), lastwantfar, far.drawn(),
			     src.read, src.grids(), src.map.pendingreqs()));
    }
}
