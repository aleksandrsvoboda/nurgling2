package nurgling.render;

import haven.Coord;
import haven.MCache;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Per-view environment detection from loaded terrain, including paved indoor floors. */
public final class InteriorTiles {
    private static final class Result {
        final int seq;
        final boolean inside;
        Result(int seq, boolean inside) { this.seq = seq; this.inside = inside; }
    }
    private final Map<MCache.Grid, Result> cached = new WeakHashMap<>();

    public static boolean interior(String name) {
        return "gfx/tiles/mine".equals(name) || "gfx/tiles/cave".equals(name) ||
               "gfx/tiles/nil".equals(name) || "gfx/tiles/deepcave".equals(name) ||
               "gfx/tiles/deeptangle".equals(name);
    }

    private Boolean scan(MCache map, MCache.Grid grid) {
        Result previous = cached.get(grid);
        if(previous != null && previous.seq == grid.seq) return previous.inside;
        Set<Integer> seen = new HashSet<>();
        boolean complete = true;
        for(int tile : grid.tiles) {
            if(!seen.add(tile)) continue;
            String name = map.tilesetname(tile);
            if(name == null) { complete = false; continue; }
            if(interior(name)) {
                cached.put(grid, new Result(grid.seq, true));
                return true;
            }
        }
        if(!complete) return null; // Retry when the tile resource becomes available.
        cached.put(grid, new Result(grid.seq, false));
        return false;
    }

    /** null means the current terrain is not ready. Never requests or loads remote grids. */
    public synchronized Boolean inside(MCache map, Coord tile) {
        Coord gc = tile.div(MCache.cmaps);
        synchronized(map.grids) {
            MCache.Grid center = map.grids.get(gc);
            if(center == null || center.removed || center.seq < 0) return null;
            Boolean result = scan(map, center);
            if(Boolean.TRUE.equals(result)) return true;
            // Nearby void/walls identify interiors even when the current grid is fully paved.
            for(int y = -1; y <= 1; y++) for(int x = -1; x <= 1; x++) {
                if(x == 0 && y == 0) continue;
                MCache.Grid neighbor = map.grids.get(gc.add(x, y));
                if(neighbor != null && !neighbor.removed && neighbor.seq >= 0 &&
                   Boolean.TRUE.equals(scan(map, neighbor))) return true;
            }
            return result;
        }
    }
}
