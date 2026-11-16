package nurgling.widgets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReliefRendererTest {
    private static final int N = 8, GREY = 0x808080;

    /** Every tile, border included, of one kind and colour, at height 0. */
    private static ReliefRenderer.Cells flat() {
	ReliefRenderer.Cells c = new ReliefRenderer.Cells(N);
	for(int y = -1; y <= N; y++) {
	    for(int x = -1; x <= N; x++) {
		int i = c.at(x, y);
		c.tile[i] = 0;
		c.rgb[i] = GREY;
	    }
	}
	return(c);
    }

    private static int red(byte[] px, int k, int x, int y) {
	return(px[((y * N * k) + x) * 4] & 0xff);
    }

    private static byte[] render(ReliefRenderer.Cells c, int k) {
	byte[] out = new byte[N * k * N * k * 4];
	ReliefRenderer.render(c, k, 0, 1.0, 0, out);
	return(out);
    }

    @Test
    void flatGroundKeepsItsColour() {
	byte[] px = render(flat(), 1);
	for(int y = 0; y < N; y++)
	    for(int x = 0; x < N; x++)
		assertEquals(0x80, red(px, 1, x, y));
    }

    @Test
    void unexploredNeighboursAddNoFakeCliff() {
	ReliefRenderer.Cells c = flat();
	for(int y = -1; y <= N; y++) {
	    for(int x = -1; x <= N; x++) {
		int i = c.at(x, y);
		/* Raised ground inside; outside the grid nothing is known (and reads as z = 0). */
		if((x < 0) || (y < 0) || (x >= N) || (y >= N))
		    c.tile[i] = -1;
		else
		    c.z[i] = 50;
	    }
	}
	byte[] px = render(c, 2);
	for(int y = 0; y < N * 2; y++)
	    for(int x = 0; x < N * 2; x++)
		assertEquals(0x80, red(px, 2, x, y), "pixel " + x + "," + y);
    }

    @Test
    void slopesFacingNorthWestAreLit() {
	ReliefRenderer.Cells up = flat(), down = flat();
	for(int y = -1; y <= N; y++) {
	    for(int x = -1; x <= N; x++) {
		/* Rising to the south-east faces the north-west light. */
		up.z[up.at(x, y)] = (x + y) * 2;
		down.z[down.at(x, y)] = -(x + y) * 2;
	    }
	}
	assertTrue(red(render(up, 1), 1, 4, 4) > 0x80);
	assertTrue(red(render(down, 1), 1, 4, 4) < 0x80);
    }

    @Test
    void zeroStrengthIsFlat() {
	ReliefRenderer.Cells c = flat();
	for(int y = -1; y <= N; y++)
	    for(int x = -1; x <= N; x++)
		c.z[c.at(x, y)] = x * 10;
	byte[] out = new byte[N * N * 4];
	ReliefRenderer.render(c, 1, 0, 0.0, 0, out);
	assertEquals(0x80, red(out, 1, 3, 3));
    }

    @Test
    void tileBoundaryIsAThinLineUpClose() {
	ReliefRenderer.Cells c = flat();
	for(int y = -1; y <= N; y++)
	    for(int x = 4; x <= N; x++)
		c.tile[c.at(x, y)] = 1;
	byte[] px = render(c, 4);
	/* Tile x = 4 starts at pixel 16: only its first column is darkened. */
	assertTrue(red(px, 4, 16, 8) < 0x80);
	assertEquals(0x80, red(px, 4, 17, 8));
	assertEquals(0x80, red(px, 4, 15, 8));
    }

    @Test
    void cliffsAreDarkAndTheirNeighboursShaded() {
	ReliefRenderer.Cells c = flat();
	c.cliff[c.at(4, 4)] = true;
	byte[] px = render(c, 1);
	assertEquals((ReliefRenderer.CLIFF >> 16) & 0xff, red(px, 1, 4, 4));
	assertTrue(red(px, 1, 5, 4) < 0x80);
	assertEquals(0x80, red(px, 1, 6, 4));
    }

    /** Ground stepping up by one contour interval at tile x = 4; no relief so only the lines show. */
    private static byte[] step(int k, double interval, boolean water) {
	ReliefRenderer.Cells c = flat();
	for(int y = -1; y <= N; y++) {
	    for(int x = -1; x <= N; x++) {
		c.z[c.at(x, y)] = (x >= 4) ? 12 : 8;
		c.water[c.at(x, y)] = water;
	    }
	}
	byte[] out = new byte[N * k * N * k * 4];
	ReliefRenderer.render(c, k, 0, 0.0, interval, out);
	return(out);
    }

    @Test
    void contourIsAThinLineUpClose() {
	byte[] px = step(4, 10, false);
	assertTrue(red(px, 4, 16, 8) < 0x80);
	assertEquals(0x80, red(px, 4, 17, 8));
	assertEquals(0x80, red(px, 4, 15, 8));
    }

    @Test
    void contourDarkensTheHighSideAtOnePixelPerTile() {
	byte[] px = step(1, 10, false);
	assertTrue(red(px, 1, 4, 3) < 0x80);
	assertEquals(0x80, red(px, 1, 3, 3));
	assertEquals(0x80, red(px, 1, 5, 3));
    }

    @Test
    void noContoursWhenOffOrOnWater() {
	assertEquals(0x80, red(step(4, 0, false), 4, 16, 8));
	assertEquals(0x80, red(step(4, 10, true), 4, 16, 8));
    }

    @Test
    void contoursWidenWhenZoomedOut() {
	assertEquals(10, ReliefRenderer.contourinterval(10, 0));
	assertEquals(10, ReliefRenderer.contourinterval(10, 1));
	assertEquals(40, ReliefRenderer.contourinterval(10, 3));
    }

    private static int px(byte[] px, int k, int x, int y) {
	int o = ((y * N * k) + x) * 4;
	return(((px[o] & 0xff) << 16) | ((px[o + 1] & 0xff) << 8) | (px[o + 2] & 0xff));
    }

    /** Flat ground at height 10, tiles 2..5 of every row in plan 0 with the given target; no shading. */
    private static byte[] planned(int k, float target, int lvl) {
	ReliefRenderer.Cells c = flat();
	for(int y = -1; y <= N; y++) {
	    for(int x = -1; x <= N; x++) {
		int i = c.at(x, y);
		c.z[i] = 10;
		if((x >= 2) && (x <= 5)) {
		    c.plan[i] = 0;
		    c.target[i] = target;
		}
	    }
	}
	byte[] out = new byte[N * k * N * k * 4];
	ReliefRenderer.render(c, k, lvl, 0.0, 0, out);
	return(out);
    }

    @Test
    void noPlanChangesNothing() {
	ReliefRenderer.Cells c = flat();
	byte[] a = render(c, 4);
	ReliefRenderer.Cells d = flat();
	d.target[d.at(3, 3)] = 5; /* A target without a plan is ignored. */
	assertArrayEquals(a, render(d, 4));
    }

    @Test
    void groundAboveTheTargetIsTintedToDig() {
	int c = px(planned(1, 4, 0), 1, 3, 4);
	assertTrue(((c >> 16) & 0xff) > ((c >> 8) & 0xff), "reddish");
	assertTrue(((c >> 16) & 0xff) > (c & 0xff), "reddish");
	/* Outside the plan the ground keeps its colour. */
	assertEquals(GREY, px(planned(1, 4, 0), 1, 7, 4));
    }

    @Test
    void groundBelowTheTargetIsTintedToFill() {
	int c = px(planned(1, 16, 0), 1, 3, 4);
	assertTrue((c & 0xff) > ((c >> 16) & 0xff), "bluish");
    }

    @Test
    void groundAtTheTargetIsLevel() {
	int c = px(planned(1, 10.2f, 0), 1, 3, 4);
	assertTrue(((c >> 8) & 0xff) > ((c >> 16) & 0xff), "greenish");
	assertTrue(((c >> 8) & 0xff) > (c & 0xff), "greenish");
    }

    @Test
    void furtherOffIsStronger() {
	int near = px(planned(1, 8, 0), 1, 3, 4), far = px(planned(1, -10, 0), 1, 3, 4);
	assertTrue((((far >> 16) & 0xff) - (far & 0xff)) > (((near >> 16) & 0xff) - (near & 0xff)));
    }

    @Test
    void outlineIsAThinOrangeLineUpClose() {
	byte[] px = planned(4, 10, 0);
	/* The plan starts at tile 2 (pixel 8) and the first tile out of it is 6 (pixel 24). */
	assertEquals(ReliefRenderer.PLANLINE, px(px, 4, 8, 9));
	assertEquals(ReliefRenderer.PLANLINE, px(px, 4, 24, 9));
	assertNotEquals(ReliefRenderer.PLANLINE, px(px, 4, 9, 9));
	assertNotEquals(ReliefRenderer.PLANLINE, px(px, 4, 12, 9));
	/* The plan runs on through the top and bottom of the grid: no line across it there. */
	assertNotEquals(ReliefRenderer.PLANLINE, px(px, 4, 13, 0));
    }

    @Test
    void zoomedOutIsAWashNotDigOrFill() {
	int lo = px(planned(1, -50, 2), 1, 3, 4), hi = px(planned(1, 50, 2), 1, 3, 4);
	assertEquals(lo, hi);
	assertNotEquals(GREY, lo);
    }

    @Test
    void noTileEdgesWhenOff() {
	ReliefRenderer.Cells c = flat();
	c.edges = false;
	for(int y = -1; y <= N; y++)
	    for(int x = 4; x <= N; x++)
		c.tile[c.at(x, y)] = 1;
	assertEquals(0x80, red(render(c, 4), 4, 16, 8));
    }
}
