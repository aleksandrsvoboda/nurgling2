package nurgling.navigation;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import monitoring.NGlobalSearchItems;
import nurgling.NConfig;
import nurgling.NMapView;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.sessions.ThreadLocalUI;
import nurgling.tools.Finder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static haven.OCache.posres;

/**
 * Turns the container search results into a route the player can see on the ground.
 *
 * The item database stores, for every container ever opened, the grid it stands on and
 * its offset within that grid - which is exactly what ChunkNav needs to plan to it. So a
 * search for "blueberry" can be answered with a real route even when the container is
 * two zones away and its gob is nowhere near loaded.
 *
 * Two very different costs are kept apart here:
 *
 *  - Planning is a cross-chunk A* that also re-records every visible grid first. It runs
 *    on a worker thread, at most one at a time, and its result is cached per container
 *    until the player's chunk changes.
 *  - Resolving a cached path to world coordinates is just grid lookups and arithmetic,
 *    so it happens on the render tick.
 *
 * A trail can only be drawn where terrain is paged in. Beyond that - past a door into a
 * house, or simply past the edge of what is loaded - it stops, and the last point is the
 * thing to head for. That is not a fallback, it is the honest edge of what the client
 * knows how to draw; the trail picks up again once the player is through.
 */
public class StorageTrailService {

    /** Only plan for this many containers at once; overlapping ribbons stop being readable. */
    private static final int MAX_TRAILS_CAP = 5;
    /** Sample one trail point every N tiles of path - the ribbon does not need every tile. */
    private static final int STEP_STRIDE = 3;
    /** Replan this often even when nothing obvious changed, in case new chunks opened a shorter way. */
    private static final double REPLAN_INTERVAL = 10.0;
    /** Two trail points closer than this collapse into one. */
    private static final double MIN_POINT_SPACING = 4.0;

    /** A route to one container, resolved to world coordinates in the current session. */
    public static class Trail {
        public final String containerHash;
        /** Player-first list of world points. At least two entries. */
        public final List<Coord2d> points;
        /** Where the trail stops: the container itself, a door, or the edge of loaded terrain. */
        public final Coord2d terminus;
        /** True when the terminus is the container, rather than a hand-off point. */
        public final boolean atContainer;
        public final int count;
        public final double maxQuality;

        Trail(String containerHash, List<Coord2d> points, boolean atContainer, int count, double maxQuality) {
            this.containerHash = containerHash;
            this.points = Collections.unmodifiableList(points);
            this.terminus = points.get(points.size() - 1);
            this.atContainer = atContainer;
            this.count = count;
            this.maxQuality = maxQuality;
        }
    }

    /** Everything cached for one candidate container. */
    private static class Entry {
        final NGlobalSearchItems.ContainerHit hit;
        final Coord localTile;          // container position as a tile coord within its grid
        volatile ChunkPath path;
        volatile boolean planned;       // a plan was attempted (path may still be null)
        volatile long plannedFromGrid;
        double lastInvalidated;         // when this entry last asked for an off-route replan

        Entry(NGlobalSearchItems.ContainerHit hit, Coord localTile) {
            this.hit = hit;
            this.localTile = localTile;
        }
    }

    private final NMapView mv;
    private final ExecutorService planner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StorageTrail-Planner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean planning = new AtomicBoolean(false);

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Containers deleted from the database, waiting to be dropped on the next tick. */
    private final Set<String> pendingForget = ConcurrentHashMap.newKeySet();
    private long lastSearchVersion = -1;
    private long lastPlayerGrid = Long.MIN_VALUE;
    private double lastReplan = 0;
    private double lastResolve = 0;
    private Coord2d lastResolvePos = null;
    private volatile List<Trail> trails = Collections.emptyList();

    public StorageTrailService(NMapView mv) {
        this.mv = mv;
    }

    /** Latest resolved trails. Never null; safe to read from the render thread. */
    public List<Trail> trails() {
        return trails;
    }

    /**
     * Hash of a trailed container whose recorded position is within {@code radius} of this
     * world point, nearest first, or null if there is none.
     *
     * Deliberately limited to containers a trail is currently pointing at. The database
     * keeps rows for containers that no longer exist, and this is how one gets removed -
     * so the only things reachable this way are the ones the client is actively sending
     * the player to, which cannot delete a container nobody was looking for.
     *
     * Cheap enough to call from a click handler: it walks at most MAX_TRAILS_CAP entries
     * and touches no database.
     */
    public String containerAt(Coord2d wc, double radius) {
        if (wc == null || entries.isEmpty())
            return null;
        MCache mcache;
        try {
            mcache = mv.glob.map;
        } catch (Loading l) {
            return null;
        }
        if (mcache == null)
            return null;

        Map<Long, Coord> origins = new HashMap<>();
        String best = null;
        double bestDist = radius;
        for (Entry e : entries.values()) {
            Coord origin = originOf(mcache, origins, e.hit.gridId);
            if (origin == null)
                continue;   // container's grid is not loaded, so it is not what was clicked
            Coord2d pos = containerWorld(origin, e.hit.coord);
            if (pos == null)
                continue;
            double d = pos.dist(wc);
            if (d <= bestDist) {
                bestDist = d;
                best = e.hit.hash;
            }
        }
        return best;
    }

    /**
     * Drop a container from the live candidate set. Called after its row is deleted, so the
     * trail goes away instead of lingering until the next search refresh.
     *
     * Called from the thread that ran the delete, not the render thread, so the removal is
     * queued rather than applied here - `entries` is an ordered map that tick() iterates,
     * and ordering carries the trail ranking, so it cannot simply be made concurrent.
     */
    public void forget(String hash) {
        if (hash != null)
            pendingForget.add(hash);
    }

    public void shutdown() {
        planner.shutdownNow();
    }

    /* ------------------------------------------------------------------ *
     *  Tick
     * ------------------------------------------------------------------ */

    /** Called once per frame from the map view. Cheap unless a replan is due. */
    public void tick() {
        if (!enabled()) {
            if (!trails.isEmpty() || !entries.isEmpty()) {
                entries.clear();
                trails = Collections.emptyList();
            }
            pendingForget.clear();
            return;
        }

        boolean forgotten = false;
        if (!pendingForget.isEmpty()) {
            for (String hash : pendingForget) {
                if (entries.remove(hash) != null)
                    forgotten = true;
            }
            pendingForget.clear();
        }

        long playerGrid = playerGridId();
        if (playerGrid == Long.MIN_VALUE) {
            trails = Collections.emptyList();
            return;
        }

        boolean queryChanged = NGlobalSearchItems.updateVersion != lastSearchVersion;
        boolean gridChanged = playerGrid != lastPlayerGrid;
        double now = haven.Utils.rtime();
        boolean stale = (now - lastReplan) > REPLAN_INTERVAL;

        if (queryChanged) {
            lastSearchVersion = NGlobalSearchItems.updateVersion;
            rebuildCandidates();
        }

        // Ask every tick while anything is unplanned, rather than only on the triggers: a
        // request made while a pass is already running is refused, and waiting for the next
        // trigger would leave the player staring at nothing for up to REPLAN_INTERVAL.
        if (queryChanged || gridChanged || stale || hasUnplanned(playerGrid)) {
            if (requestPlans(playerGrid)) {
                lastPlayerGrid = playerGrid;
                lastReplan = now;
            }
        }

        // Resolving allocates a fresh point list per trail, so it is not worth doing every
        // frame; the geometry it feeds is rebuilt on its own throttle anyway.
        Coord2d plrc = playerRc();
        boolean moved = (plrc != null) && ((lastResolvePos == null) || (lastResolvePos.dist(plrc) > 2.0));
        if (forgotten || moved || (now - lastResolve) > 0.25 || trails.isEmpty()) {
            lastResolve = now;
            lastResolvePos = plrc;
            trails = resolveAll();
        }
    }

    private boolean hasUnplanned(long playerGrid) {
        for (Entry e : entries.values()) {
            if (!e.planned || e.plannedFromGrid != playerGrid)
                return true;
        }
        return false;
    }

    private Coord2d playerRc() {
        try {
            Gob pl = mv.player();
            return (pl == null) ? null : pl.rc;
        } catch (Loading l) {
            return null;
        }
    }

    private boolean enabled() {
        Object v = NConfig.get(NConfig.Key.showStorageTrail);
        if (!(v instanceof Boolean) || !(Boolean) v)
            return false;
        Object db = NConfig.get(NConfig.Key.ndbenable);
        return (db instanceof Boolean) && (Boolean) db;
    }

    private int maxTrails() {
        Object v = NConfig.get(NConfig.Key.storageTrailMax);
        int n = (v instanceof Number) ? ((Number) v).intValue() : 3;
        return Math.max(1, Math.min(MAX_TRAILS_CAP, n));
    }

    /* ------------------------------------------------------------------ *
     *  Candidate selection
     * ------------------------------------------------------------------ */

    /**
     * Take the search hits, keep the ones ChunkNav could plausibly route to, and rank them
     * by chunk hops so the cheap BFS decides which containers are worth a real path search.
     */
    private void rebuildCandidates() {
        entries.clear();
        ChunkNavManager nav = mv.getChunkNavManager();
        if (nav == null || !nav.isInitialized())
            return;

        List<NGlobalSearchItems.ContainerHit> hits = NGlobalSearchItems.hits();
        if (hits.isEmpty())
            return;

        List<Object[]> ranked = new ArrayList<>(hits.size());
        for (NGlobalSearchItems.ContainerHit hit : hits) {
            Coord tile = tileOf(hit.coord);
            if (tile == null)
                continue;
            int hops = nav.chunkHopsTo(hit.gridId);
            if (hops < 0)
                continue;   // no chunk-level route - a plan would fail too
            ranked.add(new Object[]{hops, hit, tile});
        }
        ranked.sort((a, b) -> Integer.compare((Integer) a[0], (Integer) b[0]));

        int limit = maxTrails();
        for (int i = 0; i < ranked.size() && entries.size() < limit; i++) {
            NGlobalSearchItems.ContainerHit hit = (NGlobalSearchItems.ContainerHit) ranked.get(i)[1];
            entries.put(hit.hash, new Entry(hit, (Coord) ranked.get(i)[2]));
        }
    }

    /**
     * Queue a planning pass for any candidate whose cached path no longer starts where the
     * player is. One pass at a time: these searches are expensive and the results are only
     * advisory, so there is no point running several concurrently.
     */
    private boolean requestPlans(long playerGrid) {
        if (entries.isEmpty())
            return true;
        List<Entry> todo = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.planned || e.plannedFromGrid != playerGrid)
                todo.add(e);
        }
        if (todo.isEmpty())
            return true;
        if (!planning.compareAndSet(false, true))
            return false;   // a pass is already running; retried on the next tick

        final NUI boundUI = NUtils.getUI();
        final ChunkNavManager nav = mv.getChunkNavManager();
        if (boundUI == null || nav == null || !nav.isInitialized()) {
            planning.set(false);
            return false;
        }

        planner.submit(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                for (Entry e : todo) {
                    ChunkPath path = nav.planToGridCoord(e.hit.gridId, e.localTile);
                    e.path = path;
                    e.plannedFromGrid = playerGrid;
                    e.planned = true;
                }
            } catch (Loading ignored) {
                // Terrain moved out from under the search; the next tick will retry.
            } finally {
                ThreadLocalUI.clear();
                planning.set(false);
            }
        });
        return true;
    }

    /* ------------------------------------------------------------------ *
     *  Resolution to world coordinates
     * ------------------------------------------------------------------ */

    private List<Trail> resolveAll() {
        if (entries.isEmpty())
            return Collections.emptyList();

        // The player position and grid are the same for every trail, so resolve them once
        // rather than per entry - this runs on the render tick.
        Coord2d plrc = playerRc();
        long playerGrid = playerGridId();
        MCache mcache;
        try {
            mcache = mv.glob.map;
        } catch (Loading l) {
            return Collections.emptyList();
        }
        if (plrc == null || playerGrid == Long.MIN_VALUE || mcache == null)
            return Collections.emptyList();

        List<Trail> out = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            Trail t = resolve(e, mcache, plrc, playerGrid);
            if (t != null)
                out.add(t);
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    /**
     * Walk the cached path from the segment the player is standing in, converting tile
     * steps to world coordinates against the <em>live</em> grid origin.
     *
     * The stored ChunkNavData.worldTileOrigin is deliberately not persisted - it is
     * session-local and stale values cause exactly the kind of silently-wrong geometry an
     * on-screen trail would make very visible - so origins are looked up fresh here, the
     * same way ChunkNavExecutor does while walking.
     */
    private Trail resolve(Entry e, MCache mcache, Coord2d plrc, long playerGrid) {
        ChunkPath path = e.path;
        if (path == null || path.segments.isEmpty())
            return null;

        // Later passes win: a route that re-enters a grid should be picked up at the point
        // it is on now, not where it first passed through.
        int start = -1;
        for (int i = 0; i < path.segments.size(); i++) {
            if (path.segments.get(i).gridId == playerGrid)
                start = i;
        }
        if (start < 0) {
            // Drifted off the planned route. Replanning starts from where the player is now,
            // so the next path normally contains their grid - but if they keep moving while
            // the search runs it can miss again, so rate-limit rather than spin.
            double now = haven.Utils.rtime();
            if ((now - e.lastInvalidated) > 1.0) {
                e.lastInvalidated = now;
                e.planned = false;
            }
            return null;
        }

        Map<Long, Coord> origins = new HashMap<>();
        List<Coord2d> pts = new ArrayList<>();
        pts.add(plrc);
        boolean atContainer = false;

        for (int i = start; i < path.segments.size(); i++) {
            ChunkPath.PathSegment seg = path.segments.get(i);
            Coord origin = originOf(mcache, origins, seg.gridId);
            if (origin == null)
                break;   // terrain past here is not paged in - nothing to lay a ribbon on

            int from = (i == start) ? nearestStep(seg, origin, plrc) : 0;
            for (int k = from; k < seg.steps.size(); k += STEP_STRIDE)
                addPoint(pts, worldOf(origin, seg.steps.get(k).localCoord));
            if (!seg.steps.isEmpty())
                addPoint(pts, worldOf(origin, seg.steps.get(seg.steps.size() - 1).localCoord));

            if (seg.type == ChunkPath.SegmentType.PORTAL) {
                long next = (i + 1 < path.segments.size()) ? path.segments.get(i + 1).gridId : -1;
                Coord2d door = portalPoint(seg, next, origin);
                if (door != null)
                    addPoint(pts, door);
                break;   // the trail hands off at the door
            }

            if (seg.gridId == e.hit.gridId) {
                Coord2d cpos = containerWorld(origin, e.hit.coord);
                if (cpos != null) {
                    addPoint(pts, cpos);
                    atContainer = true;
                }
                break;
            }
        }

        if (pts.size() < 2)
            return null;
        return new Trail(e.hit.hash, pts, atContainer, e.hit.count, e.hit.maxQuality);
    }

    /** Index of the step closest to the player, so the trail does not start behind them. */
    private int nearestStep(ChunkPath.PathSegment seg, Coord origin, Coord2d plrc) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < seg.steps.size(); i++) {
            double d = worldOf(origin, seg.steps.get(i).localCoord).dist(plrc);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Where to aim for a segment that ends in a portal. The recorded portal gives a tile
     * position that is always available; if its gob happens to be loaded, its real position
     * is better, because doors are not centred on their tile.
     */
    private Coord2d portalPoint(ChunkPath.PathSegment seg, long nextGridId, Coord origin) {
        ChunkNavManager nav = mv.getChunkNavManager();
        if (nav == null)
            return null;
        ChunkNavData chunk = nav.getGraph().getChunk(seg.gridId);
        if (chunk == null || chunk.portals.isEmpty())
            return null;

        ChunkPortal best = null;
        for (ChunkPortal p : chunk.portals) {
            if (p.localCoord == null)
                continue;
            if (nextGridId != -1 && p.connectsToGridId == nextGridId) {
                best = p;
                break;
            }
        }
        if (best == null && !seg.steps.isEmpty()) {
            // No recorded destination match - take the portal nearest where the walk ends.
            Coord end = seg.steps.get(seg.steps.size() - 1).localCoord;
            double bestDist = Double.MAX_VALUE;
            for (ChunkPortal p : chunk.portals) {
                if (p.localCoord == null)
                    continue;
                double d = p.localCoord.dist(end);
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
        }
        if (best == null)
            return null;

        if (best.gobHash != null) {
            Gob gob = Finder.findGob(best.gobHash);
            if (gob != null) {
                try {
                    return gob.rc;
                } catch (Loading ignored) {
                }
            }
        }
        return worldOf(origin, best.localCoord);
    }

    /* ------------------------------------------------------------------ *
     *  Coordinate helpers
     * ------------------------------------------------------------------ */

    /** Player's current grid id, or Long.MIN_VALUE when it cannot be resolved this frame. */
    private long playerGridId() {
        try {
            Gob pl = mv.player();
            if (pl == null)
                return Long.MIN_VALUE;
            MCache.Grid g = mv.glob.map.getgridt(pl.rc.floor(MCache.tilesz));
            return (g == null) ? Long.MIN_VALUE : g.id;
        } catch (Loading l) {
            return Long.MIN_VALUE;
        } catch (RuntimeException e) {
            return Long.MIN_VALUE;
        }
    }

    /** World tile origin of a loaded grid, or null when that grid is not in memory. */
    private Coord originOf(MCache mcache, Map<Long, Coord> cache, long gridId) {
        Coord known = cache.get(gridId);
        if (known != null)
            return known;
        synchronized (mcache.grids) {
            for (MCache.Grid g : mcache.grids.values()) {
                if (g != null && g.id == gridId && g.ul != null) {
                    cache.put(gridId, g.ul);
                    return g.ul;
                }
            }
        }
        return null;
    }

    private static Coord2d worldOf(Coord origin, Coord localTile) {
        return origin.add(localTile).mul(MCache.tilesz).add(MCache.tilehsz);
    }

    /**
     * Exact container position. The stored coord is the gob's offset inside its grid in
     * posres units, so it converts straight back to a world position once the grid origin
     * is known - no rounding to tile centres.
     */
    private static Coord2d containerWorld(Coord origin, String coord) {
        Coord off = parseCoord(coord);
        if (off == null)
            return null;
        Coord2d gridOrigin = new Coord2d(origin.x * MCache.tilesz.x, origin.y * MCache.tilesz.y);
        return gridOrigin.add(new Coord2d(off.x * posres.x, off.y * posres.y));
    }

    /** The stored posres offset expressed as a tile coord (0..99), for the planner. */
    private static Coord tileOf(String coord) {
        Coord off = parseCoord(coord);
        if (off == null)
            return null;
        Coord2d world = new Coord2d(off.x * posres.x, off.y * posres.y);
        Coord tile = world.floor(MCache.tilesz);
        if (tile.x < 0 || tile.y < 0 || tile.x >= MCache.cmaps.x || tile.y >= MCache.cmaps.y)
            return null;
        return tile;
    }

    /** Parse the "(x, y)" form ContainerWatcher writes. */
    private static Coord parseCoord(String coord) {
        if (coord == null)
            return null;
        try {
            String clean = coord.replace("(", "").replace(")", "").replace(" ", "");
            String[] parts = clean.split(",");
            if (parts.length != 2)
                return null;
            return new Coord((int) Double.parseDouble(parts[0]), (int) Double.parseDouble(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addPoint(List<Coord2d> pts, Coord2d p) {
        if (p == null)
            return;
        if (!pts.isEmpty() && pts.get(pts.size() - 1).dist(p) < MIN_POINT_SPACING)
            return;
        pts.add(p);
    }
}
