package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import nurgling.actions.bots.worldexplorer.SeaGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** One map grid's water as the hull sees it, from the grid's own 100 x 100 tiles (a recorded MapFile grid or the live map):
 *  <ul>
 *  <li><b>parts</b>: connected regions of cells the hull fits; each knows whether it has fresh water, sea water, and whether it
 *  runs along a coast;</li>
 *  <li><b>openings</b>: runs of fit cells along the grid's edges, per part - where the water goes on into the next grid;</li>
 *  <li>travel costs (tiles, sea counted SEA_MUL times) from each opening to the others of its part.</li>
 *  </ul>
 *  The client records a grid's 3x3 block as soon as the player is anywhere in the grid (GameUI.mapfiletick asks for all nine
 *  and records them once loaded), so entering a grid is what reveals its neighbours.
 *  <ul>
 *  </ul>
 *  Cell coordinates are grid-local, 0..CELLS-1. Pure: tested on hand-made grids. */
public final class GridWater {
    public static final int TILES = 100, CELLS = 2 * TILES;
    /** Sea travel counts this many times fresh-water travel: rivers first. */
    public static final double SEA_MUL = 3;
    /** Shallow sea (owater) counts this many times: at sea the boat keeps to the deep side of the shallows' edge, and goes
     *  into the shallows only the short way to a river mouth. */
    public static final double SHALLOW_MUL = 8;
    /** Water within this many tiles of the shore (land, or shallows joined to it) counts as coast, even where the hull doesn't
     *  fit over the shallows themselves (a narrow band along the land). */
    static final int COAST_TILES = 4;
    /** Shore toward a neighbour: looked for this many tiles in from the shared edge, and in a CORNER_TILES square toward a
     *  diagonal one. */
    static final int SHORE_BAND = 2, CORNER_TILES = 6;

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
    final boolean[] fresh, sea, coast;
    /** Per neighbour (index as RiverFrontier.NEIGHBOURS): whether land, shallows or fresh water reach the side toward it -
     *  the coast (or a river) goes on that way. Deep sea all along that side means only more sea is there. */
    final boolean[] shoreToward;
    /** As shoreToward, but only land, fresh water or shallows joined to them within this grid: sure to be a coast. */
    final boolean[] landToward;
    /** Any land or fresh water in the grid. */
    final boolean hasLand;
    public final List<Opening> openings;
    /** [a][b]: cost from opening a's middle to opening b's, POSITIVE_INFINITY across parts. */
    final double[][] dist;

    // Kept only until compact(): the cells and their parts, for searches from a cell (the boat).
    private WaterCells cells;
    private int[] part;
    private final double fit;

    private GridWater(int parts, boolean[] fresh, boolean[] sea, boolean[] coast, boolean[] shoreToward,
                      boolean[] landToward, boolean hasLand, List<Opening> openings, double[][] dist,
                      WaterCells cells, int[] part, double fit) {
        this.shoreToward = shoreToward;
        this.landToward = landToward;
        this.hasLand = hasLand;
        this.parts = parts;
        this.fresh = fresh;
        this.sea = sea;
        this.coast = coast;
        this.openings = openings;
        this.dist = dist;
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

    /** The part is within COAST_TILES of land or shallows - a coast, or a shallow bank out at sea: RiverFrontier tells them
     *  apart by land in the grids round. */
    public boolean coast(int p) {
        return coast[p];
    }

    /** Whether land, shallows or a river reach this grid's side toward neighbour offset d (-1..1 each way). Shallows alone may
     *  be a bank out at sea: see landToward. */
    public boolean shoreToward(Coord d) {
        int k = neighbour(d);
        return k >= 0 && shoreToward[k];
    }

    /** Whether land, a river, or shallows joined to them in this grid reach the side toward d: the coast surely goes on. */
    public boolean landToward(Coord d) {
        int k = neighbour(d);
        return k >= 0 && landToward[k];
    }

    public boolean hasLand() {
        return hasLand;
    }

    private static int neighbour(Coord d) {
        for (int k = 0; k < RiverFrontier.NEIGHBOURS.length; k++)
            if (RiverFrontier.NEIGHBOURS[k].equals(d)) return k;
        return -1;
    }

    public double dist(int a, int b) {
        return dist[a][b];
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
        boolean[] landTile = shoreTiles(local, freshP, seaP, false), shoreTile = shoreTiles(local, freshP, seaP, true);
        boolean[] nearShallow = near(shoreTile, COAST_TILES);
        for (int s = 0; s < n; s++) {
            if (part[s] >= 0 || !fits(c, s, fit)) continue;
            boolean f = false, sa = false, co = false;
            int sp = 0;
            stack[sp++] = s;
            part[s] = parts;
            while (sp > 0) {
                int cur = stack[--sp];
                int x = cur % CELLS, y = cur / CELLS;
                if (c.sea(x, y)) sa = true; else f = true;
                if (nearShallow[(y >> 1) * TILES + (x >> 1)]) co = true;
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
            flags.add(new boolean[]{f, sa, co});
            parts++;
        }
        boolean[] fresh = new boolean[parts], sea = new boolean[parts], coast = new boolean[parts];
        for (int p = 0; p < parts; p++) {
            fresh[p] = flags.get(p)[0];
            sea[p] = flags.get(p)[1];
            coast[p] = flags.get(p)[2];
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
        boolean[] shore = toward(shoreTile), land = toward(landTile);
        boolean hasLand = false;
        for (boolean b : landTile) hasLand |= b;
        GridWater g = new GridWater(parts, fresh, sea, coast, shore, land, hasLand, openings, null, c, part, fit);
        int no = openings.size();
        double[][] dist = new double[no][no];
        for (int a = 0; a < no; a++) {
            Opening oa = openings.get(a);
            Coord m = oa.cell(oa.mid());
            double[] cost = g.search(m.y * CELLS + m.x);
            for (int b = 0; b < no; b++) {
                Opening ob = openings.get(b);
                Coord mb = ob.cell(ob.mid());
                dist[a][b] = (ob.part == oa.part) ? cost[mb.y * CELLS + mb.x] : Double.POSITIVE_INFINITY;
            }
        }
        return new GridWater(parts, fresh, sea, coast, shore, land, hasLand, openings, dist, c, part, fit);
    }

    private static boolean[] toward(boolean[] shoreTile) {
        boolean[] res = new boolean[RiverFrontier.NEIGHBOURS.length];
        for (int k = 0; k < res.length; k++) {
            Coord d = RiverFrontier.NEIGHBOURS[k];
            int x0, x1, y0, y1;
            if (d.x != 0 && d.y != 0) {
                x0 = (d.x < 0) ? 0 : TILES - CORNER_TILES; x1 = x0 + CORNER_TILES;
                y0 = (d.y < 0) ? 0 : TILES - CORNER_TILES; y1 = y0 + CORNER_TILES;
            } else if (d.x != 0) {
                x0 = (d.x < 0) ? 0 : TILES - SHORE_BAND; x1 = x0 + SHORE_BAND; y0 = 0; y1 = TILES;
            } else {
                y0 = (d.y < 0) ? 0 : TILES - SHORE_BAND; y1 = y0 + SHORE_BAND; x0 = 0; x1 = TILES;
            }
            search:
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++)
                    if (shoreTile[y * TILES + x]) {
                        res[k] = true;
                        break search;
                    }
        }
        return res;
    }

    /** Per tile: land, fresh water, and shallow sea - all of it (anyShallow), or only that joined within this grid to land or
     *  fresh water (the shallows along a coast, not a bank out at sea). */
    static boolean[] shoreTiles(SeaGrid.Tiles local, Predicate<String> freshP, Predicate<String> seaP, boolean anyShallow) {
        boolean[] res = new boolean[TILES * TILES];
        boolean[] shallow = new boolean[TILES * TILES];
        int[] stack = new int[TILES * TILES];
        int sp = 0;
        for (int y = 0; y < TILES; y++) {
            for (int x = 0; x < TILES; x++) {
                String n = local.name(Coord.of(x, y));
                int i = y * TILES + x;
                if (n == null || n.equals("gfx/tiles/odeeper") || n.startsWith("gfx/tiles/nurgling/")) continue;
                if (WaterCells.SHALLOW_SEA.test(n)) shallow[i] = true;
                else if (freshP.test(n) || !seaP.test(n)) res[i] = true;
            }
        }
        // Shallows next to land or fresh water, then all shallows joined to those.
        for (int i = 0; i < res.length; i++) {
            if (!shallow[i]) continue;
            int x = i % TILES, y = i / TILES;
            if ((x > 0 && res[i - 1]) || (x < TILES - 1 && res[i + 1]) || (y > 0 && res[i - TILES]) || (y < TILES - 1 && res[i + TILES]))
                stack[sp++] = i;
        }
        boolean[] coastal = new boolean[res.length];
        for (int k = 0; k < sp; k++) coastal[stack[k]] = true;
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % TILES, y = i / TILES;
            int[] nb = {(x > 0) ? i - 1 : -1, (x < TILES - 1) ? i + 1 : -1, (y > 0) ? i - TILES : -1, (y < TILES - 1) ? i + TILES : -1};
            for (int n : nb) {
                if (n >= 0 && shallow[n] && !coastal[n]) {
                    coastal[n] = true;
                    stack[sp++] = n;
                }
            }
        }
        for (int i = 0; i < res.length; i++)
            if (coastal[i] || (anyShallow && shallow[i])) res[i] = true;
        return res;
    }

    /** Per tile: a set tile within r tiles. */
    private static boolean[] near(boolean[] set, int r) {
        boolean[] res = new boolean[TILES * TILES];
        for (int y = 0; y < TILES; y++) {
            for (int x = 0; x < TILES; x++) {
                if (!set[y * TILES + x]) continue;
                for (int dy = -r; dy <= r; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= TILES) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int nx = x + dx;
                        if (nx >= 0 && nx < TILES) res[ny * TILES + nx] = true;
                    }
                }
            }
        }
        return res;
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
                return c.cost(to % CELLS, to / CELLS, len);
            }
        };
        return CellSearch.run(CELLS, CELLS, graph, new int[]{from}, null, null).cost;
    }

    /** From a grid-local cell (the boat; a cell the hull doesn't fit is moved to the nearest one that does): the cost to each
     *  opening's middle. Null when no fit cell is within 8 tiles. Needs the cells: not after compact(). */
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
        return new FromCell(part[start], toOpening);
    }

    public static final class FromCell {
        public final int part;
        public final double[] toOpening;

        FromCell(int part, double[] toOpening) {
            this.part = part;
            this.toOpening = toOpening;
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
