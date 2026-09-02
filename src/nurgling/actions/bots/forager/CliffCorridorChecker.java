package nurgling.actions.bots.forager;

import haven.Coord;
import haven.Coord2d;
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
     * sampled once per tile crossed and buffered by {@code bufferTiles} extra tiles of margin on
     * each sample. A sampled tile whose grid isn't loaded yet ({@link Loading}) is treated as
     * unknown, not as a confirmed cliff - it's skipped rather than either blocking the candidate
     * or crashing the scan.
     */
    public static boolean corridorBlocked(MCache map, Coord2d from, Coord2d to, int bufferTiles) {
        if (map == null || from == null || to == null) return false;

        double dist = from.dist(to);
        double sampleDist = Math.min(dist, MAX_CORRIDOR_SAMPLE_TILES * MCache.tilesz.x);
        int steps = Math.max(1, (int) Math.ceil(sampleDist / MCache.tilesz.x));
        Coord2d dir = (dist > 0.01) ? to.sub(from).mul(sampleDist / dist) : Coord2d.z;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Coord2d p = from.add(dir.mul(t));
            Coord center = p.floor(MCache.tilesz);

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
        }
        return false;
    }
}
