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
 * Session-wide cache of which tiles are cliffs (Ridges.brokenp), keyed in persisted-MapFile
 * segment/tile space - the same addressing ForagerWaypoint/ForagerPath.exclusionTiles already use
 * - rather than live MCache grid space, so a segment's cliff tiles stay known for the rest of the
 * session even after the live grid that produced them gets evicted as the player walks away.
 * <p>
 * Populated incrementally by {@link #scanNewGrids}, meant to be called periodically (see
 * ForagerRouteMap's throttled tick) rather than gated on any particular UI being open or panned
 * to a specific spot - this is what lets the Routes map editor's Cliff exclusion toggle reflect
 * "every cliff seen so far" instead of only whatever the tiny editor's own current viewport
 * happens to be showing. A grid can only be scanned once a GridInfo exists for it (i.e. once the
 * normal minimap system has recorded where it sits in the persisted map) - the same constraint
 * the visual cliff highlight already had. Cliffs from a segment the player hasn't been near this
 * session are simply unknown; there's no way around that, Ridges.brokenp needs live grid data.
 * <p>
 * Deliberately UI-thread-only (plain HashMap/HashSet, no synchronization beyond MapFile's own
 * lock) - every existing Ridges.brokenp/MapFile.gridinfo call site in this codebase already
 * assumes the caller is on the render/UI thread; this doesn't introduce a new threading model.
 */
public class CliffTileCache {
    private static final Map<Long, Set<Coord>> bySegment = new HashMap<>();
    private static final Set<Long> scannedGridIds = new HashSet<>();

    private CliffTileCache() {}

    /** Segment-space cliff tiles known so far for the given segment - never null, possibly
     *  empty. A live view, not a copy - callers must not mutate it. */
    public static Set<Coord> forSegment(long seg) {
        Set<Coord> tiles = bySegment.get(seg);
        return tiles != null ? tiles : Collections.emptySet();
    }

    /** Every segment with at least one known cliff tile so far. */
    public static Set<Long> knownSegments() {
        return bySegment.keySet();
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

        // Segment-tile coordinate of this grid's own origin tile - same formula
        // MiniMap.SessionLocator.locate() uses to place a live grid within the persisted segment.
        Coord segOrigin = info.sc.sub(grid.gc).mul(MCache.cmaps);
        Set<Coord> tiles = bySegment.computeIfAbsent(seg.id, k -> new HashSet<>());
        for (int y = 0; y < MCache.cmaps.y; y++) {
            for (int x = 0; x < MCache.cmaps.x; x++) {
                Coord absTile = grid.ul.add(x, y);
                try {
                    if (Ridges.brokenp(mcache, absTile)) {
                        tiles.add(segOrigin.add(x, y));
                    }
                } catch (Loading e) {
                    // A tile inside a grid we already hold shouldn't normally still be loading -
                    // skip it; scannedGridIds is permanent so this grid won't be retried, an
                    // acceptable rare partial miss rather than added complexity for a corner case.
                }
            }
        }
        scannedGridIds.add(grid.id);
    }
}
