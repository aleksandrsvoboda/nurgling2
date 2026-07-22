package nurgling.overlays.map;

import haven.*;
import nurgling.overlays.NTreeHarvestOl;
import nurgling.tools.LpExplorer;

import java.awt.Color;
import java.util.function.BiPredicate;

/**
 * Draws the undiscovered product's own icon (green-tinted) on the minimap over any currently
 * loaded gob that still has an undiscovered LP product, falling back to a flat dot when no icon
 * is resolvable (e.g. ground herbs/mushrooms). Mirrors NLPassistant's 3D-world overlay and shares
 * the same NConfig.Key.lpassistent toggle. Right-click hit-testing is handled by gobAt(), used
 * from NMiniMap.mouseup() to open the same flower menu a real gob icon would.
 */
public class MinimapDiscoveryRenderer {
    private static final Color FALLBACK_TINT = new Color(60, 255, 0, 255);
    private static final int FALLBACK_RADIUS_PX = 5;

    public static void renderDiscoveryMarkers(MiniMap map, GOut g) {
        Coord fallbackHalf = new Coord(UI.scale(FALLBACK_RADIUS_PX), UI.scale(FALLBACK_RADIUS_PX));
        forEachDiscoverableGob(map, (gob, product) -> {
            TexI icon = LpExplorer.getMarkerIcon(gob, product);
            Coord screenPos = map.p2c(gob.rc);
            Coord half = icon != null ? icon.sz().div(2) : fallbackHalf;
            if (screenPos.x < -half.x || screenPos.x > map.sz.x + half.x ||
                screenPos.y < -half.y || screenPos.y > map.sz.y + half.y)
                return false;

            if (icon != null) {
                g.usestate(new ColorMask(NTreeHarvestOl.LP_UNDISCOVERED_TINT));
                g.image(icon, screenPos.sub(half));
                g.defstate();
            } else {
                g.chcolor(FALLBACK_TINT);
                g.fellipse(screenPos, half);
                g.chcolor();
            }
            return false; // never stop early; draw every marker
        });
    }

    /** Finds the discoverable gob (if any) whose marker is under the given minimap screen coordinate. */
    public static Gob gobAt(MiniMap map, Coord screenCoord) {
        Gob[] hit = new Gob[1];
        forEachDiscoverableGob(map, (gob, product) -> {
            TexI icon = LpExplorer.getMarkerIcon(gob, product);
            int threshold = icon != null
                ? Math.max(icon.sz().x, icon.sz().y) / 2 + UI.scale(3)
                : UI.scale(FALLBACK_RADIUS_PX + 3);
            if (map.p2c(gob.rc).dist(screenCoord) >= threshold)
                return false;
            hit[0] = gob;
            return true; // stop at first match
        });
        return hit[0];
    }

    /**
     * Visits every loaded gob with an undiscovered LP product, along with which product it is
     * (resolved once here rather than separately by every caller). The visitor returns true to
     * stop early.
     */
    private static void forEachDiscoverableGob(MiniMap map, BiPredicate<Gob, String> visitor) {
        if (!LpExplorer.isEnabled())
            return;
        if (map.ui == null || map.ui.sess == null || map.dloc == null)
            return;

        OCache oc = map.ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                try {
                    if (gob.ngob == null)
                        continue;
                    String product = LpExplorer.firstUndiscoveredProduct(gob);
                    if (product != null && visitor.test(gob, product))
                        return;
                } catch (Loading l) {
                    // Position not ready yet this frame, skip.
                }
            }
        }
    }
}
