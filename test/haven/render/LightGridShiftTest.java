package haven.render;

import haven.Coord3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/* A camera sliding along moves every eye-space light by the same offset; the compiled
 * light grid should be shifted and reused rather than rebuilt each frame. */
class LightGridShiftTest {
    private static final Projection proj = Projection.ortho(-200, 200, -150, 150, -500, 500);

    private static Object[] point(float x, float y, float z) {
	float[] c = {1, 1, 1, 1};
	return(new Object[] {c, c, c, new float[] {x, y, z, 1}, 1f, 0.01f, 0.001f, 0.1f});
    }

    private static Object[][] lights(float dx, float dy, float dz) {
	return(new Object[][] {point(10 + dx, 20 + dy, 5 + dz), point(-60 + dx, 40 + dy, -30 + dz)});
    }

    private static Lighting.LightGrid.GridLights compile(Lighting.LightGrid g, Object[][] l) {
	return((Lighting.LightGrid.GridLights)g.compile(l, proj));
    }

    @Test
    void sharedShiftReusesGridAndMovesBounds() {
	Lighting.LightGrid g = new Lighting.LightGrid(64, 64, 64);
	Lighting.LightGrid.GridLights a = compile(g, lights(0, 0, 0));
	Lighting.LightGrid.GridLights b = compile(g, lights(7, -3, 2));
	assertSame(a.lstex, b.lstex);
	assertEquals(a.bbox.n.add(Coord3f.of(7, -3, 2)), b.bbox.n);
	assertNotSame(a.ldtex, b.ldtex, "light data (positions, colors) is still per frame");
    }

    @Test
    void unchangedLightsReuseGrid() {
	Lighting.LightGrid g = new Lighting.LightGrid(64, 64, 64);
	assertSame(compile(g, lights(0, 0, 0)).lstex, compile(g, lights(0, 0, 0)).lstex);
    }

    @Test
    void shiftPastMarginRecompiles() {
	Lighting.LightGrid g = new Lighting.LightGrid(64, 64, 64);
	Lighting.LightGrid.GridLights a = compile(g, lights(0, 0, 0));
	/* Margin is 1/8 of the 400-wide view per side = 50. */
	Lighting.LightGrid.GridLights b = compile(g, lights(60, 0, 0));
	assertNotSame(a.lstex, b.lstex);
	assertEquals(a.bbox.n, b.bbox.n);
    }

    @Test
    void lightsMovingApartRecompile() {
	Lighting.LightGrid g = new Lighting.LightGrid(64, 64, 64);
	Object[][] l = lights(0, 0, 0);
	Lighting.LightGrid.GridLights a = compile(g, l);
	Object[][] m = lights(0, 0, 0);
	m[1] = point(-60 + 5, 40, -30);
	assertNotSame(a.lstex, compile(g, m).lstex);
    }
}
