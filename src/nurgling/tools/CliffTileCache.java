package nurgling.tools;

import haven.Coord;
import haven.Loading;
import haven.Locked;
import haven.MCache;
import haven.MapFile;
import haven.resutil.Ridges;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cache of cliff (Ridges.brokenp) data, keyed in persisted-MapFile segment/tile space - the same
 * addressing ForagerWaypoint/ForagerPath.exclusionTiles already use.
 * <p>
 * Reads entirely from the PERSISTED MapFile (via {@link MapFile.View}, a MapSource backed by
 * on-disk Grid data - the exact same tile-type/elevation data already used to render the minimap
 * terrain itself, see MiniMap.DisplayGrid.img()) rather than live MCache. This means a segment's
 * cliffs can be computed for its ENTIRE explored history - including areas explored in previous
 * sessions - the moment its persisted grid data is available, with no need for the player to be
 * anywhere near it right now. {@link #scanSegment} is meant to be called periodically (see
 * ForagerRouteMap's throttled tick) for whichever segment is currently relevant, not gated on
 * live proximity at all.
 * <p>
 * Deliberately keeps "confirmed safe" ({@link #safeForSegment}) as its own, separate set rather
 * than just "not a known cliff" - a tile that's simply never been explored/persisted must NOT
 * read the same as a tile that's been scanned and found clear, since a bot should treat
 * unexplored ground with the same caution as a known hazard until something has actually looked
 * at it.
 * <p>
 * Deliberately UI-thread-only (plain HashMap/HashSet, no synchronization beyond MapFile's own
 * lock) - every existing Ridges.brokenp/MapFile call site in this codebase already assumes the
 * caller is on the render/UI thread; this doesn't introduce a new threading model.
 */
public class CliffTileCache {
    /** Chebyshev radius around a detected cliff tile treated as unsafe. */
    public static final int CLIFF_RADIUS = 5;

    // Capped per scanSegment() call so a segment with a large backlog of never-before-scanned
    // grids (e.g. the first time this feature is used after a lot of prior exploration) spreads
    // its one-time cost across several throttled calls instead of stalling a single frame.
    private static final int MAX_NEW_GRIDS_PER_SCAN = 4;

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

    /** Segment-space tiles confirmed (via a persisted-data scan) to be more than CLIFF_RADIUS
     *  tiles from any known cliff - i.e. safe to forage. Anything NOT in this set is either a
     *  cliff/its buffer, or simply unexplored/not yet scanned - see the class javadoc for why
     *  those two cases are deliberately not distinguished here. Never null, possibly empty; a
     *  live view, not a copy. */
    public static Set<Coord> safeForSegment(long seg) {
        Set<Coord> tiles = safeTiles.get(seg);
        return tiles != null ? tiles : Collections.emptySet();
    }

    /** Bumped every time new tiles are recorded for a segment - lets callers cheaply tell whether
     *  their own cached rendering/derived data is stale without deep-comparing the tile sets. */
    public static int getVersion(long seg) {
        return version.getOrDefault(seg, 0);
    }

    /** Scans up to MAX_NEW_GRIDS_PER_SCAN not-yet-processed grids of the given segment, straight
     *  from the persisted MapFile - no live MCache/player proximity involved. Cheap once nothing
     *  new remains (a grid-count check under the lock). Safe to call every throttled tick; if any
     *  of the segment's grid data isn't in memory yet, this simply does nothing this call and
     *  retries next time (Loading), rather than partially applying a half-loaded scan. */
    public static void scanSegment(MapFile file, long segId) {
        if (file == null) return;
        try (Locked lk = new Locked(file.lock.readLock())) {
            MapFile.Segment seg = file.segments.get(segId);
            if (seg == null) {
                System.err.println("CliffTileCache: no persisted Segment for id " + Long.toUnsignedString(segId, 16));
                return;
            }

            Map<Coord, Long> gridCoords = new HashMap<>(seg.map);
            List<Coord> pending = new ArrayList<>();
            for (Map.Entry<Coord, Long> e : gridCoords.entrySet()) {
                if (!scannedGridIds.contains(e.getValue())) pending.add(e.getKey());
            }
            System.err.println("CliffTileCache: seg " + Long.toUnsignedString(segId, 16) + " has " + gridCoords.size()
                    + " known grids, " + pending.size() + " pending scan");
            if (pending.isEmpty()) return;

            // The view must include every known grid, not just the pending ones - Ridges.brokenp
            // reads neighboring tiles at grid boundaries, which can land in an already-scanned
            // neighbor; leaving it out of the view would misread those edge tiles as unmapped.
            MapFile.View view = new MapFile.View(seg);
            for (Coord gc : gridCoords.keySet()) view.addgrid(gc);
            view.fin();

            int n = Math.min(MAX_NEW_GRIDS_PER_SCAN, pending.size());
            boolean any = false;
            for (int i = 0; i < n; i++) {
                Coord gc = pending.get(i);
                scanGrid(view, segId, gc);
                scannedGridIds.add(gridCoords.get(gc));
                any = true;
            }
            if (any) {
                version.merge(segId, 1, Integer::sum);
                System.err.println("CliffTileCache: scanned " + n + " grid(s) for seg " + Long.toUnsignedString(segId, 16)
                        + " - now " + forSegment(segId).size() + " cliff tile(s), " + safeForSegment(segId).size() + " safe tile(s)");
            }
        } catch (Loading l) {
            System.err.println("CliffTileCache: Loading during scan of seg " + Long.toUnsignedString(segId, 16) + " - " + l.getMessage());
        }
    }

    private static void scanGrid(MapFile.View view, long segId, Coord gc) {
        Coord origin = gc.mul(MCache.cmaps); // grid's own UL tile, already in segment-tile space

        Set<Coord> localCliffs = new HashSet<>(); // grid-local offsets 0..cmaps-1
        for (int y = 0; y < MCache.cmaps.y; y++) {
            for (int x = 0; x < MCache.cmaps.x; x++) {
                Coord tc = origin.add(x, y);
                try {
                    if (Ridges.brokenp(view, tc)) {
                        localCliffs.add(new Coord(x, y));
                    }
                } catch (Loading e) {
                    // A neighboring tileset resource isn't loaded yet - skip just this tile; this
                    // grid stays marked scanned regardless, an acceptable rare partial miss rather
                    // than added complexity for a corner case.
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

        if (!localCliffs.isEmpty()) {
            Set<Coord> segCliffs = cliffTiles.computeIfAbsent(segId, k -> new HashSet<>());
            for (Coord c : localCliffs) segCliffs.add(origin.add(c));
        }
        Set<Coord> segSafe = safeTiles.computeIfAbsent(segId, k -> new HashSet<>());
        for (Coord c : localSafe) segSafe.add(origin.add(c));
    }

    private static Set<Coord> fullGrid() {
        Set<Coord> all = new HashSet<>();
        for (int y = 0; y < MCache.cmaps.y; y++)
            for (int x = 0; x < MCache.cmaps.x; x++)
                all.add(new Coord(x, y));
        return all;
    }
}
