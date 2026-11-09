package nurgling.actions.bots.riverexplorer;

import haven.Coord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Where River Explorer goes next, in segment grid coordinates. The target rule is Map Explorer's (nurgling-plugins
 *  mapexplorer/ExplorerFrontier - gain(), inLeash(), alignment() and the score's heading nudge are copied from it; keep them in
 *  step): a known grid with unknown neighbours, scored by gain over cost. What differs is cost and reach - by water, for this
 *  hull, through the grids' openings (GridWater) - and the river priority:
 *  <ul>
 *  <li>sea travel costs GridWater.SEA_MUL times fresh-water travel;</li>
 *  <li>tier 1 is targets whose inner square is reached through fresh water; the coast and open sea (tier 2) are only used when
 *  no tier-1 target is reachable - so a river system is finished before the boat sails on to the next river mouth;</li>
 *  <li>among tier-1 targets, the farther upstream (fresh-water grid steps from any sea), the better.</li>
 *  </ul>
 *  Pure, for tests. */
public final class RiverFrontier {
    private RiverFrontier() {}

    public static final Coord[] NEIGHBOURS = {
            Coord.of(-1, -1), Coord.of(0, -1), Coord.of(1, -1),
            Coord.of(-1, 0), Coord.of(1, 0),
            Coord.of(-1, 1), Coord.of(0, 1), Coord.of(1, 1),
    };
    static final double HEADING_WEIGHT = 0.3;
    static final double DEPTH_WEIGHT = 0.25;
    static final int MAX_DEPTH = 8;
    /** Cost is counted in grids of travel: gain / (1 + cost / COST_SCALE). */
    static final double COST_SCALE = GridWater.TILES;

    /** One grid crossing of a route: the grid entered and the cell (grid-local) it is entered at. */
    public static final class Hop {
        public final Coord grid;
        public final Coord cell;

        Hop(Coord grid, Coord cell) {
            this.grid = grid;
            this.cell = cell;
        }
    }

    public static final class Choice {
        public final Coord grid;
        public final int gain;
        public final double cost;
        /** The part of cost sailed in water that is sea only (no river or lake in the same stretch): how far out at sea the
         *  way to the target goes. */
        public final double seaCost;
        public final boolean fresh;
        public final int depth;
        /** Crossings from the player's grid into the target's; empty when the target is the player's own grid. */
        public final List<Hop> route;

        Choice(Coord grid, int gain, double cost, double seaCost, boolean fresh, int depth, List<Hop> route) {
            this.grid = grid;
            this.gain = gain;
            this.cost = cost;
            this.seaCost = seaCost;
            this.fresh = fresh;
            this.depth = depth;
            this.route = route;
        }
    }

    /** What the search knows about the world. */
    public static final class World {
        /** Known grids (recorded in the map file). */
        public final Set<Coord> known;
        /** Water of the known grids read so far; a known grid missing here can't be crossed yet. */
        public final Map<Coord, GridWater> water;

        public World(Set<Coord> known, Map<Coord, GridWater> water) {
            this.known = known;
            this.water = water;
        }
    }

    /** Settings and run memory for a choice. */
    public static final class Ask {
        public Coord player;
        /** The boat's place in the player's grid (GridWater.from on it). */
        public GridWater.FromCell start;
        /** Neither targets nor crossed. */
        public Set<Coord> excluded = Collections.emptySet();
        /** Crossings not to use, as link(from, to). */
        public Set<String> excludedLinks = Collections.emptySet();
        /** Crossed but never targets. */
        public Set<Coord> visited = Collections.emptySet();
        public Coord heading, leashCenter;
        public int leashGrids;
        /** The target being held, if any: kept while it is still the best tier and worth going to. */
        public Coord hold;
    }

    public static String link(Coord from, Coord to) {
        return from.x + "," + from.y + ">" + to.x + "," + to.y;
    }

    public static boolean inLeash(Coord c, Coord center, int leashGrids) {
        if (leashGrids <= 0 || center == null) return true;
        return Math.max(Math.abs(c.x - center.x), Math.abs(c.y - center.y)) <= leashGrids;
    }

    public static int gain(Set<Coord> known, Coord c, Coord leashCenter, int leashGrids) {
        int n = 0;
        for (Coord d : NEIGHBOURS) {
            Coord nb = c.add(d);
            if (!known.contains(nb) && inLeash(nb, leashCenter, leashGrids)) n++;
        }
        return n;
    }

    static double alignment(Coord heading, Coord d) {
        if (heading == null || (heading.x == 0 && heading.y == 0) || (d.x == 0 && d.y == 0)) return 0;
        return (heading.x * d.x + heading.y * d.y) / (Math.hypot(heading.x, heading.y) * Math.hypot(d.x, d.y));
    }

    /** A Dijkstra node: an opening of a loaded grid. */
    private static final class Node {
        final Coord grid;
        final int opening;
        double cost = Double.POSITIVE_INFINITY;
        /** Of cost, what was sailed in sea-only parts (carried along the cheapest way, not minimised). */
        double sea;
        Node parent;
        /** Set when this node was reached by crossing into its grid: where it was entered. */
        Coord entry;
        boolean done;

        Node(Coord grid, int opening) {
            this.grid = grid;
            this.opening = opening;
        }
    }

    /** The best target, or null when no known grid with unknown neighbours can be reached by water. */
    public static Choice choose(World world, Ask ask) {
        GridWater home = world.water.get(ask.player);
        if (home == null || ask.start == null) return null;
        Map<Coord, int[]> depth = depths(world);

        Map<String, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.cost, b.cost));
        for (int k = 0; k < home.openings.size(); k++) {
            double c = ask.start.toOpening[k];
            if (Double.isInfinite(c)) continue;
            Node n = node(nodes, ask.player, k);
            n.cost = c;
            n.sea = seaOnly(home, ask.start.part) ? c : 0;
            open.add(n);
        }

        // Best arrival in each grid's inner square: cost, the node it was reached from (null = the player's own grid), fresh.
        Map<Coord, double[]> arrive = new HashMap<>();
        Map<Coord, Node> arriveNode = new HashMap<>();
        if (!ask.visited.contains(ask.player) && !Double.isInfinite(ask.start.toInner))
            arrive.put(ask.player, new double[]{ask.start.toInner, home.fresh(ask.start.part) ? 1 : 0, depthOf(depth, ask.player, ask.start.part),
                    seaOnly(home, ask.start.part) ? ask.start.toInner : 0});

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.done) continue;
            cur.done = true;
            GridWater g = world.water.get(cur.grid);
            GridWater.Opening o = g.openings.get(cur.opening);
            int part = o.part;
            // This grid's inner square, when the node was reached by entering the grid.
            if (cur.entry != null && g.hasInner(part) && !Double.isInfinite(g.toInner(cur.opening))) {
                double c = cur.cost + g.toInner(cur.opening);
                double[] best = arrive.get(cur.grid);
                if (best == null || c < best[0]) {
                    arrive.put(cur.grid, new double[]{c, g.fresh(part) ? 1 : 0, depthOf(depth, cur.grid, part),
                            cur.sea + (seaOnly(g, part) ? g.toInner(cur.opening) : 0)});
                    arriveNode.put(cur.grid, cur);
                }
            }
            // Along the part to its other openings.
            for (int k = 0; k < g.openings.size(); k++) {
                if (k == cur.opening) continue;
                double d = g.dist(cur.opening, k);
                if (Double.isInfinite(d)) continue;
                relax(open, node(nodes, cur.grid, k), cur.cost + d, cur.sea + (seaOnly(g, part) ? d : 0), cur, null);
            }
            // Across the edge into the neighbour.
            Coord nb = cur.grid.add(GridWater.step(o.edge));
            GridWater ng = world.water.get(nb);
            if (ng == null || ask.excluded.contains(nb) || ask.excludedLinks.contains(link(cur.grid, nb))) continue;
            int opp = GridWater.opposite(o.edge);
            for (int k = 0; k < ng.openings.size(); k++) {
                GridWater.Opening b = ng.openings.get(k);
                if (b.edge != opp) continue;
                int[] ov = GridWater.overlap(o, b);
                if (ov == null) continue;
                // The crossing is at the overlap's middle; the costs inside each grid are counted from the openings' middles.
                Coord entry = b.cell((ov[0] + ov[1]) / 2);
                relax(open, node(nodes, nb, k), cur.cost, cur.sea, cur, entry);
            }
        }

        // Score the arrivals; tier 1 (fresh) beats tier 2 outright.
        Coord best = null, held = null;
        double bestScore = 0;
        int bestTier = -1, heldTier = -1;
        for (Map.Entry<Coord, double[]> e : arrive.entrySet()) {
            Coord c = e.getKey();
            if (ask.visited.contains(c) || ask.excluded.contains(c) || !inLeash(c, ask.leashCenter, ask.leashGrids)) continue;
            int gain = gain(world.known, c, ask.leashCenter, ask.leashGrids);
            if (gain == 0) continue;
            double cost = e.getValue()[0];
            int tier = e.getValue()[1] > 0 ? 1 : 0;
            int dep = (int) e.getValue()[2];
            double score = gain / (1 + cost / COST_SCALE)
                    * (tier == 1 ? 1 + DEPTH_WEIGHT * Math.min(dep, MAX_DEPTH) : 1)
                    * (1 + HEADING_WEIGHT * alignment(ask.heading, c.sub(ask.player)));
            if (tier > bestTier || (tier == bestTier && score > bestScore + 1e-9)) {
                best = c;
                bestScore = score;
                bestTier = tier;
            }
            if (c.equals(ask.hold)) {
                held = c;
                heldTier = tier;
            }
        }
        if (held != null && heldTier == bestTier)
            best = held;
        if (best == null) return null;
        double[] a = arrive.get(best);
        return new Choice(best, gain(world.known, best, ask.leashCenter, ask.leashGrids), a[0], a[3], a[1] > 0, (int) a[2],
                route(arriveNode.get(best)));
    }

    private static Node node(Map<String, Node> nodes, Coord grid, int opening) {
        return nodes.computeIfAbsent(grid.x + "," + grid.y + "#" + opening, k -> new Node(grid, opening));
    }

    private static void relax(PriorityQueue<Node> open, Node n, double cost, double sea, Node from, Coord entry) {
        if (n.done || cost >= n.cost) return;
        n.cost = cost;
        n.sea = sea;
        n.parent = from;
        n.entry = entry;
        open.add(n);
    }

    /** The crossings that lead to `end`, in order. */
    private static List<Hop> route(Node end) {
        List<Hop> res = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            if (n.entry != null)
                res.add(new Hop(n.grid, n.entry));
        }
        Collections.reverse(res);
        return res;
    }

    /** A part with sea water and no fresh water: open sea or a coast with no river or lake joining it. */
    static boolean seaOnly(GridWater g, int part) {
        return g.sea(part) && !g.fresh(part);
    }

    private static int depthOf(Map<Coord, int[]> depth, Coord grid, int part) {
        int[] d = depth.get(grid);
        return (d == null || part >= d.length) ? MAX_DEPTH : d[part];
    }

    /** Per loaded grid and part: fresh-water grid steps from the nearest part with sea water in it (MAX_DEPTH when no sea is
     *  connected - a landlocked lake system counts as fully inland). */
    static Map<Coord, int[]> depths(World world) {
        Map<Coord, int[]> depth = new HashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (Map.Entry<Coord, GridWater> e : world.water.entrySet()) {
            GridWater g = e.getValue();
            int[] d = new int[g.parts];
            java.util.Arrays.fill(d, Integer.MAX_VALUE);
            for (int p = 0; p < g.parts; p++) {
                if (g.sea(p)) {
                    d[p] = 0;
                    queue.add(new Object[]{e.getKey(), p});
                }
            }
            depth.put(e.getKey(), d);
        }
        while (!queue.isEmpty()) {
            Object[] q = queue.poll();
            Coord grid = (Coord) q[0];
            int part = (Integer) q[1];
            GridWater g = world.water.get(grid);
            int here = depth.get(grid)[part];
            for (GridWater.Opening o : g.openings) {
                if (o.part != part) continue;
                Coord nb = grid.add(GridWater.step(o.edge));
                GridWater ng = world.water.get(nb);
                if (ng == null) continue;
                int[] nd = depth.get(nb);
                for (GridWater.Opening b : ng.openings) {
                    if (b.edge != GridWater.opposite(o.edge) || GridWater.overlap(o, b) == null) continue;
                    if (nd[b.part] > here + 1) {
                        nd[b.part] = here + 1;
                        queue.add(new Object[]{nb, b.part});
                    }
                }
            }
        }
        for (int[] d : depth.values())
            for (int p = 0; p < d.length; p++)
                if (d[p] == Integer.MAX_VALUE) d[p] = MAX_DEPTH;
        return depth;
    }
}
