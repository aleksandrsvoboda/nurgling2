package nurgling.overlays.map;

import haven.*;
import nurgling.overlays.NObjHarvestOl;
import nurgling.tools.LpExplorer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draws the undiscovered products' own icons (green-tinted) on the minimap over any currently
 * loaded gob that still has something left to find, falling back to a flat dot while the icons
 * are still loading. Mirrors NLPassistant's 3D-world overlay - same combined multi-product image,
 * same NConfig.Key.lpassistent toggle - so the two views agree. Right-click hit-testing is handled
 * by gobAt(), used from NMiniMap.mouseup() to open the same flower menu a real gob icon would.
 *
 * Everything here runs on the render thread, which shapes two decisions: gob iteration snapshots
 * the OCache rather than working under its monitor, and icon resolution is non-blocking.
 */
public class MinimapDiscoveryRenderer {
    private static final Color FALLBACK_TINT = new Color(60, 255, 0, 255);
    private static final int FALLBACK_RADIUS_PX = 5;

    /** A gob that still has undiscovered LP products, paired with which products those are. */
    private static class Marker {
        final Gob gob;
        final List<String> products;

        Marker(Gob gob, List<String> products) {
            this.gob = gob;
            this.products = products;
        }
    }

    public static void renderDiscoveryMarkers(MiniMap map, GOut g) {
        Coord fallbackHalf = new Coord(UI.scale(FALLBACK_RADIUS_PX), UI.scale(FALLBACK_RADIUS_PX));
        for (Marker marker : discoverable(map)) {
            // Non-blocking: an icon that hasn't loaded yet is simply left out of the composition
            // (and the whole marker falls back to a dot if none of them have), rather than parking
            // the render thread on a resource fetch.
            TexI icon = LpExplorer.getMarkerIcon(marker.gob, marker.products, false, false);
            Coord screenPos = map.p2c(marker.gob.rc);
            Coord half = icon != null ? icon.sz().div(2) : fallbackHalf;
            if (screenPos.x < -half.x || screenPos.x > map.sz.x + half.x ||
                screenPos.y < -half.y || screenPos.y > map.sz.y + half.y)
                continue;

            if (icon != null) {
                g.usestate(new ColorMask(NObjHarvestOl.LP_UNDISCOVERED_TINT));
                g.image(icon, screenPos.sub(half));
                g.defstate();
            } else {
                g.chcolor(FALLBACK_TINT);
                g.fellipse(screenPos, half);
                g.chcolor();
            }
        }
    }

    /** Finds the discoverable gob (if any) whose marker is under the given minimap screen coordinate. */
    public static Gob gobAt(MiniMap map, Coord screenCoord) {
        for (Marker marker : discoverable(map)) {
            TexI icon = LpExplorer.getMarkerIcon(marker.gob, marker.products, false, false);
            int threshold = icon != null
                ? Math.max(icon.sz().x, icon.sz().y) / 2 + UI.scale(3)
                : UI.scale(FALLBACK_RADIUS_PX + 3);
            if (map.p2c(marker.gob.rc).dist(screenCoord) < threshold)
                return marker.gob;
        }
        return null;
    }

    /** Every loaded gob that still has an undiscovered LP product, with its products resolved. */
    private static List<Marker> discoverable(MiniMap map) {
        if (!LpExplorer.isEnabled())
            return Collections.emptyList();
        if (map.ui == null || map.ui.sess == null)
            return Collections.emptyList();
        // The same guard MiniMap.drawicons() applies to its own gob icons. Without the sessloc
        // check, p2c() -> st2c() dereferences a null sessloc; without the segment check, the
        // transform is meaningless whenever the minimap is panned to a segment other than the
        // session's, and markers land at arbitrary points over unrelated terrain.
        if ((map.dloc == null) || (map.sessloc == null) || (map.dloc.seg.id != map.sessloc.seg.id))
            return Collections.emptyList();

        // Snapshot under the OCache monitor and do everything else - the discovery scan, icon
        // resolution, drawing - outside it, exactly how OCache.ctick()/gtick() iterate. Holding
        // the monitor across that work would block every object update behind a frame's rendering.
        OCache oc = map.ui.sess.glob.oc;
        List<Gob> gobs = new ArrayList<>();
        synchronized (oc) {
            for (Gob gob : oc)
                gobs.add(gob);
        }

        List<Marker> markers = new ArrayList<>();
        for (Gob gob : gobs) {
            try {
                if (gob.ngob == null)
                    continue;
                List<String> products = LpExplorer.allUndiscoveredProducts(gob);
                if (!products.isEmpty())
                    markers.add(new Marker(gob, products));
            } catch (Loading l) {
                // Sprite not ready yet this frame, skip.
            }
        }
        return markers;
    }
}
