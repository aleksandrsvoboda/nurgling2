package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Coord2d;
import nurgling.actions.bots.worldexplorer.SeaGrid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** River Explorer's course over the loaded map: A* on half-tile cells from the boat into a goal rectangle of tiles.
 *  <ul>
 *  <li>hard: cells the hull doesn't fit (bank, unloaded map, solid gobs other than reeds), and danger/snag discs grown by the fit;</li>
 *  <li>cost: tiles travelled, sea counted SEA_MUL times, more near the bank (BANK_RANGE) so the course keeps to the middle;</li>
 *  <li>reeds: cells the hull only fits with the reeds gone cost REED_CELL more - a short detour wins, a choked channel is still
 *  crossed and its reeds get chopped.</li>
 *  </ul>
 *  Coordinates are the session's (world units and tiles). Pure, for tests. */
public final class Pilot {
    private Pilot() {}

    static final double TILE = WaterCells.TILE;
    /** How far from the bank the course is pushed toward the middle, and how hard. */
    static final double BANK_RANGE = 2 * TILE;
    static final double BANK_K = 1.0;
    /** Extra cost per cell (orthogonal step) that needs reeds gone: ~15 tiles per reed for a rowboat, about one chop's time. */
    static final double REED_CELL = 3;
    /** The boat may be wedged against a bank: cells within this many of the start only need to be water. */
    static final int START_FREE = 4;
    /** ...at this extra cost per tile, so the course gets out to water the hull fits as soon as it can. */
    static final double UNFIT_K = 8;
    /** Inside a danger disc the boat already is in: allowed, at this cost per tile, so the course leaves it quickly. */
    static final double IN_DISC_K = 10;

    public static final class Input {
        public SeaGrid.Tiles tiles;
        public Predicate<String> fresh, sea;
        /** Window to plan in: tiles [ul, ul + (wt, ht)). */
        public Coord ul;
        public int wt, ht;
        /** Solid tiles (gobs other than reeds, and reeds that wouldn't chop). */
        public Collection<Coord> solid = new ArrayList<>();
        /** Tiles under reeds. */
        public Collection<Coord> reeds = new ArrayList<>();
        /** No-go discs: dangerous animals, snags. */
        public List<SeaGrid.Disc> discs = new ArrayList<>();
        public double fit;
        public Coord2d start;
        /** Goal: tiles [goalUL, goalBR). */
        public Coord goalUL, goalBR;
    }

    /** A planned course: cell centres from the boat to the goal, and which of them need reeds gone. */
    public static final class Course {
        public final List<Coord2d> pts;
        public final boolean[] reed;
        public final double cost;
        final WaterCells withReeds;
        final List<SeaGrid.Disc> discs;
        final double fit;

        Course(List<Coord2d> pts, boolean[] reed, double cost, WaterCells withReeds, List<SeaGrid.Disc> discs, double fit) {
            this.pts = pts;
            this.reed = reed;
            this.cost = cost;
            this.withReeds = withReeds;
            this.discs = discs;
            this.fit = fit;
        }

        /** Index of the first point at or after `from` that needs reeds gone, -1 if none. */
        public int firstReed(int from) {
            for (int i = Math.max(0, from); i < pts.size(); i++)
                if (reed[i]) return i;
            return -1;
        }

        /** The point nearest p among indices [from, from + span). */
        public int nearest(Coord2d p, int from, int span) {
            int best = Math.max(0, Math.min(from, pts.size() - 1));
            double bd = Double.MAX_VALUE;
            for (int i = Math.max(0, from); i < Math.min(pts.size(), from + span); i++) {
                double d = pts.get(i).dist(p);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }

        /** True when the hull can sail straight from a to b as things stand (reeds in place): every sampled cell fits and is
         *  outside the discs. The first tile from a is skipped - the boat may be close to the bank or a reed. */
        public boolean clearLine(Coord2d a, Coord2d b) {
            double len = a.dist(b);
            int n = (int) Math.ceil(len / (WaterCells.CELL / 2));
            for (int k = 1; k <= n; k++) {
                Coord2d p = a.add(b.sub(a).mul((double) k / n));
                if (p.dist(a) < TILE) continue;
                Coord c = withReeds.cellOf(p);
                if (withReeds.clear(c.x, c.y) < fit) return false;
                for (SeaGrid.Disc d : discs)
                    if (!d.contains(a) && p.dist(d.c) < d.r) return false;
            }
            return true;
        }

        /** Reed tiles that keep the hull from the reed points from index r on (the run of reed points that starts there). */
        public Set<Coord> blockingReeds(int r, Collection<Coord> reedTiles) {
            Set<Coord> res = new HashSet<>();
            for (int i = r; i < pts.size() && reed[i]; i++) {
                Coord2d p = pts.get(i);
                // A cell of slack: the course's points and the reeds' tiles are on different lattices.
                for (Coord t : reedTiles)
                    if (tileDist(p, t) < fit + WaterCells.CELL) res.add(t);
            }
            return res;
        }
    }

    /** Distance from point p to the square of tile t. */
    public static double tileDist(Coord2d p, Coord t) {
        double ex = Math.max(0, Math.max(t.x * TILE - p.x, p.x - (t.x + 1) * TILE));
        double ey = Math.max(0, Math.max(t.y * TILE - p.y, p.y - (t.y + 1) * TILE));
        return Math.hypot(ex, ey);
    }

    /** The course, or null when there is no way into the goal. */
    public static Course plan(Input in) {
        List<Coord> solidAndReeds = new ArrayList<>(in.solid);
        solidAndReeds.addAll(in.reeds);
        WaterCells open = WaterCells.build(in.tiles, in.ul, in.wt, in.ht, in.fresh, in.sea, in.solid, in.fit + BANK_RANGE);
        WaterCells shut = WaterCells.build(in.tiles, in.ul, in.wt, in.ht, in.fresh, in.sea, solidAndReeds, in.fit + WaterCells.CELL);
        int w = open.w, h = open.h;
        Coord s = open.cellOf(in.start);
        if (!open.inside(s.x, s.y)) return null;
        int start = open.index(s.x, s.y);
        // Discs the boat is in already only cost; the others are walls.
        List<SeaGrid.Disc> around = new ArrayList<>(), walls = new ArrayList<>();
        for (SeaGrid.Disc d : in.discs)
            (d.contains(in.start) ? around : walls).add(d);
        double fit = in.fit;
        Coord gul = in.goalUL.sub(in.ul).mul(2), gbr = in.goalBR.sub(in.ul).mul(2);

        CellSearch.Graph graph = new CellSearch.Graph() {
            public boolean passable(int i) {
                int x = i % w, y = i / w;
                boolean nearStart = Math.max(Math.abs(x - s.x), Math.abs(y - s.y)) <= START_FREE;
                if (open.clear(i) < fit && !(nearStart && open.water(x, y))) return false;
                if (walls.isEmpty()) return true;
                Coord2d p = open.centre(x, y);
                for (SeaGrid.Disc d : walls)
                    if (p.dist(d.c) < d.r + fit) return false;
                return true;
            }

            public double step(int to, double len) {
                int x = to % w, y = to / w;
                double c = open.sea(x, y) ? len * GridWater.SEA_MUL : len;
                double clear = open.clear(to);
                c += len * BANK_K * Math.max(0, (fit + BANK_RANGE - clear) / BANK_RANGE);
                if (clear < fit)
                    c += len * UNFIT_K;
                if (shut.clear(to) < fit && open.clear(to) >= fit)
                    c += REED_CELL * len / CellSearch.ORTH;
                if (!around.isEmpty()) {
                    Coord2d p = open.centre(x, y);
                    for (SeaGrid.Disc d : around)
                        if (p.dist(d.c) < d.r) c += len * IN_DISC_K;
                }
                return c;
            }
        };
        CellSearch.Goal goal = new CellSearch.Goal() {
            // From the first tile centre in: a course ending on the rectangle's edge leaves a boat that stops a little short
            // in the tile before it, never "in" the goal.
            public boolean reached(int i) {
                int x = i % w, y = i / w;
                return x > gul.x && y > gul.y && x < gbr.x && y < gbr.y && open.clear(i) >= fit;
            }

            public double estimate(int i) {
                int x = i % w, y = i / w;
                int dx = Math.max(0, Math.max(gul.x + 1 - x, x - (gbr.x - 1)));
                int dy = Math.max(0, Math.max(gul.y + 1 - y, y - (gbr.y - 1)));
                return CellSearch.octile(dx, dy);
            }
        };
        CellSearch.Result r = CellSearch.run(w, h, graph, new int[]{start}, null, goal);
        int[] cells = r.path(r.goal);
        if (cells == null) return null;
        List<Coord2d> pts = new ArrayList<>(cells.length);
        boolean[] reed = new boolean[cells.length];
        for (int k = 0; k < cells.length; k++) {
            int x = cells[k] % w, y = cells[k] / w;
            pts.add(open.centre(x, y));
            reed[k] = shut.clear(cells[k]) < fit && open.clear(cells[k]) >= fit;
        }
        return new Course(pts, reed, r.cost[r.goal], shut, walls, fit);
    }
}
