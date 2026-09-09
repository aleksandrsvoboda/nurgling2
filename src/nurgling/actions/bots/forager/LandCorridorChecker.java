package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
import haven.Line2d;
import haven.Loading;
import haven.MCache;
import nurgling.pf.NPFMap;

/** Stateless "is there a land tile between these two points" check, used by ForagerRouteConstraints
 *  to keep Forager - while in water mode, mounted on a coracle - from chasing a detour gob across
 *  land it can't actually cross. Same tile-sampling shape as CliffCorridorChecker, but checking
 *  water-tile membership (NPFMap.isValidWaterTileName, the same classification PathFinder/CoracleBot
 *  already use for water-mode reachability) instead of a cliff/ridge property. */
public class LandCorridorChecker {
    private LandCorridorChecker() {}

    // Caps worst-case cost for a very distant candidate - same rationale as CliffCorridorChecker.
    private static final int MAX_CORRIDOR_SAMPLE_TILES = 300;

    /** True if any tile within the first MAX_CORRIDOR_SAMPLE_TILES tiles from `from` toward `to` is
     *  land (not a valid water tile); an unloaded tile is skipped as unknown, not confirmed land. */
    public static boolean corridorBlocked(MCache map, Coord2d from, Coord2d to) {
        if (map == null || from == null || to == null) return false;

        double dist = from.dist(to);
        if (dist < 0.01) {
            // GridIsect's iterator divides by the direction vector - handle from==to directly instead.
            return isLand(map, from.floor(MCache.tilesz));
        }
        Coord2d cappedTo = to;
        double maxDist = MAX_CORRIDOR_SAMPLE_TILES * MCache.tilesz.x;
        if (dist > maxDist) {
            cappedTo = from.add(to.sub(from).mul(maxDist / dist));
        }

        Coord2d prev = null;
        for (Coord2d p : new Line2d.GridIsect(from, cappedTo, MCache.tilesz, true)) {
            if (prev != null) {
                // Midpoint of the segment, not the crossing points themselves (which sit on a tile boundary and could floor() to either neighbor).
                Coord tile = prev.add(p).div(2).floor(MCache.tilesz);
                if (isLand(map, tile)) {
                    return true;
                }
            }
            prev = p;
        }
        return false;
    }

    private static boolean isLand(MCache map, Coord tile) {
        try {
            String name = map.tilesetname(map.gettile(tile));
            return !NPFMap.isValidWaterTileName(name);
        } catch (Loading l) {
            // Unloaded tile - unknown, not confirmed land. Keep sampling rather than blocking on missing data.
            return false;
        }
    }
}
