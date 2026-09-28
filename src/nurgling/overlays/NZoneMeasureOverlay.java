package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NMapView;
import nurgling.NUtils;

import java.awt.Color;
import java.util.*;

public class NZoneMeasureOverlay {
    // Ground highlight overlay
    private MCache.Overlay groundOverlay;

    // Border line overlay
    private NZoneBorderOverlay borderOverlay;
    private RenderTree.Slot borderSlot;

    // Zone bounds (in tile coordinates)
    private final Coord tileUL;
    private final Coord tileBR;
    private final int width;
    private final int height;

    // Colors
    private final Color fillColor;
    private final Color edgeColor;

    // Virtual gob for center label
    private OCache.Virtual labelGob;
    private NZoneMeasureLabelSprite labelSprite;

    // Gobs inside the zone at the last count, by resource name, largest first
    private List<Map.Entry<String, Integer>> gobCounts = Collections.emptyList();
    private int gobTotal = 0;

    public NZoneMeasureOverlay(MCache map, Coord tileStart, Coord tileEnd,
                               int width, int height, Color fillColor, Color edgeColor) {
        this.width = width;
        this.height = height;
        this.fillColor = fillColor;
        this.edgeColor = edgeColor;

        // Normalize coordinates (ensure ul < br)
        this.tileUL = new Coord(
            Math.min(tileStart.x, tileEnd.x),
            Math.min(tileStart.y, tileEnd.y)
        );
        this.tileBR = new Coord(
            Math.max(tileStart.x, tileEnd.x),
            Math.max(tileStart.y, tileEnd.y)
        );

        // Create ground overlay with fill color
        Area area = new Area(tileUL, tileBR.add(1, 1));
        groundOverlay = map.new Overlay(area, createOverlayInfo(fillColor));

        // Create border overlay with edge color
        createBorderOverlay();

        // Create center label
        createCenterLabel();
    }

    private MCache.OverlayInfo createOverlayInfo(Color color) {
        return new MCache.OverlayInfo() {
            final Material mat = new Material(
                new BaseColor(color),
                States.maskdepth
            );

            public Collection<String> tags() {
                return Arrays.asList("show");
            }

            public Material mat() {
                return mat;
            }
        };
    }

    private void createBorderOverlay() {
        borderOverlay = new NZoneBorderOverlay(tileUL, tileBR, edgeColor);
        NMapView mapView = (NMapView) NUtils.getGameUI().map;
        borderSlot = mapView.basic.add(borderOverlay);
    }

    private void createCenterLabel() {
        Glob glob = NUtils.getGameUI().ui.sess.glob;
        OCache oc = glob.oc;

        double tileSzX = MCache.tilesz.x;
        double tileSzY = MCache.tilesz.y;

        // Calculate center of zone in world coordinates
        Coord2d center = new Coord2d(
            (tileUL.x + tileBR.x + 1) / 2.0 * tileSzX,
            (tileUL.y + tileBR.y + 1) / 2.0 * tileSzY
        );

        // Create virtual gob with label sprite
        labelGob = oc.new Virtual(center, 0);
        labelGob.virtual = true;
        labelSprite = new NZoneMeasureLabelSprite(labelGob, width, height);
        labelGob.addcustomol(labelSprite);
        oc.add(labelGob);
    }

    public boolean contains(Coord tileCoord) {
        return tileCoord.x >= tileUL.x && tileCoord.x <= tileBR.x &&
               tileCoord.y >= tileUL.y && tileCoord.y <= tileBR.y;
    }

    /**
     * Recounts the loaded gobs whose position falls on the zone's tiles. Skips virtual gobs,
     * the player, gobs whose resource has not loaded yet, and effects.
     */
    public void countGobs(OCache oc) {
        double x0 = tileUL.x * MCache.tilesz.x, y0 = tileUL.y * MCache.tilesz.y;
        double x1 = (tileBR.x + 1) * MCache.tilesz.x, y1 = (tileBR.y + 1) * MCache.tilesz.y;
        long player = NUtils.playerID();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        synchronized (oc) {
            for (Gob gob : oc) {
                if (gob instanceof OCache.Virtual || gob.id == player)
                    continue;
                String name = gob.ngob.name;
                if (name == null || name.startsWith("gfx/fx/"))
                    continue;
                // Half-open bounds: a gob on the line between two zones belongs to one tile only
                if (gob.rc.x >= x0 && gob.rc.x < x1 && gob.rc.y >= y0 && gob.rc.y < y1) {
                    counts.merge(name, 1, Integer::sum);
                    total++;
                }
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        gobCounts = sorted;
        gobTotal = total;
        if (labelSprite != null)
            labelSprite.setGobCount(total);
    }

    public List<Map.Entry<String, Integer>> getGobCounts() {
        return gobCounts;
    }

    public int getGobTotal() {
        return gobTotal;
    }

    public void destroy() {
        if (groundOverlay != null) {
            groundOverlay.destroy();
            groundOverlay = null;
        }

        // Remove border overlay
        if (borderSlot != null) {
            borderSlot.remove();
            borderSlot = null;
            borderOverlay = null;
        }

        // Remove virtual gob
        if (labelGob != null) {
            Glob glob = NUtils.getGameUI().ui.sess.glob;
            glob.oc.remove(labelGob);
            labelGob = null;
            labelSprite = null;
        }
    }

    public Coord getTileUL() {
        return tileUL;
    }

    public Coord getTileBR() {
        return tileBR;
    }
}
