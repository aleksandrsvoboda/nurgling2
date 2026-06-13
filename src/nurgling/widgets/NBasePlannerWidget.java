package nurgling.widgets;

import haven.*;
import haven.Window;
import nurgling.NCore;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.planning.PlanningFolder;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayer;
import nurgling.planning.PlanningLayerManager;
import nurgling.planning.PlanningNode;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

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
                NUtils.getGameUI().msg("Base planner: drag a rectangle to delete ghosts in the active layer");
            }
        }, btnEye.pos("ur").adds(UI.scale(10), 0));
        btnClearArea.settip("Arm rectangle-select to delete ghosts in the active layer");

        // Left panel: tree list of folders + layers
        treeList = add(new TreeList(UI.scale(new Coord(280, 420))), new Coord(0, btnNewFolder.sz.y + UI.scale(10)));

        // Right panel: ghosts of active layer
        ghostList = add(new GhostList(UI.scale(new Coord(380, 420))), treeList.pos("ur").adds(UI.scale(10), 0));

        pack();
    }

    public boolean isClearInAreaArmed() { return clearInAreaArmed; }
    public void disarmClearInArea() { clearInAreaArmed = false; }

    /** Parent folder for a newly created layer: the folder we're navigated
     *  inside, else the selected row's folder, else null (root). */
    private String currentParentFolderId() {
        if (currentFolderId != null) return currentFolderId;
        if (treeList.sel == null) return null;
        PlanningNode n = treeList.sel.node;
        if (n == null) return null;
        if (n instanceof PlanningFolder) return n.id;
        return n.parentId;
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
            add(new Label(shortName(g.resName)), new Coord(UI.scale(8), yMid - UI.scale(1)));
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
