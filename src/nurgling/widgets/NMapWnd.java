package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tools.MapDbTransfer;
import nurgling.i18n.L10n;

import java.util.Map;

import static haven.MCache.tilesz;

public class NMapWnd extends MapWnd {
    public String searchPattern = "";  // For terrain/tile search
    public String markerSearchPattern = "";  // For marker/icon search
    public Resource.Image searchRes = null;
    MapToggleButton treeBtn;
    MapToggleButton fishBtn;
    MapToggleButton mapToolsBtn;
    MapToggleButton vectorClearBtn;
    TextEntry markerSearchField;
    Button dbExportBtn;
    Button dbImportBtn;
    private static final int btnw = UI.scale(95);
    private static final int dbbtnw = UI.scale(110);

    public class MapToggleButton extends ICheckBox {
        private final Runnable rightClickAction;
        
        public MapToggleButton(String base, String tooltip, Runnable rightClickAction) {
            super("nurgling/hud/buttons/" + base + "/", "u", "d", "h", "dh");
            this.rightClickAction = rightClickAction;
            settip(tooltip);
        }
        
        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if(ev.b == 3 && checkhit(ev.c)) {
                if(rightClickAction != null)
                    rightClickAction.run();
                return true;
            }
            return super.mousedown(ev);
        }
    }

    public NMapWnd(MapFile file, MapView mv, Coord sz, String title) {
        super(file, mv, sz, title);
        searchRes = Resource.local().loadwait("alttex/selectedtex").layer(Resource.imgc);
        
        // Position buttons in top-right corner (15px right, 10px down from original position)
        int btnSpacing = UI.scale(5);
        Coord btnPos = view.c.add(view.sz.x - UI.scale(35), UI.scale(15));
        
        // Map tools button (rightmost) - opens the Map Tools panel (no icon toggle)
        mapToolsBtn = add(new MapToggleButton("maptools", L10n.get("maptools.button_tip"), MapToolsWindow::toggle), btnPos);
        mapToolsBtn.a = false; // Always show as unpressed (no toggle state)
        mapToolsBtn.click(MapToolsWindow::toggle); // Left click opens the panel

        // Fish button (middle) - shares its state with the Map Tools panel through NConfig
        btnPos = btnPos.sub(mapToolsBtn.sz.x + btnSpacing, 0);
        fishBtn = add(new MapToggleButton("fish", "Toggle fish icons (Right-click: Fish Search)", MapToolsWindow::openFishSearch), btnPos);
        fishBtn.state(() -> NMiniMap.showFishIcons());
        fishBtn.set(val -> NMiniMap.showFishIcons(val));

        // Tree button
        btnPos = btnPos.sub(fishBtn.sz.x + btnSpacing, 0);
        treeBtn = add(new MapToggleButton("tree", "Toggle tree icons (Right-click: Tree Search)", MapToolsWindow::openTreeSearch), btnPos);
        treeBtn.state(() -> NMiniMap.showTreeIcons());
        treeBtn.set(val -> NMiniMap.showTreeIcons(val));

        // Vector clear button (leftmost)
        btnPos = btnPos.sub(treeBtn.sz.x + btnSpacing, 0);
        vectorClearBtn = add(new MapToggleButton("vector", "Clear tracking vectors", null), btnPos);
        vectorClearBtn.a = false; // Always show as unpressed
        vectorClearBtn.click(this::clearVectors);

        // Add marker search field at bottom-right (no label, no button)
        add(markerSearchField = new TextEntry(UI.scale(200), "") {
            @Override
            public void changed() {
                super.changed();
                applyMarkerSearch();
            }
            
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    applyMarkerSearch();
                    return true;
                }
                return super.keydown(ev);
            }
        }, view.pos("br").sub(UI.scale(205), UI.scale(5)));

        /* The stock Export.../Import... buttons in the marker panel move a .hmap file; these move
         * the same data through the village database. Hidden unless a shared PostgreSQL is
         * configured, because there is nothing to share with otherwise. */
        add(dbExportBtn = new Button(dbbtnw, "Export to DB", false) {
            @Override
            public void click() {
                MapDbTransfer.export(NUtils.getGameUI(), file);
            }
        });
        dbExportBtn.settip("Upload your explored map to the shared database");
        add(dbImportBtn = new Button(dbbtnw, "Import from DB", false) {
            @Override
            public void click() {
                MapDbTransfer.importFrom(NUtils.getGameUI(), file);
            }
        });
        dbImportBtn.settip("Merge in the maps every other player has uploaded");
        placeDbButtons();
    }

    /** Bottom-right of the map view, stacked above the marker search field. */
    private void placeDbButtons() {
        if((dbExportBtn == null) || (dbImportBtn == null))
            return;
        int spacing = UI.scale(5);
        int y = view.c.y + view.sz.y - UI.scale(25) - dbExportBtn.sz.y - spacing;
        int x = view.c.x + view.sz.x - UI.scale(5) - (dbbtnw * 2) - spacing;
        dbExportBtn.c = new Coord(x, y);
        dbImportBtn.c = new Coord(x + dbbtnw + spacing, y);
    }

    private double dbBtnCheck = 0;

    @Override
    public void tick(double dt) {
        super.tick(dt);
        /* Database settings can be switched at runtime, so visibility is re-checked rather than
         * fixed at construction - but twice a second is plenty for a settings change. */
        if(dbExportBtn != null) {
            dbBtnCheck -= dt;
            if(dbBtnCheck <= 0) {
                dbBtnCheck = 0.5;
                boolean on = MapDbTransfer.configured();
                if(dbExportBtn.visible() != on) {
                    dbExportBtn.show(on);
                    dbImportBtn.show(on);
                }
            }
        }
    }

    private void clearVectors() {
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui != null && gui.map instanceof nurgling.NMapView) {
            nurgling.NMapView mapView = (nurgling.NMapView) gui.map;
            if(!mapView.directionalVectors.isEmpty()) {
                int count = mapView.directionalVectors.size();
                mapView.clearDirectionalVectors();
                nurgling.tools.DirectionalVector.resetColorCycle();
                gui.msg("Cleared " + count + " directional vector" + (count > 1 ? "s" : ""));
            }
        }
    }

    public long playerSegmentId() {
        MiniMap.Location sessloc = view.sessloc;
        if(sessloc == null) {return 0;}
        return sessloc.seg.id;
    }

    public Coord2d findMarkerPosition(String name) {
        MiniMap.Location sessloc = view.sessloc;
        if(sessloc == null) {return null;}
        for (MapFile.Marker mark : file.markers) {
            if(mark instanceof MapFile.SMarker) {
                MapFile.SMarker m = (MapFile.SMarker) mark;
                if(m.seg == sessloc.seg.id && m.nm != null && name != null && m.nm.contains(name)) {
                    return m.tc.sub(sessloc.tc).mul(tilesz);
                }
            }
        }
        return null;
    }
    
    private void applyMarkerSearch() {
        String pattern = markerSearchField.text().trim();
        markerSearchPattern = pattern;
    }

    @Override
    public void resize(Coord sz) {
        super.resize(sz);
        
        // Position buttons in top-right corner (15px right, 10px down from original position)
        if(mapToolsBtn != null && fishBtn != null && treeBtn != null && vectorClearBtn != null) {
            int btnSpacing = UI.scale(5);
            Coord btnPos = view.c.add(view.sz.x - UI.scale(35), UI.scale(15));

            mapToolsBtn.c = btnPos;
            btnPos = btnPos.sub(mapToolsBtn.sz.x + btnSpacing, 0);
            fishBtn.c = btnPos;
            btnPos = btnPos.sub(fishBtn.sz.x + btnSpacing, 0);
            treeBtn.c = btnPos;
            btnPos = btnPos.sub(treeBtn.sz.x + btnSpacing, 0);
            vectorClearBtn.c = btnPos;
        }
        
        // Keep marker search field at bottom-right
        if(markerSearchField != null)
            markerSearchField.c = view.c.add(view.sz.x - UI.scale(205), view.sz.y - UI.scale(25));

        placeDbButtons();
    }
    
    @Override
    public boolean mousedown(MouseDownEvent ev) {
        // Handle alt+left-click for waypoint queueing (on button release handled below)
        // Handle shift+right-click for resource timers
        if(view.c != null) {
            // Convert global coordinates to view coordinates
            Coord viewCoord = ev.c.sub(view.parentpos(this));

            // Check if the click is within the view bounds
            if(viewCoord.x >= 0 && viewCoord.x < view.sz.x &&
               viewCoord.y >= 0 && viewCoord.y < view.sz.y) {

                // Shift+right-click for resource timers and tree locations
                if(ev.b == 3 && ui.modshift) {
                    // First check for tree icons
                    if(handleTreeSaveClick(viewCoord)) {
                        return true; // Consume the event
                    }
                    // Then check if there's a resource marker at this location
                    if(handleResourceTimerClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }
            }
        }

        return super.mousedown(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if(view.c != null) {
            Coord viewCoord = ev.c.sub(view.parentpos(this));

            // Check if the click is within the view bounds
            if(viewCoord.x >= 0 && viewCoord.x < view.sz.x &&
               viewCoord.y >= 0 && viewCoord.y < view.sz.y) {

                // Left-click for forager path recording (without modifier)
                if(ev.b == 1 && !ui.modmeta && !ui.modshift && !ui.modctrl) {
                    if(handleForagerRecordingClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }
                
                // alt+left-click for waypoint queueing; shift is excluded because
                // alt+shift+left-click is the map ping (NMiniMap.sendPointPing)
                if(ev.b == 1 && ui.modmeta && !ui.modshift) {
                    if(handleWaypointClick(viewCoord)) {
                        return true; // Consume the event
                    }
                }

                // Right-click for clearing waypoint queue (fish handling is in parent NMiniMap)
                if(ev.b == 3 && !ui.modshift) {
                    // Clear waypoint queue on regular right-click (if not on fish/marker)
                    NGameUI gui = (NGameUI) NUtils.getGameUI();
                    if(gui != null && gui.waypointMovementService != null) {
                        gui.waypointMovementService.clearQueue();
                    }
                    // Let parent handle fish location clicks and other right-click behavior
                }
            }
        }

        return super.mouseup(ev);
    }

    private boolean handleForagerRecordingClick(Coord c) {
        // Check if a PathRecordable window is open and in recording mode
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui == null) return false;

        // Find a PathRecordable window (Forager or TrufflePigHunter)
        nurgling.widgets.bots.PathRecordable pathWnd = null;
        for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if(wdg instanceof nurgling.widgets.bots.PathRecordable) {
                pathWnd = (nurgling.widgets.bots.PathRecordable) wdg;
                break;
            }
        }

        if(pathWnd == null || !pathWnd.isRecording()) {
            return false; // Not recording, don't consume the event
        }

        // Get the location at the clicked position
        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null || view.sessloc == null) return false;

        // Only handle if in same segment
        if(clickLoc.seg.id != view.sessloc.seg.id) return false;

        // Create ForagerWaypoint from MiniMap.Location
        nurgling.routes.ForagerWaypoint waypoint = new nurgling.routes.ForagerWaypoint(clickLoc);

        // Add waypoint to the recording path
        pathWnd.addWaypointToRecording(waypoint);

        return true; // Consume the event
    }
    
    private boolean handleWaypointClick(Coord c) {
        // Try to get the location at clicked coordinates
        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null || view.sessloc == null) return false;

        // Only handle if in same segment
        if(clickLoc.seg.id != view.sessloc.seg.id) return false;

        // Use the service to add waypoint
        NGameUI gui = (NGameUI) NUtils.getGameUI();
        if(gui != null && gui.waypointMovementService != null) {
            gui.waypointMovementService.addWaypoint(clickLoc, view.sessloc);
            return true;
        }

        return false;
    }
    
    private boolean handleResourceTimerClick(Coord c) {
        // Try to find a resource marker at the clicked location
        MiniMap.Location clickLoc = view.xlate(c);
        if(clickLoc == null) return false;

        MiniMap.DisplayMarker marker = view.markerat(clickLoc.tc);
        if(marker != null && marker.m instanceof MapFile.SMarker) {
            MapFile.SMarker smarker = (MapFile.SMarker) marker.m;

            // Handle through service
            NGameUI gui = (NGameUI) NUtils.getGameUI();
            if(gui != null && gui.localizedResourceTimerService != null) {
                return gui.localizedResourceTimerService.handleResourceClick(smarker);
            }
        }

        return false;
    }

    private boolean handleTreeSaveClick(Coord c) {
        // TODO: Implement tree saving from map click
        // For now, trees can be saved through other means
        // This would require access to gobs at the clicked location
        return false;
    }

    @Override
    public void recenter() {
        super.recenter();
    }
}
