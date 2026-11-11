package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import haven.Coord2d;
import nurgling.actions.bots.worldexplorer.SeaGrid;
import nurgling.conf.NRiverExplorerProp;
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

/** The seam other bots sail River Explorer's boat through: Ask.value, GridWater.tilePart and the reset after a stopover. */
class BoatSeamTest {
    static final String DEEP = "gfx/tiles/deep", SHALLOW = "gfx/tiles/water", LAND = "gfx/tiles/grass";
    static final Predicate<String> FRESH = n -> n.equals(DEEP) || n.startsWith(SHALLOW);
    static final Predicate<String> SEA_P = n -> n.equals("gfx/tiles/odeep") || n.equals("gfx/tiles/owater");
    static final double ROWBOAT = 6.9 + 1;

    static SeaGrid.Tiles fn(BiFunction<Integer, Integer, Character> f) {
        return tc -> {
            char ch = f.apply(tc.x, tc.y);
            return ch == '.' ? DEEP : ch == '~' ? SHALLOW : LAND;
        };
    }

    static SeaGrid.Tiles river() {
        return fn((x, y) -> (y >= 48 && y <= 51) ? '.' : '#');
    }

    static Map<Coord, GridWater> world(Coord... grids) {
        Map<Coord, GridWater> res = new HashMap<>();
        for (Coord g : grids)
            res.put(g, GridWater.build(river(), FRESH, SEA_P, ROWBOAT));
        return res;
    }

    static RiverFrontier.Ask ask(Map<Coord, GridWater> w, Coord player) {
        RiverFrontier.Ask a = new RiverFrontier.Ask();
        a.player = player;
        a.start = w.get(player).from(Coord.of(100, 99));
        a.visited = new HashSet<>(Arrays.asList(player));
        return a;
    }

    @Test
    void noValueChoosesAsBefore() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), Coord.of(1, 0), Coord.of(2, 0));
        RiverFrontier.Choice c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), ask(w, Coord.of(0, 0)));
        assertEquals(Coord.of(2, 0), c.grid);
        assertEquals(1, c.gain);
    }

    @Test
    void valueReplacesTheGain() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), Coord.of(1, 0), Coord.of(2, 0));
        RiverFrontier.Ask a = ask(w, Coord.of(0, 0));
        List<Coord> asked = new ArrayList<>();
        a.value = (grid, reached) -> {
            asked.add(grid);
            // The river runs through both edges of every grid; the boat reaches the west opening of the grids east of it.
            assertFalse(reached.isEmpty());
            return grid.equals(Coord.of(1, 0)) ? 5 : 0;
        };
        RiverFrontier.Choice c = RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), a);
        assertEquals(Coord.of(1, 0), c.grid);
        assertEquals(5, c.gain);
        assertTrue(asked.contains(Coord.of(2, 0)));
        assertFalse(asked.contains(Coord.of(0, 0)), "the boat's own (visited) grid is never asked");
    }

    @Test
    void valueZeroEverywhereMeansDone() {
        Map<Coord, GridWater> w = world(Coord.of(0, 0), Coord.of(1, 0), Coord.of(2, 0));
        RiverFrontier.Ask a = ask(w, Coord.of(0, 0));
        a.value = (grid, reached) -> 0;
        assertNull(RiverFrontier.choose(new RiverFrontier.World(w.keySet(), w), a));
    }

    @Test
    void tilePartsFollowTheWaterTheHullFits() {
        // The river (rows 48..51) and a creek at the top (rows 2..5) the boat can't get to from the river.
        GridWater g = GridWater.build(fn((x, y) -> (y >= 48 && y <= 51) ? '.' : (y >= 2 && y <= 5 && x >= 10 && x <= 40) ? '~' : '#'),
                FRESH, SEA_P, ROWBOAT);
        int river = g.openings.get(0).part;
        assertEquals(river, g.tilePart(50, 49));
        assertEquals(river, g.tilePart(0, 50));
        int creek = g.tilePart(25, 3);
        assertTrue(creek >= 0);
        assertNotEquals(river, creek);
        assertEquals(-1, g.tilePart(50, 30), "land");
        // The river's outermost rows: water, but too near the bank for a rowboat's middle.
        assertEquals(-1, g.tilePart(50, 48));
    }

    @Test
    void aStopIsNotTakenForASnag() {
        Boat b = new Boat(new NRiverExplorerProp("", ""), new Hull(6.9, 15.9, false));
        b.lastTarget = Coord2d.of(100, 100);
        b.progressT = 0;
        b.prevLink = "1>2";
        // Rowing and stuck since the start of the leg...
        assertTrue(b.stuck(Boat.SNAG_MS + 1));
        // ...until a stop: the clock starts afresh where the boat is, the click is redone, the last crossing forgotten.
        b.resume(Coord2d.of(50, 50), 10_000);
        assertNull(b.lastTarget);
        assertEquals(Coord2d.of(50, 50), b.progressRc);
        assertEquals(10_000, b.progressT);
        assertNull(b.prevLink);
        assertFalse(b.stuck(10_000 + Boat.SNAG_MS + 1), "no click out yet: not stuck");
        b.lastTarget = Coord2d.of(60, 60);
        assertFalse(b.stuck(10_000 + Boat.SNAG_MS - 1));
        assertTrue(b.stuck(10_000 + Boat.SNAG_MS + 1));
    }
}
