package nurgling.overlays.map;

import haven.*;
import nurgling.NConfig;
import nurgling.tools.VSpec;

import java.awt.Color;

/**
 * Draws a flat green dot on the minimap over any currently loaded gob that still has an
 * undiscovered LP product, mirroring NLPassistant's 3D-world overlay. Shares the same
 * NConfig.Key.lpassistent toggle as NLPassistant. Right-click hit-testing is handled by
 * gobAt(), used from NMiniMap.mouseup() to open the same flower menu a real gob icon would.
 */
public class MinimapDiscoveryRenderer {
    private static final Color MARKER_TINT = new Color(60, 255, 0, 255);
    private static final int MARKER_RADIUS_PX = 5;

    public static void renderDiscoveryMarkers(MiniMap map, GOut g) {
        if (!(Boolean) NConfig.get(NConfig.Key.lpassistent))
            return;
        if (map.ui == null || map.ui.sess == null || map.dloc == null)
            return;

        Coord half = new Coord(UI.scale(MARKER_RADIUS_PX), UI.scale(MARKER_RADIUS_PX));
        OCache oc = map.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                try {
                    if (gob.ngob == null || !VSpec.hasUndiscoveredProduct(gob.ngob.name))
                        continue;

                    Coord screenPos = map.p2c(gob.rc);
                    if (screenPos.x < -half.x || screenPos.x > map.sz.x + half.x ||
                        screenPos.y < -half.y || screenPos.y > map.sz.y + half.y)
                        continue;

                    g.chcolor(MARKER_TINT);
                    g.fellipse(screenPos, half);
                    g.chcolor();
                } catch (Loading l) {
                    // Position not ready yet this frame, skip.
                }
            }
        }
    }

    /** Finds the discoverable gob (if any) whose marker is under the given minimap screen coordinate. */
    public static Gob gobAt(MiniMap map, Coord screenCoord) {
        if (!(Boolean) NConfig.get(NConfig.Key.lpassistent))
            return null;
        if (map.ui == null || map.ui.sess == null || map.dloc == null)
            return null;

        int threshold = UI.scale(MARKER_RADIUS_PX + 3);
        OCache oc = map.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                try {
                    if (gob.ngob == null || !VSpec.hasUndiscoveredProduct(gob.ngob.name))
                        continue;
                    if (map.p2c(gob.rc).dist(screenCoord) < threshold)
                        return gob;
                } catch (Loading l) {
                    // Position not ready yet this frame, skip.
                }
            }
        }
        return null;
    }
}
