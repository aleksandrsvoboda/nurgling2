package nurgling.overlays;

import haven.Coord;
import haven.Coord2d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* After Lanfir7/nurgling2's tests of the same (commit 205479b21);
 * here a mask's end is exclusive. */
class NMiningSupportTest {

    @Test
    void roundSupportsKeepTheirRadiiAndAddMonumentalColumn() {
        assertEquals(92, NMiningSupport.specFor("gfx/terobjs/map/naturalminesupport").circleRadius);
        assertEquals(100, NMiningSupport.specFor("gfx/terobjs/minesupport").circleRadius);
        assertEquals(100, NMiningSupport.specFor("gfx/terobjs/ladder").circleRadius);
        assertEquals(100, NMiningSupport.specFor("gfx/terobjs/trees/towercap").circleRadius);
        assertEquals(125, NMiningSupport.specFor("gfx/terobjs/column").circleRadius);
        assertEquals(150, NMiningSupport.specFor("gfx/terobjs/minebeam").circleRadius);
        assertEquals(330, NMiningSupport.specFor("gfx/terobjs/monumentalcolumn").circleRadius);
        assertFalse(NMiningSupport.specFor("gfx/terobjs/monumentalcolumn").isRect());
    }

    @Test
    void tunnelsAreForwardRectangles() {
        NMiningSupport.Spec timber = NMiningSupport.specFor("gfx/terobjs/timbertunnel");
        assertTrue(timber.isRect());
        assertEquals(1, timber.widthTiles);
        assertEquals(4, timber.lengthTiles);
        NMiningSupport.Spec reinf = NMiningSupport.specFor("gfx/terobjs/reinforcedtunnel");
        assertEquals(2, reinf.widthTiles);
        assertEquals(8, reinf.lengthTiles);
        NMiningSupport.Spec arch = NMiningSupport.specFor("gfx/terobjs/stonearchtunnel");
        assertEquals(3, arch.widthTiles);
        assertEquals(15, arch.lengthTiles);
    }

    @Test
    void stumpsAndOtherObjectsHoldNothingUp() {
        assertNull(NMiningSupport.specFor("gfx/terobjs/trees/towercapstump"));
        assertNull(NMiningSupport.specFor("gfx/terobjs/dframe"));
        assertNull(NMiningSupport.specFor(null));
    }

    @Test
    void timberTunnelMatchesVanillaInEveryDirection() {
        Coord2d rc = Coord2d.of(5.5, 5.5);
        assertRect(NMiningSupport.computeRect(rc, 0, 1, 5, false), 5, new Coord(0, 0), new Coord(4, 0));
        assertRect(NMiningSupport.computeRect(rc, Math.PI / 2, 1, 5, false), 5, new Coord(0, 0), new Coord(0, 4));
        assertRect(NMiningSupport.computeRect(rc, Math.PI, 1, 5, false), 5, new Coord(-5, 0), new Coord(-1, 0));
        assertRect(NMiningSupport.computeRect(rc, -Math.PI / 2, 1, 5, false), 5, new Coord(0, -5), new Coord(0, -1));
    }

    @Test
    void reinforcedTunnelMatchesVanillaInEveryDirection() {
        Coord2d rc = Coord2d.of(5.5, 5.5);
        assertRect(NMiningSupport.computeRect(rc, 0, 2, 8, false), 16, new Coord(0, -1), new Coord(7, 0));
        assertRect(NMiningSupport.computeRect(rc, Math.PI / 2, 2, 8, false), 16, new Coord(-1, 0), new Coord(0, 7));
        assertRect(NMiningSupport.computeRect(rc, Math.PI, 2, 8, false), 16, new Coord(-8, -1), new Coord(-1, 0));
        assertRect(NMiningSupport.computeRect(rc, -Math.PI / 2, 2, 8, false), 16, new Coord(-1, -8), new Coord(0, -1));
    }

    @Test
    void stoneArchTunnelMatchesVanillaInEveryDirection() {
        Coord2d rc = Coord2d.of(5.5, 5.5);
        assertRect(NMiningSupport.computeRect(rc, 0, 3, 15, false), 45, new Coord(0, -1), new Coord(14, 1));
        assertRect(NMiningSupport.computeRect(rc, Math.PI / 2, 3, 15, false), 45, new Coord(-1, 0), new Coord(1, 14));
        assertRect(NMiningSupport.computeRect(rc, Math.PI, 3, 15, false), 45, new Coord(-15, -1), new Coord(-1, 1));
        assertRect(NMiningSupport.computeRect(rc, -Math.PI / 2, 3, 15, false), 45, new Coord(-1, -15), new Coord(1, -1));
    }

    /* A preview stands on the frame's line (a tile edge); the built
     * frame's gob sits a little behind it. Both must prop the same
     * tiles in every direction - north and west used to reach one tile
     * too far once built, and the tunneler dug into cave-ins. */
    @Test
    void builtTunnelPropsWhatItsPreviewShowed() {
        double t = 11, behind = 0.1;
        // East, line x=11: tiles x=1..4
        assertCover(NMiningSupport.computeRect(Coord2d.of(t, 5.5), 0, 1, 4, false), new Coord(1, 0), new Coord(5, 1));
        assertCover(NMiningSupport.computeRect(Coord2d.of(t - behind, 5.5), 0, 1, 4, true), new Coord(1, 0), new Coord(5, 1));
        // South, line y=11: tiles y=1..4
        assertCover(NMiningSupport.computeRect(Coord2d.of(5.5, t), Math.PI / 2, 1, 4, false), new Coord(0, 1), new Coord(1, 5));
        assertCover(NMiningSupport.computeRect(Coord2d.of(5.5, t - behind), Math.PI / 2, 1, 4, true), new Coord(0, 1), new Coord(1, 5));
        // West, line x=11: tiles x=-3..0
        assertCover(NMiningSupport.computeRect(Coord2d.of(t, 5.5), Math.PI, 1, 4, false), new Coord(-3, 0), new Coord(1, 1));
        assertCover(NMiningSupport.computeRect(Coord2d.of(t + behind, 5.5), Math.PI, 1, 4, true), new Coord(-3, 0), new Coord(1, 1));
        // North, line y=11: tiles y=-3..0
        assertCover(NMiningSupport.computeRect(Coord2d.of(5.5, t), -Math.PI / 2, 1, 4, false), new Coord(0, -3), new Coord(1, 1));
        assertCover(NMiningSupport.computeRect(Coord2d.of(5.5, t + behind), -Math.PI / 2, 1, 4, true), new Coord(0, -3), new Coord(1, 1));
    }

    private static void assertCover(NMiningSupport.Mask mask, Coord begin, Coord end) {
        assertEquals(begin, mask.begin);
        assertEquals(end, mask.end);
    }

    /* last: the last lit tile (the mask's end is one past it). */
    private static void assertRect(NMiningSupport.Mask mask, int count, Coord first, Coord last) {
        int n = 0;
        for (boolean[] col : mask.data)
            for (boolean v : col)
                if (v) n++;
        assertEquals(count, n);
        assertEquals(first, mask.begin);
        assertEquals(last.add(1, 1), mask.end);
        assertTrue(mask.data[0][0]);
        assertTrue(mask.data[last.x - first.x][last.y - first.y]);
    }
}
