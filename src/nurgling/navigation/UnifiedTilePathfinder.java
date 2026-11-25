package nurgling.navigation;

import haven.Coord;
import nurgling.tasks.GateDetector;

import java.util.*;

import static nurgling.navigation.ChunkNavConfig.*;

/**
 * A* over an implicit tile graph spanning all recorded chunks: walkable tiles, edge crossings between neighbour
 * chunks, and portals. Works from stored data only, so it plans through chunks that are not loaded.
 *
 * One search can aim at a whole set of goal tiles ({@link AreaGoalSet}) and stops at the cheapest one it reaches,
 * so "go to this area" is one search instead of one per corner, and "which of these areas is nearest" is one search
 * too ({@link #findNearest}).
 *
 * The distance estimate comes from {@link ChunkLayout}: inside the goal's walkable component it is the straight
 * line to the goal tiles' bounding box; elsewhere it is the cheapest chain of portal costs into that component, and
 * components with no portal chain at all are never expanded. It is built once per search, not per step.
 */
public class UnifiedTilePathfinder {
    private static final int TILES = CHUNK_SIZE * CHUNK_SIZE;
    private static final int MAX_POPS = 500_000;
    private static final float SQRT2 = (float) Math.sqrt(2);
    private static final float EDGE_COST = 1.5f;
    // Towing only (see Search.cartCost): an aisle the cart barely fits costs as much as a long way round; a tight spot a little.
    private static final float CART_TOO_NARROW_COST = 15f;
    private static final float CART_TIGHT_COST = 1f;
    private static final float INF = Float.POSITIVE_INFINITY;
    private static final int[][] DIRS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}, {-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

    private final ChunkNavGraph graph;

    // Chunks where portal traversal has failed - avoid using portals on these chunks
    private Set<Long> excludedPortalChunks = Collections.emptySet();

    /** Nodes popped and time taken by the last search on this instance (diagnostics and the A/B harness). */
    public int lastPops;
    public long lastNanos;

    public UnifiedTilePathfinder(ChunkNavGraph graph) {
        this.graph = graph;
    }

    public void setExcludedPortalChunks(Set<Long> excluded) {
        this.excludedPortalChunks = excluded != null ? excluded : Collections.emptySet();
    }

    /** Path from a start tile to one target tile, or null if there is none. */
    public UnifiedPath findPath(long startChunkId, Coord startLocal, long targetChunkId, Coord targetLocal) {
        if (graph.getChunk(startChunkId) == null || graph.getChunk(targetChunkId) == null) {
            lastPops = 0;
            return null;
        }
        return findPathToAny(startChunkId, startLocal, AreaGoalSet.ofTile(targetChunkId, targetLocal));
    }

    /** Path from a start tile to the cheapest tile of the goal set, or null if none is reachable. */
    public UnifiedPath findPathToAny(long startChunkId, Coord startLocal, AreaGoalSet goal) {
        return findNearest(startChunkId, startLocal, Collections.singletonList(goal));
    }

    /**
     * Path to the cheapest tile of any of the goal sets. {@link UnifiedPath#goalIndex} says which set it reached.
     * Null if none is reachable.
     */
    public UnifiedPath findNearest(long startChunkId, Coord startLocal, List<AreaGoalSet> goals) {
        long t0 = System.nanoTime();
        try {
            lastPops = 0;
            if (startLocal == null || goals == null || goals.isEmpty()) return null;
            boolean any = false;
            for (AreaGoalSet g : goals) any |= g != null && !g.isEmpty();
            if (!any) return null;
            if (graph.getChunk(startChunkId) == null) return null;
            Search s = new Search(goals);
            UnifiedPath p = s.run(startChunkId, startLocal);
            lastPops = s.pops;
            return p;
        } finally {
            lastNanos = System.nanoTime() - t0;
        }
    }

    /** One search: layout, heuristic data and per-chunk node arrays, all thrown away afterwards. */
    private final class Search {
        final ChunkLayout layout = new ChunkLayout(graph);
        final List<AreaGoalSet> goals;
        final float[][] g = new float[layout.size()][];
        final int[][] parent = new int[layout.size()][];
        final byte[][] flags = new byte[layout.size()][];   // bit 0 closed, bit 1 reached via portal
        final PortalEdge[][] portals = new PortalEdge[layout.size()][];
        final float[] compLB;                  // lower bound of portal costs from a component into a goal component
        final float[][][] boxes;               // per component: goal bounding boxes {x0, y0, x1, y1} in layout tiles
        final LongHeap heap = new LongHeap();
        int pops = 0;
        // Towing a cart (see CartTow): narrow tiles cost extra, so the route keeps to where the cart fits when it can.
        final boolean cart = nurgling.actions.cart.CartTow.planningWithCart();

        Search(List<AreaGoalSet> goals) {
            this.goals = goals;
            int nc = layout.componentCount;
            List<List<float[]>> perComp = new ArrayList<>(nc);
            for (int i = 0; i < nc; i++) perComp.add(null);
            for (AreaGoalSet set : goals) {
                if (set == null) continue;
                Map<Integer, float[]> box = new HashMap<>();
                for (Map.Entry<Long, BitSet> e : set.tiles.entrySet()) {
                    int ci = layout.indexOf(e.getKey());
                    if (ci < 0) continue;
                    int c = layout.comp[ci];
                    BitSet bits = e.getValue();
                    for (int b = bits.nextSetBit(0); b >= 0; b = bits.nextSetBit(b + 1)) {
                        float px = layout.gx[ci] * CHUNK_SIZE + (b % CHUNK_SIZE);
                        float py = layout.gy[ci] * CHUNK_SIZE + (b / CHUNK_SIZE);
                        float[] bx = box.get(c);
                        if (bx == null) box.put(c, new float[] {px, py, px, py});
                        else {
                            bx[0] = Math.min(bx[0], px); bx[1] = Math.min(bx[1], py);
                            bx[2] = Math.max(bx[2], px); bx[3] = Math.max(bx[3], py);
                        }
                    }
                }
                for (Map.Entry<Integer, float[]> e : box.entrySet()) {
                    List<float[]> l = perComp.get(e.getKey());
                    if (l == null) perComp.set(e.getKey(), l = new ArrayList<>(2));
                    l.add(e.getValue());
                }
            }
            boxes = new float[nc][][];
            for (int i = 0; i < nc; i++) {
                List<float[]> l = perComp.get(i);
                boxes[i] = (l == null) ? null : l.toArray(new float[0][]);
            }
            compLB = portalLowerBounds();
        }

        /** Dijkstra over components, backwards from the goal components, along portal links. */
        private float[] portalLowerBounds() {
            int nc = layout.componentCount;
            float[] lb = new float[nc];
            Arrays.fill(lb, INF);
            List<List<float[]>> back = new ArrayList<>(nc);   // into comp: {fromComp, cost}
            for (int i = 0; i < nc; i++) back.add(new ArrayList<>());
            for (int ci = 0; ci < layout.size(); ci++) {
                ChunkNavData c = layout.chunks[ci];
                for (ChunkPortal p : c.portals) {
                    if (p.connectsToGridId == -1) continue;
                    int di = layout.indexOf(p.connectsToGridId);
                    if (di < 0) continue;
                    int from = layout.comp[ci], to = layout.comp[di];
                    if (from == to) continue;
                    float w = Objects.equals(c.layer, layout.chunks[di].layer) ? EDGE_COST : PORTAL_TRAVERSAL_COST;
                    back.get(to).add(new float[] {from, w});
                }
            }
            PriorityQueue<float[]> q = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));
            for (int i = 0; i < nc; i++) {
                if (boxes[i] != null) {
                    lb[i] = 0;
                    q.add(new float[] {i, 0});
                }
            }
            while (!q.isEmpty()) {
                float[] cur = q.poll();
                int c = (int) cur[0];
                if (cur[1] > lb[c]) continue;
                for (float[] e : back.get(c)) {
                    int from = (int) e[0];
                    float nd = cur[1] + e[1];
                    if (nd < lb[from]) {
                        lb[from] = nd;
                        q.add(new float[] {from, nd});
                    }
                }
            }
            return lb;
        }

        private float heuristic(int ci, int x, int y) {
            int c = layout.comp[ci];
            float[][] bs = boxes[c];
            if (bs == null) return compLB[c];
            if (!layout.geometric[c]) return 0;
            float px = layout.gx[ci] * CHUNK_SIZE + x, py = layout.gy[ci] * CHUNK_SIZE + y;
            float best = INF;
            for (float[] b : bs) {
                float dx = Math.max(0, Math.max(b[0] - px, px - b[2]));
                float dy = Math.max(0, Math.max(b[1] - py, py - b[3]));
                best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
            }
            return best;
        }

        private void ensure(int ci) {
            if (g[ci] == null) {
                float[] gi = new float[TILES];
                Arrays.fill(gi, INF);
                g[ci] = gi;
                parent[ci] = new int[TILES];
                flags[ci] = new byte[TILES];
            }
        }

        private void relax(int ci, int x, int y, float ng, int from, boolean viaPortal) {
            ensure(ci);
            int t = y * CHUNK_SIZE + x;
            if (ng >= g[ci][t]) return;
            float h = heuristic(ci, x, y);
            if (h == INF) return;
            g[ci][t] = ng;
            parent[ci][t] = from;
            flags[ci][t] = (byte) (viaPortal ? 2 : 0);   // also re-opens a closed node that got cheaper
            heap.push(ng + h, ci * TILES + t);
        }

        UnifiedPath run(long startChunkId, Coord startLocal) {
            int si = layout.indexOf(startChunkId);
            if (si < 0) return null;
            if (startLocal.x < 0 || startLocal.x >= CHUNK_SIZE || startLocal.y < 0 || startLocal.y >= CHUNK_SIZE) return null;
            relax(si, startLocal.x, startLocal.y, 0, -1, false);

            while (!heap.isEmpty() && pops < MAX_POPS) {
                int node = heap.popNode();
                int ci = node / TILES, t = node % TILES;
                if ((flags[ci][t] & 1) != 0) continue;   // stale entry
                flags[ci][t] |= 1;
                if ((++pops & 1023) == 0 && Thread.currentThread().isInterrupted()) return null;

                ChunkNavData chunk = layout.chunks[ci];
                int x = t % CHUNK_SIZE, y = t / CHUNK_SIZE;
                for (int k = 0; k < goals.size(); k++) {
                    AreaGoalSet set = goals.get(k);
                    if (set != null && set.contains(chunk.gridId, x, y)) return reconstruct(node, k);
                }

                float gc = g[ci][t];

                for (int[] d : DIRS) {
                    int nx = x + d[0], ny = y + d[1];
                    float step = (d[0] != 0 && d[1] != 0) ? SQRT2 : 1f;
                    if (nx >= 0 && nx < CHUNK_SIZE && ny >= 0 && ny < CHUNK_SIZE) {
                        if (AreaGoalSet.isTileWalkable(chunk, nx, ny)) relax(ci, nx, ny, gc + step + cartCost(chunk, nx, ny), node, false);
                    } else {
                        crossChunk(chunk, nx, ny, gc, node);
                    }
                }
                for (PortalEdge pe : portalsOf(ci)) {
                    float dx = x - pe.px, dy = y - pe.py;
                    if (dx * dx + dy * dy > pe.prox * pe.prox) continue;
                    float cost;
                    if (pe.destIdx == ci) {
                        float ex = x - pe.ex, ey = y - pe.ey;
                        cost = (float) Math.sqrt(ex * ex + ey * ey);
                    } else {
                        cost = Objects.equals(chunk.layer, layout.chunks[pe.destIdx].layer) ? EDGE_COST : PORTAL_TRAVERSAL_COST;
                    }
                    relax(pe.destIdx, pe.ex, pe.ey, gc + cost, node, true);
                }
            }
            return null;
        }

        /** A step off the chunk's edge: into the neighbour on that side, or through a corner into the diagonal one. */
        private void crossChunk(ChunkNavData from, int nx, int ny, float gc, int node) {
            long[] t = crossTile(graph, from, nx, ny);
            if (t == null) return;
            int ni = layout.indexOf(t[0]);
            if (ni < 0) return;
            ChunkNavData to = layout.chunks[ni];
            if (!ChunkLayout.walkable(from, to)) return;
            if (AreaGoalSet.isTileWalkable(to, (int) t[1], (int) t[2])) relax(ni, (int) t[1], (int) t[2], gc + EDGE_COST + cartCost(to, (int) t[1], (int) t[2]), node, false);
        }

        /**
         * 0 unless towing. Then by how much room the tile has for a cart, judged from the cells around its 2x2:
         * blocked on two opposite sides within one cell (an aisle between chest rows) is too narrow
         * ({@link #CART_TOO_NARROW_COST}: a long way round wins, but it is still usable when nothing else is);
         * blocked on one side only, or in the tile itself (a gate post, a wall alongside) is tight
         * ({@link #CART_TIGHT_COST}); nothing blocked around it is open (free).
         */
        private float cartCost(ChunkNavData chunk, int tx, int ty) {
            if (!cart)
                return 0f;
            int cx = tx * ChunkNavConfig.CELLS_PER_TILE, cy = ty * ChunkNavConfig.CELLS_PER_TILE;
            int lo = -1, hi = ChunkNavConfig.CELLS_PER_TILE;
            boolean left = false, right = false, top = false, bottom = false, inside = false;
            for (int d = lo; d <= hi; d++) {
                left |= blocked(chunk, cx + lo, cy + d);
                right |= blocked(chunk, cx + hi, cy + d);
                top |= blocked(chunk, cx + d, cy + lo);
                bottom |= blocked(chunk, cx + d, cy + hi);
            }
            for (int x = 0; x < ChunkNavConfig.CELLS_PER_TILE; x++)
                for (int y = 0; y < ChunkNavConfig.CELLS_PER_TILE; y++)
                    inside |= blocked(chunk, cx + x, cy + y);
            if ((left && right) || (top && bottom))
                return CART_TOO_NARROW_COST;
            if (left || right || top || bottom || inside)
                return CART_TIGHT_COST;
            return 0f;
        }

        /** A cell that isn't plain walkable ground; cells off this chunk's edge count as open. */
        private boolean blocked(ChunkNavData chunk, int x, int y) {
            if (x < 0 || y < 0 || x >= ChunkNavConfig.CELLS_PER_EDGE || y >= ChunkNavConfig.CELLS_PER_EDGE)
                return false;
            return chunk.walkability[x][y] != 0;
        }

        private PortalEdge[] portalsOf(int ci) {
            PortalEdge[] res = portals[ci];
            if (res != null) return res;
            ChunkNavData chunk = layout.chunks[ci];
            List<PortalEdge> list = new ArrayList<>();
            if (excludedPortalChunks.isEmpty() || !excludedPortalChunks.contains(chunk.gridId)) {
                for (ChunkPortal portal : chunk.portals) {
                    if (portal.localCoord == null || portal.connectsToGridId == -1) continue;
                    int di = layout.indexOf(portal.connectsToGridId);
                    if (di < 0) continue;
                    ChunkNavData dest = layout.chunks[di];
                    if (!isPortalTargetLayerValid(portal.type, dest.layer)) continue;
                    Coord exit = findPortalExitCoord(dest, portal, chunk.gridId);
                    if (exit == null || exit.x < 0 || exit.x >= CHUNK_SIZE || exit.y < 0 || exit.y >= CHUNK_SIZE) continue;
                    int prox = ChunkPortal.isBuildingExterior(portal.gobName) ? 6 : 2;
                    list.add(new PortalEdge(portal.localCoord.x, portal.localCoord.y, prox, di, exit.x, exit.y));
                }
            }
            return portals[ci] = list.toArray(new PortalEdge[0]);
        }

        private UnifiedPath reconstruct(int goalNode, int goalIndex) {
            UnifiedPath path = new UnifiedPath();
            path.reachable = true;
            path.goalIndex = goalIndex;
            List<TileNode> rev = new ArrayList<>();
            int node = goalNode;
            while (node != -1) {
                int ci = node / TILES, t = node % TILES;
                rev.add(new TileNode(layout.chunks[ci].gridId, new Coord(t % CHUNK_SIZE, t / CHUNK_SIZE), (flags[ci][t] & 2) != 0));
                node = parent[ci][t];
            }
            Collections.reverse(rev);
            path.steps.addAll(rev);
            path.cost = g[goalNode / TILES][goalNode % TILES];
            path.pops = pops;
            return path;
        }
    }

    /**
     * (gridId, x, y) of tile (nx, ny) just outside a chunk, through its neighbour links; null if that neighbour is
     * not known. A corner step reaches the diagonal chunk through either side neighbour. (Before 2026-11 a corner
     * step with no horizontal neighbour fell back to the vertical one at the wrong x: a 99-tile jump for 1.5.)
     */
    static long[] crossTile(ChunkNavGraph graph, ChunkNavData from, int nx, int ny) {
        int sx = nx < 0 ? -1 : nx >= CHUNK_SIZE ? 1 : 0;
        int sy = ny < 0 ? -1 : ny >= CHUNK_SIZE ? 1 : 0;
        if (sx == 0 && sy == 0) return null;
        int x = sx == 0 ? nx : (sx < 0 ? nx + CHUNK_SIZE : nx - CHUNK_SIZE);
        int y = sy == 0 ? ny : (sy < 0 ? ny + CHUNK_SIZE : ny - CHUNK_SIZE);
        long id;
        if (sx != 0 && sy != 0) {
            id = vertical(graph.getChunk(horizontal(from, sx)), sy);
            if (id == -1) id = horizontal(graph.getChunk(vertical(from, sy)), sx);
        } else if (sx != 0) {
            id = horizontal(from, sx);
        } else {
            id = vertical(from, sy);
        }
        return id == -1 ? null : new long[] {id, x, y};
    }

    private static long horizontal(ChunkNavData c, int sx) {
        return c == null ? -1 : (sx < 0 ? c.neighborWest : c.neighborEast);
    }

    private static long vertical(ChunkNavData c, int sy) {
        return c == null ? -1 : (sy < 0 ? c.neighborNorth : c.neighborSouth);
    }

    private static final class PortalEdge {
        final int px, py, prox, destIdx, ex, ey;
        PortalEdge(int px, int py, int prox, int destIdx, int ex, int ey) {
            this.px = px; this.py = py; this.prox = prox; this.destIdx = destIdx; this.ex = ex; this.ey = ey;
        }
    }

    /** Binary min-heap of (f, node) packed into longs; f >= 0, so its float bits sort like the float. */
    private static final class LongHeap {
        private long[] a = new long[1024];
        private int n = 0;

        boolean isEmpty() { return n == 0; }

        void push(float f, int node) {
            long key = ((long) Float.floatToRawIntBits(f) << 32) | (node & 0xffffffffL);
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (a[p] <= key) break;
                a[i] = a[p];
                i = p;
            }
            a[i] = key;
        }

        int popNode() {
            long top = a[0];
            long last = a[--n];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= n) break;
                int r = l + 1;
                int c = (r < n && a[r] < a[l]) ? r : l;
                if (a[c] >= last) break;
                a[i] = a[c];
                i = c;
            }
            if (n > 0) a[i] = last;
            return (int) top;
        }
    }

    /**
     * Find a walkable cell within a tile and return its world coordinate.
     * This targets the center of a specific walkable cell rather than the tile center,
     * which prevents pathfinding to blocked areas when only part of a tile is walkable.
     *
     * The method picks the walkable cell that is furthest from any blocked cells
     * to avoid edge cases where rounding puts the coordinate in blocked territory.
     *
     * @param chunk The chunk data
     * @param tileX Tile X coordinate (0-99)
     * @param tileY Tile Y coordinate (0-99)
     * @param worldTileOrigin The chunk's world tile origin
     * @return World coordinate of a walkable cell's center, or tile center if no cell info
     */
    public static haven.Coord2d findWalkableCellWorldCoord(ChunkNavData chunk, int tileX, int tileY, haven.Coord worldTileOrigin) {
        if (chunk == null || worldTileOrigin == null) {
            // Fallback to tile center
            haven.Coord worldTile = worldTileOrigin != null ? worldTileOrigin.add(tileX, tileY) : new haven.Coord(tileX, tileY);
            return worldTile.mul(haven.MCache.tilesz).add(haven.MCache.tilehsz);
        }

        int cellX = tileX * CELLS_PER_TILE;
        int cellY = tileY * CELLS_PER_TILE;

        // Find the walkable cell with the most distance from blocked cells
        // This avoids edge cases where the target is right at a blocked boundary
        int bestCx = -1, bestCy = -1;
        int bestScore = -1;

        int[][] cellOffsets = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};

        for (int[] offset : cellOffsets) {
            int cx = cellX + offset[0];
            int cy = cellY + offset[1];
            if (cx >= 0 && cx < CELLS_PER_EDGE && cy >= 0 && cy < CELLS_PER_EDGE) {
                if (chunk.walkability[cx][cy] == 0) {
                    // Count how many of the 8 neighbors are also walkable (higher = safer)
                    int score = 0;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (dx == 0 && dy == 0) continue;
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx >= 0 && nx < CELLS_PER_EDGE && ny >= 0 && ny < CELLS_PER_EDGE) {
                                if (chunk.walkability[nx][ny] == 0) {
                                    score++;
                                }
                            }
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestCx = cx;
                        bestCy = cy;
                    }
                }
            }
        }

        if (bestCx >= 0 && bestCy >= 0) {
            // Found a walkable cell - compute its world coordinate (center of the subcell)
            double worldX = worldTileOrigin.x * haven.MCache.tilesz.x + bestCx * haven.MCache.tilehsz.x + haven.MCache.tilehsz.x / 2;
            double worldY = worldTileOrigin.y * haven.MCache.tilesz.y + bestCy * haven.MCache.tilehsz.y + haven.MCache.tilehsz.y / 2;
            return new haven.Coord2d(worldX, worldY);
        }

        // No walkable cell found - fallback to tile center
        haven.Coord worldTile = worldTileOrigin.add(tileX, tileY);
        return worldTile.mul(haven.MCache.tilesz).add(haven.MCache.tilehsz);
    }


    private Coord findPortalExitCoord(ChunkNavData destChunk, ChunkPortal entryPortal, long sourceChunkId) {
        // BEST: Use stored exit coordinate if available (recorded during traversal)
        // This handles all cases including multiple identical portals (e.g., 2 mineholes to same mine)
        if (entryPortal.exitLocalCoord != null) {
            Coord walkable = findWalkableTileNear(destChunk, entryPortal.exitLocalCoord);
            return walkable != null ? walkable : entryPortal.exitLocalCoord;
        }

        // FALLBACK: Search for matching portal in destination chunk
        // Requires: matching portal type AND connects back to our source chunk
        Coord bestExitCoord = null;

        for (ChunkPortal portal : destChunk.portals) {
            if (portal.localCoord == null) continue;

            // Must connect back to the chunk we came from
            if (portal.connectsToGridId != sourceChunkId) continue;

            // Must be a matching portal pair (e.g., stonestead-door <-> stonestead)
            if (isMatchingPortalPair(entryPortal.gobName, portal.gobName)) {
                Coord walkable = findWalkableTileNear(destChunk, portal.localCoord);
                if (walkable != null) {
                    return walkable;
                }
                // Even if no walkable nearby, this is our exit
                return portal.localCoord;
            }

            // Same gob type is also valid (bidirectional portals like ladders)
            if (entryPortal.gobName != null && entryPortal.gobName.equals(portal.gobName)) {
                bestExitCoord = portal.localCoord;
            }
        }

        // Use best match if found
        if (bestExitCoord != null) {
            Coord walkable = findWalkableTileNear(destChunk, bestExitCoord);
            return walkable != null ? walkable : bestExitCoord;
        }

        // Fallback: center of chunk (shouldn't happen if data is recorded correctly)
        return new Coord(CHUNK_SIZE / 2, CHUNK_SIZE / 2);
    }

    /**
     * Check if two portal types are a matching pair (entry/exit).
     * Uses GateDetector.getDoorPair() for consistent door pair matching.
     */
    private boolean isMatchingPortalPair(String entryGobName, String exitGobName) {
        if (entryGobName == null || exitGobName == null) return false;

        // Use GateDetector's door pair lookup (same as routes system)
        String expectedPair = GateDetector.getDoorPair(entryGobName);
        if (GateDetector.isSameDoor(exitGobName, expectedPair)) {
            return true;
        }

        // Same type (some portals are bidirectional with same gob)
        return entryGobName.equals(exitGobName);
    }

    /**
     * Find a walkable tile near the given coordinate.
     * Coordinates are in tile space (0-99).
     */
    private Coord findWalkableTileNear(ChunkNavData chunk, Coord target) {
        // Check the target itself first
        if (target.x >= 0 && target.x < CHUNK_SIZE &&
            target.y >= 0 && target.y < CHUNK_SIZE &&
            AreaGoalSet.isTileWalkable(chunk, target.x, target.y)) {
            return target;
        }

        // Search in expanding rings around the target
        int[][] dirs = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int radius = 1; radius <= 3; radius++) {
            for (int[] dir : dirs) {
                int nx = target.x + dir[0] * radius;
                int ny = target.y + dir[1] * radius;
                if (nx >= 0 && nx < CHUNK_SIZE && ny >= 0 && ny < CHUNK_SIZE) {
                    if (AreaGoalSet.isTileWalkable(chunk, nx, ny)) {
                        return new Coord(nx, ny);
                    }
                }
            }
        }

        return null;
    }

    /**
     * A tile in the unified graph.
     */
    public static class TileNode {
        public final long chunkId;
        public final Coord localCoord;
        public final boolean viaPortal;  // True if reached via portal (door/gate), false for edge crossing

        public TileNode(long chunkId, Coord localCoord) {
            this(chunkId, localCoord, false);
        }

        public TileNode(long chunkId, Coord localCoord, boolean viaPortal) {
            this.chunkId = chunkId;
            this.localCoord = localCoord;
            this.viaPortal = viaPortal;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TileNode tileNode = (TileNode) o;
            return chunkId == tileNode.chunkId && localCoord.equals(tileNode.localCoord);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkId, localCoord);
        }

        @Override
        public String toString() {
            return "Tile[chunk=" + chunkId + ", local=" + localCoord + "]";
        }
    }

    /**
     * Result of unified pathfinding.
     */
    public static class UnifiedPath {
        public boolean reachable = false;
        public List<TileNode> steps = new ArrayList<>();
        public double cost = 0;
        /** Which goal set the path reached ({@link UnifiedTilePathfinder#findNearest}); 0 for a single goal. */
        public int goalIndex = -1;
        /** Nodes the search popped to find it. */
        public int pops = 0;

        public boolean isEmpty() {
            return steps.isEmpty();
        }

        public int size() {
            return steps.size();
        }

        /**
         * Convert this path to ChunkPath segments for the executor.
         */
        public void populateChunkPath(ChunkPath chunkPath, ChunkNavGraph graph) {
            if (steps.isEmpty()) return;

            long currentChunkId = steps.get(0).chunkId;
            ChunkNavData currentChunk = graph.getChunk(currentChunkId);
            ChunkPath.PathSegment currentSegment = null;

            if (currentChunk != null) {
                currentSegment = new ChunkPath.PathSegment(currentChunkId, currentChunk.worldTileOrigin);
            }

            for (TileNode step : steps) {
                if (step.chunkId != currentChunkId) {
                    // Chunk changed - finish current segment and start new one
                    if (currentSegment != null && !currentSegment.isEmpty()) {
                        // Only mark as PORTAL if this step was reached via an actual portal (door/gate/mine)
                        // Edge crossings between neighbor chunks are just WALK
                        currentSegment.type = step.viaPortal ? ChunkPath.SegmentType.PORTAL : ChunkPath.SegmentType.WALK;
                        chunkPath.segments.add(currentSegment);
                    }

                    currentChunkId = step.chunkId;
                    currentChunk = graph.getChunk(currentChunkId);
                    if (currentChunk != null) {
                        currentSegment = new ChunkPath.PathSegment(currentChunkId, currentChunk.worldTileOrigin);
                    } else {
                        currentSegment = null;
                    }
                }

                if (currentSegment != null) {
                    // Use cell-level coordinate to target a walkable cell within the tile
                    // This prevents targeting blocked parts of partially-walkable tiles
                    haven.Coord2d cellWorldCoord = findWalkableCellWorldCoord(
                        currentChunk, step.localCoord.x, step.localCoord.y, currentChunk.worldTileOrigin);
                    currentSegment.steps.add(new ChunkPath.TileStep(step.localCoord, cellWorldCoord));
                }
            }

            // Add final segment
            if (currentSegment != null && !currentSegment.isEmpty()) {
                currentSegment.type = ChunkPath.SegmentType.WALK;
                chunkPath.segments.add(currentSegment);
            }

            chunkPath.totalCost = (float) cost;
        }
    }

    /**
     * Validate that a portal type is compatible with the target chunk's layer.
     */
    private boolean isPortalTargetLayerValid(ChunkPortal.PortalType type, String targetLayer) {
        if (type == null || targetLayer == null) return true;
        switch (type) {
            case MINEHOLE:
            case MINE_ENTRANCE:
                return "outside".equals(targetLayer);
            case CELLAR:
                return "cellar".equals(targetLayer) || "inside".equals(targetLayer);
            case LADDER:
            case CAVEIN:
            case CAVEOUT:
                return "outside".equals(targetLayer);
            case STAIRS_UP:
            case STAIRS_DOWN:
                return "inside".equals(targetLayer);
            case DOOR:
                return "inside".equals(targetLayer) || "outside".equals(targetLayer);
            default:
                return true;
        }
    }
}
