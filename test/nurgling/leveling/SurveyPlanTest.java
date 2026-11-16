package nurgling.leveling;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SurveyPlanTest {
    @TempDir
    Path dir;

    /** Session tiles map straight onto 100x100 grids with id = 1000 + grid x + 10 * grid y. */
    private static final SurveyPlan.Locator LOC = tc -> {
        Coord gc = tc.div(100);
        return(new long[] {1000 + gc.x + (10L * gc.y), tc.x - (gc.x * 100), tc.y - (gc.y * 100)});
    };

    private LevelPlans store() {
        return(new LevelPlans(dir.resolve("p.json").toString(), Runnable::run));
    }

    /** A plan over session tiles [ul, br), in grid 1000 only, with a target. */
    private static LevelPlan plan(LevelPlans s, String name, Coord ul, Coord br, double target) {
        LevelPlan p = s.create(name);
        s.paint(p.id, Collections.singletonMap(1000L, Area.corn(ul, br)), true);
        s.update(s.get(p.id).withTarget(target));
        return(s.get(p.id));
    }

    @Test
    void cornersOfPlanTilesGetThePlanHeightOthersKeepTheGround() {
        LevelPlans s = store();
        LevelPlan p = plan(s, "A", Coord.of(10, 10), Coord.of(12, 12), 50);
        /* Survey tiles 9..12 x 10..10: corners 9..13 x 10..11. */
        Area varea = Area.corni(Coord.of(9, 10), Coord.of(13, 11));
        int[] t = SurveyPlan.targets(p, varea, 1.0, LOC, vc -> 30 + vc.x);
        /* Corner x = 9 touches only tile 9, outside the plan: its ground. */
        assertEquals(39, t[varea.ridx(Coord.of(9, 10))]);
        /* Corners 10..12 touch plan tiles 10 and 11. */
        assertEquals(50, t[varea.ridx(Coord.of(10, 10))]);
        assertEquals(50, t[varea.ridx(Coord.of(12, 11))]);
        /* Corner 13 touches only tile 12, outside. */
        assertEquals(43, t[varea.ridx(Coord.of(13, 11))]);
    }

    @Test
    void granularityScalesAndRounds() {
        LevelPlans s = store();
        LevelPlan p = plan(s, "A", Coord.of(0, 0), Coord.of(5, 5), 10.4);
        Area varea = Area.corni(Coord.of(0, 0), Coord.of(2, 2));
        int[] t = SurveyPlan.targets(p, varea, 10.0 / 11.0, LOC, vc -> 0);
        assertEquals(9, t[0]);
    }

    @Test
    void pickTakesThePlanCoveringMostOfTheSurvey() {
        LevelPlans s = store();
        LevelPlan a = plan(s, "A", Coord.of(0, 0), Coord.of(3, 10), 10);
        LevelPlan b = plan(s, "B", Coord.of(3, 0), Coord.of(10, 10), 20);
        assertEquals(b.id, SurveyPlan.pick(s.plans(), Area.corn(Coord.of(1, 0), Coord.of(6, 3)), LOC).id);
        assertEquals(a.id, SurveyPlan.pick(s.plans(), Area.corn(Coord.of(0, 0), Coord.of(4, 3)), LOC).id);
        assertNull(SurveyPlan.pick(s.plans(), Area.corn(Coord.of(50, 50), Coord.of(55, 55)), LOC));
    }

    @Test
    void aPlanWithoutATargetIsNotPicked() {
        LevelPlans s = store();
        LevelPlan p = s.create("A");
        s.paint(p.id, Collections.singletonMap(1000L, Area.corn(Coord.of(0, 0), Coord.of(5, 5))), true);
        assertNull(SurveyPlan.pick(s.plans(), Area.corn(Coord.of(0, 0), Coord.of(2, 2)), LOC));
    }

    @Test
    void planAcrossAGridEdge() {
        LevelPlans s = store();
        LevelPlan p = s.create("A");
        s.paint(p.id, Collections.singletonMap(1001L, Area.corn(Coord.of(0, 0), Coord.of(2, 2))), true);
        s.update(s.get(p.id).withTarget(7));
        p = s.get(p.id);
        /* Session tiles 98..101: tiles 100 and 101 are grid 1001 tiles 0 and 1. */
        Area varea = Area.corni(Coord.of(98, 0), Coord.of(102, 1));
        int[] t = SurveyPlan.targets(p, varea, 1.0, LOC, vc -> 0);
        assertEquals(0, t[varea.ridx(Coord.of(99, 0))]);
        assertEquals(7, t[varea.ridx(Coord.of(100, 0))]);
        assertEquals(7, t[varea.ridx(Coord.of(102, 1))]);
    }

    @Test
    void fitsChecksTheEightBitRange() {
        assertTrue(SurveyPlan.fits(new int[] {100, 355}));
        assertFalse(SurveyPlan.fits(new int[] {100, 356}));
    }
}
