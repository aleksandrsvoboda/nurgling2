package nurgling.colony;

import haven.MCache;
import nurgling.sessions.squad.WorldRef;

/** Distances between {@link WorldRef}s, which mean the same thing in every session. Pure arithmetic. */
public final class ColonyGeo {
    /** Tiles per map grid. */
    public static final int GRID_TILES = MCache.cmaps.x;

    private ColonyGeo() {}

    /**
     * Distance in tiles, or -1 when the two can't be compared: on different map segments (or no segment known), and
     * not in the same grid either.
     */
    public static double tiles(WorldRef a, WorldRef b) {
        if (a == null || b == null)
            return -1;
        if (a.segPos != null && b.segPos != null && a.segId == b.segId)
            return a.segPos.dist(b.segPos) / MCache.tilesz.x;
        if (a.gridId >= 0 && a.gridId == b.gridId && a.off != null && b.off != null)
            return a.off.dist(b.off) / MCache.tilesz.x;
        return -1;
    }

    /** Whether {@code r} is within {@code radiusGrids} map grids of {@code home}. */
    public static boolean near(WorldRef home, WorldRef r, int radiusGrids) {
        double d = tiles(home, r);
        return d >= 0 && d <= radiusGrids * GRID_TILES;
    }
}
