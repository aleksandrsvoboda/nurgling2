package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tools.MapDbTransfer;
import nurgling.i18n.L10n;

import java.awt.Color;
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
    PeerRoster peerRoster;
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
        add(dbExportBtn = new Button(dbbtnw, L10n.get("mapdb.btn_export"), false) {
            @Override
            public void click() {
                /* The window's own session, not whichever one happens to be in front: a second
                 * client in the same process must not upload this map under its name. */
                MapDbTransfer.export(getparent(GameUI.class), file);
            }
        });
        dbExportBtn.settip(L10n.get("mapdb.btn_export_tip"));
        add(dbImportBtn = new Button(dbbtnw, L10n.get("mapdb.btn_import"), false) {
            @Override
            public void click() {
                MapDbTransfer.importFrom(getparent(GameUI.class), file);
            }
        });
        dbImportBtn.settip(L10n.get("mapdb.btn_import_tip"));
        /* Same width as the two database buttons side by side, so the right-hand edge of the map
         * reads as one column rather than three things that happen to be near each other. */
        add(peerRoster = new PeerRoster((dbbtnw * 2) + UI.scale(5)));
        placeDbButtons();
    }

    /**
     * Live roster of everyone publishing to the shared database, sitting in the map window above the
     * database buttons. Left-clicking a name pans the map to that character.
     *
     * <p>Deliberately part of the map rather than a window of its own: the answer to "where is
     * Bjorn" is a place on this map, and a separate window would mean reading a name in one place
     * and hunting for the marker in another. It is also only here - the corner minimap has neither
     * the room for it nor a way to pan.
     *
     * <p>Collapses to its header, and disappears entirely when nobody is online, so an empty village
     * costs no map area at all.
     */
    public class PeerRoster extends Widget {
        private static final int MAXROWS = 8;
        private final int rowh = UI.scale(15);
        private final int headh = UI.scale(16);
        private final Text.Foundry fnd = new Text.Foundry(Text.dfont, UI.scale(10)).aa(true);
        private final java.util.List<Row> rows = new java.util.ArrayList<>();
        private final RosterBox box;
        private boolean collapsed = false;
        private double refresh = 0;
        private Text head = null;
        private int headn = -1;

        /** One rendered row. Text is built on the refresh tick, never per frame. */
        private final class Row {
            final nurgling.PeerPosition kp;
            final Text name, status;
            final Color col;
            final boolean placed;

            Row(nurgling.PeerPosition kp, Text name, Text status, Color col, boolean placed) {
                this.kp = kp; this.name = name; this.status = status;
                this.col = col; this.placed = placed;
            }
        }

        private class RosterBox extends Listbox<Row> {
            RosterBox(int w, int h) {
                super(w, h, rowh);
            }

            protected Row listitem(int i) {return(rows.get(i));}

            protected int listitems() {return(rows.size());}

            protected void drawbg(GOut g) {
                g.chcolor(0, 0, 0, 148);
                g.frect(Coord.z, sz);
                g.chcolor();
            }

            protected void drawsel(GOut g) {
                g.chcolor(255, 255, 255, 28);
                g.frect(Coord.z, g.sz());
                g.chcolor();
            }

            protected void drawitem(GOut g, Row row, int idx) {
                // Banded rows: at this size a flat panel of names is genuinely hard to track across.
                if((idx % 2) == 1) {
                    g.chcolor(255, 255, 255, 12);
                    g.frect(Coord.z, g.sz());
                    g.chcolor();
                }
                int mid = g.sz().y / 2;
                g.chcolor(row.col.getRed(), row.col.getGreen(), row.col.getBlue(),
                          row.placed ? 255 : 120);
                g.fellipse(new Coord(UI.scale(7), mid), new Coord(UI.scale(3), UI.scale(3)));
                g.chcolor();
                g.aimage(row.name.tex(), new Coord(UI.scale(14), mid), 0, 0.5);
                if(row.status != null)
                    g.aimage(row.status.tex(), new Coord(g.sz().x - UI.scale(4), mid), 1, 0.5);
            }

            public void change(Row row) {
                super.change(row);
                if(row == null)
                    return;
                MiniMap.Location loc = row.kp.ref.loc();
                if(loc == null) {
                    /* Online, but in land this client has never walked or imported. Saying so is the
                     * useful answer - and names the fix - where a dead click would just look broken. */
                    GameUI gui = getparent(GameUI.class);
                    if(gui != null)
                        gui.msg(row.kp.charName + " is somewhere your map does not cover yet - "
                                + "import the shared map to place them.");
                    return;
                }
                view.center(new MiniMap.SpecLocator(loc.seg.id, loc.tc));
            }
        }

        PeerRoster(int w) {
            super(new Coord(w, UI.scale(16)));
            box = add(new RosterBox(w, MAXROWS), new Coord(0, headh));
            relayout();
        }

        public void tick(double dt) {
            super.tick(dt);
            /* Twice a second. The sync worker only refreshes the underlying data every three, and
             * re-rendering eight rows of text per frame to show the same numbers would be waste. */
            refresh -= dt;
            if(refresh <= 0) {
                refresh = 0.5;
                rebuild();
            }
        }

        private void rebuild() {
            NGameUI gui = (NGameUI)getparent(GameUI.class);
            rows.clear();
            if(gui != null && gui.peerPositionService != null
               && (Boolean)nurgling.NConfig.get(nurgling.NConfig.Key.showPeerPositions)) {
                java.util.List<nurgling.PeerPosition> live = gui.peerPositionService.snapshot();
                live.sort((a, b) -> a.charName.compareToIgnoreCase(b.charName));
                Coord ptc = playerTile();
                for(nurgling.PeerPosition kp : live) {
                    if(!kp.online())
                        continue;
                    boolean placed = kp.ref.loc() != null;
                    Color col = NMiniMap.peercol(gui, kp.charName);
                    rows.add(new Row(kp,
                                     fnd.render(kp.charName, placed ? Color.WHITE : new Color(158, 158, 158)),
                                     fnd.render(status(kp, ptc, placed), new Color(168, 168, 168)),
                                     col, placed));
                }
            }
            if(rows.size() != headn) {
                headn = rows.size();
                head = fnd.render(L10n.get("mapdb.roster_title") + " (" + headn + ")", Color.WHITE);
            }
            relayout();
        }

        /** Right-hand column: how far, or why there is no distance to give. */
        private String status(nurgling.PeerPosition kp, Coord ptc, boolean placed) {
            if(!placed)
                return(L10n.get("mapdb.roster_unmapped"));
            MiniMap.Location loc = kp.ref.loc();
            MiniMap.Location sessloc = view.sessloc;
            if((ptc == null) || (sessloc == null) || (loc.seg.id != sessloc.seg.id))
                return(L10n.get("mapdb.roster_elsewhere"));
            long d = Math.round(ptc.dist(loc.tc));
            return((d >= 1000) ? String.format("%.1fk", d / 1000.0) : Long.toString(d));
        }

        private Coord playerTile() {
            try {
                MiniMap.Location sessloc = view.sessloc;
                GameUI gui = getparent(GameUI.class);
                if((sessloc != null) && (gui != null) && (gui.map != null))
                    return(new Coord2d(gui.map.getcc()).floor(tilesz).add(sessloc.tc));
            } catch(Loading l) {
            }
            return(null);
        }

        private void relayout() {
            int n = Math.min(rows.size(), MAXROWS);
            /* Nobody online, no panel - not even a header saying zero. Visibility follows the row
             * count alone rather than also being driven from tick(): with no shared database the
             * sync worker never delivers anyone, so the list is empty and this hides itself. One
             * rule, rather than two that can disagree about who is in charge. */
            show(n > 0);
            boolean showbox = !collapsed && (n > 0);
            box.show(showbox);
            if(showbox) {
                box.h = n;
                box.resize(new Coord(box.sz.x, n * rowh));
                /* The scrollbar was sized at construction and does not follow a resize on its own,
                 * so a shrunk list would otherwise keep a full-height bar hanging past its rows. */
                box.sb.resize(new Coord(box.sb.sz.x, n * rowh));
            }
            resize(new Coord(sz.x, headh + (showbox ? (n * rowh) : 0)));
            placeDbButtons();
        }

        public void draw(GOut g) {
            g.chcolor(0, 0, 0, 178);
            g.frect(Coord.z, new Coord(sz.x, headh));
            g.chcolor(255, 255, 255, 28);
            g.frect(Coord.z, new Coord(sz.x, UI.scale(1)));
            g.chcolor();
            if(head != null)
                g.aimage(head.tex(), new Coord(UI.scale(6), headh / 2), 0, 0.5);
            // Collapse chevron, pointing the way the panel will go.
            int cx = sz.x - UI.scale(9), cy = headh / 2, a = UI.scale(3);
            g.chcolor(210, 210, 210, 220);
            if(collapsed) {
                g.line(new Coord(cx - a, cy - a), new Coord(cx, cy + a), 1);
                g.line(new Coord(cx + a, cy - a), new Coord(cx, cy + a), 1);
            } else {
                g.line(new Coord(cx - a, cy + a), new Coord(cx, cy - a), 1);
                g.line(new Coord(cx + a, cy + a), new Coord(cx, cy - a), 1);
            }
            g.chcolor();
            super.draw(g);
        }

        public boolean mousedown(MouseDownEvent ev) {
            if((ev.b == 1) && (ev.c.y < headh)) {
                collapsed = !collapsed;
                relayout();
                return(true);
            }
            return(super.mousedown(ev));
        }

        /** Height this panel wants right now, used to stack it above the database buttons. */
        int wanted() {
            return(sz.y);
        }
    }

    /** Bottom-right of the map view, stacked above the marker search field. */
    private void placeDbButtons() {
        if((dbExportBtn == null) || (dbImportBtn == null))
            return;
        int spacing = UI.scale(5);
        int y = view.c.y + view.sz.y - UI.scale(25) - dbExportBtn.sz.y - spacing;
        int x = view.c.x + view.sz.x - UI.scale(5) - (dbbtnw * 2) - spacing;
        /* A window narrow enough to leave no room would otherwise push them off the left edge. */
        int lx = Math.max(view.c.x, x);
        dbExportBtn.c = new Coord(lx, y);
        dbImportBtn.c = new Coord(lx + dbbtnw + spacing, y);
        /* Stacked directly on top of the buttons and growing upward, so the list expanding never
         * moves the buttons under the player's cursor. */
        if(peerRoster != null)
            peerRoster.c = new Coord(lx, y - spacing - peerRoster.wanted());
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
