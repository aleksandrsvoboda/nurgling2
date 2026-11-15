package nurgling.navigation.legacy;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.PathFinder;
import nurgling.areas.NArea;
import nurgling.navigation.ChunkNavData;
import nurgling.navigation.ChunkNavGraph;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.sessions.ThreadLocalUI;

import java.util.Map;

import static nurgling.navigation.ChunkNavConfig.*;

/**
 * TEMPORARY, A/B testing only (NavigationABTest and the offline harness): the "nearest of four corners" area
 * navigation exactly as it was before the edge goal-set planner replaced it. Delete this package once the A/B
 * comparison is done. Nothing in production code may call it.
 */
public final class LegacyAreaNav {
    private LegacyAreaNav() {}

    // ---------------------------------------------------------------- old NUtils.navigateToArea(area, ensurePresence)

    public static boolean navigateToArea(NArea area, boolean ensurePresence) throws InterruptedException {
        if (area == null) return false;

        if (isAreaReachableByLocalPF(area)) {
            if (ensurePresence) {
                Gob player = NUtils.player();
                if (player != null && !area.checkHit(player.rc)) {
                    Coord2d corner = findNearestReachableCorner(area);
                    if (corner != null) {
                        new PathFinder(corner).run(NUtils.getGameUI());
                    }
                }
            }
            return true;
        }

        NGameUI currentGui = NUtils.getGameUI();
        if (currentGui == null || currentGui.map == null) return false;

        ChunkNavManager chunkNav = ((NMapView) currentGui.map).getChunkNavManager();
        if (chunkNav != null && chunkNav.isInitialized()) {
            ChunkPath bestPath = findShortestPathToAreaCorners(area, chunkNav);
            if (bestPath != null) {
                return chunkNav.navigateWithPath(bestPath, area, currentGui).IsSuccess();
            }
        }
        return false;
    }

    /** Old NContext.getDistanceToArea: cost of the shortest corner path, MAX_VALUE when unreachable. */
    public static double distanceToArea(NArea area, NGameUI gui) throws InterruptedException {
        if (gui == null || !(gui.map instanceof NMapView)) return Double.MAX_VALUE;
        ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
        if (chunkNav == null || !chunkNav.isInitialized()) return Double.MAX_VALUE;
        if (!chunkNav.isAreaReachableByChunks(area)) return Double.MAX_VALUE;
        ChunkPath path = findShortestPathToAreaCorners(area, chunkNav);
        return path != null ? path.totalCost : Double.MAX_VALUE;
    }

    // ---------------------------------------------------------------- old AreaNavigationHelper

    public static ChunkPath findShortestPathToAreaCorners(NArea area, ChunkNavManager chunkNav) throws InterruptedException {
        if (area == null || area.space == null || area.space.space == null || area.space.space.isEmpty()) {
            return legacyPlanToArea(chunkNav, area);
        }
        final NUI boundUI = NUtils.getUI();
        final ChunkPath[] paths = new ChunkPath[4];
        Thread[] threads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                ThreadLocalUI.set(boundUI);
                try {
                    // old ChunkNavManager.planToAreaCorner: record visible grids, then plan this corner
                    if (chunkNav.isEnabled() && chunkNav.isInitialized()) {
                        chunkNav.forceRecordVisibleGrids();
                        PlayerLoc loc = playerLocation();
                        if (loc != null)
                            paths[idx] = planToAreaCorner(chunkNav.getGraph(), new LegacyUnifiedTilePathfinder(chunkNav.getGraph()),
                                    area.space.space, idx, loc.gridId, loc.local);
                    }
                } finally {
                    ThreadLocalUI.clear();
                }
            });
            threads[i].start();
        }
        try {
            for (Thread t : threads) t.join();
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            throw e;
        }
        ChunkPath bestPath = null;
        float bestCost = Float.MAX_VALUE;
        for (ChunkPath path : paths) {
            if (path != null && path.totalCost < bestCost) {
                bestPath = path;
                bestCost = path.totalCost;
            }
        }
        if (bestPath == null) return legacyPlanToArea(chunkNav, area);
        return bestPath;
    }

    /** The old fallback (planToArea with entry truncation) is approximated by the current planToArea. */
    private static ChunkPath legacyPlanToArea(ChunkNavManager chunkNav, NArea area) {
        return chunkNav.planToArea(area);
    }

    public static boolean isAreaReachableByLocalPF(NArea area) throws InterruptedException {
        if (area == null || NUtils.player() == null) return false;
        if (!area.isVisible()) return false;
        Pair<Coord2d, Coord2d> rcArea = area.getRCArea();
        if (rcArea == null) return false;
        Coord2d[] corners = corners(rcArea);
        final NUI boundUI = NUtils.getUI();
        final boolean[] reachable = new boolean[4];
        Thread[] threads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            final Coord2d corner = corners[i];
            threads[i] = new Thread(() -> {
                ThreadLocalUI.set(boundUI);
                try {
                    reachable[idx] = PathFinder.isAvailable(corner);
                } catch (InterruptedException e) {
                    reachable[idx] = false;
                } finally {
                    ThreadLocalUI.clear();
                }
            });
            threads[i].start();
        }
        try {
            for (Thread t : threads) t.join();
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            throw e;
        }
        for (boolean r : reachable) if (r) return true;
        return false;
    }

    public static Coord2d findNearestReachableCorner(NArea area) throws InterruptedException {
        if (area == null || NUtils.player() == null) return null;
        if (!area.isVisible()) return null;
        Pair<Coord2d, Coord2d> rcArea = area.getRCArea();
        if (rcArea == null) return null;
        Coord2d[] corners = corners(rcArea);
        final NUI boundUI = NUtils.getUI();
        final double[] pathCosts = new double[4];
        final boolean[] reachable = new boolean[4];
        Thread[] threads = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            final Coord2d corner = corners[i];
            pathCosts[idx] = Double.MAX_VALUE;
            threads[i] = new Thread(() -> {
                ThreadLocalUI.set(boundUI);
                try {
                    int cost = PathFinder.getPathCost(corner);
                    if (cost >= 0) {
                        pathCosts[idx] = cost;
                        reachable[idx] = true;
                    }
                } catch (InterruptedException e) {
                    // unreachable
                } finally {
                    ThreadLocalUI.clear();
                }
            });
            threads[i].start();
        }
        try {
            for (Thread t : threads) t.join();
        } catch (InterruptedException e) {
            for (Thread t : threads) t.interrupt();
            throw e;
        }
        Coord2d nearest = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            if (reachable[i] && pathCosts[i] < best) {
                best = pathCosts[i];
                nearest = corners[i];
            }
        }
        return nearest;
    }

    private static Coord2d[] corners(Pair<Coord2d, Coord2d> rc) {
        return new Coord2d[] {rc.a, rc.b, Coord2d.of(rc.a.x, rc.b.y), Coord2d.of(rc.b.x, rc.a.y)};
    }

    // ---------------------------------------------------------------- old ChunkNavPlanner.planToAreaCorner (pure)

    /**
     * Old per-corner plan: for every grid the area touches, an A* to a walkable tile just outside that grid
     * piece's corner; keeps the cheapest. Pure: the start is passed in, so the offline harness can call it.
     */
    public static ChunkPath planToAreaCorner(ChunkNavGraph graph, LegacyUnifiedTilePathfinder pf, Map<Long, NArea.VArea> space,
                                             int cornerIndex, long startChunkId, Coord playerLocal) {
        if (space == null || space.isEmpty()) return null;
        ChunkPath bestPath = null;
        float bestCost = Float.MAX_VALUE;
        for (Map.Entry<Long, NArea.VArea> entry : space.entrySet()) {
            long gridId = entry.getKey();
            NArea.VArea varea = entry.getValue();
            if (varea == null || varea.area == null) continue;
            ChunkNavData chunk = graph.getChunk(gridId);
            if (chunk == null) continue;

            Coord cornerLocal;
            int dx, dy;
            switch (cornerIndex) {
                case 1: cornerLocal = varea.area.br.sub(1, 1); dx = 1; dy = 1; break;
                case 2: cornerLocal = new Coord(varea.area.ul.x, varea.area.br.y - 1); dx = -1; dy = 1; break;
                case 3: cornerLocal = new Coord(varea.area.br.x - 1, varea.area.ul.y); dx = 1; dy = -1; break;
                default: cornerLocal = varea.area.ul; dx = -1; dy = -1;
            }
            Coord walkable = findWalkableTileOutsideArea(chunk, cornerLocal, dx, dy, varea.area);
            if (walkable == null) walkable = findWalkableTileNear(chunk, cornerLocal);
            if (walkable == null) walkable = cornerLocal;

            LegacyUnifiedTilePathfinder.UnifiedPath up = pf.findPath(startChunkId, playerLocal, gridId, walkable);
            if (up != null && up.reachable) {
                ChunkPath path = new ChunkPath();
                up.populateChunkPath(path, graph);
                if (path.totalCost < bestCost) {
                    bestPath = path;
                    bestCost = path.totalCost;
                }
            }
        }
        return bestPath;
    }

    /** Old four-corner search with an explicit start, single-threaded (for the offline harness). */
    public static ChunkPath shortestToCorners(ChunkNavGraph graph, Map<Long, NArea.VArea> space, long startChunkId, Coord startLocal) {
        LegacyUnifiedTilePathfinder pf = new LegacyUnifiedTilePathfinder(graph);
        ChunkPath best = null;
        for (int i = 0; i < 4; i++) {
            ChunkPath p = planToAreaCorner(graph, pf, space, i, startChunkId, startLocal);
            if (p != null && (best == null || p.totalCost < best.totalCost)) best = p;
        }
        return best;
    }

    private static boolean isTileWalkable(ChunkNavData chunk, int tileX, int tileY) {
        int cellX = tileX * CELLS_PER_TILE, cellY = tileY * CELLS_PER_TILE;
        for (int dx = 0; dx < CELLS_PER_TILE; dx++)
            for (int dy = 0; dy < CELLS_PER_TILE; dy++) {
                int cx = cellX + dx, cy = cellY + dy;
                if (cx >= 0 && cx < CELLS_PER_EDGE && cy >= 0 && cy < CELLS_PER_EDGE && chunk.walkability[cx][cy] == 0)
                    return true;
            }
        return false;
    }

    private static boolean isCellWalkable(ChunkNavData chunk, int cx, int cy) {
        return cx >= 0 && cx < CELLS_PER_EDGE && cy >= 0 && cy < CELLS_PER_EDGE && chunk.walkability[cx][cy] == 0;
    }

    private static Coord findWalkableTileOutsideArea(ChunkNavData chunk, Coord corner, int dirX, int dirY, Area bounds) {
        for (int dist = 1; dist <= 3; dist++) {
            int tx = corner.x + dirX * dist, ty = corner.y + dirY * dist;
            if (outside(tx, ty, bounds) && valid(tx, ty) && isTileWalkable(chunk, tx, ty)) return new Coord(tx, ty);
            tx = corner.x + dirX * dist; ty = corner.y;
            if (outside(tx, ty, bounds) && valid(tx, ty) && isTileWalkable(chunk, tx, ty)) return new Coord(tx, ty);
            tx = corner.x; ty = corner.y + dirY * dist;
            if (outside(tx, ty, bounds) && valid(tx, ty) && isTileWalkable(chunk, tx, ty)) return new Coord(tx, ty);
            for (int dx = -dist; dx <= dist; dx++) {
                for (int dy = -dist; dy <= dist; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    if ((dirX > 0 && dx < 0) || (dirX < 0 && dx > 0)) continue;
                    if ((dirY > 0 && dy < 0) || (dirY < 0 && dy > 0)) continue;
                    tx = corner.x + dx; ty = corner.y + dy;
                    if (outside(tx, ty, bounds) && valid(tx, ty) && isTileWalkable(chunk, tx, ty)) return new Coord(tx, ty);
                }
            }
        }
        return null;
    }

    private static boolean outside(int x, int y, Area b) {
        return b == null || x < b.ul.x || x >= b.br.x || y < b.ul.y || y >= b.br.y;
    }

    private static boolean valid(int x, int y) {
        return x >= 0 && x < CHUNK_SIZE && y >= 0 && y < CHUNK_SIZE;
    }

    private static Coord findWalkableTileNear(ChunkNavData chunk, Coord target) {
        int tcx = target.x * CELLS_PER_TILE, tcy = target.y * CELLS_PER_TILE;
        if (isCellWalkable(chunk, tcx, tcy)) return target;
        for (int r = 1; r <= 4; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    int cx = tcx + dx, cy = tcy + dy;
                    if (isCellWalkable(chunk, cx, cy)) {
                        int tx = cx / CELLS_PER_TILE, ty = cy / CELLS_PER_TILE;
                        if (valid(tx, ty)) return new Coord(tx, ty);
                    }
                }
        return null;
    }

    private static final class PlayerLoc {
        final long gridId;
        final Coord local;
        PlayerLoc(long gridId, Coord local) { this.gridId = gridId; this.local = local; }
    }

    private static PlayerLoc playerLocation() {
        NGameUI gui = NUtils.getGameUI();
        Gob player = NUtils.player();
        if (gui == null || gui.map == null || gui.map.glob == null || player == null) return null;
        MCache mcache = gui.map.glob.map;
        Coord tile = player.rc.floor(MCache.tilesz);
        MCache.Grid grid;
        try {
            grid = mcache.getgridt(tile);
        } catch (haven.Loading e) {
            return null;
        }
        if (grid == null) return null;
        Coord local = tile.sub(grid.ul);
        if (local.x < 0 || local.x >= CHUNK_SIZE || local.y < 0 || local.y >= CHUNK_SIZE) return null;
        return new PlayerLoc(grid.id, local);
    }
}
