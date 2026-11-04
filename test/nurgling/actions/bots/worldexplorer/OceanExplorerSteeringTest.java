package nurgling.actions.bots.worldexplorer;

import haven.Coord;
import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Ocean Explorer's steering on hand-made tile maps: '.' deep, ':' very deep, '~' shallow, '#' land; off the map is unloaded. */
class OceanExplorerSteeringTest {
    static final String DEEP = "gfx/tiles/odeep", DEEPER = "gfx/tiles/odeeper", SHALLOW = "gfx/tiles/owater", LAND = "gfx/tiles/grass";
    static final Predicate<String> SHORE_BAND = n -> n.equals(DEEP) || n.equals(DEEPER);
    static final Predicate<String> DEEPER_BAND = n -> n.equals(DEEP) || n.equals(SHALLOW);
    static final Predicate<String> SAILABLE = n -> n.equals(DEEP) || n.equals(DEEPER) || n.equals(SHALLOW);
    static final double TILE = 11;

    /** A map from rows of characters, top-left at tile (0,0). */
    static SeaGrid.Tiles ascii(String... rows) {
        return tc -> {
            if (tc.y < 0 || tc.y >= rows.length || tc.x < 0 || tc.x >= rows[tc.y].length()) return null;
            switch (rows[tc.y].charAt(tc.x)) {
                case '.': return DEEP;
                case ':': return DEEPER;
                case '~': return SHALLOW;
                default: return LAND;
            }
        };
    }

    /** A w x h map of `fill` with the tiles for which `inside` holds set to `in`. */
    static SeaGrid.Tiles shape(int w, int h, String fill, String in, Predicate<Coord> inside) {
        return tc -> {
            if (tc.x < 0 || tc.y < 0 || tc.x >= w || tc.y >= h) return null;
            return inside.test(tc) ? in : fill;
        };
    }

    static SeaGrid grid(SeaGrid.Tiles tiles, Predicate<String> band, Coord2d p, int radius, int clearance) {
        return SeaGrid.build(tiles, band, SAILABLE, SeaGrid.tileOf(p), radius, List.of(), Set.of(), List.of(), clearance);
    }

    static Coord2d at(int tx, int ty) {
        return SeaGrid.centreOf(Coord.of(tx, ty));
    }

    @Test
    void handFollowsDirectionAndEdge() {
        // Shoreline clockwise round the land = land on the right; the deeper edge lies across the band, so it flips.
        assertTrue(Helm.edgeOnRight(true, false));
        assertFalse(Helm.edgeOnRight(false, false));
        assertFalse(Helm.edgeOnRight(true, true));
        assertTrue(Helm.edgeOnRight(false, true));
    }

    @Test
    void runsAlongAStraightCoast() {
        // Land along the top; heading east keeps it on the left.
        String[] rows = new String[30];
        for (int y = 0; y < 30; y++)
            rows[y] = (y < 5 ? "#" : y < 7 ? "~" : ".").repeat(60);
        Coord2d p = at(30, 11);
        SeaGrid g = grid(ascii(rows), SHORE_BAND, p, 14, 2);
        Helm helm = new Helm(false, 0);
        Double a = helm.steer(g, p, 22, 100, true, () -> null);
        assertNotNull(a);
        assertTrue(helm.following);
        assertTrue(Helm.diff(a, 0) <= Math.toRadians(30), "course " + Math.toDegrees(a));
    }

    @Test
    void turnsRoundWhenTheEdgeIsOnTheWrongSide() {
        // Same coast, but the edge should be on the right: the helm heads for it and turns along it westward.
        String[] rows = new String[30];
        for (int y = 0; y < 30; y++)
            rows[y] = (y < 5 ? "#" : ".").repeat(60);
        SeaGrid.Tiles t = ascii(rows);
        Coord2d p = at(30, 9);
        SeaGrid g = grid(t, SHORE_BAND, p, 14, 2);
        Helm helm = new Helm(true, 0);
        Double a = helm.steer(g, p, 22, 100, true, () -> SeaGrid.centreOf(SeaSearch.nearestEdge(t, SHORE_BAND, Set.of(), SeaGrid.tileOf(p), 60)));
        assertNotNull(a);
        assertFalse(helm.following);
        a = helm.steer(g, p, 22, 100, true, () -> null);
        assertTrue(helm.following);
        assertTrue(Helm.diff(a, Math.PI) <= Math.toRadians(30), "course " + Math.toDegrees(a));
    }

    @Test
    void boatHardAgainstTheWallStillFindsAWayOff() {
        // One tile off the shallows with clearance 2: its own tile is blocked, but rays start beyond the clearance.
        String[] rows = new String[30];
        for (int y = 0; y < 30; y++)
            rows[y] = (y < 5 ? "~" : ".").repeat(60);
        Coord2d p = at(30, 5);
        SeaGrid g = grid(ascii(rows), SHORE_BAND, p, 14, 2);
        assertTrue(g.blocked(SeaGrid.tileOf(p)));
        assertNotNull(new Helm(false, 0).steer(g, p, 22, 100, true, () -> null));
    }

    @Test
    void solidGobTilesBlockRays() {
        String[] rows = new String[40];
        for (int y = 0; y < 40; y++) rows[y] = ".".repeat(40);
        Coord2d p = at(10, 20);
        List<Coord> rock = new ArrayList<>();
        for (int y = 18; y <= 22; y++) rock.add(Coord.of(16, y));
        SeaGrid g = SeaGrid.build(ascii(rows), SHORE_BAND, SAILABLE, SeaGrid.tileOf(p), 14, rock, Set.of(), List.of(), 2);
        assertFalse(g.rayClear(p, 0, 22, 100));
        // A rock is steered round, never followed.
        assertFalse(g.rayTouchesEdge(p, 0, 100));
    }

    @Test
    void ignoredIslandIsNoLongerAnEdge() {
        SeaGrid.Tiles t = shape(60, 60, DEEP, LAND, c -> Math.hypot(c.x - 30, c.y - 30) < 4);
        Coord2d p = at(30, 22);
        Set<Coord> island = SeaSearch.island(t, SHORE_BAND, Coord.of(30, 30), 1000);
        assertNotNull(island);
        SeaGrid g = SeaGrid.build(t, SHORE_BAND, SAILABLE, SeaGrid.tileOf(p), 14, List.of(), island, List.of(), 2);
        Helm helm = new Helm(true, 0);
        assertNotNull(helm.steer(g, p, 22, 100, true, () -> null));
        assertFalse(helm.following);
        assertFalse(g.rayClear(p, Math.PI / 2, 22, 100), "the island still blocks");
    }

    @Test
    void islandSearchRefusesTheMainland() {
        // Land touching the map's (unloaded) border is not an island.
        SeaGrid.Tiles t = shape(60, 60, DEEP, LAND, c -> c.y < 10);
        assertNull(SeaSearch.island(t, SHORE_BAND, Coord.of(30, 5), 100000));
        // Nor is one bigger than the cap.
        SeaGrid.Tiles t2 = shape(60, 60, DEEP, LAND, c -> Math.hypot(c.x - 30, c.y - 30) < 10);
        assertNull(SeaSearch.island(t2, SHORE_BAND, Coord.of(30, 30), 50));
    }

    @Test
    void nearestEdgeIsTheClosestOutsideTheBand() {
        SeaGrid.Tiles t = shape(80, 80, DEEP, SHALLOW, c -> c.x >= 50 || c.y < 5);
        assertEquals(Coord.of(50, 40), SeaSearch.nearestEdge(t, SHORE_BAND, Set.of(), Coord.of(40, 40), 60));
        assertNull(SeaSearch.nearestEdge(shape(80, 80, DEEP, DEEP, c -> false), SHORE_BAND, Set.of(), Coord.of(40, 40), 30));
    }

    @Test
    void escapeLeavesTheShallowsOverSailableTiles() {
        // Starting in the shallows when following the shoreline: the way out runs over water to the nearest free deep tile.
        SeaGrid.Tiles t = shape(60, 60, DEEP, SHALLOW, c -> c.y < 12);
        SeaGrid g = grid(t, SHORE_BAND, at(30, 8), 16, 2);
        List<Coord> path = SeaSearch.escape(g, Coord.of(30, 8));
        assertNotNull(path);
        assertFalse(path.isEmpty());
        Coord end = path.get(path.size() - 1);
        assertFalse(g.blocked(end));
        assertEquals(14, end.y);
        for (Coord c : path) assertFalse(g.hard(c));
    }

    @Test
    void escapeFailsWhenWalledIn() {
        SeaGrid.Tiles t = shape(30, 30, LAND, DEEP, c -> c.x == 15 && c.y == 15);
        SeaGrid g = grid(t, SHORE_BAND, at(15, 15), 10, 2);
        assertNull(SeaSearch.escape(g, Coord.of(15, 15)));
    }

    // ---- Sailing simulations: the boat moves toward its target each tick; it must never enter a tile it can't be on. ----

    /** WorldExplorer.Voyage.steer without the danger/snag parts. */
    static Coord2d steer(SeaGrid g, Helm helm, Coord2d rc, SeaGrid.Tiles tiles, Predicate<String> band, Set<Coord> ignored, int clearance) {
        double lookahead = 100;
        for (double len : new double[]{lookahead, lookahead / 2, lookahead / 4}) {
            double from = g.rayStart(rc, clearance * TILE, len);
            Double a = helm.steer(g, rc, from, len, len == lookahead, () -> {
                Coord seek = SeaSearch.nearestEdge(tiles, band, ignored, SeaGrid.tileOf(rc), 60);
                return seek == null ? null : SeaGrid.centreOf(seek);
            });
            if (a != null) return rc.add(Coord2d.sc(a, len));
        }
        List<Coord> path = SeaSearch.escape(g, SeaGrid.tileOf(rc));
        if (path == null || path.isEmpty()) return null;
        return SeaSearch.farthestInSight(g, rc, path);
    }

    /** Sails `ticks` ticks at `speed` units per tick; returns the angle swept round `centre` (clockwise on the map is positive). */
    static double sail(SeaGrid.Tiles tiles, Predicate<String> band, Coord2d start, boolean edgeOnRight, Coord2d centre,
                       int ticks, double speed, Predicate<Coord> edge) {
        return sail(tiles, band, start, edgeOnRight, centre, ticks, speed, edge, List.of());
    }

    static double sail(SeaGrid.Tiles tiles, Predicate<String> band, Coord2d start, boolean edgeOnRight, Coord2d centre,
                       int ticks, double speed, Predicate<Coord> edge, List<Coord> rocks) {
        int clearance = 2;
        Helm helm = new Helm(edgeOnRight, 0);
        Coord2d rc = start;
        double swept = 0, prevA = Math.atan2(rc.y - centre.y, rc.x - centre.x);
        for (int i = 0; i < ticks; i++) {
            SeaGrid g = SeaGrid.build(tiles, band, SAILABLE, SeaGrid.tileOf(rc), 14, rocks, Set.of(), List.of(), clearance);
            Coord2d target = steer(g, helm, rc, tiles, band, Set.of(), clearance);
            assertNotNull(target, "boxed in at " + rc);
            Coord2d d = target.sub(rc);
            Coord2d next = d.abs() <= speed ? target : rc.add(d.norm(speed));
            String under = tiles.name(SeaGrid.tileOf(next));
            assertTrue(under != null && SAILABLE.test(under), "ran aground at " + next);
            assertFalse(rocks.contains(SeaGrid.tileOf(next)), "hit a rock at " + next);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    assertFalse(edge.test(SeaGrid.tileOf(next).add(dx, dy)), "within a tile of the edge at " + next);
            rc = next;
            double a = Math.atan2(rc.y - centre.y, rc.x - centre.x);
            swept += Helm.norm(a - prevA);
            prevA = a;
        }
        return swept;
    }

    @Test
    void sailsClockwiseRoundAnIslandWithTheLandOnTheRight() {
        Predicate<Coord> land = c -> Math.hypot(c.x - 50, c.y - 50) < 9;
        Predicate<Coord> shallow = c -> Math.hypot(c.x - 50, c.y - 50) < 11;
        SeaGrid.Tiles t = tc -> {
            if (tc.x < 0 || tc.y < 0 || tc.x >= 100 || tc.y >= 100) return null;
            return land.test(tc) ? LAND : shallow.test(tc) ? SHALLOW : DEEP;
        };
        double swept = sail(t, SHORE_BAND, at(50, 30), true, at(50, 50), 600, 6, shallow);
        assertTrue(swept > 2 * Math.PI, "swept " + Math.toDegrees(swept));
    }

    @Test
    void sailsCounterClockwiseWithTheLandOnTheLeft() {
        Predicate<Coord> land = c -> Math.hypot(c.x - 50, c.y - 50) < 11;
        SeaGrid.Tiles t = shape(100, 100, DEEP, LAND, land);
        double swept = sail(t, SHORE_BAND, at(50, 30), false, at(50, 50), 600, 6, land);
        assertTrue(swept < -2 * Math.PI, "swept " + Math.toDegrees(swept));
    }

    @Test
    void followsACoastWithABayAndACape() {
        // A square island with a deep notch cut into its east side.
        Predicate<Coord> land = c -> c.x >= 35 && c.x < 65 && c.y >= 35 && c.y < 65 && !(c.x >= 52 && c.y >= 46 && c.y < 54);
        SeaGrid.Tiles t = shape(100, 100, DEEP, LAND, land);
        double swept = sail(t, SHORE_BAND, at(50, 25), true, at(50, 50), 900, 6, land);
        assertTrue(swept > 2 * Math.PI, "swept " + Math.toDegrees(swept));
    }

    @Test
    void passesARockOffTheCoastAndKeepsGoing() {
        // A 2x2 rock five tiles off the island's north shore, right on the boat's line: steered round, never circled.
        Predicate<Coord> land = c -> Math.hypot(c.x - 50, c.y - 50) < 11;
        SeaGrid.Tiles t = shape(100, 100, DEEP, LAND, land);
        List<Coord> rock = List.of(Coord.of(44, 34), Coord.of(45, 34), Coord.of(44, 35), Coord.of(45, 35));
        double swept = sail(t, SHORE_BAND, at(60, 30), true, at(50, 50), 900, 6, land, rock);
        assertTrue(swept > 2 * Math.PI, "swept " + Math.toDegrees(swept));
    }

    @Test
    void followsTheDeeperEdge() {
        // A pool of very deep water in the deep band; following the deeper edge keeps it on the chosen side.
        Predicate<Coord> pool = c -> Math.hypot(c.x - 50, c.y - 50) < 10;
        SeaGrid.Tiles t = shape(100, 100, DEEP, DEEPER, pool);
        double swept = sail(t, DEEPER_BAND, at(50, 32), false, at(50, 50), 600, 6, pool);
        assertTrue(swept < -2 * Math.PI, "swept " + Math.toDegrees(swept));
    }

    @Test
    void openWaterHeadsForTheNearestEdge() {
        // Nothing within the lookahead: the boat heads for the coast 40 tiles east rather than circling.
        SeaGrid.Tiles t = shape(120, 100, DEEP, LAND, c -> c.x >= 90);
        Coord2d start = at(50, 50);
        Helm helm = new Helm(true, Math.PI);  // coming from the east
        SeaGrid g = grid(t, SHORE_BAND, start, 14, 2);
        Coord2d target = steer(g, helm, start, t, SHORE_BAND, Collections.emptySet(), 2);
        assertNotNull(target);
        assertTrue(target.x > start.x + 90, "target " + target);
    }
}
