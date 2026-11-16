package nurgling.leveling;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LevelPlansTest {
    @TempDir
    Path dir;

    /** File writes happen in place, so none is still running when the temp folder is cleaned up. */
    private static LevelPlans plans(String path) {
        return(new LevelPlans(path, Runnable::run));
    }

    private static Map<Long, Iterable<Coord>> rect(long gid, int x0, int y0, int x1, int y1) {
        return(Collections.singletonMap(gid, Area.corn(Coord.of(x0, y0), Coord.of(x1, y1))));
    }

    @Test
    void paintAddsAndRemovesTiles() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        s.paint(p.id, rect(7, 0, 0, 3, 2), true);
        assertEquals(6, s.get(p.id).tiles());
        assertTrue(s.get(p.id).contains(7, 2, 1));
        s.paint(p.id, Collections.singletonMap(7L, List.of(Coord.of(2, 1))), false);
        assertFalse(s.get(p.id).contains(7, 2, 1));
        assertEquals(5, s.get(p.id).tiles());
        /* Out-of-grid offsets are ignored, not wrapped. */
        s.paint(p.id, Collections.singletonMap(7L, List.of(Coord.of(100, 0), Coord.of(-1, 5))), true);
        assertEquals(5, s.get(p.id).tiles());
    }

    @Test
    void aTileBelongsToOnePlan() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan a = s.create("A"), b = s.create("B");
        s.paint(a.id, rect(1, 0, 0, 4, 4), true);
        s.paint(b.id, rect(1, 2, 2, 6, 6), true);
        assertEquals(12, s.get(a.id).tiles());
        assertEquals(16, s.get(b.id).tiles());
        assertSame(s.get(b.id), s.planAt(1, 3, 3));
        /* Erasing with one plan never takes tiles from another. */
        s.paint(b.id, rect(1, 0, 0, 2, 2), false);
        assertEquals(12, s.get(a.id).tiles());
    }

    @Test
    void emptiedGridIsDropped() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        s.paint(p.id, rect(5, 0, 0, 1, 1), true);
        assertTrue(s.touches(5));
        s.paint(p.id, rect(5, 0, 0, 1, 1), false);
        assertFalse(s.touches(5));
    }

    @Test
    void gridRevisionMovesOnlyWhereSomethingChanged() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        s.paint(p.id, rect(1, 0, 0, 2, 2), true);
        long r1 = s.gridRevision(1), r2 = s.gridRevision(2);
        s.paint(p.id, rect(2, 0, 0, 2, 2), true);
        assertEquals(r1, s.gridRevision(1));
        assertTrue(s.gridRevision(2) > r2);
        /* A new target redraws every grid of the plan. */
        r1 = s.gridRevision(1);
        s.update(s.get(p.id).withTarget(12.5));
        assertTrue(s.gridRevision(1) > r1);
        /* Painting the same tiles again changes nothing. */
        r1 = s.gridRevision(1);
        s.paint(p.id, rect(1, 0, 0, 2, 2), true);
        assertEquals(r1, s.gridRevision(1));
    }

    @Test
    void updateKeepsTilesPaintedSinceTheCallerReadThePlan() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        LevelPlan stale = s.get(p.id);
        s.paint(p.id, rect(3, 0, 0, 2, 2), true);
        s.update(stale.withName("B"));
        assertEquals("B", s.get(p.id).name);
        assertEquals(4, s.get(p.id).tiles());
    }

    @Test
    void lockKeepsTargetAndBaseline() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        s.update(s.get(p.id).withTarget(40).lock(123));
        assertTrue(s.get(p.id).locked);
        assertEquals(123, s.get(p.id).baseline, 1e-9);
        s.update(s.get(p.id).unlock());
        assertFalse(s.get(p.id).locked);
        assertTrue(Double.isNaN(s.get(p.id).baseline));
        assertEquals(40, s.get(p.id).target, 1e-9);
    }

    @Test
    void savesAndLoadsEverything() {
        String path = dir.resolve("p.json").toString();
        LevelPlans s = plans(path);
        LevelPlan p = s.create("Village core");
        long big = 0x7fedcba987654321L;
        s.paint(p.id, rect(big, 10, 20, 15, 23), true);
        s.update(s.get(p.id).withMode(LevelPlan.Mode.BUDGET).withBudget(250).withTarget(3412.5).lock(900));
        s.recordGran(10.0 / 11.0);
        s.flush();

        LevelPlans t = plans(path);
        LevelPlan q = t.get(p.id);
        assertNotNull(q);
        assertEquals("Village core", q.name);
        assertEquals(15, q.tiles());
        assertTrue(q.contains(big, 14, 22));
        assertFalse(q.contains(big, 15, 22));
        assertEquals(LevelPlan.Mode.BUDGET, q.mode);
        assertEquals(250, q.budget);
        assertEquals(3412.5, q.target, 1e-9);
        assertTrue(q.locked);
        assertEquals(900, q.baseline, 1e-9);
        assertEquals(10.0 / 11.0, t.gran(), 1e-12);
    }

    @Test
    void deleteRemovesThePlan() {
        LevelPlans s = plans(dir.resolve("p.json").toString());
        LevelPlan p = s.create("A");
        s.paint(p.id, rect(1, 0, 0, 1, 1), true);
        long r = s.gridRevision(1);
        s.delete(p.id);
        assertNull(s.get(p.id));
        assertTrue(s.gridRevision(1) > r);
        assertFalse(s.touches(1));
    }
}
