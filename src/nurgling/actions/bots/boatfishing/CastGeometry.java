package nurgling.actions.bots.boatfishing;

import haven.Coord2d;
import haven.MCache;

/** Where to cast and where to row to. No game state; unit tested. */
final class CastGeometry {
    /** How far a cast reaches from the boat: about 5 tiles. */
    static final double CAST_RANGE = 5 * MCache.tilesz.x;
    /** Rowing stops this far from the aim point, leaving a margin inside the cast range. */
    static final double ROW_STOP = CAST_RANGE - MCache.tilesz.x;

    private CastGeometry() {}

    /** The area's middle when it is in reach, otherwise the area's point nearest to {@code from}. */
    static Coord2d aim(Coord2d from, Coord2d a, Coord2d b) {
        Coord2d min = Coord2d.of(Math.min(a.x, b.x), Math.min(a.y, b.y));
        Coord2d max = Coord2d.of(Math.max(a.x, b.x), Math.max(a.y, b.y));
        Coord2d mid = min.add(max).div(2);
        if (from.dist(mid) <= CAST_RANGE)
            return mid;
        return Coord2d.of(clamp(from.x, min.x, max.x), clamp(from.y, min.y, max.y));
    }

    static boolean inRange(Coord2d from, Coord2d target) {
        return from.dist(target) <= CAST_RANGE;
    }

    /** The point on the line from {@code from} to {@code target} that is {@link #ROW_STOP} short of the target. */
    static Coord2d rowTo(Coord2d from, Coord2d target) {
        double d = from.dist(target);
        if (d <= ROW_STOP)
            return from;
        return from.add(target.sub(from).mul((d - ROW_STOP) / d));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
