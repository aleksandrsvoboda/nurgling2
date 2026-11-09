package nurgling.actions.bots.riverexplorer;

import java.util.Arrays;

/** Dijkstra / A* over a w x h cell lattice, 8-connected, never cutting a corner between two impassable cells. Costs are in tiles.
 *  Primitive arrays and a hand-rolled heap: a 600 x 600 window is searched many times a run. */
final class CellSearch {
    private CellSearch() {}

    interface Graph {
        /** Whether the search may stand on cell i. */
        boolean passable(int i);

        /** Cost of stepping onto cell `to` (from a neighbour), for a step `len` tiles long. */
        double step(int to, double len);
    }

    interface Goal {
        boolean reached(int i);

        /** Lower bound of the cost from i to the goal, 0 for plain Dijkstra. */
        double estimate(int i);
    }

    static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};
    static final double ORTH = WaterCells.CELL / WaterCells.TILE, DIAG = ORTH * Math.sqrt(2);

    /** Result of a search: the cost to every settled cell (POSITIVE_INFINITY elsewhere), each cell's parent, and the goal reached. */
    static final class Result {
        final double[] cost;
        final int[] parent;
        final int goal;

        Result(double[] cost, int[] parent, int goal) {
            this.cost = cost;
            this.parent = parent;
            this.goal = goal;
        }

        /** Cells from a source to the goal (or to `end`), source first; null when it wasn't reached. */
        int[] path(int end) {
            if (end < 0 || Double.isInfinite(cost[end])) return null;
            int n = 1;
            for (int c = end; parent[c] != c; c = parent[c]) n++;
            int[] res = new int[n];
            for (int c = end, k = n - 1; k >= 0; c = parent[c], k--)
                res[k] = c;
            return res;
        }
    }

    /**
     * @param sources  starting cells (never tested for passable)
     * @param startCost their starting costs, or null for all 0
     * @param goal     stops at the first goal cell settled; null searches everything reachable
     */
    static Result run(int w, int h, Graph g, int[] sources, double[] startCost, Goal goal) {
        int n = w * h;
        double[] cost = new double[n];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        int[] parent = new int[n];
        boolean[] done = new boolean[n];
        Heap open = new Heap();
        for (int k = 0; k < sources.length; k++) {
            int s = sources[k];
            double c = (startCost == null) ? 0 : startCost[k];
            if (c < cost[s]) {
                cost[s] = c;
                parent[s] = s;
                open.push(s, c + (goal == null ? 0 : goal.estimate(s)));
            }
        }
        // Cells tested once each: 0 untested, 1 passable, 2 not.
        byte[] pass = new byte[n];
        while (open.size > 0) {
            int cur = open.pop();
            if (done[cur]) continue;
            done[cur] = true;
            if (goal != null && goal.reached(cur))
                return new Result(cost, parent, cur);
            int x = cur % w, y = cur / w;
            for (int k = 0; k < 8; k++) {
                int nx = x + DX[k], ny = y + DY[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                int ni = ny * w + nx;
                if (done[ni] || !ok(g, pass, ni)) continue;
                if (k >= 4 && (!ok(g, pass, y * w + nx) || !ok(g, pass, ny * w + x))) continue;
                double nc = cost[cur] + g.step(ni, k >= 4 ? DIAG : ORTH);
                if (nc < cost[ni]) {
                    cost[ni] = nc;
                    parent[ni] = cur;
                    open.push(ni, nc + (goal == null ? 0 : goal.estimate(ni)));
                }
            }
        }
        return new Result(cost, parent, -1);
    }

    private static boolean ok(Graph g, byte[] pass, int i) {
        if (pass[i] == 0) pass[i] = g.passable(i) ? (byte) 1 : (byte) 2;
        return pass[i] == 1;
    }

    /** Octile distance in tiles between two cells. */
    static double octile(int dx, int dy) {
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        return ORTH * Math.max(dx, dy) + (DIAG - ORTH) * Math.min(dx, dy);
    }

    /** Binary min-heap of (cell, key). */
    private static final class Heap {
        int[] cell = new int[1024];
        double[] key = new double[1024];
        int size = 0;

        void push(int c, double k) {
            if (size == cell.length) {
                cell = Arrays.copyOf(cell, size * 2);
                key = Arrays.copyOf(key, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (key[p] <= k) break;
                cell[i] = cell[p];
                key[i] = key[p];
                i = p;
            }
            cell[i] = c;
            key[i] = k;
        }

        int pop() {
            int top = cell[0];
            int lc = cell[--size];
            double lk = key[size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int r = l + 1;
                int m = (r < size && key[r] < key[l]) ? r : l;
                if (key[m] >= lk) break;
                cell[i] = cell[m];
                key[i] = key[m];
                i = m;
            }
            cell[i] = lc;
            key[i] = lk;
            return top;
        }
    }
}
