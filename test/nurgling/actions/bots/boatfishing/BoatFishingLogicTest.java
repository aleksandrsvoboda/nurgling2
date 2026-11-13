package nurgling.actions.bots.boatfishing;

import haven.Coord2d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoatFishingLogicTest {
    private static final Coord2d A = Coord2d.of(100, 100);
    private static final Coord2d B = Coord2d.of(144, 122);

    @Test
    void aimsAtMiddleWhenInReach() {
        Coord2d mid = Coord2d.of(122, 111);
        assertEquals(mid, CastGeometry.aim(Coord2d.of(122, 70), A, B));
        // Corners given in any order.
        assertEquals(mid, CastGeometry.aim(Coord2d.of(122, 70), B, A));
    }

    @Test
    void aimsAtNearestEdgeWhenMiddleIsOutOfReach() {
        Coord2d from = Coord2d.of(60, 111);
        Coord2d aim = CastGeometry.aim(from, A, B);
        assertEquals(Coord2d.of(100, 111), aim);
        assertTrue(CastGeometry.inRange(from, aim));
    }

    @Test
    void rangeIsFiveTiles() {
        assertTrue(CastGeometry.inRange(Coord2d.of(0, 0), Coord2d.of(55, 0)));
        assertFalse(CastGeometry.inRange(Coord2d.of(0, 0), Coord2d.of(56, 0)));
    }

    @Test
    void rowingStopsInsideCastRange() {
        Coord2d from = Coord2d.of(0, 0);
        Coord2d target = Coord2d.of(200, 0);
        Coord2d to = CastGeometry.rowTo(from, target);
        assertEquals(200 - CastGeometry.ROW_STOP, to.x, 1e-9);
        assertEquals(0, to.y, 1e-9);
        assertTrue(CastGeometry.inRange(to, target));
        // Already close: stay put.
        assertEquals(from, CastGeometry.rowTo(from, Coord2d.of(30, 0)));
    }

    @Test
    void fishNamesSkipHeaders() {
        assertEquals("Perch", BiteWindow.fishName("Perch:"));
        assertNull(BiteWindow.fishName("This is bait:"));
        assertNull(BiteWindow.fishName("There are fish around:"));
        assertNull(BiteWindow.fishName("45%"));
        assertNull(BiteWindow.fishName(null));
    }

    @Test
    void percentParses() {
        assertEquals(45.0, BiteWindow.percent("45%"));
        assertEquals(12.5, BiteWindow.percent("12.5 %"));
        assertEquals(-1, BiteWindow.percent("Aim for"));
    }

    private static final List<String> FISH = Arrays.asList("Perch", "Bream", "Pike");
    private static final List<Double> CHANCE = Arrays.asList(30.0, 50.0, 20.0);

    @Test
    void picksBestTargetAmongTargets() {
        assertEquals(0, BiteWindow.choose(FISH, CHANCE, Arrays.asList("Pike", "Perch"), false));
    }

    @Test
    void recastsWhenNoTargetUnlessGoForBest() {
        List<String> targets = Collections.singletonList("Salmon");
        assertEquals(-1, BiteWindow.choose(FISH, CHANCE, targets, false));
        assertEquals(1, BiteWindow.choose(FISH, CHANCE, targets, true));
    }

    @Test
    void noTargetsMeansBest() {
        assertEquals(1, BiteWindow.choose(FISH, CHANCE, Collections.emptyList(), false));
        assertEquals(-1, BiteWindow.choose(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), true));
    }
}
