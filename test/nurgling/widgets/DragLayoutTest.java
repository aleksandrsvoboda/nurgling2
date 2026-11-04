package nurgling.widgets;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DragLayoutTest {
    /* Frame 135x120 with content at (10,10) sized 100x100: insets 10 left/top/bottom, 25 right. */
    static final Area BOX = Area.sized(new Coord(10, 10), new Coord(100, 100));
    static final Coord SCREEN = new Coord(1000, 800);

    @Test void clampLetsTheFrameHangOffScreenByItsInset() {
        assertEquals(new Coord(-10, -10), DragLayout.clamp(new Coord(-10, -10), BOX, SCREEN));
        assertEquals(new Coord(890, 690), DragLayout.clamp(new Coord(890, 690), BOX, SCREEN));
    }

    @Test void clampKeepsTheContentOnScreen() {
        assertEquals(new Coord(-10, -10), DragLayout.clamp(new Coord(-60, -40), BOX, SCREEN));
        assertEquals(new Coord(890, 690), DragLayout.clamp(new Coord(950, 790), BOX, SCREEN));
    }

    @Test void clampLeavesAnOnScreenPositionAlone() {
        // No more jumping onto the edge from a few pixels away.
        assertEquals(new Coord(885, 685), DragLayout.clamp(new Coord(885, 685), BOX, SCREEN));
    }

    @Test void contentWiderThanTheScreenShowsItsTopLeft() {
        Area wide = Area.sized(new Coord(10, 10), new Coord(1200, 900));
        assertEquals(new Coord(-10, -10), DragLayout.clamp(new Coord(300, 200), wide, SCREEN));
    }
}
