package nurgling.navigation;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.PathFinder;
import nurgling.areas.NArea;

/**
 * Getting to an area: the nearest way in, from whichever side is cheapest.
 *
 * One chunk-navigation search to the area's edge ({@link ChunkNavPlanner#planToArea}) decides where to go. When
 * that route stays on loaded grids without portals, one local {@link PathFinder} walks there; otherwise the chunk
 * navigation executor walks it. When the area is loaded but the recorded map finds no local route (a closed gate
 * the local pathfinder can open, a fresher obstacle), one local pathfinder attempt to the nearest point of the area
 * is the backstop.
 */
public class AreaNavigationHelper {

    /**
     * Navigate to an area.
     *
     * @param ensurePresence false: if the area is already within local reach, return true without walking (its
     *                       storages are in range). true: always end up at the area, e.g. so all of its grids are
     *                       streamed in before choosing a drop cell.
     * @return true when at (or, without ensurePresence, within local reach of) the area
     */
    public static boolean navigate(NArea area, boolean ensurePresence) throws InterruptedException {
        if (area == null) return false;
        NGameUI gui = NUtils.getGameUI();
        Gob player = NUtils.player();
        if (gui == null || !(gui.map instanceof NMapView) || player == null) return false;

        Pair<Coord2d, Coord2d> rc = area.getRCArea();   // non-null only while the area is loaded and near
        if (rc != null && area.checkHit(player.rc)) return true;

        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        boolean navOk = nav != null && nav.isInitialized();
        ChunkPath path = navOk ? nav.planToArea(area) : null;

        // Towing a cart: always follow the chunk route, whose tiles are scored for room (UnifiedTilePathfinder.cartCost),
        // instead of one local walk that takes the shortest way through any gap, however narrow.
        if (path != null && isLocal(path, gui) && nurgling.actions.cart.CartTow.planningWithCart()) {
            if (!ensurePresence) return true;
            System.out.println("[Cart] area " + area.name + " is local, following the chunk route for the cart");
            return nav.navigateWithPath(path, area, gui).IsSuccess();
        }

        if (path != null && isLocal(path, gui)) {
            if (!ensurePresence) return true;
            Coord2d goal = goalWorld(path, gui);
            if (goal == null || goal.dist(player.rc) < MCache.tilesz.x) return true;
            if (new PathFinder(goal).run(gui).IsSuccess()) return true;
            return nav.navigateWithPath(path, area, gui).IsSuccess();
        }

        if (rc != null) {
            Coord2d target = nearestPoint(rc, player.rc);
            if (PathFinder.isAvailable(target)) {
                if (!ensurePresence) return true;
                if (new PathFinder(target).run(gui).IsSuccess()) return true;
            }
        }

        if (path == null) return false;
        return nav.navigateWithPath(path, area, gui).IsSuccess();
    }

    /**
     * Whether the area can be reached without leaving the loaded grids or using a portal (the old "reachable by
     * local pathfinding"). One chunk plan, no local map builds; falls back to one local check when the recorded
     * map has no route.
     */
    public static boolean isAreaReachableByLocalPF(NArea area) throws InterruptedException {
        if (area == null || NUtils.player() == null) return false;
        if (area.getRCArea() == null) return false;
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || !(gui.map instanceof NMapView)) return false;
        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        if (nav != null && nav.isInitialized()) {
            ChunkPath path = nav.planToArea(area);
            if (path != null && isLocal(path, gui)) return true;
        }
        return PathFinder.isAvailable(nearestPoint(area.getRCArea(), NUtils.player().rc));
    }

    /** Walking cost to an area by chunk navigation, Double.MAX_VALUE when unreachable or unavailable. */
    public static double costTo(NArea area, NGameUI gui) {
        if (area == null || gui == null || !(gui.map instanceof NMapView)) return Double.MAX_VALUE;
        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        if (nav == null || !nav.isInitialized()) return Double.MAX_VALUE;
        return nav.costToArea(area);
    }

    /** True when every segment is on a currently loaded grid and none is a portal. */
    static boolean isLocal(ChunkPath path, NGameUI gui) {
        if (path.segments.isEmpty()) return false;
        MCache map = gui.map.glob.map;
        for (ChunkPath.PathSegment seg : path.segments) {
            if (seg.type == ChunkPath.SegmentType.PORTAL) return false;
            if (map.findGrid(seg.gridId) == null) return false;
        }
        return true;
    }

    /** World position of the path's last tile, from the live grid. */
    static Coord2d goalWorld(ChunkPath path, NGameUI gui) {
        ChunkPath.PathSegment last = path.segments.get(path.segments.size() - 1);
        if (last.steps.isEmpty()) return null;
        ChunkPath.TileStep step = last.steps.get(last.steps.size() - 1);
        MCache.Grid grid = gui.map.glob.map.findGrid(last.gridId);
        if (grid == null) return step.worldCoord;
        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        ChunkNavData chunk = nav.getGraph().getChunk(last.gridId);
        return UnifiedTilePathfinder.findWalkableCellWorldCoord(chunk, step.localCoord.x, step.localCoord.y, grid.ul);
    }

    /** The point of the rectangle closest to p. */
    static Coord2d nearestPoint(Pair<Coord2d, Coord2d> rc, Coord2d p) {
        double x0 = Math.min(rc.a.x, rc.b.x), x1 = Math.max(rc.a.x, rc.b.x);
        double y0 = Math.min(rc.a.y, rc.b.y), y1 = Math.max(rc.a.y, rc.b.y);
        return Coord2d.of(Math.max(x0, Math.min(x1, p.x)), Math.max(y0, Math.min(y1, p.y)));
    }
}
