package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Coord2d;
import nurgling.actions.bots.worldexplorer.SeaGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** River Explorer's planning on hand-made maps: '.' deep fresh water, '~' shallow fresh water, 'o' sea, '#' land. */
class RiverExplorerPlanningTest {
    static final String DEEP = "gfx/tiles/deep", SHALLOW = "gfx/tiles/water", SEA = "gfx/tiles/odeep", LAND = "gfx/tiles/grass";
    static final Predicate<String> FRESH = n -> n.equals(DEEP) || n.startsWith(SHALLOW);
    static final Predicate<String> SEA_P = n -> n.equals(SEA);
    static final double TILE = 11;

    // Fit radii (half-beam + 1) from each vessel's obst layer.
    static final double CORACLE = 2.9 + 1, DUGOUT = 1.9 + 1, ROWBOAT = 6.9 + 1, SNEKKJA = 11.9 + 1, KNARR = 17.9 + 1;

    static String name(char ch) {
        switch (ch) {
            case '.': return DEEP;
            case '~': return SHALLOW;
            case 'o': return SEA;
            default: return LAND;
        }
    }

    static SeaGrid.Tiles ascii(String... rows) {
        return tc -> (tc.y < 0 || tc.y >= rows.length || tc.x < 0 || tc.x >= rows[tc.y].length()) ? null : name(rows[tc.y].charAt(tc.x));
    }

    static SeaGrid.Tiles fn(BiFunction<Integer, Integer, Character> f) {
        return tc -> name(f.apply(tc.x, tc.y));
    }

    /** Whether a hull with this fit radius fits anywhere in a straight channel `width` tiles wide. */
    static boolean fitsChannel(int width, double fit) {
        int w = width + 4;
        SeaGrid.Tiles t = tc -> (tc.x >= 2 && tc.x < 2 + width) ? DEEP : LAND;
        WaterCells c = WaterCells.build(t, Coord.z, w, 10, FRESH, null, null, fit + 6);
        for (int cx = 0; cx < c.w; cx++)
            if (c.clear(cx, 10) >= fit) return true;
        return false;
    }

    @Test
    void narrowestChannelPerHull() {
        assertTrue(fitsChannel(1, CORACLE));
        assertTrue(fitsChannel(1, DUGOUT));
        assertFalse(fitsChannel(1, ROWBOAT));
        assertTrue(fitsChannel(2, ROWBOAT));
        assertFalse(fitsChannel(2, SNEKKJA));
        assertTrue(fitsChannel(3, SNEKKJA));
        assertFalse(fitsChannel(3, KNARR));
        assertTrue(fitsChannel(4, KNARR));
    }

    /** A grid with a 4-tile river across it (rows 48..51) and a separate creek near the top (rows 2..5, x 10..40). */
    static SeaGrid.Tiles riverAndCreek() {
        return fn((x, y) -> (y >= 48 && y <= 51) ? '.' : (y >= 2 && y <= 5 && x >= 10 && x <= 40) ? '~' : '#');
    }

    @Test
    void gridPartsAndOpenings() {
        GridWater g = GridWater.build(riverAndCreek(), FRESH, SEA_P, ROWBOAT);
        assertEquals(2, g.parts);
        int river = -1, creek = -1;
        for (int p = 0; p < g.parts; p++) {
            if (g.hasInner(p)) river = p; else creek = p;
        }
        assertTrue(river >= 0 && creek >= 0);
        assertTrue(g.fresh(river));
        assertFalse(g.sea(river));
        // The river leaves through the west and east edges; the creek touches no edge.
        List<Integer> riverOpenings = new ArrayList<>();
        for (int k = 0; k < g.openings.size(); k++) {
            GridWater.Opening o = g.openings.get(k);
            assertEquals(river, o.part);
            riverOpenings.add(o.edge);
        }
        assertEquals(new HashSet<>(Arrays.asList(GridWater.W, GridWater.E)), new HashSet<>(riverOpenings));
        assertEquals(2, g.openings.size());
        double across = g.dist(0, 1);
        assertTrue(across > 95 && across < 105, "about a grid across: " + across);
        assertTrue(Double.isFinite(g.toInner(0)));
    }

    @Test
    void damAcrossTheRiverSplitsIt() {
        List<Coord> dam = new ArrayList<>();
        for (int y = 45; y <= 54; y++)
            for (int x = 60; x <= 65; x++)
                dam.add(Coord.of(x, y));
        GridWater g = GridWater.build(riverAndCreek(), FRESH, SEA_P, ROWBOAT, dam);
        assertEquals(3, g.parts);
        int west = -1, east = -1;
        for (GridWater.Opening o : g.openings) {
            if (o.edge == GridWater.W) west = o.part;
            if (o.edge == GridWater.E) east = o.part;
        }
        assertTrue(west >= 0 && east >= 0);
        assertNotEquals(west, east, "the dam leaves no way from one end of the river to the other");
    }

    @Test
    void searchFromTheBoatSnapsToWaterItFits() {
        GridWater g = GridWater.build(riverAndCreek(), FRESH, SEA_P, ROWBOAT);
        // On the bank right next to the river: moved onto the river.
        GridWater.FromCell f = g.from(Coord.of(2 * 50, 2 * 47));
        assertNotNull(f);
        assertTrue(g.hasInner(f.part));
        assertTrue(Double.isFinite(f.toInner));
        // Far inland: nothing.
        assertNull(g.from(Coord.of(2 * 80, 2 * 80)));
    }

    /** Grids by segment coord, all built from the given tile pictures. */
    static Map<Coord, GridWater> world(Object... gridAndTiles) {
        Map<Coord, GridWater> res = new HashMap<>();
        for (int i = 0; i < gridAndTiles.length; i += 2)
            res.put((Coord) gridAndTiles[i], GridWater.build((SeaGrid.Tiles) gridAndTiles[i + 1], FRESH, SEA_P, ROWBOAT));
        return res;
    }

    static SeaGrid.Tiles river() {
        return fn((x, y) -> (y >= 48 && y <= 51) ? '.' : '#');
    }

    static SeaGrid.Tiles allSea() {
        return fn((x, y) -> 'o');
    }

    /** A lake in the grid's middle, the river leaving east, the lake open to the sea southward. */
    static SeaGrid.Tiles lakeToSeaAndRiver() {
        return fn((x, y) -> (y >= 48 && y <= 51 && x >= 50) ? '.' : (x >= 40 && x <= 60 && y >= 40) ? (y >= 70 ? 'o' : '.') : '#');
    }

    static RiverFrontier.Ask ask(Map<Coord, GridWater> w, Coord player, Coord boatCell, Set<Coord> visited) {
        RiverFrontier.Ask a = new RiverFrontier.Ask();
        a.player = player;
        a.start = w.get(player).from(boatCell);
        a.visited = visited;
        return a;
    }

    @Test
    void goesUpTheRiverToTheNearestFrontier() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), river(), Coord.of(1, 0), river(), Coord.of(2, 0), river());
        Set<Coord> visited = new HashSet<>(Arrays.asList(Coord.of(0, 0)));
        RiverFrontier.Choice c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 0), Coord.of(100, 99), visited));
        assertNotNull(c);
        assertEquals(Coord.of(1, 0), c.grid);
        assertEquals(1, c.route.size());
        assertEquals(Coord.of(1, 0), c.route.get(0).grid);
        // Entered on its west edge, in the river.
        assertEquals(0, c.route.get(0).cell.x);
        assertTrue(c.route.get(0).cell.y >= 96 && c.route.get(0).cell.y <= 104);
        assertTrue(c.fresh);
    }

    @Test
    void riversBeforeTheSea() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), lakeToSeaAndRiver(), Coord.of(1, 0), river(), Coord.of(2, 0), river(),
                Coord.of(0, 1), allSea());
        Coord boat = Coord.of(2 * 50, 2 * 55);
        Set<Coord> visited = new HashSet<>(Arrays.asList(Coord.of(0, 0), Coord.of(1, 0)));
        // The sea grid is right next door and reveals about as much, but the river's end comes first.
        RiverFrontier.Choice c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 0), boat, visited));
        assertNotNull(c);
        assertEquals(Coord.of(2, 0), c.grid);
        assertTrue(c.fresh);
        assertEquals(2, c.depth);
        assertEquals(2, c.route.size());
        // River done: now the sea.
        visited.add(Coord.of(2, 0));
        c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 0), boat, visited));
        assertNotNull(c);
        assertEquals(Coord.of(0, 1), c.grid);
        assertFalse(c.fresh);
    }

    @Test
    void seaCostCountsOnlyTheWayOutAtSea() {
        // A river mouth (the lake open to the sea) right above the boat; the river's unexplored end six grids upstream.
        Map<Coord, GridWater> w = world(Coord.of(0, 0), lakeToSeaAndRiver(), Coord.of(1, 0), river(), Coord.of(2, 0), river(),
                Coord.of(3, 0), river(), Coord.of(4, 0), river(), Coord.of(5, 0), river(), Coord.of(6, 0), river(),
                Coord.of(0, 1), allSea(), Coord.of(0, 2), allSea(), Coord.of(0, 3), allSea());
        Set<Coord> visited = new HashSet<>();
        for (int x = 0; x <= 5; x++) visited.add(Coord.of(x, 0));
        for (int y = 1; y <= 3; y++) visited.add(Coord.of(0, y));
        RiverFrontier.Choice c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 1), Coord.of(100, 20), visited));
        assertNotNull(c);
        assertEquals(Coord.of(6, 0), c.grid);
        assertTrue(c.fresh);
        assertTrue(c.cost > 600, "far up the river: " + c.cost);
        assertTrue(c.seaCost < 60, "but the mouth is right there: " + c.seaCost);
        // The same river from two grids farther out at sea.
        c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 3), Coord.of(100, 180), visited));
        assertNotNull(c);
        assertEquals(Coord.of(6, 0), c.grid);
        assertTrue(c.seaCost > 600, "a long way out at sea: " + c.seaCost);
    }

    @Test
    void failedCrossingIsNotUsed() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), river(), Coord.of(1, 0), river(), Coord.of(2, 0), river());
        RiverFrontier.Ask a = ask(w, Coord.of(0, 0), Coord.of(100, 99), new HashSet<>(Arrays.asList(Coord.of(0, 0))));
        a.excludedLinks = new HashSet<>(Arrays.asList(RiverFrontier.link(Coord.of(0, 0), Coord.of(1, 0))));
        assertNull(RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), a));
    }

    @Test
    void landBetweenGridsIsNoWay() {
        // The river in the next grid runs at other rows: the openings don't line up.
        SeaGrid.Tiles other = fn((x, y) -> (y >= 10 && y <= 13) ? '.' : '#');
        Map<Coord, GridWater> w = world(Coord.of(0, 0), river(), Coord.of(1, 0), other);
        RiverFrontier.Ask a = ask(w, Coord.of(0, 0), Coord.of(100, 99), new HashSet<>(Arrays.asList(Coord.of(0, 0))));
        assertNull(RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), a));
    }

    static Pilot.Input input(SeaGrid.Tiles tiles, int w, int h, Coord2d start, Coord gul, Coord gbr, double fit) {
        Pilot.Input in = new Pilot.Input();
        in.tiles = tiles;
        in.fresh = FRESH;
        in.sea = SEA_P;
        in.ul = Coord.z;
        in.wt = w;
        in.ht = h;
        in.fit = fit;
        in.start = start;
        in.goalUL = gul;
        in.goalBR = gbr;
        return in;
    }

    static Coord2d centre(int tx, int ty) {
        return Coord2d.of((tx + 0.5) * TILE, (ty + 0.5) * TILE);
    }

    @Test
    void narrowRiverChokedByReedsIsCrossedAndTheReedsNamed() {
        // A 2-tile river, rows 5..6, reeds across it at x = 20.
        SeaGrid.Tiles t = fn((x, y) -> (y == 5 || y == 6) ? '.' : '#');
        Pilot.Input in = input(t, 40, 12, centre(2, 5), Coord.of(36, 5), Coord.of(38, 7), ROWBOAT);
        in.reeds.add(Coord.of(20, 5));
        in.reeds.add(Coord.of(20, 6));
        Pilot.Course c = Pilot.plan(in);
        assertNotNull(c);
        int r = c.firstReed(0);
        assertTrue(r > 0);
        assertEquals(new HashSet<>(in.reeds), c.blockingReeds(r, in.reeds));
        // The stand-off point is short of the reeds.
        assertTrue(c.pts.get(r - 1).x < 20 * TILE);
    }

    @Test
    void wideRiverGoesRoundAReed() {
        // An 8-tile river, rows 2..9, one reed in the middle of the course.
        SeaGrid.Tiles t = fn((x, y) -> (y >= 2 && y <= 9) ? '.' : '#');
        Pilot.Input in = input(t, 40, 12, centre(2, 5), Coord.of(36, 5), Coord.of(38, 7), ROWBOAT);
        in.reeds.add(Coord.of(20, 5));
        in.reeds.add(Coord.of(20, 6));
        Pilot.Course c = Pilot.plan(in);
        assertNotNull(c);
        assertEquals(-1, c.firstReed(0));
    }

    @Test
    void courseKeepsToTheMiddle() {
        // A 6-tile river, rows 3..8: the course runs along rows 5..6 (y about 66).
        SeaGrid.Tiles t = fn((x, y) -> (y >= 3 && y <= 8) ? '.' : '#');
        Pilot.Input in = input(t, 50, 12, centre(2, 4), Coord.of(45, 3), Coord.of(47, 9), ROWBOAT);
        Pilot.Course c = Pilot.plan(in);
        assertNotNull(c);
        Coord2d mid = c.pts.get(c.pts.size() / 2);
        assertEquals(66, mid.y, 6.0);
    }

    @Test
    void noWayIntoAnotherLake() {
        SeaGrid.Tiles t = ascii(
                "##########",
                "#...##...#",
                "#...##...#",
                "#...##...#",
                "##########");
        Pilot.Input in = input(t, 10, 5, centre(2, 2), Coord.of(6, 1), Coord.of(9, 4), CORACLE);
        assertNull(Pilot.plan(in));
    }

    @Test
    void dangerDiscIsAWall() {
        SeaGrid.Tiles t = fn((x, y) -> (y == 5 || y == 6) ? '.' : '#');
        Pilot.Input in = input(t, 40, 12, centre(2, 5), Coord.of(36, 5), Coord.of(38, 7), ROWBOAT);
        in.discs.add(new SeaGrid.Disc(centre(20, 6), 3 * TILE));
        assertNull(Pilot.plan(in));
        in.discs.clear();
        assertNotNull(Pilot.plan(in));
    }

    /** A winding river (9 tiles across vertically, ~3.5 at right angles where it is steepest) over a full 3x3-grid window, planned from one end to the other: what a live re-plan costs. */
    @Test
    void fullSizeIsFastEnough() {
        SeaGrid.Tiles t = fn((x, y) -> {
            double c = 150 + 60 * Math.sin(x / 25.0);
            return Math.abs(y - c) <= 4.5 ? '.' : '#';
        });
        Pilot.Input in = input(t, 300, 300, centre(2, 150), Coord.of(290, 0), Coord.of(300, 300), ROWBOAT);
        for (int i = 0; i < 40; i++) in.reeds.add(Coord.of(100 + i % 3, (int) (150 + 60 * Math.sin(100 / 25.0)) - 2 + i % 5));
        long t0 = System.nanoTime();
        Pilot.Course c = Pilot.plan(in);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull(c);
        long g0 = System.nanoTime();
        GridWater g = GridWater.build(fn((x, y) -> Math.abs(y - (50 + 30 * Math.sin(x / 15.0))) <= 2.5 || Math.abs(x - 50) <= 3 ? '.' : '#'), FRESH, SEA_P, ROWBOAT);
        long gms = (System.nanoTime() - g0) / 1_000_000;
        System.out.println("[perf] live plan " + ms + " ms over 300x300 tiles, " + c.pts.size() + " points; grid build " + gms + " ms, " + g.openings.size() + " openings");
        assertTrue(ms < 3000, "plan took " + ms + " ms");
    }
}
