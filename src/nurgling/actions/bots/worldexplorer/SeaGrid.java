package nurgling.actions.bots.worldexplorer;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Ocean Explorer's picture of the water around the boat, in tiles. Built fresh every steering tick from a tile lookup, the tiles
 *  solid gobs cover and the danger/snag discs, so it is pure and testable on a hand-made map.
 *  <ul>
 *  <li><b>hard</b>: the hull can't be on it at all - not sailable water, not loaded, or under a solid gob.</li>
 *  <li><b>wall</b>: hard, or water outside the followed band (e.g. shallow water when following the shoreline).</li>
 *  <li><b>blocked</b>: wall or a disc, grown by the clearance radius - a ray only has to test the tiles it crosses.</li>
 *  <li><b>edge</b>: only the walls the boat <i>follows</i> (known tiles outside the band, minus ignored islands), grown the same
 *  way. Gobs, discs and unloaded map are obstacles to steer around, never something to follow.</li>
 *  </ul> */
public final class SeaGrid {
    /** A tile's tileset name, null where it isn't loaded. */
    public interface Tiles {
        String name(Coord tc);
    }

    /** A round no-go area: a dangerous animal or a spot the boat got snagged on. */
    public static final class Disc {
        public final Coord2d c;
        public final double r;

        public Disc(Coord2d c, double r) {
            this.c = c;
            this.r = r;
        }

        public boolean contains(Coord2d p) {
            return p.dist(c) < r;
        }
    }

    public final Coord ul;
    public final int size;
    public final int clearance;
    private final boolean[] hard, wall, blocked, edge;

    private SeaGrid(Coord ul, int size, int clearance) {
        this.ul = ul;
        this.size = size;
        this.clearance = clearance;
        int n = size * size;
        hard = new boolean[n];
        wall = new boolean[n];
        blocked = new boolean[n];
        edge = new boolean[n];
    }

    /**
     * @param tiles     tileset lookup
     * @param band      the water the boat stays in (the followed edge is whatever borders it)
     * @param sailable  any water a hull can float on
     * @param centre    the boat's tile
     * @param radius    tiles each way around centre
     * @param solid     tiles covered by solid gobs
     * @param ignored   wall tiles that are obstacles but never followed (islands already sailed around)
     * @param discs     no-go discs
     * @param clearance tiles of water the hull keeps from any wall
     */
    public static SeaGrid build(Tiles tiles, java.util.function.Predicate<String> band, java.util.function.Predicate<String> sailable,
                                Coord centre, int radius, Collection<Coord> solid, Set<Coord> ignored, List<Disc> discs, int clearance) {
        SeaGrid g = new SeaGrid(centre.sub(radius, radius), 2 * radius + 1, clearance);
        boolean[] edgeSrc = new boolean[g.size * g.size];
        boolean[] wallSrc = new boolean[g.size * g.size];
        for (int y = 0; y < g.size; y++) {
            for (int x = 0; x < g.size; x++) {
                Coord tc = g.ul.add(x, y);
                String name = tiles.name(tc);
                int i = y * g.size + x;
                boolean h = name == null || !sailable.test(name);
                boolean w = h || !band.test(name);
                g.hard[i] = h;
                g.wall[i] = w;
                wallSrc[i] = w;
                edgeSrc[i] = name != null && !band.test(name) && !ignored.contains(tc);
            }
        }
        for (Coord tc : solid) {
            int i = g.index(tc);
            if (i >= 0) {
                g.hard[i] = true;
                g.wall[i] = true;
                wallSrc[i] = true;
            }
        }
        for (Disc d : discs) {
            for (int y = 0; y < g.size; y++) {
                for (int x = 0; x < g.size; x++) {
                    if (d.contains(centreOf(g.ul.add(x, y))))
                        wallSrc[y * g.size + x] = true;
                }
            }
        }
        List<Coord> kernel = kernel(clearance);
        g.grow(wallSrc, g.blocked, kernel);
        g.grow(edgeSrc, g.edge, kernel);
        return g;
    }

    /** Offsets within clearance tiles (centre to centre). */
    private static List<Coord> kernel(int clearance) {
        List<Coord> k = new ArrayList<>();
        for (int dy = -clearance; dy <= clearance; dy++)
            for (int dx = -clearance; dx <= clearance; dx++)
                if (dx * dx + dy * dy <= clearance * clearance)
                    k.add(Coord.of(dx, dy));
        return k;
    }

    private void grow(boolean[] src, boolean[] dst, List<Coord> kernel) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!src[y * size + x]) continue;
                for (Coord o : kernel) {
                    int nx = x + o.x, ny = y + o.y;
                    if (nx >= 0 && ny >= 0 && nx < size && ny < size)
                        dst[ny * size + nx] = true;
                }
            }
        }
    }

    public static Coord2d centreOf(Coord tc) {
        return tc.mul(MCache.tilesz).add(MCache.tilehsz);
    }

    public static Coord tileOf(Coord2d p) {
        return p.floor(MCache.tilesz);
    }

    int index(Coord tc) {
        int x = tc.x - ul.x, y = tc.y - ul.y;
        if (x < 0 || y < 0 || x >= size || y >= size) return -1;
        return y * size + x;
    }

    /** Off the grid counts as everything: hard, wall, blocked. */
    public boolean hard(Coord tc) {
        int i = index(tc);
        return i < 0 || hard[i];
    }

    public boolean wall(Coord tc) {
        int i = index(tc);
        return i < 0 || wall[i];
    }

    public boolean blocked(Coord tc) {
        int i = index(tc);
        return i < 0 || blocked[i];
    }

    /** Within clearance of a followed edge. Off the grid is not an edge. */
    public boolean edge(Coord tc) {
        int i = index(tc);
        return i >= 0 && edge[i];
    }

    /** Where rays start: at the boat, or - when the boat is already within the clearance of a wall - `skip` out, so it can
     *  still find its way off the wall. Never more than half the ray. */
    public double rayStart(Coord2d p, double skip, double len) {
        return blocked(tileOf(p)) ? Math.min(skip, len / 2) : 0;
    }

    /** True if the ray from p at angle a is clear out to `to`: no hard tile before `from`, no blocked tile from there on. */
    public boolean rayClear(Coord2d p, double a, double from, double to) {
        return !walk(p, Math.cos(a), Math.sin(a), to, (tc, t) -> (t < from) ? hard(tc) : blocked(tc));
    }

    /** True if the ray from p at angle a reaches a followed edge within `to`. */
    public boolean rayTouchesEdge(Coord2d p, double a, double to) {
        return walk(p, Math.cos(a), Math.sin(a), to, (tc, t) -> edge(tc));
    }

    /** True if the straight line a..b crosses no hard tile. */
    public boolean lineSailable(Coord2d a, Coord2d b) {
        double len = a.dist(b);
        if (len == 0) return !hard(tileOf(a));
        return !walk(a, (b.x - a.x) / len, (b.y - a.y) / len, len, (tc, t) -> hard(tc));
    }

    private interface Hit {
        /** tc is entered t units along the ray; true stops the walk. */
        boolean test(Coord tc, double t);
    }

    /** Visits every tile the segment from p along unit direction (c, s) for len units crosses, in order (a grid traversal - no
     *  tile corner is skipped). True as soon as hit says so. */
    private static boolean walk(Coord2d p, double c, double s, double len, Hit hit) {
        double T = MCache.tilesz.x;
        int x = (int) Math.floor(p.x / T), y = (int) Math.floor(p.y / T);
        int sx = (c > 0) ? 1 : (c < 0) ? -1 : 0, sy = (s > 0) ? 1 : (s < 0) ? -1 : 0;
        double nextX = (sx > 0) ? ((x + 1) * T - p.x) / c : (sx < 0) ? (x * T - p.x) / c : Double.POSITIVE_INFINITY;
        double nextY = (sy > 0) ? ((y + 1) * T - p.y) / s : (sy < 0) ? (y * T - p.y) / s : Double.POSITIVE_INFINITY;
        double dX = (sx != 0) ? T / Math.abs(c) : Double.POSITIVE_INFINITY, dY = (sy != 0) ? T / Math.abs(s) : Double.POSITIVE_INFINITY;
        double t = 0;
        while (true) {
            if (hit.test(Coord.of(x, y), t)) return true;
            if (nextX < nextY) {
                t = nextX;
                if (t > len) return false;
                x += sx;
                nextX += dX;
            } else {
                t = nextY;
                if (t > len) return false;
                y += sy;
                nextY += dY;
            }
        }
    }
}
