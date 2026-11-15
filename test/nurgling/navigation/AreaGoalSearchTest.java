package nurgling.navigation;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Edge goal sets and the multi-goal tile search, on small synthetic chunk graphs. */
class AreaGoalSearchTest {
    static final int S = ChunkNavConfig.CHUNK_SIZE;

    static ChunkNavData chunk(ChunkNavGraph g, long id, long instance, String layer) {
        ChunkNavData c = new ChunkNavData(id);
        c.instanceId = instance;
        c.layer = layer;
        for (byte[] col : c.walkability) Arrays.fill(col, (byte) 0);
        g.addChunk(c);
        return c;
    }

    static void block(ChunkNavData c, int tx, int ty) {
        for (int dx = 0; dx < 2; dx++)
            for (int dy = 0; dy < 2; dy++) c.walkability[tx * 2 + dx][ty * 2 + dy] = 2;
    }

    static Map<Long, Area> area(long gid, int x0, int y0, int x1, int y1) {
        Map<Long, Area> m = new HashMap<>();
        m.put(gid, new Area(new Coord(x0, y0), new Coord(x1, y1)));
        return m;
    }

    static ChunkPath.TileStep last(ChunkPath p) {
        ChunkPath.PathSegment s = p.segments.get(p.segments.size() - 1);
        return s.steps.get(s.steps.size() - 1);
    }

    static long lastGrid(ChunkPath p) {
        return p.segments.get(p.segments.size() - 1).gridId;
    }

    @Test
    void stopsAtTheNearestEdgeNotACorner() {
        ChunkNavGraph g = new ChunkNavGraph();
        chunk(g, 1, 1, "outside");
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 61, 61), 1, new Coord(10, 55));
        assertNotNull(p);
        assertEquals(new Coord(49, 55), last(p).localCoord, "first ring tile straight ahead, not a corner");
        assertEquals(39, p.totalCost, 0.01);
    }

    @Test
    void startingInsideTheAreaIsAlreadyThere() {
        ChunkNavGraph g = new ChunkNavGraph();
        chunk(g, 1, 1, "outside");
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 61, 61), 1, new Coord(55, 55));
        assertNotNull(p);
        assertEquals(0, p.totalCost, 0.001);
        assertEquals(1, p.getTotalTileSteps());
    }

    @Test
    void aSeamBetweenTheAreasGridsIsNotItsEdge() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData a = chunk(g, 1, 1, "outside"), b = chunk(g, 2, 1, "outside");
        a.neighborEast = 2;
        b.neighborWest = 1;
        Map<Long, Area> pieces = new HashMap<>();
        pieces.put(1L, new Area(new Coord(90, 40), new Coord(S, 61)));
        pieces.put(2L, new Area(new Coord(0, 40), new Coord(11, 61)));
        AreaGoalSet goal = AreaGoalSet.ofArea(g, pieces);
        assertTrue(goal.contains(1, 99, 50), "seam tile on the west grid is inside");
        assertTrue(goal.contains(2, 0, 50), "seam tile on the east grid is inside");
        assertTrue(goal.contains(2, 11, 50), "ring east of the area");
        assertFalse(goal.contains(2, 12, 50), "two tiles out is not a way in");
        assertFalse(goal.contains(1, 50, 50));

        ChunkPath p = new ChunkNavPlanner(g).planToArea(pieces, 2, new Coord(50, 50));
        assertNotNull(p);
        assertEquals(2, lastGrid(p));
        assertEquals(new Coord(11, 50), last(p).localCoord);
    }

    @Test
    void aPalisadeRightAroundTheAreaMakesItUnreachable() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData c = chunk(g, 1, 1, "outside");
        // 10x10 area, wall on the 12x12 ring right around it: nothing in the area can be reached.
        for (int x = 49; x <= 60; x++) { block(c, x, 49); block(c, x, 60); }
        for (int y = 49; y <= 60; y++) { block(c, 49, y); block(c, 60, y); }
        assertNull(new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 60, 60), 1, new Coord(20, 55)));
    }

    @Test
    void wallsOnThreeSidesGoesRoundToTheOpenSide() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData c = chunk(g, 1, 1, "outside");
        // Walls on the west (near), north and south sides; the east side, farthest from the start, is open.
        for (int x = 49; x <= 60; x++) { block(c, x, 49); block(c, x, 60); }
        for (int y = 49; y <= 60; y++) block(c, 49, y);
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 60, 60), 1, new Coord(20, 55));
        assertNotNull(p);
        assertEquals(60, last(p).localCoord.x, "stops on the open east side's ring");
    }

    @Test
    void anAreaWhoseRingIsAllWaterIsUnreachable() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData c = chunk(g, 1, 1, "outside");
        // Water from x=40 eastwards; the area starts 2 tiles out from the shore (x=39).
        for (int x = 40; x < S; x++) for (int y = 0; y < S; y++) block(c, x, y);
        assertNull(new ChunkNavPlanner(g).planToArea(area(1, 42, 40, 60, 60), 1, new Coord(10, 50)));
        assertNotNull(new ChunkNavPlanner(g).planToArea(area(1, 40, 40, 60, 60), 1, new Coord(10, 50)), "shore is the ring");
    }

    @Test
    void aWalledOffAreaHasNoPath() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData c = chunk(g, 1, 1, "outside");
        for (int x = 40; x <= 70; x++) { block(c, x, 40); block(c, x, 70); }
        for (int y = 40; y <= 70; y++) { block(c, 40, y); block(c, 70, y); }
        assertNull(new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 61, 61), 1, new Coord(10, 10)));
    }

    @Test
    void anotherInstanceWithNoPortalIsRejectedWithoutSearching() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData a = chunk(g, 1, 1, "outside");
        chunk(g, 2, 7, "outside");   // a mine level, no portal recorded
        a.neighborEast = 2;          // a stale link must not matter: different instance
        ChunkNavPlanner pl = new ChunkNavPlanner(g);
        assertNull(pl.planToArea(area(2, 10, 10, 20, 20), 1, new Coord(50, 50)));
        assertEquals(0, pl.getTilePathfinder().lastPops, "start's component has no portal chain to the goal");
    }

    @Test
    void throughADoorIntoABuilding() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData out = chunk(g, 1, 1, "outside");
        chunk(g, 2, 2, "inside");
        ChunkPortal door = new ChunkPortal("h1", "gfx/terobjs/arch/logcabin", ChunkPortal.PortalType.DOOR, new Coord(30, 30));
        door.connectsToGridId = 2;
        door.exitLocalCoord = new Coord(50, 90);
        out.portals.add(door);
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(2, 40, 40, 61, 61), 1, new Coord(30, 40));
        assertNotNull(p);
        assertEquals(2, lastGrid(p));
        assertEquals(ChunkPath.SegmentType.PORTAL, p.segments.get(0).type);
        assertTrue(p.totalCost >= ChunkNavConfig.PORTAL_TRAVERSAL_COST);
    }

    @Test
    void aChunkOfUnknownInstanceStillHasARing() {
        ChunkNavGraph g = new ChunkNavGraph();
        chunk(g, 1, 0, "outside");
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(1, 50, 50, 61, 61), 1, new Coord(10, 55));
        assertNotNull(p);
        assertEquals(new Coord(49, 55), last(p).localCoord);
    }

    @Test
    void nearestOfPicksTheCheapestByWalkingNotStraightLine() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData c = chunk(g, 1, 1, "outside");
        // Area 0 is close in a straight line but behind a long wall; area 1 is further but open.
        for (int y = 0; y < 95; y++) block(c, 30, y);
        List<Map<Long, Area>> cands = Arrays.asList(area(1, 35, 10, 40, 15), area(1, 10, 60, 15, 65));
        ChunkNavPlanner.Nearest n = new ChunkNavPlanner(g).nearestOf(cands, 1, new Coord(25, 12));
        assertNotNull(n);
        assertEquals(1, n.index);
    }

    @Test
    void aCornerStepWithoutASideNeighbourDoesNotJumpAcrossTheChunk() {
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData a = chunk(g, 1, 1, "outside"), n = chunk(g, 2, 1, "outside");
        a.neighborNorth = 2;
        n.neighborSouth = 1;
        // Area in the north chunk's bottom-right corner; the old rule stepped from A(0,0) up-left straight to N(99,99).
        ChunkPath p = new ChunkNavPlanner(g).planToArea(area(2, 97, 97, S, S), 1, new Coord(0, 0));
        assertNotNull(p);
        assertTrue(p.totalCost > 90, "walks across, cost " + p.totalCost);
    }

    @Test
    void costsMatchAPlainDijkstraOnRandomObstacles() {
        Random rnd = new Random(42);
        ChunkNavGraph g = new ChunkNavGraph();
        ChunkNavData[][] grid = new ChunkNavData[2][2];
        long id = 1;
        for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) grid[i][j] = chunk(g, id++, 1, "outside");
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++) {
                ChunkNavData c = grid[i][j];
                if (i > 0) c.neighborWest = grid[i - 1][j].gridId;
                if (i < 1) c.neighborEast = grid[i + 1][j].gridId;
                if (j > 0) c.neighborNorth = grid[i][j - 1].gridId;
                if (j < 1) c.neighborSouth = grid[i][j + 1].gridId;
                for (int x = 0; x < S; x++) for (int y = 0; y < S; y++) if (rnd.nextDouble() < 0.3) block(c, x, y);
            }
        UnifiedTilePathfinder pf = new UnifiedTilePathfinder(g);
        int compared = 0;
        for (int q = 0; q < 25; q++) {
            ChunkNavData sc = grid[rnd.nextInt(2)][rnd.nextInt(2)], tc = grid[rnd.nextInt(2)][rnd.nextInt(2)];
            Coord s = new Coord(rnd.nextInt(S), rnd.nextInt(S));
            int ax = rnd.nextInt(S - 10), ay = rnd.nextInt(S - 10);
            AreaGoalSet goal = AreaGoalSet.ofArea(g, area(tc.gridId, ax, ay, ax + 1 + rnd.nextInt(8), ay + 1 + rnd.nextInt(8)));
            UnifiedTilePathfinder.UnifiedPath p = pf.findPathToAny(sc.gridId, s, goal);
            double ref = reference(g, sc.gridId, s, goal);
            if (ref == Double.MAX_VALUE) {
                assertNull(p, "query " + q);
            } else {
                assertNotNull(p, "query " + q);
                assertEquals(ref, p.cost, 1e-3, "query " + q);
                compared++;
            }
        }
        assertTrue(compared > 10);
    }

    /** Plain Dijkstra with the same move rules (no portals), for the comparison above. */
    static double reference(ChunkNavGraph g, long sChunk, Coord s, AreaGoalSet goal) {
        Map<String, Double> dist = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
        pq.add(new Object[] {0.0, sChunk, s.x, s.y});
        Set<String> done = new HashSet<>();
        while (!pq.isEmpty()) {
            Object[] cur = pq.poll();
            double d = (Double) cur[0];
            long gid = (Long) cur[1];
            int x = (Integer) cur[2], y = (Integer) cur[3];
            if (!done.add(gid + ":" + x + ":" + y)) continue;
            if (goal.contains(gid, x, y)) return d;
            ChunkNavData c = g.getChunk(gid);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    long ng = gid;
                    double w = Math.sqrt(dx * dx + dy * dy);
                    if (nx < 0 || nx >= S || ny < 0 || ny >= S) {
                        long[] r = cross(g, c, nx, ny);
                        if (r == null) continue;
                        ng = r[0]; nx = (int) r[1]; ny = (int) r[2]; w = 1.5;
                    } else if (!AreaGoalSet.isTileWalkable(c, nx, ny)) continue;
                    String k = ng + ":" + nx + ":" + ny;
                    if (done.contains(k)) continue;
                    Double old = dist.get(k);
                    if (old == null || d + w < old) {
                        dist.put(k, d + w);
                        pq.add(new Object[] {d + w, ng, nx, ny});
                    }
                }
        }
        return Double.MAX_VALUE;
    }

    /** The edge-crossing rule, written out again on its own. */
    static long[] cross(ChunkNavGraph graph, ChunkNavData from, int nx, int ny) {
        // Written out separately from the planner: a corner step goes through either side neighbour.
        int n = ChunkNavConfig.CHUNK_SIZE;
        int sx = nx < 0 ? -1 : nx >= n ? 1 : 0, sy = ny < 0 ? -1 : ny >= n ? 1 : 0;
        if (sx == 0 && sy == 0) return null;
        int x = nx - sx * n, y = ny - sy * n;
        long id;
        if (sx != 0 && sy != 0) {
            ChunkNavData h = graph.getChunk(sx < 0 ? from.neighborWest : from.neighborEast);
            id = h == null ? -1 : (sy < 0 ? h.neighborNorth : h.neighborSouth);
            if (id == -1) {
                ChunkNavData v = graph.getChunk(sy < 0 ? from.neighborNorth : from.neighborSouth);
                id = v == null ? -1 : (sx < 0 ? v.neighborWest : v.neighborEast);
            }
        } else if (sx != 0) {
            id = sx < 0 ? from.neighborWest : from.neighborEast;
        } else {
            id = sy < 0 ? from.neighborNorth : from.neighborSouth;
        }
        if (id == -1) return null;
        ChunkNavData to = graph.getChunk(id);
        if (to == null || !Objects.equals(from.layer, to.layer) || from.instanceId == 0 || to.instanceId == 0 || from.instanceId != to.instanceId)
            return null;
        if (!AreaGoalSet.isTileWalkable(to, x, y)) return null;
        return new long[] {id, x, y};
    }
}
