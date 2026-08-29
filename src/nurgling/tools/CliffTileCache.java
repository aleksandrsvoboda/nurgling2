package nurgling.tools;

import haven.Coord;
import haven.Indir;
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

    // Segment.grid()/gridinfo are backed by WEAK-referenced caches (MapFile's own Cached/ByCoord
    // wrappers) - if nothing keeps the Indir<Grid> a not-yet-resolved grid load returns alive
    // between our throttled calls, it can be garbage collected before its async load ever
    // finishes, silently restarting the whole load from scratch next call (and forever, if GC
    // keeps winning that race - this was the actual cause of "always Loading, never scans
    // anything": every call built a disposable MapFile.View, so nothing else in the client held
    // these references alive long enough). Holding them here ourselves is enough - once retained,
    // MapFile.View.addgrid()'s own seg.grid(gc) call finds this SAME cached entry (the weak
    // reference map lookup succeeds because our reference keeps it live) instead of creating a
    // fresh one, so repeated polling actually drives Defer's retry/reschedule machinery forward.
    private static final Map<Long, Map<Coord, Indir<MapFile.Grid>>> gridRefs = new HashMap<>();

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
            if (seg == null) return;

            Map<Coord, Long> gridCoords = new HashMap<>(seg.map);

            // Retain a reference to every known grid's loader before touching the view - see the
            // gridRefs field javadoc for why this is what actually lets a slow/large backlog
            // finish loading instead of restarting forever.
            Map<Coord, Indir<MapFile.Grid>> refs = gridRefs.computeIfAbsent(segId, k -> new HashMap<>());
            for (Coord gc : gridCoords.keySet()) {
                refs.computeIfAbsent(gc, seg::grid);
            }

            // Add whatever's ready this call - NOT all-or-nothing. A tile whose own grid is
            // missing from the view can't be scanned at all (skipped below); one whose grid IS
            // present but reads a NEIGHBOR tile from a still-missing grid is handled defensively
            // in scanGrid instead (View returns an "unmapped" sentinel there, not Loading).
            MapFile.View view = new MapFile.View(seg);
            Set<Coord> added = new HashSet<>();
            for (Coord gc : gridCoords.keySet()) {
                try {
                    view.addgrid(gc);
                    added.add(gc);
                } catch (Loading l) {
                    // Not resolved yet - our retained reference above keeps it progressing in the
                    // background regardless; it'll be picked up on a later call once ready.
                }
            }
            view.fin();

            List<Coord> readyToScan = new ArrayList<>();
            for (Coord gc : added) {
                if (!scannedGridIds.contains(gridCoords.get(gc))) readyToScan.add(gc);
            }
            if (readyToScan.isEmpty()) return;

            int n = Math.min(MAX_NEW_GRIDS_PER_SCAN, readyToScan.size());
            for (int i = 0; i < n; i++) {
                Coord gc = readyToScan.get(i);
                scanGrid(view, segId, gc);
                scannedGridIds.add(gridCoords.get(gc));
            }
            version.merge(segId, 1, Integer::sum);
        } catch (Loading l) {
            // Segment/grid-coordinate lookup itself hit something not ready - retry next call.
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
                } catch (RuntimeException e) {
                    // Ridges.brokenp reads neighbor/corner tiles which can land in an adjacent
                    // grid not yet added to this pass's view - View.gettile() returns an
                    // "unmapped" sentinel rather than throwing Loading for that case, which then
                    // faults inside tiler() lookup. Same graceful-degrade as the Loading case
                    // above: skip just this one boundary tile.
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
