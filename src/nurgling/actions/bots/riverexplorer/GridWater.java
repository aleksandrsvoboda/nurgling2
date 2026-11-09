package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import nurgling.actions.bots.worldexplorer.SeaGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** One map grid's water as the hull sees it, from the grid's own 100 x 100 tiles (a recorded MapFile grid or the live map):
 *  <ul>
 *  <li><b>parts</b>: connected regions of cells the hull fits; each knows whether it has fresh water, sea water, and cells in the
 *  grid's inner square (being there is what makes the map record the grid's 3x3 block);</li>
 *  <li><b>openings</b>: runs of fit cells along the grid's edges, per part - where the water goes on into the next grid;</li>
 *  <li>travel costs (tiles, sea counted SEA_MUL times) from each opening to the others of its part and to its part's inner
 *  square.</li>
 *  </ul>
 *  Cell coordinates are grid-local, 0..CELLS-1. Pure: tested on hand-made grids. */
public final class GridWater {
    public static final int TILES = 100, CELLS = 2 * TILES;
    /** The inner square, in cells: the map records a grid's 3x3 block only once all nine are loaded, and the client loads map
     *  within 75 tiles of the player - so from tiles 25..74. Two tiles of slack each way (the Map Explorer walker's INSET 27). */
    public static final int INNER_LO = 2 * 27, INNER_HI = 2 * 73;
    /** Sea travel counts this many times fresh-water travel: rivers first. */
    public static final double SEA_MUL = 3;

    public static final int N = 0, E = 1, S = 2, W = 3;

    /** A run of fit cells along one edge, all in one part. lo..hi are positions along the edge (x for N/S, y for E/W). */
    public static final class Opening {
        public final int edge, lo, hi, part;

        Opening(int edge, int lo, int hi, int part) {
            this.edge = edge;
            this.lo = lo;
            this.hi = hi;
            this.part = part;
        }

        public int mid() {
            return (lo + hi) / 2;
        }

        /** The grid-local cell at position p along this opening's edge. */
        public Coord cell(int p) {
            switch (edge) {
                case N: return Coord.of(p, 0);
                case S: return Coord.of(p, CELLS - 1);
                case E: return Coord.of(CELLS - 1, p);
                default: return Coord.of(0, p);
            }
        }
    }

    public final int parts;
    final boolean[] fresh, sea, inner;
    public final List<Opening> openings;
    /** [a][b]: cost from opening a's middle to opening b's, POSITIVE_INFINITY across parts. */
    final double[][] dist;
    /** Per opening: cost from its middle to the nearest inner cell of its part. */
    final double[] toInner;

    // Kept only until compact(): the cells and their parts, for searches from a cell (the boat).
    private WaterCells cells;
    private int[] part;
    private final double fit;

    private GridWater(int parts, boolean[] fresh, boolean[] sea, boolean[] inner, List<Opening> openings, double[][] dist,
                      double[] toInner, WaterCells cells, int[] part, double fit) {
        this.parts = parts;
        this.fresh = fresh;
        this.sea = sea;
        this.inner = inner;
        this.openings = openings;
        this.dist = dist;
        this.toInner = toInner;
        this.cells = cells;
        this.part = part;
        this.fit = fit;
    }

    public boolean fresh(int p) {
        return fresh[p];
    }

    public boolean sea(int p) {
        return sea[p];
    }

    public boolean hasInner(int p) {
        return inner[p];
    }

    public double dist(int a, int b) {
        return dist[a][b];
    }

    public double toInner(int o) {
        return toInner[o];
    }

    /** Drops the cells; what's left is a few hundred bytes, fine to keep for every grid of a run. */
    public GridWater compact() {
        cells = null;
        part = null;
        return this;
    }

    /**
     * @param local tiles of this grid, grid-local 0..TILES-1 (null = unknown, a wall); outside the grid is taken as water, so
     *              the edges aren't eroded - a neighbour's land shows up as its own openings not lining up with these
     */
    public static GridWater build(SeaGrid.Tiles local, Predicate<String> freshP, Predicate<String> seaP, double fit) {
        return build(local, freshP, seaP, fit, null);
    }

    /** As build, with extra wall tiles (grid-local): objects that close the water for good, such as beaver dams. */
    public static GridWater build(SeaGrid.Tiles local, Predicate<String> freshP, Predicate<String> seaP, double fit,
                                  java.util.Collection<Coord> walls) {
        final String OUTSIDE = "outside";
        Predicate<String> freshOrOutside = n -> n.equals(OUTSIDE) || freshP.test(n);
        SeaGrid.Tiles tiles = tc -> (tc.x < 0 || tc.y < 0 || tc.x >= TILES || tc.y >= TILES) ? OUTSIDE : local.name(tc);
        WaterCells c = WaterCells.build(tiles, Coord.z, TILES, TILES, freshOrOutside, seaP, walls, fit + WaterCells.CELL);
        int n = CELLS * CELLS;
        int[] part = new int[n];
        java.util.Arrays.fill(part, -1);
        List<boolean[]> flags = new ArrayList<>();
        int parts = 0;
        int[] stack = new int[n];
        for (int s = 0; s < n; s++) {
            if (part[s] >= 0 || !fits(c, s, fit)) continue;
            boolean f = false, sa = false, in = false;
            int sp = 0;
            stack[sp++] = s;
            part[s] = parts;
            while (sp > 0) {
                int cur = stack[--sp];
                int x = cur % CELLS, y = cur / CELLS;
                if (c.sea(x, y)) sa = true; else f = true;
                if (x >= INNER_LO && x < INNER_HI && y >= INNER_LO && y < INNER_HI) in = true;
                for (int k = 0; k < 8; k++) {
                    int nx = x + CellSearch.DX[k], ny = y + CellSearch.DY[k];
                    if (nx < 0 || ny < 0 || nx >= CELLS || ny >= CELLS) continue;
                    int ni = ny * CELLS + nx;
                    if (part[ni] >= 0 || !fits(c, ni, fit)) continue;
                    if (k >= 4 && (!fits(c, y * CELLS + nx, fit) || !fits(c, ny * CELLS + x, fit))) continue;
                    part[ni] = parts;
                    stack[sp++] = ni;
                }
            }
            flags.add(new boolean[]{f, sa, in});
            parts++;
        }
        boolean[] fresh = new boolean[parts], sea = new boolean[parts], inner = new boolean[parts];
        for (int p = 0; p < parts; p++) {
            fresh[p] = flags.get(p)[0];
            sea[p] = flags.get(p)[1];
            inner[p] = flags.get(p)[2];
        }

        List<Opening> openings = new ArrayList<>();
        for (int edge = 0; edge < 4; edge++) {
            int runStart = -1, runPart = -1;
            for (int p = 0; p <= CELLS; p++) {
                int pp = -1;
                if (p < CELLS) {
                    Coord cc = new Opening(edge, 0, 0, 0).cell(p);
                    pp = part[cc.y * CELLS + cc.x];
                }
                if (pp != runPart) {
                    if (runPart >= 0)
                        openings.add(new Opening(edge, runStart, p - 1, runPart));
                    runStart = p;
                    runPart = pp;
                }
            }
        }

        // A first instance only to search with; the finished one gets the costs.
        GridWater g = new GridWater(parts, fresh, sea, inner, openings, null, null, c, part, fit);
        int no = openings.size();
        double[][] dist = new double[no][no];
        double[] toInner = new double[no];
        for (int a = 0; a < no; a++) {
            Opening oa = openings.get(a);
            Coord m = oa.cell(oa.mid());
            double[] cost = g.search(m.y * CELLS + m.x);
            for (int b = 0; b < no; b++) {
                Opening ob = openings.get(b);
                Coord mb = ob.cell(ob.mid());
                dist[a][b] = (ob.part == oa.part) ? cost[mb.y * CELLS + mb.x] : Double.POSITIVE_INFINITY;
            }
            toInner[a] = nearestInner(cost);
        }
        return new GridWater(parts, fresh, sea, inner, openings, dist, toInner, c, part, fit);
    }

    private static boolean fits(WaterCells c, int i, double fit) {
        return c.clear(i) >= fit;
    }

    /** Travel cost from cell `from` (grid-local cell index) to every cell. */
    double[] search(int from) {
        WaterCells c = cells;
        CellSearch.Graph graph = new CellSearch.Graph() {
            public boolean passable(int i) {
                return fits(c, i, fit);
            }

            public double step(int to, double len) {
                return c.sea(to % CELLS, to / CELLS) ? len * SEA_MUL : len;
            }
        };
        return CellSearch.run(CELLS, CELLS, graph, new int[]{from}, null, null).cost;
    }

    private static double nearestInner(double[] cost) {
        double best = Double.POSITIVE_INFINITY;
        for (int y = INNER_LO; y < INNER_HI; y++)
            for (int x = INNER_LO; x < INNER_HI; x++)
                best = Math.min(best, cost[y * CELLS + x]);
        return best;
    }

    /** From a grid-local cell (the boat; a cell the hull doesn't fit is moved to the nearest one that does): the cost to each
     *  opening's middle and to the inner square. Null when no fit cell is within 8 tiles. Needs the cells: not after compact(). */
    public FromCell from(Coord cell) {
        int start = nearestFit(cell, 2 * 8);
        if (start < 0) return null;
        double[] cost = search(start);
        double[] toOpening = new double[openings.size()];
        for (int k = 0; k < toOpening.length; k++) {
            Opening o = openings.get(k);
            Coord m = o.cell(o.mid());
            toOpening[k] = cost[m.y * CELLS + m.x];
        }
        return new FromCell(part[start], toOpening, nearestInner(cost));
    }

    public static final class FromCell {
        public final int part;
        public final double[] toOpening;
        public final double toInner;

        FromCell(int part, double[] toOpening, double toInner) {
            this.part = part;
            this.toOpening = toOpening;
            this.toInner = toInner;
        }
    }

    /** Index of the fit cell nearest `cell` within r cells (Chebyshev rings), -1 if none. */
    private int nearestFit(Coord cell, int r) {
        for (int d = 0; d <= r; d++) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int dy = -d; dy <= d; dy++) {
                for (int dx = -d; dx <= d; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != d) continue;
                    int x = cell.x + dx, y = cell.y + dy;
                    if (x < 0 || y < 0 || x >= CELLS || y >= CELLS) continue;
                    int i = y * CELLS + x;
                    if (part[i] < 0) continue;
                    double dd = Math.hypot(dx, dy);
                    if (dd < bd) {
                        bd = dd;
                        best = i;
                    }
                }
            }
            if (best >= 0) return best;
        }
        return -1;
    }

    /** Positions where opening a (this grid, edge e) and opening b (the neighbour across e, the opposite edge) overlap, or null. */
    public static int[] overlap(Opening a, Opening b) {
        int lo = Math.max(a.lo, b.lo), hi = Math.min(a.hi, b.hi);
        return (lo <= hi) ? new int[]{lo, hi} : null;
    }

    public static int opposite(int edge) {
        return (edge + 2) % 4;
    }

    /** Grid offset across an edge. */
    public static Coord step(int edge) {
        switch (edge) {
            case N: return Coord.of(0, -1);
            case S: return Coord.of(0, 1);
            case E: return Coord.of(1, 0);
            default: return Coord.of(-1, 0);
        }
    }
}
