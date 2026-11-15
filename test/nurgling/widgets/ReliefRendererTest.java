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
	ReliefRenderer.render(c, k, 0, 1.0, out);
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
	ReliefRenderer.render(c, 1, 0, 0.0, out);
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
}
