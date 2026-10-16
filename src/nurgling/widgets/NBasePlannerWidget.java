package nurgling.widgets;

import haven.*;
import haven.Window;
import nurgling.NCore;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.NGameUI;
import nurgling.planning.PlannerPreset;
import nurgling.planning.PlannerPresetStore;
import nurgling.planning.WorldCapture;
import nurgling.planning.PlanningFolder;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayer;
import nurgling.planning.PlanningLayerManager;
import nurgling.planning.PlanningNode;
import nurgling.actions.bots.blueprint.BlueprintBuilder;
import nurgling.actions.bots.blueprint.BuildRecipes;
import nurgling.actions.bots.blueprint.BuildingNames;
import nurgling.sessions.BotExecutor;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static nurgling.i18n.L10n.get;

/**
 * Base planner window. Mirrors the layout of {@link NAreasWidget}: a toolbar
 * across the top with create/import/export/visibility/clear-in-area, a
 * left-hand tree panel listing folders and layers, and a right-hand panel
 * showing the ghost contents of the currently active layer.
 *
 * Window-open/close controls only user interactions (capture, delete, clone)
 * via {@link PlanningLayerManager#setWindowOpen(boolean)}; rendering
 * visibility is governed by per-row and master eye toggles.
 */
public class NBasePlannerWidget extends Window {

    private static final Tex folderIcon = new TexI(Resource.loadsimg("nurgling/hud/folder/u"));

    public TreeList treeList;
    public GhostList ghostList;

    private boolean clearInAreaArmed = false;
    private boolean savePresetRegionArmed = false;
    private boolean buildRegionArmed = false;
    private boolean saveWorldRegionArmed = false;
    private final Button btnSavePreset;

    /** Folder we are currently navigated "inside", or null for the root view.
     *  Mirrors {@link NAreasWidget#currentPath}: clicking a folder drills in,
     *  clicking the ".." row goes back up. The tree is single-level so this is
     *  either null (root) or one folder id. */
    private String currentFolderId = null;

    public NBasePlannerWidget() {
        super(UI.scale(new Coord(700, 500)), "Base planner");

        IButton btnNewFolder = add(new IButton(NStyle.addfolder[0].back, NStyle.addfolder[1].back, NStyle.addfolder[2].back) {
            @Override public void click() {
                super.click();
                promptName("New folder", "Folder", name -> {
                    if (name == null || name.isEmpty()) return;
                    manager().createFolder(name);
                    // Folders always live at the root; surface the new one.
                    currentFolderId = null;
                    refresh();
                });
            }
        }, new Coord(0, UI.scale(5)));
        btnNewFolder.settip("New folder");

        IButton btnNewLayer = add(new IButton(NStyle.addarea[0].back, NStyle.addarea[1].back, NStyle.addarea[2].back) {
            @Override public void click() {
                super.click();
                String suggested = manager().suggestNextLayerName();
                promptName("New layer", suggested, name -> {
                    if (name == null || name.isEmpty()) return;
                    String parentFolderId = currentParentFolderId();
                    PlanningLayer layer = manager().createLayer(name, parentFolderId);
                    manager().setActiveLayer(layer.id);
                    refresh();
                });
            }
        }, btnNewFolder.pos("ur").adds(UI.scale(5), 0));
        btnNewLayer.settip("New layer");

        IButton btnImport = add(new IButton(NStyle.importb[0].back, NStyle.importb[1].back, NStyle.importb[2].back) {
            @Override public void click() {
                super.click();
                java.awt.EventQueue.invokeLater(() -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new FileNameExtensionFilter("Base planner export (JSON)", "json"));
                    if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return;
                    if (fc.getSelectedFile() == null) return;
                    int n = manager().importFromFile(fc.getSelectedFile().getAbsolutePath());
                    NUtils.getGameUI().msg("Base planner: imported " + n + " node(s)");
                    refresh();
                });
            }
        }, btnNewLayer.pos("ur").adds(UI.scale(5), 0));
        btnImport.settip("Import from file");

        IButton btnExport = add(new IButton(NStyle.exportb[0].back, NStyle.exportb[1].back, NStyle.exportb[2].back) {
            @Override public void click() {
                super.click();
                java.awt.EventQueue.invokeLater(() -> {
                    JFileChooser fc = new JFileChooser();
                    fc.setFileFilter(new FileNameExtensionFilter("Base planner export (JSON)", "json"));
                    if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return;
                    String path = fc.getSelectedFile().getAbsolutePath();
                    if (!path.endsWith(".json")) path = path + ".json";
                    String nodeId = (treeList.sel != null) ? treeList.sel.node.id : null;
                    if (manager().exportToFile(path, nodeId)) {
                        NUtils.getGameUI().msg("Base planner: exported");
                    } else {
                        NUtils.getGameUI().msg("Base planner: export failed");
                    }
                });
            }
        }, btnImport.pos("ur").adds(UI.scale(5), 0));
        btnExport.settip("Export selected (or all)");

        IButton btnEye = add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/eye_2/u"),
                Resource.loadsimg("nurgling/hud/buttons/eye_2/d"),
                Resource.loadsimg("nurgling/hud/buttons/eye_2/h")) {
            @Override public void click() {
                super.click();
                manager().masterToggleVisibility();
                refresh();
            }
        }, btnExport.pos("ur").adds(UI.scale(10), 0));
        btnEye.settip("Toggle all visibility");

        IButton btnClearArea = add(new IButton(
                Resource.loadsimg("nurgling/hud/buttons/clear_in_area/u"),
                Resource.loadsimg("nurgling/hud/buttons/clear_in_area/d"),
                Resource.loadsimg("nurgling/hud/buttons/clear_in_area/h")) {
            @Override public void click() {
                super.click();
                if (manager().getActiveLayer() == null) {
                    NUtils.getGameUI().msg("Base planner: select a layer first");
                    return;
                }
                clearInAreaArmed = true;
                savePresetRegionArmed = false;
                buildRegionArmed = false;
                saveWorldRegionArmed = false;
                NUtils.getGameUI().msg("Base planner: drag a rectangle to delete ghosts in the active layer");
            }
        }, btnEye.pos("ur").adds(UI.scale(10), 0));
        btnClearArea.settip("Arm rectangle-select to delete ghosts in the active layer");

        Button btnPresets = add(new Button(UI.scale(70), get("area.preset.btn")) {
            @Override public void click() {
                super.click();
                NPlannerPresetsWindow.open((NGameUI) ui.gui);
            }
        }, btnClearArea.pos("ur").adds(UI.scale(10), 0));
        btnPresets.settip(get("planner.preset.btn_tip"));

        btnSavePreset = add(new Button(UI.scale(90), get("planner.preset.save_btn")) {
            @Override public void click() {
                super.click();
                savePreset();
            }
        }, btnPresets.pos("ur").adds(UI.scale(5), 0));
        btnSavePreset.settip(get("planner.preset.save_btn_tip"));

        Button btnSaveRegion = add(new Button(UI.scale(90), get("planner.preset.region_btn")) {
            @Override public void click() {
                super.click();
                savePresetRegionArmed = true;
                clearInAreaArmed = false;
                buildRegionArmed = false;
                saveWorldRegionArmed = false;
                NUtils.getGameUI().msg(get("planner.preset.region_hint"));
            }
        }, btnSavePreset.pos("ur").adds(UI.scale(5), 0));
        btnSaveRegion.settip(get("planner.preset.region_btn_tip"));

        Button btnBuild = add(new Button(UI.scale(60), get("bbuilder.planner.build")) {
            @Override public void click() {
                super.click();
                PlanningNode n = selectedNode();
                if (n == null) {
                    NUtils.getGameUI().msg(get("bbuilder.planner.select"));
                    return;
                }
                startBuilder(manager().ghostsOf(n.id));
            }
        }, btnSaveRegion.pos("ur").adds(UI.scale(10), 0));
        btnBuild.settip(get("bbuilder.planner.build_tip"));

        Button btnBuildRegion = add(new Button(UI.scale(90), get("bbuilder.planner.region")) {
            @Override public void click() {
                super.click();
                buildRegionArmed = true;
                clearInAreaArmed = false;
                savePresetRegionArmed = false;
                saveWorldRegionArmed = false;
                NUtils.getGameUI().msg(get("bbuilder.planner.region_hint"));
            }
        }, btnBuild.pos("ur").adds(UI.scale(5), 0));
        btnBuildRegion.settip(get("bbuilder.planner.region_tip"));

        // Left panel: tree list of folders + layers
        // The text buttons are taller than the icons: centre them on the icon row, and start the
        // lists below whichever reaches lower.
        int iconMid = btnNewFolder.c.y + btnNewFolder.sz.y / 2;
        for (Button b : new Button[] {btnPresets, btnSavePreset, btnSaveRegion, btnBuild, btnBuildRegion})
            b.c = Coord.of(b.c.x, Math.max(0, iconMid - b.sz.y / 2));
        int toolbarBottom = Math.max(btnNewFolder.pos("bl").y, btnPresets.pos("bl").y);
        treeList = add(new TreeList(UI.scale(new Coord(280, 420))), new Coord(0, toolbarBottom + UI.scale(5)));

        // Right panel: ghosts of active layer, and below them what the layer takes to build
        materials = add(new Scrollport(UI.scale(new Coord(380, 96))), treeList.pos("ur").adds(UI.scale(10), UI.scale(420 - 96)));
        ghostList = add(new GhostList(UI.scale(new Coord(380, 420 - 96 - 22))), treeList.pos("ur").adds(UI.scale(10), 0));
        materialsTitle = add(new Label(get("bbuilder.planner.materials")), ghostList.pos("bl").adds(0, UI.scale(3)));
        materialsTitle.setcolor(DIM);
        updateMaterials();

        pack();
    }

    public boolean isClearInAreaArmed() { return clearInAreaArmed; }
    public void disarmClearInArea() { clearInAreaArmed = false; }
    public boolean isSavePresetRegionArmed() { return savePresetRegionArmed; }
    public void disarmSavePresetRegion() { savePresetRegionArmed = false; }
    public boolean isBuildRegionArmed() { return buildRegionArmed; }
    public void disarmBuildRegion() { buildRegionArmed = false; }
    public boolean isSaveWorldRegionArmed() { return saveWorldRegionArmed; }
    public void disarmSaveWorldRegion() { saveWorldRegionArmed = false; }

    /** Planner presets "From world": the next rectangle dragged on the map saves what is built there. */
    public void armSaveWorldRegion() {
        saveWorldRegionArmed = true;
        clearInAreaArmed = false;
        savePresetRegionArmed = false;
        buildRegionArmed = false;
        NUtils.getGameUI().msg(get("planner.preset.world_hint"));
    }

    /** "Build region": the Blueprint Builder over the visible ghosts inside the dragged world rectangle. */
    public void buildRegion(Coord2d minWorld, Coord2d maxWorld) {
        List<PlanningGhost> ghosts = manager().visibleGhostsIn(minWorld, maxWorld);
        if (ghosts.isEmpty()) {
            NUtils.getGameUI().msg(get("bbuilder.planner.region_empty"));
            return;
        }
        startBuilder(ghosts);
    }

    private void startBuilder(List<PlanningGhost> ghosts) {
        BotExecutor.runWithSupports("blueprint_builder", new BlueprintBuilder(ghosts), false, null);
    }

    /** The selected folder or layer, if it still exists. */
    private PlanningNode selectedNode() {
        if (treeList == null || treeList.sel == null || treeList.sel.node == null) return null;
        return manager().getNode(treeList.sel.node.id);
    }

    /** "Save preset": the selected layer, or the selected folder with all its layers. */
    private void savePreset() {
        PlanningNode n = selectedNode();
        if (n == null) return;
        String id = n.id;
        NAreaPresetNameDialog.open(ui, get("area.preset.save_title"), n.name,
                name -> savePreset(() -> manager().capturePreset(name, id)));
    }

    /** "Save region": ghosts of the visible layers inside the dragged world rectangle. */
    public void savePresetRegion(Coord2d minWorld, Coord2d maxWorld) {
        NAreaPresetNameDialog.open(ui, get("area.preset.save_title"), "",
                name -> savePreset(() -> manager().capturePresetRegion(name, minWorld, maxWorld)));
    }

    /** "From world": what is built inside the dragged world rectangle, with the visible ghosts there. */
    public void saveWorldRegion(Coord2d minWorld, Coord2d maxWorld) {
        NAreaPresetNameDialog.open(ui, get("area.preset.save_title"), "", name -> {
            WorldCapture.Result[] res = {null};
            boolean saved = savePreset(() -> {
                res[0] = manager().capturePresetWorld(name, minWorld, maxWorld);
                return res[0].preset;
            });
            if (saved && !res[0].skipped.isEmpty())
                ui.gui.msg(WorldCapture.skippedText(res[0].skipped));
        });
    }

    private interface Capture { PlannerPreset run() throws PlannerPreset.CaptureException; }

    /** Captures and saves a preset; false, with the reason shown, if that failed. */
    private boolean savePreset(Capture capture) {
        NGameUI gui = (NGameUI) ui.gui;
        try {
            PlannerPreset preset = capture.run();
            PlannerPresetStore.saveNew(preset);
            gui.msg(get("planner.preset.saved", preset.name, preset.ghostCount()));
            NPlannerPresetsWindow.open(gui).load(preset);
            return true;
        } catch (PlannerPreset.CaptureException e) {
            gui.error(e.getMessage());
        } catch (IOException e) {
            gui.error(get("area.preset.save_failed", e.getMessage()));
        }
        return false;
    }

    /** Shows {@code node} in the tree: the root view for a root node, its folder for a layer inside one. */
    public void showNode(PlanningNode node) {
        currentFolderId = (node instanceof PlanningLayer) ? node.parentId : null;
        refresh();
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        btnSavePreset.disable(selectedNode() == null);
    }

    /** Parent folder for a newly created layer: the folder we're navigated
     *  inside, else the selected row's folder, else null (root). */
    public String currentParentFolderId() {
        if (currentFolderId != null) return currentFolderId;
        if (treeList.sel == null) return null;
        PlanningNode n = treeList.sel.node;
        if (n == null) return null;
        if (n instanceof PlanningFolder) return n.id;
        return n.parentId;
    }

    private static final Color DIM = new Color(160, 170, 170);
    private Scrollport materials;
    private Label materialsTitle;

    /** What the active layer's ghosts take to build, from the game's recipes (the wiki's, or a construction sign's once seen). */
    private void updateMaterials() {
        if (materials == null) return;
        for (Widget c = materials.cont.child; c != null; ) {
            Widget next = c.next;
            c.destroy();
            c = next;
        }
        PlanningLayer layer = manager().getActiveLayer();
        List<String> res = new ArrayList<>();
        int unknown = 0;
        if (layer != null) {
            for (PlanningGhost g : layer.ghosts) {
                res.add(g.resName);
                if (BuildRecipes.forGhost(g.resName) == null) unknown++;
            }
        }
        int y = 0;
        for (Map.Entry<String, Integer> e : BuildRecipes.totals(res).entrySet()) {
            String count = (e.getValue() < 0) ? "?" : String.valueOf(e.getValue());
            materials.cont.add(new Label(count + "  " + e.getKey()), new Coord(UI.scale(8), y));
            y += UI.scale(16);
        }
        if (unknown > 0) {
            materials.cont.add(new Label(get("bbuilder.planner.materials_unknown", unknown)), new Coord(UI.scale(8), y)).setcolor(DIM);
            y += UI.scale(16);
        }
        if (y == 0)
            materials.cont.add(new Label(get("bbuilder.planner.materials_none")), new Coord(UI.scale(8), 0)).setcolor(DIM);
        materials.cont.update();
    }

    public void refresh() {
        if (treeList != null) treeList.rebuild();
        if (ghostList != null) ghostList.rebuild();
    }

    @Override
    public void show() {
        super.show();
        manager().setWindowOpen(true);
        refresh();
    }

    @Override
    public void hide() {
        manager().setWindowOpen(false);
        disarmClearInArea();
        disarmSavePresetRegion();
        disarmBuildRegion();
        disarmSaveWorldRegion();
        super.hide();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    private static PlanningLayerManager manager() {
        return NUtils.getUI().core.planningLayer;
    }

    /* ---------- Name-prompt helper ---------- */

    public interface NameCallback { void accept(String name); }

    public void promptName(String title, String suggested, NameCallback cb) {
        NamePromptWindow w = new NamePromptWindow(title, suggested, cb);
        ui.gui.add(w, this.c.add(this.sz.x / 2 - UI.scale(130), this.sz.y / 2 - UI.scale(50)));
        w.raise();
        w.takeFocus();
    }

    private static class NamePromptWindow extends Window {
        final TextEntry te;
        NamePromptWindow(String title, String suggested, NameCallback cb) {
            super(UI.scale(new Coord(260, 70)), title);
            te = add(new TextEntry(UI.scale(180), suggested != null ? suggested : "") {
                @Override public boolean keydown(KeyDownEvent ev) {
                    if (ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                        cb.accept(text().trim());
                        NamePromptWindow.this.reqdestroy();
                        return true;
                    }
                    if (ev.code == java.awt.event.KeyEvent.VK_ESCAPE) {
                        NamePromptWindow.this.reqdestroy();
                        return true;
                    }
                    return super.keydown(ev);
                }
            }, UI.scale(5, 5));
            add(new Button(UI.scale(60), "OK") {
                @Override public void click() {
                    cb.accept(te.text().trim());
                    NamePromptWindow.this.reqdestroy();
                }
            }, te.pos("ur").adds(UI.scale(5), 0));
            pack();
        }

        public void takeFocus() {
            if (te != null) parent.setfocus(te);
        }

        @Override
        public void wdgmsg(Widget sender, String msg, Object... args) {
            if (msg.equals("close")) reqdestroy();
            else super.wdgmsg(sender, msg, args);
        }
    }

    /* ---------- Left panel: flattened tree list ---------- */

    /** A single visible row in the tree: a folder, a layer, or the synthetic
     *  ".." back row ({@code back == true}, {@code node == null}). */
    public static class TreeRow {
        public final PlanningNode node;
        public final int depth;
        public final boolean back;
        public TreeRow(PlanningNode node, int depth) { this.node = node; this.depth = depth; this.back = false; }
        private TreeRow() { this.node = null; this.depth = 0; this.back = true; }
        public static TreeRow backRow() { return new TreeRow(); }
    }

    public class TreeList extends SListBox<TreeRow, Widget> {
        public TreeRowWidget sel;
        private final List<TreeRow> rows = new ArrayList<>();

        TreeList(Coord sz) {
            super(sz, UI.scale(22));
            rebuild();
        }

        public void rebuild() {
            rows.clear();
            PlanningFolder folder = (currentFolderId != null) ? manager().getFolder(currentFolderId) : null;
            // The folder may have vanished (deleted locally or via DB sync) while
            // we were inside it — fall back to the root view.
            if (currentFolderId != null && folder == null) currentFolderId = null;
            if (folder != null) {
                rows.add(TreeRow.backRow());
                for (PlanningLayer layer : folder.layers) {
                    rows.add(new TreeRow(layer, 0));
                }
            } else {
                for (PlanningNode n : manager().getRoots()) {
                    rows.add(new TreeRow(n, 0));
                }
            }
            this.reset();
        }

        @Override
        protected List<TreeRow> items() { return rows; }

        @Override
        protected Widget makeitem(TreeRow row, int idx, Coord sz) {
            return new TreeRowWidget(this, row, sz);
        }

        @Override
        public void change(TreeRow row) {
            super.change(row);
            if (row == null) return;
            // change() only fires when the click wasn't consumed by a child
            // button (eye/remove), so navigation here never steals those clicks.
            if (row.back) {
                currentFolderId = null;
                NBasePlannerWidget.this.refresh();
            } else if (row.node instanceof PlanningFolder) {
                currentFolderId = row.node.id;
                NBasePlannerWidget.this.refresh();
            } else if (row.node instanceof PlanningLayer) {
                manager().setActiveLayer(row.node.id);
                if (ghostList != null) ghostList.rebuild();
            }
        }

        Color bg = new Color(30, 40, 40, 160);

        @Override
        public void draw(GOut g) {
            g.chcolor(bg);
            g.frect(Coord.z, g.sz());
            super.draw(g);
        }
    }

    public class TreeRowWidget extends SListWidget.ItemWidget<TreeRow> {
        public final PlanningNode node;
        private final ICheckBox eye;
        private final IButton remove;

        public TreeRowWidget(TreeList list, TreeRow row, Coord sz) {
            super(list, sz, row);
            this.node = row.node;

            // The ".." back row has no node and no eye/remove controls.
            if (row.back) {
                eye = null;
                remove = null;
                Label lbl = add(new Label(".."), Coord.z);
                lbl.c = new Coord(UI.scale(28), (sz.y - lbl.sz.y) / 2);
                return;
            }

            int indentX = UI.scale(8 + 14 * row.depth);

            // Add widgets first (positions adjusted below using each widget's own sz).
            String displayName = row.node.name;
            Label lbl = add(new Label(displayName), Coord.z);

            eye = add(new ICheckBox(
                    "nurgling/hud/buttons/eye", "/u", "/d", "/h", "/dh") {
                @Override public void changed(boolean val) {
                    manager().setVisible(node.id, val);
                    super.changed(val);
                }
            }, Coord.z);
            eye.a = node.visible;
            eye.settip("Toggle visibility");

            remove = add(new IButton(NStyle.removei[0].back, NStyle.removei[1].back, NStyle.removei[2].back) {
                @Override public void click() {
                    String id = node.id;
                    manager().deleteNode(id);
                    if (list.sel == TreeRowWidget.this) list.sel = null;
                    NBasePlannerWidget.this.refresh();
                }
            }, Coord.z);
            remove.settip("Delete");

            // Now vertically center each widget against its own sz.y.
            int rightMargin = UI.scale(5);
            int gap = UI.scale(6);
            remove.c = new Coord(sz.x - remove.sz.x - rightMargin, (sz.y - remove.sz.y) / 2);
            eye.c    = new Coord(remove.c.x - gap - eye.sz.x,       (sz.y - eye.sz.y) / 2);
            lbl.c    = new Coord(indentX + (row.node instanceof PlanningFolder ? UI.scale(20) : 0),
                                 (sz.y - lbl.sz.y) / 2);
        }

        @Override
        public void draw(GOut g) {
            if (((TreeRow) item).back) {
                g.image(folderIcon, new Coord(UI.scale(8), (sz.y - UI.scale(16)) / 2), UI.scale(16, 16));
                super.draw(g);
                return;
            }
            // Active-layer highlight stripe
            String activeId = manager().getActiveLayerId();
            if (node instanceof PlanningLayer && activeId != null && activeId.equals(node.id)) {
                g.chcolor(new Color(200, 180, 30, 80));
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            if (node instanceof PlanningFolder) {
                int indentX = UI.scale(8 + 14 * ((TreeRow) item).depth);
                g.image(folderIcon, new Coord(indentX, (sz.y - UI.scale(16)) / 2), UI.scale(16, 16));
            }
            super.draw(g);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            // Don't make the ".." back row the export selection (its node is null).
            if (!((TreeRow) item).back) ((TreeList) list).sel = this;
            return super.mousedown(ev);
        }
    }

    /* ---------- Right panel: ghosts of active layer ---------- */

    public class GhostList extends SListBox<PlanningGhost, Widget> {
        private final List<PlanningGhost> rows = new ArrayList<>();

        GhostList(Coord sz) {
            super(sz, UI.scale(18));
            rebuild();
        }

        public void rebuild() {
            rows.clear();
            PlanningLayer layer = manager().getActiveLayer();
            if (layer != null) {
                // newest-first
                for (int i = layer.ghosts.size() - 1; i >= 0; i--) {
                    rows.add(layer.ghosts.get(i));
                }
            }
            updateMaterials();
        }

        @Override
        protected List<PlanningGhost> items() { return rows; }

        @Override
        protected Widget makeitem(PlanningGhost g, int idx, Coord sz) {
            return new GhostRowWidget(this, g, sz);
        }

        Color bg = new Color(30, 40, 40, 160);

        @Override
        public void draw(GOut g) {
            g.chcolor(bg);
            g.frect(Coord.z, g.sz());
            super.draw(g);
        }
    }

    public class GhostRowWidget extends SListWidget.ItemWidget<PlanningGhost> {
        public GhostRowWidget(GhostList list, PlanningGhost g, Coord sz) {
            super(list, sz, g);
            int yMid = (sz.y - UI.scale(14)) / 2;
            String name = BuildingNames.nameOf(g.resName);
            Label nl = add(new Label((name != null) ? name : shortName(g.resName)), new Coord(UI.scale(8), yMid - UI.scale(1)));
            // what this one building takes, after its name; the whole list as the tooltip when it doesn't fit
            String cost = BuildRecipes.describe(BuildRecipes.forGhost(g.resName));
            if (cost != null) {
                int x = Math.max(nl.pos("ur").x + UI.scale(12), UI.scale(150));
                int room = sz.x - UI.scale(28) - x;
                Label cl = add(new Label(cost), new Coord(x, yMid - UI.scale(1)));
                cl.setcolor(DIM);
                if (cl.sz.x > room) {
                    String s = cost;
                    while (s.length() > 1 && Text.render(s + "...").sz().x > room)
                        s = s.substring(0, s.length() - 1);
                    cl.settext(s + "...");
                }
                settip(cost);
            }
            add(new IButton(NStyle.removei[0].back, NStyle.removei[1].back, NStyle.removei[2].back) {
                @Override public void click() {
                    manager().removeGhost(g);
                    NBasePlannerWidget.this.refresh();
                }
            }, new Coord(sz.x - UI.scale(20), yMid));
        }
    }

    /** Pretty-print "gfx/terobjs/cupboard" → "Cupboard". */
    public static String shortName(String res) {
        if (res == null) return "?";
        int slash = res.lastIndexOf('/');
        String tail = (slash >= 0) ? res.substring(slash + 1) : res;
        if (tail.isEmpty()) return res;
        return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
    }
}
