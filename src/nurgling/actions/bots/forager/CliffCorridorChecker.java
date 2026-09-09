package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
import haven.Line2d;
import haven.Loading;
import haven.MCache;
import haven.resutil.Ridges;

/** Stateless "is there a cliff between these two points" check, used by ForagerRouteConstraints to keep Forager from chasing a detour gob across a cliff edge - not a PathFinder/NPFMap change since neither has a "forbidden tile" concept. */
public class CliffCorridorChecker {
    private CliffCorridorChecker() {}

    // Caps worst-case cost for a very distant candidate - closer hops resample at higher resolution anyway once the bot's actually near them.
    private static final int MAX_CORRIDOR_SAMPLE_TILES = 300;

    /** True if a broken/cliff-edge ridge tile (Ridges.brokenp) lies within the first MAX_CORRIDOR_SAMPLE_TILES tiles from `from` toward `to`, buffered by bufferTiles; an unloaded tile is skipped as unknown, not a confirmed cliff. */
    public static boolean corridorBlocked(MCache map, Coord2d from, Coord2d to, int bufferTiles) {
        if (map == null || from == null || to == null) return false;

        double dist = from.dist(to);
        if (dist < 0.01) {
            // GridIsect's iterator divides by the direction vector - handle from==to directly instead.
            return tileOrBufferBroken(map, from.floor(MCache.tilesz), bufferTiles);
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
                if (tileOrBufferBroken(map, tile, bufferTiles)) {
                    return true;
                }
            }
            prev = p;
        }
        return false;
    }

    private static boolean tileOrBufferBroken(MCache map, Coord center, int bufferTiles) {
        for (int dx = -bufferTiles; dx <= bufferTiles; dx++) {
            for (int dy = -bufferTiles; dy <= bufferTiles; dy++) {
                try {
                    if (Ridges.brokenp(map, center.add(dx, dy))) {
                        return true;
                    }
                } catch (Loading l) {
                    // Unloaded tile - unknown, not a confirmed cliff. Keep sampling.
                }
            }
        }
        return false;
    }
}
