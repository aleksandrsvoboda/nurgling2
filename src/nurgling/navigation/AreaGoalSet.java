package nurgling.navigation;

import haven.Area;
import haven.Coord;

import java.util.*;

import static nurgling.navigation.ChunkNavConfig.CHUNK_SIZE;

/**
 * The tiles that count as "arrived" for a navigation goal, per chunk.
 *
 * For an area: every walkable tile inside it, plus every walkable tile in the one-tile ring around it - the tiles
 * from which something in the area can be reached. A search to this set stops at the first of these tiles it
 * reaches, which is the cheapest way in from whichever side. If none of them can be reached, nothing in the area
 * can be used, and the area counts as unreachable. The ring is worked out against the whole area, across grid
 * seams, so a seam between two of the area's grids is not mistaken for its edge.
 */
public final class AreaGoalSet {
    private static final int TILES = CHUNK_SIZE * CHUNK_SIZE;

    final Map<Long, BitSet> tiles = new HashMap<>();
    private int count = 0;

    private AreaGoalSet() {}

    /** Goal = the single tile (gridId, local). */
    public static AreaGoalSet ofTile(long gridId, Coord local) {
        AreaGoalSet set = new AreaGoalSet();
        if (local != null && local.x >= 0 && local.x < CHUNK_SIZE && local.y >= 0 && local.y < CHUNK_SIZE)
            set.add(gridId, local.x, local.y);
        return set;
    }

    /**
     * Goal for an area given as its per-grid pieces (local tile bounds, br exclusive). Grids that are not recorded
     * contribute nothing; an area none of whose grids are recorded gives an empty set.
     */
    public static AreaGoalSet ofArea(ChunkNavGraph graph, Map<Long, Area> pieces) {
        AreaGoalSet set = new AreaGoalSet();
        if (pieces == null) return set;
        for (Map.Entry<Long, Area> e : pieces.entrySet()) {
            Area a = e.getValue();
            ChunkNavData chunk = graph.getChunk(e.getKey());
            if (a == null || chunk == null) continue;
            int x0 = Math.max(0, a.ul.x), y0 = Math.max(0, a.ul.y);
            int x1 = Math.min(CHUNK_SIZE, a.br.x), y1 = Math.min(CHUNK_SIZE, a.br.y);
            for (int x = x0; x < x1; x++) {
                for (int y = y0; y < y1; y++) {
                    if (isTileWalkable(chunk, x, y))
                        set.add(chunk.gridId, x, y);
                    // The ring: only tiles on the piece's border can have outside neighbours.
                    if (x > x0 && x < x1 - 1 && y > y0 && y < y1 - 1) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            long[] t = resolve(graph, chunk, x + dx, y + dy);
                            if (t == null) continue;
                            long gid = t[0];
                            int tx = (int) t[1], ty = (int) t[2];
                            if (inside(pieces, gid, tx, ty)) continue;
                            ChunkNavData tc = graph.getChunk(gid);
                            if (tc != null && isTileWalkable(tc, tx, ty))
                                set.add(gid, tx, ty);
                        }
                    }
                }
            }
        }
        return set;
    }

    private static boolean inside(Map<Long, Area> pieces, long gridId, int x, int y) {
        Area a = pieces.get(gridId);
        return a != null && x >= a.ul.x && x < a.br.x && y >= a.ul.y && y < a.br.y;
    }

    /** (gridId, x, y) of a tile just outside a chunk, following its neighbour links; null if unknown. */
    static long[] resolve(ChunkNavGraph graph, ChunkNavData chunk, int x, int y) {
        if (x >= 0 && x < CHUNK_SIZE && y >= 0 && y < CHUNK_SIZE) return new long[] {chunk.gridId, x, y};
        long[] t = UnifiedTilePathfinder.crossTile(graph, chunk, x, y);
        if (t == null) return null;
        ChunkNavData end = graph.getChunk(t[0]);
        if (end == null || !ChunkLayout.walkable(chunk, end)) return null;
        return t;
    }

    private void add(long gridId, int x, int y) {
        BitSet b = tiles.computeIfAbsent(gridId, k -> new BitSet(TILES));
        int bit = y * CHUNK_SIZE + x;
        if (!b.get(bit)) {
            b.set(bit);
            count++;
        }
    }

    public boolean contains(long gridId, int x, int y) {
        BitSet b = tiles.get(gridId);
        return b != null && b.get(y * CHUNK_SIZE + x);
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }

    public Set<Long> chunks() {
        return tiles.keySet();
    }

    static boolean isTileWalkable(ChunkNavData chunk, int tileX, int tileY) {
        int cx = tileX * ChunkNavConfig.CELLS_PER_TILE, cy = tileY * ChunkNavConfig.CELLS_PER_TILE;
        byte[][] w = chunk.walkability;
        return w[cx][cy] == 0 || w[cx + 1][cy] == 0 || w[cx][cy + 1] == 0 || w[cx + 1][cy + 1] == 0;
    }
}
