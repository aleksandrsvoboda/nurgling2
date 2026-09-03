package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
import haven.Line2d;
import haven.Loading;
import haven.MCache;
import haven.resutil.Ridges;

/**
 * Stateless "is there a cliff between these two points" check, used by {@link ForagerRouteConstraints}
 * to keep Forager from chasing a detour gob across a cliff edge - climbing is unreliable, so the fix
 * is to never attempt the crossing in the first place rather than fight a stuck-on-a-cliff bot after
 * the fact. Deliberately not a PathFinder/NPFMap change - neither has any "forbidden tile" concept
 * (confirmed absent), so this only ever gates which detour targets Forager considers, never how
 * PathFinder itself walks.
 */
public class CliffCorridorChecker {
    private CliffCorridorChecker() {}

    // Candidates can be up to SCAN_RADIUS (effectively unbounded) away, but the bot only ever
    // actually walks one MAX_HOP_DISTANCE-ish hop before findNearestActionableGob is called
    // again from a new (closer) position - so sampling a full straight-line corridor out to a
    // very distant candidate is mostly wasted work that gets redone, at higher resolution, from
    // closer range on every subsequent hop anyway. Capping the sampled distance bounds worst-case
    // cost (a sparse map with avoidCliffs on could otherwise trigger tens of thousands of
    // Ridges.brokenp lookups checking a single far-off candidate) without giving up any accuracy
    // for the stretch that's actually about to be walked.
    private static final int MAX_CORRIDOR_SAMPLE_TILES = 300;

    /**
     * True if a broken/cliff-edge ridge tile (see {@link Ridges#brokenp}) lies anywhere in the
     * first MAX_CORRIDOR_SAMPLE_TILES tiles of the corridor from {@code from} toward {@code to},
     * buffered by {@code bufferTiles} extra tiles of margin on each sampled tile. A sampled tile
     * whose grid isn't loaded yet ({@link Loading}) is treated as unknown, not as a confirmed
     * cliff - it's skipped rather than either blocking the candidate or crashing the scan.
     * <p>
     * Uses {@link Line2d.GridIsect} (the same tile-grid line-traversal Gob.java's own line-of-
     * sight height sampling already relies on) to visit exactly the tiles the line actually
     * crosses, once each, rather than a fixed-step sampling loop that can both double-sample and
     * skip tiles depending on step spacing versus tile alignment.
     */
    public static boolean corridorBlocked(MCache map, Coord2d from, Coord2d to, int bufferTiles) {
        if (map == null || from == null || to == null) return false;

        double dist = from.dist(to);
        if (dist < 0.01) {
            // GridIsect's own iterator divides by the line's direction vector - a from==to
            // (or near enough) corridor has none, so just check the one tile directly instead.
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
                // Midpoint of each crossing-to-crossing segment, not the crossing points
                // themselves (which sit exactly on a tile boundary and could floor() to either
                // neighbor) - guaranteed to land inside the one tile that segment crosses.
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
