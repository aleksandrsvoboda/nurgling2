package nurgling.actions.bots.forager;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.MiniMap;
import nurgling.routes.ForagerPath;

/**
 * The route-geometry limits a player configures per-route in Forager Settings (Routes section) -
 * previously captured data with no bot logic reading any of it. Built once per run from the
 * running preset's ForagerPath, then consulted by Forager.findNearestActionableGob (the single
 * funnel every detour-target selection already goes through) to decide whether a candidate gob is
 * even eligible to be chased, and by the detour-chaining loops to decide when to stop chaining and
 * head back to the route. Mirrors, in spirit, how nurgling.guarding keeps "is this allowed"
 * predicates as small composable objects separate from the control flow that acts on them - a
 * different concern (route geometry vs. bot safety), same idea.
 */
public class ForagerRouteConstraints {
    private final ForagerPath path;
    private final int maxDistanceTiles;
    private final int maxChains;
    private final int maxChainDistanceTiles;
    private final boolean avoidCliffs;
    private final int cliffBufferTiles;

    public ForagerRouteConstraints(ForagerPath path) {
        this.path = path;
        this.maxDistanceTiles = path.maxDistance;
        this.maxChains = path.maxChains;
        this.maxChainDistanceTiles = path.maxChainDistance;
        this.avoidCliffs = path.avoidCliffs;
        this.cliffBufferTiles = path.cliffBufferTiles;
    }

    /**
     * True if this gob's tile is inside a brush-painted exclusion zone. gob.rc is a live,
     * session-relative world coordinate; exclusionTiles (like ForagerWaypoint.tc/MiniMap.Location.tc)
     * is keyed by absolute segment-tile coordinates, so the session's own sessloc.tc offset must be
     * added back in to convert between the two - same conversion this codebase already does at
     * every other live-gob-to-segment-tile site (e.g. NGob.java's tempMarkList: "gob.rc.floor(tilesz)
     * .add(gui.mmap.sessloc.tc)").
     */
    public boolean isGobExcluded(MiniMap.Location sessloc, Gob gob) {
        if (sessloc == null || gob == null) return false;
        haven.Coord tc = gob.rc.floor(MCache.tilesz).add(sessloc.tc);
        return path.isExcluded(sessloc.seg.id, tc);
    }

    /**
     * True (no constraint) if maxDistance is uncapped or anchor is unavailable; otherwise true
     * only while candidate is within maxDistance tiles of anchor. anchor must be held fixed for
     * the duration of one chained detour episode by the caller - re-deriving it from the bot's
     * current (already-drifted) position every hop would make this never fire.
     */
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
    public int maxChains() {
        return maxChains;
    }

    /** -1 = unlimited cumulative distance per detour episode, in tiles. */
    public int maxChainDistanceTiles() {
        return maxChainDistanceTiles;
    }
}
