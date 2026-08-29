package nurgling.tools;

import haven.Coord;
import haven.Loading;
import haven.Locked;
import haven.MCache;
import haven.MapFile;
import haven.resutil.Ridges;
import nurgling.NGameUI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Session-wide cache of cliff (Ridges.brokenp) data, keyed in persisted-MapFile segment/tile
 * space - the same addressing ForagerWaypoint/ForagerPath.exclusionTiles already use - rather
 * than live MCache grid space, so a segment's data stays known for the rest of the session even
 * after the live grid that produced it gets evicted as the player walks away.
 * <p>
 * Populated incrementally by {@link #scanNewGrids}, meant to be called periodically (see
 * ForagerRouteMap's throttled tick) rather than gated on any particular UI being open or panned
 * to a specific spot - this is what lets the Routes map editor's Cliff exclusion toggle reflect
 * "every cliff seen so far" instead of only whatever the tiny editor's own current viewport
 * happens to be showing. A grid can only be scanned once a GridInfo exists for it (i.e. once the
 * normal minimap system has recorded where it sits in the persisted map). Cliffs (and safe tiles)
 * from a segment the player hasn't been near this session are simply unknown; there's no way
 * around that, Ridges.brokenp needs live grid data.
 * <p>
 * Deliberately keeps "confirmed safe" ({@link #safeForSegment}) as its own, separate set rather
 * than just "not a known cliff" - a tile that's simply never been scanned must NOT read the same
 * as a tile that's been scanned and found clear, since a bot should treat unexplored ground with
 * the same caution as a known hazard until something has actually looked at it.
 * <p>
 * Deliberately UI-thread-only (plain HashMap/HashSet, no synchronization beyond MapFile's own
 * lock) - every existing Ridges.brokenp/MapFile.gridinfo call site in this codebase already
 * assumes the caller is on the render/UI thread; this doesn't introduce a new threading model.
 */
public class CliffTileCache {
    /** Chebyshev radius around a detected cliff tile treated as unsafe. */
    public static final int CLIFF_RADIUS = 5;

    private static final Map<Long, Set<Coord>> cliffTiles = new HashMap<>();
    private static final Map<Long, Set<Coord>> safeTiles = new HashMap<>();
    private static final Map<Long, Integer> version = new HashMap<>();
    private static final Set<Long> scannedGridIds = new HashSet<>();

    private CliffTileCache() {}

    /** Segment-space tiles confirmed to be cliffs so far - never null, possibly empty. A live
     *  view, not a copy - callers must not mutate it. */
    public static Set<Coord> forSegment(long seg) {
        Set<Coord> tiles = cliffTiles.get(seg);
        return tiles != null ? tiles : Collections.emptySet();
    }

    /** Segment-space tiles confirmed (via a live scan) to be more than CLIFF_RADIUS tiles from
     *  any known cliff - i.e. safe to forage. Anything NOT in this set is either a cliff/its
     *  buffer, or simply unexplored - see the class javadoc for why those two cases are
     *  deliberately not distinguished here. Never null, possibly empty; a live view, not a copy. */
    public static Set<Coord> safeForSegment(long seg) {
        Set<Coord> tiles = safeTiles.get(seg);
        return tiles != null ? tiles : Collections.emptySet();
    }

    /** Bumped every time new tiles are recorded for a segment - lets callers cheaply tell whether
     *  their own cached rendering/derived data is stale without deep-comparing the tile sets. */
    public static int getVersion(long seg) {
        return version.getOrDefault(seg, 0);
    }

    /** Scans any currently-loaded MCache grid not yet processed. Cheap once nothing new has
     *  loaded (just an id-set membership check per live grid). Must run on the UI thread, same
     *  as every other Ridges.brokenp/MapFile.gridinfo caller. */
    public static void scanNewGrids(NGameUI gui, MapFile file) {
        if (gui == null || gui.map == null || gui.map.glob == null || file == null) return;
        MCache mcache = gui.map.glob.map;
        Collection<MCache.Grid> grids;
        synchronized (mcache.grids) {
            grids = new ArrayList<>(mcache.grids.values());
        }
        for (MCache.Grid grid : grids) {
            if (scannedGridIds.contains(grid.id)) continue;
            scanGrid(mcache, file, grid);
        }
    }

    private static void scanGrid(MCache mcache, MapFile file, MCache.Grid grid) {
        MapFile.GridInfo info;
        MapFile.Segment seg;
        // gridinfo/segments are backed by disk IO and asserted (via checklock()) to only be
        // touched while holding this - same pattern MiniMap itself already uses.
        try (Locked lk = new Locked(file.lock.readLock())) {
            info = file.gridinfo.get(grid.id);
            if (info == null) return; // not yet mapped into the persisted file - retry later
            seg = file.segments.get(info.seg);
            if (seg == null) return;
        }

        // Grid-local (0..cmaps-1) cliff tiles first, so the unsafe-buffer expansion below only
        // needs this one grid's own cliffs - a tile just past the edge of an as-yet-unscanned
        // neighboring grid is an acceptable, small (<=CLIFF_RADIUS tile) imprecision, not worth
        // re-touching already-scanned neighbors every time a new one arrives.
        Set<Coord> localCliffs = new HashSet<>();
        for (int y = 0; y < MCache.cmaps.y; y++) {
            for (int x = 0; x < MCache.cmaps.x; x++) {
                Coord absTile = grid.ul.add(x, y);
                try {
                    if (Ridges.brokenp(mcache, absTile)) {
                        localCliffs.add(new Coord(x, y));
                    }
                } catch (Loading e) {
                    // A tile inside a grid we already hold shouldn't normally still be loading -
                    // skip it; scannedGridIds is permanent so this grid won't be retried, an
                    // acceptable rare partial miss rather than added complexity for a corner case.
                }
            }
        }

        Set<Coord> localSafe;
        if (localCliffs.isEmpty()) {
            localSafe = fullGrid();
        } else {
            Set<Coord> localUnsafe = new HashSet<>();
            for (Coord c : localCliffs) {
                for (int dy = -CLIFF_RADIUS; dy <= CLIFF_RADIUS; dy++) {
                    for (int dx = -CLIFF_RADIUS; dx <= CLIFF_RADIUS; dx++) {
                        localUnsafe.add(c.add(dx, dy));
                    }
                }
            }
            localSafe = new HashSet<>();
            for (int y = 0; y < MCache.cmaps.y; y++) {
                for (int x = 0; x < MCache.cmaps.x; x++) {
                    Coord local = new Coord(x, y);
                    if (!localUnsafe.contains(local)) {
                        localSafe.add(local);
                    }
                }
            }
        }

        // Segment-tile coordinate of this grid's own origin tile - same formula
        // MiniMap.SessionLocator.locate() uses to place a live grid within the persisted segment.
        Coord segOrigin = info.sc.sub(grid.gc).mul(MCache.cmaps);
        if (!localCliffs.isEmpty()) {
            Set<Coord> segCliffs = cliffTiles.computeIfAbsent(seg.id, k -> new HashSet<>());
            for (Coord c : localCliffs) segCliffs.add(segOrigin.add(c));
        }
        Set<Coord> segSafe = safeTiles.computeIfAbsent(seg.id, k -> new HashSet<>());
        for (Coord c : localSafe) segSafe.add(segOrigin.add(c));

        version.merge(seg.id, 1, Integer::sum);
        scannedGridIds.add(grid.id);
    }

    private static Set<Coord> fullGrid() {
        Set<Coord> all = new HashSet<>();
        for (int y = 0; y < MCache.cmaps.y; y++)
            for (int x = 0; x < MCache.cmaps.x; x++)
                all.add(new Coord(x, y));
        return all;
    }
}
