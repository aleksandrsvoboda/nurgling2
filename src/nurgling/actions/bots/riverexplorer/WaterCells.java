package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Coord2d;
import nurgling.actions.bots.worldexplorer.SeaGrid;

import java.util.Collection;
import java.util.function.Predicate;

/** River Explorer's picture of the water over a window of tiles, on a lattice of points every half tile (5.5 units): on every
 *  tile's corners, edge middles and centre. "Cell" (cx, cy) is the point at (2 * ul + (cx, cy)) * 5.5. For each point: whether
 *  its tile is fresh water, sea water or a wall, and how far it is from the nearest wall tile. A hull fits a point when that
 *  distance is at least its fit radius (Hull.fit). Points on tile centres and tile edges are what make the narrowest channels
 *  come out right: a coracle or dugout fits 1 tile (centre: 5.5 from both banks), a rowboat 2, a snekkja 3, a knarr 4.
 *  Pure, built from a tile lookup, so it is tested on hand-made maps. */
public final class WaterCells {
    public static final double TILE = 11, CELL = TILE / 2;
    static final byte WALL = 0, FRESH = 1, SEA = 2;

    /** Window: tiles [ul, ul + (wt, ht)). */
    public final Coord ul;
    public final int wt, ht;
    /** Cells: 2 per tile each way. */
    public final int w, h;
    private final byte[] kind;
    private final float[] clear;
    private final float maxClear;

    private WaterCells(Coord ul, int wt, int ht, float maxClear) {
        this.ul = ul;
        this.wt = wt;
        this.ht = ht;
        this.w = 2 * wt;
        this.h = 2 * ht;
        this.kind = new byte[wt * ht];
        this.clear = new float[w * h];
        this.maxClear = maxClear;
    }

    /**
     * @param tiles    tileset lookup, null where unknown (a wall); it is also asked about tiles up to maxClear outside the window
     * @param fresh    fresh water (rivers, lakes)
     * @param sea      sea water, or null when the sea isn't used (then it is a wall)
     * @param walls    extra wall tiles: under solid gobs, reeds when they count
     * @param maxClear distances are measured up to this many units; farther is reported as maxClear
     */
    public static WaterCells build(SeaGrid.Tiles tiles, Coord ul, int wt, int ht, Predicate<String> fresh, Predicate<String> sea,
                                   Collection<Coord> walls, double maxClear) {
        WaterCells c = new WaterCells(ul, wt, ht, (float) maxClear);
        int m = (int) Math.ceil(maxClear / TILE);
        int pw = wt + 2 * m, ph = ht + 2 * m;
        // Wall flags over the window padded by m tiles each way.
        boolean[] wall = new boolean[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                Coord tc = Coord.of(ul.x - m + x, ul.y - m + y);
                byte k = kindOf(tiles.name(tc), fresh, sea);
                wall[y * pw + x] = (k == WALL);
                int ix = x - m, iy = y - m;
                if (ix >= 0 && iy >= 0 && ix < wt && iy < ht)
                    c.kind[iy * wt + ix] = k;
            }
        }
        if (walls != null) {
            for (Coord tc : walls) {
                int x = tc.x - ul.x + m, y = tc.y - ul.y + m;
                if (x < 0 || y < 0 || x >= pw || y >= ph) continue;
                wall[y * pw + x] = true;
                int ix = x - m, iy = y - m;
                if (ix >= 0 && iy >= 0 && ix < wt && iy < ht)
                    c.kind[iy * wt + ix] = WALL;
            }
        }
        // Each cell's distance to the nearest wall tile within m tiles (point-to-square, in units).
        double cap2 = maxClear * maxClear;
        for (int cy = 0; cy < c.h; cy++) {
            for (int cx = 0; cx < c.w; cx++) {
                int i = cy * c.w + cx;
                int tx = cx >> 1, ty = cy >> 1;
                if (c.kind[ty * wt + tx] == WALL) {
                    c.clear[i] = 0;
                    continue;
                }
                // The point in window-local units.
                double px = cx * CELL, py = cy * CELL;
                double best = cap2;
                for (int dy = -m; dy <= m; dy++) {
                    int wy = ty + dy;
                    double ey = Math.max(0, Math.max(wy * TILE - py, py - (wy + 1) * TILE));
                    double ey2 = ey * ey;
                    if (ey2 >= best) continue;
                    for (int dx = -m; dx <= m; dx++) {
                        if (!wall[(wy + m) * pw + (tx + dx + m)]) continue;
                        int wx = tx + dx;
                        double ex = Math.max(0, Math.max(wx * TILE - px, px - (wx + 1) * TILE));
                        double d2 = ex * ex + ey2;
                        if (d2 < best) best = d2;
                    }
                }
                c.clear[i] = (float) Math.sqrt(best);
            }
        }
        return c;
    }

    static byte kindOf(String name, Predicate<String> fresh, Predicate<String> sea) {
        if (name == null) return WALL;
        if (fresh.test(name)) return FRESH;
        if (sea != null && sea.test(name)) return SEA;
        return WALL;
    }

    public boolean inside(int cx, int cy) {
        return cx >= 0 && cy >= 0 && cx < w && cy < h;
    }

    public int index(int cx, int cy) {
        return cy * w + cx;
    }

    /** Distance from the point to the nearest wall, capped; 0 on a wall and off the window. */
    public float clear(int cx, int cy) {
        return inside(cx, cy) ? clear[cy * w + cx] : 0;
    }

    public float clear(int i) {
        return clear[i];
    }

    public float maxClear() {
        return maxClear;
    }

    /** The cell's tile is water (fresh or sea). */
    public boolean water(int cx, int cy) {
        return inside(cx, cy) && kind[(cy >> 1) * wt + (cx >> 1)] != WALL;
    }

    public boolean sea(int cx, int cy) {
        return inside(cx, cy) && kind[(cy >> 1) * wt + (cx >> 1)] == SEA;
    }

    public boolean fresh(int cx, int cy) {
        return inside(cx, cy) && kind[(cy >> 1) * wt + (cx >> 1)] == FRESH;
    }

    /** The lattice point nearest p (world units, same frame as the window's tiles); may be outside the window. */
    public Coord cellOf(Coord2d p) {
        return Coord.of((int) Math.round(p.x / CELL) - 2 * ul.x, (int) Math.round(p.y / CELL) - 2 * ul.y);
    }

    /** The point in world units. */
    public Coord2d centre(int cx, int cy) {
        return Coord2d.of((2 * ul.x + cx) * CELL, (2 * ul.y + cy) * CELL);
    }
}
