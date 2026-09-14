package nurgling.actions.bots.forager;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.MiniMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The gathering stops still ahead on the route, so a detour can leave an item for a later stop that passes closer to it. */
public class RouteLookahead {
    // A stop ahead has to beat the anchor by this much (world units) before an item is left for it.
    private static final double MARGIN = 2 * MCache.tilesz.x;

    private final List<Coord2d> stopsAhead;
    private final Coord2d anchor;
    private final ForagerRouteConstraints constraints;

    // Anchor and stops are fixed for this instance's life, so each gob's answer is too - saves re-running corridor checks on every rescan.
    private final Map<Long, Boolean> decided = new HashMap<>();

    /** anchor: the route point the current walk or detour left from; null = never leave anything for later. */
    public RouteLookahead(List<Coord2d> stopsAhead, Coord2d anchor, ForagerRouteConstraints constraints) {
        this.stopsAhead = stopsAhead;
        this.anchor = anchor;
        this.constraints = constraints;
    }

    /** True if a stop ahead is closer to gob than the anchor by MARGIN and nothing static (exclusion zone, cliff, land in water mode) blocks the way from it - an item reachable now is never given up for a stop that can't reach it. */
    public boolean leaveForLaterStop(Gob gob, MCache map, MiniMap.Location sessloc, boolean waterMode) {
        if (anchor == null) return false;
        Boolean known = decided.get(gob.id);
        if (known != null) return known;

        Coord2d best = null;
        double bestDist = Double.MAX_VALUE;
        for (Coord2d stop : stopsAhead) {
            double d = stop.dist(gob.rc);
            if (d < bestDist) {
                bestDist = d;
                best = stop;
            }
        }
        boolean later = best != null
                && bestDist + MARGIN < anchor.dist(gob.rc)
                && !(map != null && constraints.cliffCorridorBlocked(map, best, gob.rc))
                && !(map != null && constraints.landCorridorBlocked(map, best, gob.rc, waterMode))
                && !constraints.corridorExcluded(sessloc, best, gob.rc);
        decided.put(gob.id, later);
        return later;
    }
}
