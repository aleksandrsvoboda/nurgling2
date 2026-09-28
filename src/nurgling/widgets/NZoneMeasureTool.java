package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.overlays.NZoneMeasureOverlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NZoneMeasureTool extends Window {
    private final NGameUI gui;

    // State management
    private boolean isClearingSelection = false;

    // Collection of active zone overlays
    private final List<NZoneMeasureOverlay> zones = new ArrayList<>();

    // UI Components
    private Button selectAreaBtn;
    private Button clearOneBtn;
    private Button clearAllBtn;
    private Label statusLabel;
    private Label gobTotalLabel;
    private GobCountList gobCountList;

    // Gob counts follow gobs loading, moving and being removed
    private static final double RECOUNT_INTERVAL = 1.0;
    private double sinceRecount = 0;

    // Color selection
    private NColorWidget fillColorWidget;
    private NColorWidget edgeColorWidget;

    // Default colors
    private static final Color DEFAULT_FILL_COLOR = new Color(255, 200, 0, 64);
    private static final Color DEFAULT_EDGE_COLOR = new Color(255, 200, 0, 255);

    public NZoneMeasureTool(NGameUI gui) {
        super(UI.scale(200, 190), L10n.get("zone.measure_title"), true);
        this.gui = gui;

        Widget prev;

        // Row 1: Select Area button
        prev = add(selectAreaBtn = new Button(UI.scale(180), L10n.get("zone.select_area")) {
            @Override
            public void click() {
                startAreaSelection();
            }
        }, UI.scale(5, 5));

        // Row 2: Clear One button
        prev = add(clearOneBtn = new Button(UI.scale(180), L10n.get("zone.clear_selection")) {
            @Override
            public void click() {
                startClearSelectionMode();
            }
        }, prev.pos("bl").adds(0, 5));

        // Row 3: Clear All button
        prev = add(clearAllBtn = new Button(UI.scale(180), L10n.get("zone.clear_all")) {
            @Override
            public void click() {
                clearAllZones();
            }
        }, prev.pos("bl").adds(0, 5));

        // Color selection section
        prev = add(new Label(L10n.get("zone.colors")), prev.pos("bl").adds(0, 10));

        prev = add(fillColorWidget = new NColorWidget(L10n.get("zone.fill")), prev.pos("bl").adds(0, 2));
        fillColorWidget.color = DEFAULT_FILL_COLOR;

        prev = add(edgeColorWidget = new NColorWidget(L10n.get("zone.edge")), prev.pos("bl").adds(0, 2));
        edgeColorWidget.color = DEFAULT_EDGE_COLOR;

        // Status label
        prev = add(statusLabel = new Label(L10n.get("zone.ready")), prev.pos("bl").adds(0, 10));

        // Gobs in the most recent zone
        prev = add(gobTotalLabel = new Label(""), prev.pos("bl").adds(0, 5));
        prev = add(gobCountList = new GobCountList(UI.scale(180), 8), prev.pos("bl").adds(0, 2));

        pack();
    }

    private void startAreaSelection() {
        isClearingSelection = false;
        statusLabel.settext("Click and drag to select...");

        // Set flag on NMapView to intercept mouse events
        NMapView mapView = (NMapView) gui.map;
        mapView.zoneMeasureMode = true;
        mapView.zoneMeasureTool = this;
    }

    public void onAreaSelected(Coord tileStart, Coord tileEnd) {
        NMapView mapView = (NMapView) gui.map;
        mapView.zoneMeasureMode = false;

        // Calculate dimensions
        int width = Math.abs(tileEnd.x - tileStart.x) + 1;
        int height = Math.abs(tileEnd.y - tileStart.y) + 1;

        // Create overlay with selected colors
        NZoneMeasureOverlay overlay = new NZoneMeasureOverlay(
            gui.map.glob.map, tileStart, tileEnd, width, height,
            fillColorWidget.color, edgeColorWidget.color
        );
        zones.add(overlay);

        statusLabel.settext("Zone: " + width + " x " + height + " tiles");
        recountGobs();
    }

    public void onSelectionCancelled() {
        NMapView mapView = (NMapView) gui.map;
        mapView.zoneMeasureMode = false;
        statusLabel.settext("Selection cancelled");
    }

    private void startClearSelectionMode() {
        if (zones.isEmpty()) {
            statusLabel.settext("No zones to clear");
            return;
        }
        isClearingSelection = true;
        statusLabel.settext("Click a zone to clear...");

        NMapView mapView = (NMapView) gui.map;
        mapView.zoneClearMode = true;
        mapView.zoneMeasureTool = this;
    }

    public void onZoneClicked(Coord tileCoord) {
        if (!isClearingSelection) return;

        // Find zone at click position
        NZoneMeasureOverlay toRemove = null;
        for (NZoneMeasureOverlay zone : zones) {
            if (zone.contains(tileCoord)) {
                toRemove = zone;
                break;
            }
        }

        if (toRemove != null) {
            toRemove.destroy();
            zones.remove(toRemove);
            recountGobs();
            statusLabel.settext("Zone cleared. " + zones.size() + " remaining");
        } else {
            statusLabel.settext("No zone at that location");
        }

        isClearingSelection = false;
        NMapView mapView = (NMapView) gui.map;
        mapView.zoneClearMode = false;
    }

    private void clearAllZones() {
        for (NZoneMeasureOverlay zone : zones) {
            zone.destroy();
        }
        zones.clear();
        recountGobs();
        statusLabel.settext("All zones cleared");
    }

    private void recountGobs() {
        sinceRecount = 0;
        for (NZoneMeasureOverlay zone : zones) {
            zone.countGobs(gui.map.glob.oc);
        }
        if (zones.isEmpty()) {
            gobTotalLabel.settext("");
            gobCountList.setCounts(null);
        } else {
            NZoneMeasureOverlay latest = zones.get(zones.size() - 1);
            gobTotalLabel.settext(L10n.get("zone.gobs", latest.getGobTotal()));
            gobCountList.setCounts(latest.getGobCounts());
        }
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        sinceRecount += dt;
        if (!zones.isEmpty() && sinceRecount >= RECOUNT_INTERVAL) {
            recountGobs();
        }
    }

    public void cleanup() {
        clearAllZones();

        // Reset map view modes
        NMapView mapView = (NMapView) gui.map;
        mapView.zoneMeasureMode = false;
        mapView.zoneClearMode = false;
        mapView.zoneMeasureTool = null;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if ((sender == this) && Objects.equals(msg, "close")) {
            cleanup();
            reqdestroy();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    @Override
    public void destroy() {
        cleanup();
        super.destroy();
    }

    /** Gob types in a zone with their counts, largest first. */
    private static class GobCountList extends Listbox<GobCountList.Row> {
        private static final Text.Foundry fnd = new Text.Foundry(Text.sans, 11).aa(true);
        private List<Row> rows = new ArrayList<>();
        private List<Map.Entry<String, Integer>> shown = null;

        private static class Row {
            final Text name;
            final Text count;

            Row(String res, int count) {
                this.name = fnd.render(String.valueOf(NUtils.prettyResName(res)));
                this.count = fnd.render(String.valueOf(count));
            }
        }

        GobCountList(int w, int h) {
            super(w, h, UI.scale(16));
        }

        void setCounts(List<Map.Entry<String, Integer>> counts) {
            if (Objects.equals(counts, shown))
                return;
            shown = counts;
            for (Row row : rows) {
                row.name.dispose();
                row.count.dispose();
            }
            List<Row> next = new ArrayList<>();
            if (counts != null) {
                for (Map.Entry<String, Integer> e : counts) {
                    next.add(new Row(e.getKey(), e.getValue()));
                }
            }
            rows = next;
        }

        @Override
        protected Row listitem(int i) {
            return rows.get(i);
        }

        @Override
        protected int listitems() {
            return rows.size();
        }

        @Override
        protected void drawitem(GOut g, Row item, int i) {
            int y = (itemh - item.name.sz().y) / 2;
            g.image(item.count.tex(), new Coord(g.sz().x - item.count.sz().x - UI.scale(4), y));
            g.image(item.name.tex(), new Coord(UI.scale(4), y));
        }

        @Override
        protected void itemclick(Row item, int button) {
            // Read-only list
        }
    }
}
