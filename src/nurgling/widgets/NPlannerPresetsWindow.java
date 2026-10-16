package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.planning.PlannerPreset;
import nurgling.planning.PlannerPresetPlacer;
import nurgling.planning.PlannerPresetStore;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

import static nurgling.i18n.L10n.get;

/**
 * The Base planner preset library: the player's presets, a picture of the selected one, and
 * Place / Rename / Delete / Export / Import / Description. A cut-down {@link NAreaPresetsWindow}:
 * there are no built-in presets.
 */
public class NPlannerPresetsWindow extends Window
{
    private static final Coord THUMB = UI.scale(280, 150);
    /** Three buttons per row, as wide as the thumbnail, so "Description..." fits. */
    private static final int BTN_W = 90;
    /** Thumbnail colour of each layer, in turn. */
    private static final Color[] LAYER_COLOURS = {
            new Color(233, 156, 84), new Color(127, 179, 213), new Color(155, 209, 122),
            new Color(214, 130, 190), new Color(230, 210, 100), new Color(140, 220, 200)};

    private final List<PlannerPreset> presets = new ArrayList<>();
    private final PresetList list;
    private final Thumb thumb;
    private final Label info;
    private final RichTextBox about;
    private final Button place, rename, delete, export, describeBtn;
    /* Set by the file choosers, which run on the AWT thread; picked up in tick(). */
    private volatile boolean reload = false;
    private volatile String pendingMsg = null;

    public NPlannerPresetsWindow()
    {
        super(UI.scale(new Coord(420, 230)), get("planner.preset.title"));
        list = add(new PresetList(UI.scale(new Coord(180, 220))), Coord.z);
        thumb = add(new Thumb(), list.pos("ur").adds(8, 0));
        info = add(new Label(""), thumb.pos("bl").adds(0, 4));
        place = add(new Button(UI.scale(BTN_W), get("area.preset.place")) {
            @Override
            public void click() {
                placeSelected();
            }
        }, info.pos("bl").adds(0, 6));
        place.settip(get("planner.preset.place_tip"));
        rename = add(new Button(UI.scale(BTN_W), get("area.preset.rename")) {
            @Override
            public void click() {
                renameSelected();
            }
        }, place.pos("ur").adds(5, 0));
        delete = add(new Button(UI.scale(BTN_W), get("area.preset.delete")) {
            @Override
            public void click() {
                deleteSelected();
            }
        }, rename.pos("ur").adds(5, 0));
        export = add(new Button(UI.scale(BTN_W), get("area.preset.export")) {
            @Override
            public void click() {
                exportSelected();
            }
        }, place.pos("bl").adds(0, 5));
        Button importBtn = add(new Button(UI.scale(BTN_W), get("area.preset.import")) {
            @Override
            public void click() {
                importFile();
            }
        }, export.pos("ur").adds(5, 0));
        describeBtn = add(new Button(UI.scale(BTN_W), get("area.preset.edit_description")) {
            @Override
            public void click() {
                describeSelected();
            }
        }, importBtn.pos("ur").adds(5, 0));
        describeBtn.settip(get("planner.preset.edit_description_tip"));
        Button fromWorld = add(new Button(UI.scale(BTN_W), get("planner.preset.world_btn")) {
            @Override
            public void click() {
                captureFromWorld();
            }
        }, export.pos("bl").adds(0, 5));
        fromWorld.settip(get("planner.preset.world_btn_tip"));
        int top = Math.max(list.pos("bl").y, fromWorld.pos("bl").y) + UI.scale(8);
        about = add(new RichTextBox(Coord.of(thumb.pos("ur").x - list.c.x, UI.scale(120)), "", RichText.stdf), Coord.of(list.c.x, top));
        about.bg = new Color(30, 40, 40, 160);
        pack();
        load(null);
    }

    /** Opens the window, or brings the open one to the front. */
    public static NPlannerPresetsWindow open(NGameUI gui)
    {
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof NPlannerPresetsWindow) {
                NPlannerPresetsWindow wnd = (NPlannerPresetsWindow) w;
                wnd.load(wnd.selected());
                wnd.show();
                wnd.raise();
                return wnd;
            }
        }
        NPlannerPresetsWindow wnd = new NPlannerPresetsWindow();
        gui.add(wnd, gui.basePlanner.c.add(gui.basePlanner.sz.x, 0));
        wnd.raise();
        return wnd;
    }

    private PlannerPreset selected()
    {
        return list.sel;
    }

    /** Re-reads the presets, keeping {@code keep} (matched by file) selected. */
    void load(PlannerPreset keep)
    {
        presets.clear();
        presets.addAll(PlannerPresetStore.all());
        PlannerPreset sel = presets.isEmpty() ? null : presets.get(0);
        for (PlannerPreset p : presets) {
            if (keep != null && Objects.equals(keep.file, p.file)) {
                sel = p;
                break;
            }
        }
        list.reset();
        list.change(sel);
    }

    private void placeSelected()
    {
        PlannerPreset p = selected();
        if (p == null)
            return;
        NGameUI gui = (NGameUI) ui.gui;
        NBasePlannerWidget planner = gui.basePlanner;
        Runnable back = () -> {
            show();
            planner.show();
            raise();
        };
        if (PlannerPresetPlacer.start(gui, p, planner.currentParentFolderId(), back, made -> {
            planner.show();
            planner.showNode(made);
            planner.raise();
        })) {
            hide();
            planner.hide();
        }
    }

    /** Arms a rectangle on the map; the buildings dragged over are saved as a preset (Base planner must be open). */
    private void captureFromWorld()
    {
        NGameUI gui = (NGameUI) ui.gui;
        NMapView map = (NMapView) gui.map;
        if (map == null || map.presetPlacer != null || map.isAreaSelectionMode.get()) {
            gui.error(get("planner.preset.busy"));
            return;
        }
        if (!gui.basePlanner.visible())
            gui.basePlanner.show();
        gui.basePlanner.armSaveWorldRegion();
    }

    private void describeSelected()
    {
        PlannerPreset p = selected();
        if (p == null)
            return;
        NAreaPresetDescriptionDialog.open(ui, get("area.preset.description_title", p.name),
                                          get("planner.preset.description_hint"), p.description, text -> {
            p.description = text;
            try {
                PlannerPresetStore.write(p);
            } catch (IOException e) {
                ui.gui.error(get("area.preset.save_failed", e.getMessage()));
            }
            load(p);
        });
    }

    private void renameSelected()
    {
        PlannerPreset p = selected();
        if (p == null)
            return;
        NAreaPresetNameDialog.open(ui, get("area.preset.name_title"), p.name, n -> {
            p.name = n;
            try {
                PlannerPresetStore.write(p);
            } catch (IOException e) {
                ui.gui.error(get("area.preset.save_failed", e.getMessage()));
            }
            load(p);
        });
    }

    private void deleteSelected()
    {
        PlannerPreset p = selected();
        if (p == null)
            return;
        try {
            PlannerPresetStore.delete(p);
        } catch (IOException e) {
            ui.gui.error(get("area.preset.save_failed", e.getMessage()));
        }
        load(null);
    }

    private void exportSelected()
    {
        PlannerPreset p = selected();
        if (p == null)
            return;
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Base planner preset", "json"));
            fc.setSelectedFile(new File(p.name + ".json"));
            if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".json"))
                f = new File(f.getPath() + ".json");
            try {
                PlannerPresetStore.export(p, f.toPath());
                pendingMsg = get("area.preset.exported", f.getPath());
            } catch (IOException e) {
                pendingMsg = get("area.preset.save_failed", e.getMessage());
            }
        });
    }

    private void importFile()
    {
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Base planner preset", "json"));
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            File f = fc.getSelectedFile();
            try {
                PlannerPreset p = PlannerPresetStore.importFile(f.toPath());
                pendingMsg = get("area.preset.imported", p.name);
            } catch (IOException | org.json.JSONException | IllegalArgumentException e) {
                pendingMsg = get("planner.preset.import_failed", f.getName());
            }
            reload = true;
        });
    }

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        if (reload) {
            reload = false;
            load(selected());
        }
        String msg = pendingMsg;
        if (msg != null) {
            pendingMsg = null;
            ui.gui.msg(msg);
        }
        boolean none = selected() == null;
        place.disable(none);
        export.disable(none);
        rename.disable(none);
        delete.disable(none);
        describeBtn.disable(none);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args)
    {
        if (msg.equals("close"))
            hide();
        else
            super.wdgmsg(sender, msg, args);
    }

    private void selectionChanged()
    {
        PlannerPreset p = selected();
        thumb.set(p);
        info.settext((p == null) ? get("area.preset.empty") : get("planner.preset.info", p.w, p.h, p.ghostCount(), p.layers.size()));
        if (p == null)
            info.settip(get("planner.preset.empty_tip"));
        else
            info.tooltip = null;
        if (p == null)
            about.settext("");
        else if (p.description.isEmpty())
            about.settext(RichText.Parser.quote(get("planner.preset.no_description")));
        else
            about.settext(NAreaPresetsWindow.describe(p.description));
    }

    private class PresetList extends SListBox<PlannerPreset, Widget>
    {
        PresetList(Coord sz)
        {
            super(sz, UI.scale(18));
        }

        @Override
        protected List<PlannerPreset> items()
        {
            return presets;
        }

        @Override
        protected Widget makeitem(PlannerPreset p, int idx, Coord sz)
        {
            return new RowWidget(this, sz, p);
        }

        @Override
        public void change(PlannerPreset p)
        {
            super.change(p);
            selectionChanged();
        }

        @Override
        public void draw(GOut g)
        {
            g.chcolor(30, 40, 40, 160);
            g.frect(Coord.z, g.sz());
            g.chcolor();
            super.draw(g);
        }
    }

    private static class RowWidget extends SListWidget.ItemWidget<PlannerPreset>
    {
        private final Text.Line text;
        private final Text.Line count;

        RowWidget(PresetList list, Coord sz, PlannerPreset p)
        {
            super(list, sz, p);
            count = Text.std.render(String.valueOf(p.ghostCount()), new Color(143, 163, 164));
            text = Text.std.ellipsize(p.name, sz.x - count.sz().x - UI.scale(18));
        }

        @Override
        public void draw(GOut g)
        {
            int y = (sz.y - text.sz().y) / 2;
            g.image(text.tex(), Coord.of(UI.scale(10), y));
            g.image(count.tex(), Coord.of(sz.x - count.sz().x - UI.scale(4), y));
        }

        @Override
        public void dispose()
        {
            text.dispose();
            count.dispose();
            super.dispose();
        }
    }

    /** The selected preset's object footprints, coloured by layer and scaled to fit. */
    private static class Thumb extends Widget
    {
        private PlannerPreset preset;
        /** Footprint bounds {minx, miny, maxx, maxy} and layer index of every ghost. */
        private final List<double[]> rects = new ArrayList<>();
        private final List<Integer> layerOf = new ArrayList<>();

        Thumb()
        {
            super(THUMB);
        }

        void set(PlannerPreset p)
        {
            preset = p;
            rects.clear();
            layerOf.clear();
            if (p == null)
                return;
            for (int i = 0; i < p.layers.size(); i++) {
                for (PlannerPreset.Ghost g : p.layers.get(i).ghosts) {
                    rects.add(PlannerPreset.footprint(g));
                    layerOf.add(i);
                }
            }
        }

        @Override
        public void draw(GOut g)
        {
            g.chcolor(32, 48, 51, 255);
            g.frect(Coord.z, sz);
            g.chcolor(61, 76, 77, 255);
            g.rect(Coord.z, sz);
            g.chcolor();
            if (preset == null)
                return;
            int pad = UI.scale(6);
            double bw = preset.w * MCache.tilesz.x, bh = preset.h * MCache.tilesz.y;
            double scale = Math.min((sz.x - 2.0 * pad) / bw, (sz.y - 2.0 * pad) / bh);
            Coord off = Coord.of((int) ((sz.x - bw * scale) / 2), (int) ((sz.y - bh * scale) / 2));
            g.chcolor(255, 255, 255, 40);
            g.rect(off, Coord.of((int) (bw * scale), (int) (bh * scale)));
            for (int i = 0; i < rects.size(); i++) {
                double[] r = rects.get(i);
                Coord ul = off.add((int) (r[0] * scale), (int) (r[1] * scale));
                Coord rsz = Coord.of(Math.max(2, (int) ((r[2] - r[0]) * scale)), Math.max(2, (int) ((r[3] - r[1]) * scale)));
                Color c = LAYER_COLOURS[layerOf.get(i) % LAYER_COLOURS.length];
                g.chcolor(c.getRed(), c.getGreen(), c.getBlue(), 170);
                g.frect(ul, rsz);
                g.chcolor(255, 255, 255, 140);
                g.rect(ul, rsz);
            }
            g.chcolor();
        }
    }
}
