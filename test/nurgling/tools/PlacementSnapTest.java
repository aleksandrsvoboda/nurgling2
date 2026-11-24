package nurgling.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlacementSnapTest {
    // Smelter footprint on y, origin-relative: -20 .. 11 (not centred on the origin).
    private static final double LO = -20, HI = 11;

    @Test
    void ghostAboveNeighbourEndsFlushWithItsTop() {
        // Neighbour smelter at y=100 spans 80..111. Ghost cursor a bit above it.
        double[] r = PlacementSnap.snapAxis(70, LO, HI, 80, 111, 11);
        assertNotNull(r);
        assertEquals(80, r[1] + HI, 1e-9, "ghost bottom edge must touch neighbour top edge");
    }

    @Test
    void ghostBelowNeighbourEndsFlushWithItsBottom() {
        double[] r = PlacementSnap.snapAxis(130, LO, HI, 80, 111, 11);
        assertNotNull(r);
        assertEquals(111, r[1] + LO, 1e-9, "ghost top edge must touch neighbour bottom edge");
    }

    @Test
    void sameSizeNeighbourAlignsOrigins() {
        // Two smelters side by side: aligning the edges puts the origins on one line.
        double[] r = PlacementSnap.snapAxis(103, LO, HI, 80, 111, 11);
        assertNotNull(r);
        assertEquals(100, r[1], 1e-9);
    }

    @Test
    void nothingWithinCapture() {
        assertNull(PlacementSnap.snapAxis(200, LO, HI, 80, 111, 11));
    }
}
