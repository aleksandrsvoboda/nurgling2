package nurgling.sessions.commander;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FocusTest {
    private static final double GRID = MCache.cmaps.x * MCache.tilesz.x;

    @Test
    void gridOfFloorsNegativePositions() {
        assertEquals(Coord.of(0, 0), Focus.gridOf(Coord2d.of(0, 0)));
        assertEquals(Coord.of(0, 0), Focus.gridOf(Coord2d.of(GRID - 0.01, GRID - 0.01)));
        assertEquals(Coord.of(1, -1), Focus.gridOf(Coord2d.of(GRID, -0.01)));
        assertEquals(Coord.of(-17, 19), Focus.gridOf(Coord2d.of(-18309.5, 21532.5)));
    }

    @Test
    void addKeepsTheSegment() {
        Focus f = new Focus(42, Coord2d.of(10, 20)).add(Coord2d.of(5, -5));
        assertEquals(42, f.seg);
        assertEquals(Coord2d.of(15, 15), f.pos);
        assertEquals(Coord.of(0, 0), f.grid());
    }

    @Test
    void ofNullRefIsNull() {
        assertNull(Focus.of((nurgling.sessions.squad.WorldRef) null));
        assertNull(Focus.of(new nurgling.sessions.squad.WorldRef(-1, null, 7, null)));
        Focus f = Focus.of(new nurgling.sessions.squad.WorldRef(-1, null, 7, Coord2d.of(3, 4)));
        assertEquals(7, f.seg);
        assertEquals(Coord2d.of(3, 4), f.pos);
    }
}
