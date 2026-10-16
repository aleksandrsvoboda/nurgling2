package nurgling.planning;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WorldCaptureTest {

    private static PlannerPreset.Ghost g(String res, double x, double y) {
        return new PlannerPreset.Ghost(res, null, x, y, 0);
    }

    /** A one-tile box around each object. */
    private static Map<PlannerPreset.Ghost, double[]> tiles(List<PlannerPreset.Ghost> objects) {
        Map<PlannerPreset.Ghost, double[]> res = new IdentityHashMap<>();
        for (PlannerPreset.Ghost o : objects)
            res.put(o, new double[] {o.x - 5.5, o.y - 5.5, o.x + 5.5, o.y + 5.5});
        return res;
    }

    @Test void objectsAndGhostsGoInOneLayerNamedAfterThePreset() {
        List<PlannerPreset.Ghost> objects = List.of(g("gfx/terobjs/cupboard", 1105.5, -434.5));
        List<PlannerPreset.Ghost> ghosts = List.of(g("gfx/terobjs/kiln", 1127.5, -423.5));
        PlannerPreset p = WorldCapture.build("Yard", objects, tiles(objects), ghosts);
        assertNotNull(p);
        assertEquals(PlannerPreset.Kind.LAYER, p.kind);
        assertEquals(1, p.layers.size());
        assertEquals("Yard", p.layers.get(0).name);
        assertEquals(2, p.ghostCount());
    }

    @Test void boxStartsOnATileCornerAndKeepsSubTileOffsets() {
        List<PlannerPreset.Ghost> objects = List.of(g("a", 1105.5, -434.5), g("b", 1127.5, -423.5));
        PlannerPreset p = WorldCapture.build("P", objects, tiles(objects), List.of());
        PlannerPreset.Ghost a = p.layers.get(0).ghosts.get(0), b = p.layers.get(0).ghosts.get(1);
        assertEquals(5.5, a.x, 1e-9);
        assertEquals(5.5, a.y, 1e-9);
        assertEquals(27.5, b.x, 1e-9);
        assertEquals(16.5, b.y, 1e-9);
        assertEquals(3, p.w);
        assertEquals(2, p.h);
    }

    @Test void aGhostOfAnObjectAlreadyBuiltThereIsSavedOnce() {
        List<PlannerPreset.Ghost> objects = List.of(g("gfx/terobjs/kiln", 100, 100));
        List<PlannerPreset.Ghost> ghosts = List.of(
                g("gfx/terobjs/kiln", 100.8, 100.5),     // the same kiln, planned before it was built
                g("gfx/terobjs/kiln", 130, 100),         // another kiln still to build
                g("gfx/terobjs/cupboard", 100, 100));    // something else on the same spot
        PlannerPreset p = WorldCapture.build("P", objects, tiles(objects), ghosts);
        assertEquals(3, p.ghostCount());
    }

    @Test void nothingAtAllIsNoPreset() {
        assertNull(WorldCapture.build("P", List.of(), new IdentityHashMap<>(), List.of()));
    }

    @Test void onlyGhostsStillMakeAPreset() {
        PlannerPreset p = WorldCapture.build("P", List.of(), new IdentityHashMap<>(), List.of(g("gfx/terobjs/kiln", 50, 50)));
        assertNotNull(p);
        assertEquals(1, p.ghostCount());
    }

    @Test void savedPresetRoundTripsThroughJson() {
        List<PlannerPreset.Ghost> objects = List.of(g("gfx/terobjs/cupboard", 1105.5, -434.5));
        PlannerPreset p = WorldCapture.build("Yard", objects, tiles(objects), List.of());
        PlannerPreset back = PlannerPreset.fromJson(p.toJson());
        assertEquals(p.w, back.w);
        assertEquals(p.h, back.h);
        assertEquals("gfx/terobjs/cupboard", back.layers.get(0).ghosts.get(0).res);
    }
}
