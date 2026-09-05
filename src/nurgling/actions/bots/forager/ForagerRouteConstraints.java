package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Line2d;
import haven.MCache;
import haven.MiniMap;
import nurgling.routes.ForagerPath;

/** Per-route geometry limits (Forager Settings > Routes), consulted by Forager's detour/chase logic. */
public class ForagerRouteConstraints {
    private final ForagerPath path;
    private final int maxDistanceTiles;
    private final int maxBranches;
    private final int maxBranchDistanceTiles;
    private final boolean avoidCliffs;
    private final int cliffBufferTiles;

    // Caps worst-case corridor-check cost for a very distant candidate, matching CliffCorridorChecker's own cap.
    private static final int MAX_EXCLUSION_CORRIDOR_SAMPLE_TILES = 300;

    public ForagerRouteConstraints(ForagerPath path) {
        this.path = path;
        this.maxDistanceTiles = path.maxDistance;
        this.maxBranches = path.maxBranches;
        this.maxBranchDistanceTiles = path.maxBranchDistance;
        this.avoidCliffs = path.avoidCliffs;
        this.cliffBufferTiles = path.cliffBufferTiles;
    }

    /** True if this gob's tile is inside a brush-painted exclusion zone. */
    public boolean isGobExcluded(MiniMap.Location sessloc, Gob gob) {
        if (sessloc == null || gob == null) return false;
        return isExcludedAt(sessloc, gob.rc);
    }

    /** True if any tile in the straight-line corridor from `from` to `to` is inside a brush-painted
     *  exclusion zone - so a candidate on the far side of a zone (but not itself excluded) still
     *  gets rejected, the same corridor-check treatment cliffCorridorBlocked already gets. */
    public boolean corridorExcluded(MiniMap.Location sessloc, Coord2d from, Coord2d to) {
        if (sessloc == null || from == null || to == null) return false;

        double dist = from.dist(to);
        if (dist < 0.01) {
            return isExcludedAt(sessloc, from);
        }
        Coord2d cappedTo = to;
        double maxDist = MAX_EXCLUSION_CORRIDOR_SAMPLE_TILES * MCache.tilesz.x;
        if (dist > maxDist) {
            cappedTo = from.add(to.sub(from).mul(maxDist / dist));
        }

        Coord2d prev = null;
        for (Coord2d p : new Line2d.GridIsect(from, cappedTo, MCache.tilesz, true)) {
            if (prev != null && isExcludedAt(sessloc, prev.add(p).div(2))) {
                return true;
            }
            prev = p;
        }
        return false;
    }

    private boolean isExcludedAt(MiniMap.Location sessloc, Coord2d worldPos) {
        Coord tc = worldPos.floor(MCache.tilesz).add(sessloc.tc);
        return path.isExcluded(sessloc.seg.id, tc);
    }

    /** True if maxDistance is uncapped, or candidate is within maxDistance tiles of anchor (held fixed per episode). */
    public boolean withinLeash(Coord2d anchor, Coord2d candidate) {
        if (maxDistanceTiles < 0 || anchor == null || candidate == null) return true;
        return anchor.dist(candidate) <= maxDistanceTiles * MCache.tilesz.x;
    }

    /** True (no constraint) unless avoidCliffs is on for this route. */
    public boolean cliffCorridorBlocked(MCache map, Coord2d from, Coord2d to) {
        if (!avoidCliffs) return false;
        return CliffCorridorChecker.corridorBlocked(map, from, to, cliffBufferTiles);
    }

    /** -1 = unlimited hops per detour episode. */
    public int maxBranches() {
        return maxBranches;
    }

    /** -1 = unlimited cumulative distance per detour episode, in tiles. */
    public int maxBranchDistanceTiles() {
        return maxBranchDistanceTiles;
    }
}
