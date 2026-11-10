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
 *  <li>a coast target is worth only the unknown neighbours the coast goes on into (land, shallows or a river at the side
 *  toward them, GridWater.shoreToward): unknown open sea next to a coast is worth nothing, so the boat follows the landmass
 *  round instead of hopping between coasts that only border unknown ocean;</li>
 *  <li>tier 1 is targets entered through fresh water, worth RIVER_WEIGHT times a coast target (tier 0:
 *  sea with shallows by land), the farther upstream the more; distance weighs squared, so the boat finishes the rivers and
 *  coast at hand before sailing far, even for a river. Open sea (tier -1) is a target only when neither is reachable
 *  (Ask.openSea: before any coast is found), so the boat heads out, straight on (the heading nudge), until one comes into
 *  view;</li>
 *  <li>the held target stays unless another scores HOLD_MARGIN times more; a chosen direction (Ask.bias) and other bots'
 *  claims (Ask.claimed) weigh the score.</li>
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
    /** Arriving in a grid: this many tiles past the crossing (the leg ends once in, and the client records the grids round it). */
    static final int ENTER_TILES = 3;
    /** A river or lake grid is worth this many times a coast grid (before the upstream bonus). */
    static final double RIVER_WEIGHT = 3;
    /** The held target stays unless another scores this many times more. */
    static final double HOLD_MARGIN = 1.5;
    /** How much the chosen direction weighs: +-50% for a target straight that way or straight the other way. */
    static final double BIAS_WEIGHT = 0.5;
    /** A target within CLAIM_REACH grids of another bot's target is worth this much: they spread out. */
    static final double CLAIM_FACTOR = 0.15;
    static final int CLAIM_REACH = 2;
    static final double DEPTH_WEIGHT = 0.25;
    static final int MAX_DEPTH = 8;
    /** Cost is counted in grids of travel, and weighs squared: gain / (1 + cost / COST_SCALE)^2. Squared, the nearest
     *  unexplored water wins unless a farther one reveals far more - replayed on a real map, that sailed 17-25% less for the
     *  same grids than gain / (1 + cost / COST_SCALE), which left nearby water behind for a bigger gain farther off and came
     *  back for it later. */
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
        /** 1 river or lake, 0 coast, -1 open sea. */
        public final int tier;
        public final int depth;
        /** Crossings from the player's grid into the target's; empty when the target is the player's own grid. */
        public final List<Hop> route;
        /** Where in the target grid the boat arrives, a few tiles past the crossing (grid-local cell), or null. */
        public Coord arrival;
        /** The best few candidates as "grid score gain cost tier", for the console: why this one. */
        public String candidates = "";

        Choice(Coord grid, int gain, double cost, double seaCost, int tier, int depth, List<Hop> route) {
            this.grid = grid;
            this.gain = gain;
            this.cost = cost;
            this.seaCost = seaCost;
            this.tier = tier;
            this.fresh = tier == 1;
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
        /** The direction the user asked for (any length), or null. */
        public Coord bias;
        /** Other bots' targets. */
        public Set<Coord> claimed = Collections.emptySet();
        /** Whether open sea may be a target at all (when nothing else is in reach). */
        public boolean openSea = true;
        /** Grids past the world's edge: never known, so neither a gain nor a target. */
        public java.util.function.Predicate<Coord> beyond = c -> false;
    }

    public static String link(Coord from, Coord to) {
        return from.x + "," + from.y + ">" + to.x + "," + to.y;
    }

    public static boolean inLeash(Coord c, Coord center, int leashGrids) {
        if (leashGrids <= 0 || center == null) return true;
        return Math.max(Math.abs(c.x - center.x), Math.abs(c.y - center.y)) <= leashGrids;
    }

    public static int gain(Set<Coord> known, Coord c, Coord leashCenter, int leashGrids) {
        return gain(known, c, leashCenter, leashGrids, x -> false);
    }

    static int gain(Set<Coord> known, Coord c, Coord leashCenter, int leashGrids, java.util.function.Predicate<Coord> beyond) {
        int n = 0;
        for (Coord d : NEIGHBOURS) {
            Coord nb = c.add(d);
            if (!known.contains(nb) && inLeash(nb, leashCenter, leashGrids) && !beyond.test(nb)) n++;
        }
        return n;
    }

    /** What going to c is worth: the unknown grids beside it (through a side - water crosses into a grid through a side) that
     *  c's own water, water the boat can get to, runs into - and for a coast target, that the coast runs into (coastInto).
     *  Entering c reveals all its neighbours, but only these are worth the trip: the boat goes where its water leads into the
     *  unknown, and follows it. A grid whose water leads nowhere new - a dead-end inlet, a lake that doesn't reach its west
     *  side - is worth nothing however much unknown is round it: what lies there is land, or water the boat reaches (and
     *  reveals) some other way anyway. */
    static int waterGain(World world, Coord c, Ask ask, boolean coast, java.util.function.BiPredicate<Coord, Integer> reached) {
        GridWater g = world.water.get(c);
        if (g == null) return 0;
        int n = 0;
        for (int edge = 0; edge < 4; edge++) {
            Coord nb = c.add(GridWater.step(edge));
            if (world.known.contains(nb) || !inLeash(nb, ask.leashCenter, ask.leashGrids) || ask.beyond.test(nb)) continue;
            if (coast && !coastInto(world, nb)) continue;
            for (int i = 0; i < g.openings.size(); i++) {
                if (g.openings.get(i).edge == edge && reached.test(c, i)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** Whether the coast runs into unknown grid u: some known grid next to it has land, shallows or a river on its side toward
     *  u - not only the grid the boat can get to (where the coast turns through grids that are nearly all land, the boat can't
     *  reach their middle, but the coast still goes on past them). */
    static boolean coastInto(World world, Coord u) {
        for (Coord d : NEIGHBOURS) {
            Coord kc = u.sub(d);
            GridWater k = world.water.get(kc);
            if (k != null && (k.landToward(d) || (k.shoreToward(d) && landNear(world, kc)))) return true;
        }
        return false;
    }

    static boolean nearClaim(Set<Coord> claimed, Coord c) {
        for (Coord o : claimed)
            if (Math.max(Math.abs(o.x - c.x), Math.abs(o.y - c.y)) <= CLAIM_REACH) return true;
        return false;
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

        // Cheapest way into each other grid: cost, tier, depth, sea part; the node it was entered by, and where (a few tiles in).
        // Entering a grid is all it takes: the client records the 3x3 block round the grid the player is in.
        Map<Coord, double[]> arrive = new HashMap<>();
        Map<Coord, Node> arriveNode = new HashMap<>();
        Map<Coord, Coord> arriveCell = new HashMap<>();

        while (!open.isEmpty()) {
            Node cur = open.poll();
            if (cur.done) continue;
            cur.done = true;
            GridWater g = world.water.get(cur.grid);
            GridWater.Opening o = g.openings.get(cur.opening);
            int part = o.part;
            // Entered this grid here: arrived, ENTER_TILES in.
            if (cur.entry != null) {
                double c = cur.cost + ENTER_TILES;
                double[] best = arrive.get(cur.grid);
                if (best == null || c < best[0]) {
                    arrive.put(cur.grid, new double[]{c, tier(world, cur.grid, g, part), depthOf(depth, cur.grid, part),
                            cur.sea + (seaOnly(g, part) ? ENTER_TILES : 0)});
                    arriveNode.put(cur.grid, cur);
                    Coord in = GridWater.step(o.edge).mul(-2 * ENTER_TILES);
                    arriveCell.put(cur.grid, cur.entry.add(in));
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

        // Openings the search got to: the water the boat can reach.
        java.util.function.BiPredicate<Coord, Integer> reached = (grid, o) -> {
            Node n = nodes.get(grid.x + "," + grid.y + "#" + o);
            return n != null && !Double.isInfinite(n.cost);
        };
        // Score the arrivals. Rivers and lakes weigh RIVER_WEIGHT times a coast, distance still counting: a river far off
        // doesn't pull the boat away from the coast and rivers at hand. Open sea only when neither is in reach at all.
        Coord best = null, held = null;
        double bestScore = 0, heldScore = 0;
        List<Object[]> scored = new ArrayList<>();
        int bestTier = -2;
        for (Map.Entry<Coord, double[]> e : arrive.entrySet()) {
            Coord c = e.getKey();
            if (ask.visited.contains(c) || ask.excluded.contains(c) || !inLeash(c, ask.leashCenter, ask.leashGrids) || ask.beyond.test(c)) continue;
            int tier = (int) e.getValue()[1];
            if (tier < 0 && !ask.openSea) continue;
            int gain = waterGain(world, c, ask, tier == 0, reached);
            if (gain == 0) continue;
            double cost = e.getValue()[0];
            int dep = (int) e.getValue()[2];
            double near = 1 + cost / COST_SCALE;
            double score = gain / (near * near)
                    * (tier == 1 ? RIVER_WEIGHT * (1 + DEPTH_WEIGHT * Math.min(dep, MAX_DEPTH)) : 1)
                    * (1 + HEADING_WEIGHT * alignment(ask.heading, c.sub(ask.player)))
                    * (1 + BIAS_WEIGHT * alignment(ask.bias, c.sub(ask.player)))
                    * (nearClaim(ask.claimed, c) ? CLAIM_FACTOR : 1);
            scored.add(new Object[]{c, (tier < 0) ? score / 1000 : score, gain, cost, tier});
            int rank = (tier < 0) ? -1 : 0;
            int bestRank = (bestTier < 0) ? -1 : 0;
            if (best == null || rank > bestRank || (rank == bestRank && score > bestScore + 1e-12)) {
                best = c;
                bestScore = score;
                bestTier = tier;
            }
            if (c.equals(ask.hold) && !nearClaim(ask.claimed, c)) {
                held = c;
                heldScore = (tier < 0) ? -1 : score;
            }
        }
        // The target being sailed for is kept unless another is clearly better: no flipping between two of about equal worth.
        if (held != null && (bestTier < 0 || heldScore * HOLD_MARGIN >= bestScore))
            best = held;
        if (best == null) return null;
        double[] a = arrive.get(best);
        int bestGain = waterGain(world, best, ask, (int) a[1] == 0, reached);
        Choice res = new Choice(best, bestGain, a[0], a[3], (int) a[1], (int) a[2], route(arriveNode.get(best)));
        res.arrival = arriveCell.get(best);
        scored.sort((x, y) -> Double.compare((Double) y[1], (Double) x[1]));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, scored.size()); i++) {
            Object[] o = scored.get(i);
            sb.append(String.format("%s%s score %.3f gain %d cost %.0f %s", (i > 0) ? " | " : "", o[0], (Double) o[1], (Integer) o[2],
                    (Double) o[3], ((Integer) o[4] == 1) ? "river" : ((Integer) o[4] == 0) ? "coast" : "open sea"));
        }
        res.candidates = sb.toString();
        return res;
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

    /** 1: a river or lake (whatever sea it joins); 0: a coast; -1: open sea, a target only when nothing else is in reach. */
    static int tier(World world, Coord c, GridWater g, int part) {
        if (g.fresh(part)) return 1;
        return (g.coast(part) && landNear(world, c)) ? 0 : -1;
    }

    /** Land in the grid or one next to it: shallows there are a coast's, not a bank out at sea. */
    static boolean landNear(World world, Coord c) {
        GridWater g = world.water.get(c);
        if (g != null && g.hasLand()) return true;
        for (Coord d : NEIGHBOURS) {
            GridWater n = world.water.get(c.add(d));
            if (n != null && n.hasLand()) return true;
        }
        return false;
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
