package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.areas.AreaPreset;
import nurgling.areas.AreaPresetPlacer;
import nurgling.areas.AreaPresetStore;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

import static nurgling.i18n.L10n.get;

/**
 * The area preset library: presets shipped with the client and the player's own, a picture of the
 * selected one, and Place / Rename / Delete / Export / Import.
 */
public class NAreaPresetsWindow extends Window
{
    private static final Coord THUMB = UI.scale(220, 150);

    private final List<Row> rows = new ArrayList<>();
    private final PresetList list;
    private final Thumb thumb;
    private final Label info;
    private final Button place, rename, delete, export;
    /* Set by the file choosers, which run on the AWT thread; picked up in tick(). */
    private volatile boolean reload = false;
    private volatile String pendingMsg = null;

    /** A section heading when {@code preset} is null. */
    private static class Row
    {
        final String header;
        final AreaPreset preset;

        Row(String header, AreaPreset preset)
        {
            this.header = header;
            this.preset = preset;
        }
    }

    public NAreaPresetsWindow()
    {
        super(UI.scale(new Coord(420, 230)), get("area.preset.title"));
        list = add(new PresetList(UI.scale(new Coord(180, 220))), Coord.z);
        thumb = add(new Thumb(), list.pos("ur").adds(8, 0));
        info = add(new Label(""), thumb.pos("bl").adds(0, 4));
        place = add(new Button(UI.scale(70), get("area.preset.place")) {
            @Override
            public void click() {
                placeSelected();
            }
        }, info.pos("bl").adds(0, 6));
        place.settip(get("area.preset.place_tip"));
        rename = add(new Button(UI.scale(70), get("area.preset.rename")) {
            @Override
            public void click() {
                renameSelected();
            }
        }, place.pos("ur").adds(5, 0));
        delete = add(new Button(UI.scale(70), get("area.preset.delete")) {
            @Override
            public void click() {
                deleteSelected();
            }
        }, rename.pos("ur").adds(5, 0));
        export = add(new Button(UI.scale(70), get("area.preset.export")) {
            @Override
            public void click() {
                exportSelected();
            }
        }, place.pos("bl").adds(0, 5));
        add(new Button(UI.scale(70), get("area.preset.import")) {
            @Override
            public void click() {
                importFile();
            }
        }, export.pos("ur").adds(5, 0));
        pack();
        load(null);
    }

    /** Opens the window, or brings the open one to the front. */
    public static NAreaPresetsWindow open(NGameUI gui)
    {
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof NAreaPresetsWindow) {
                NAreaPresetsWindow wnd = (NAreaPresetsWindow) w;
                wnd.load(wnd.selected());
                wnd.show();
                wnd.raise();
                return wnd;
            }
        }
        NAreaPresetsWindow wnd = new NAreaPresetsWindow();
        gui.add(wnd, gui.areas.c.add(gui.areas.sz.x, 0));
        wnd.raise();
        return wnd;
    }

    private AreaPreset selected()
    {
        return (list.sel == null) ? null : list.sel.preset;
    }

    /** Re-reads the presets, keeping {@code keep} (matched by file) selected. */
    void load(AreaPreset keep)
    {
        rows.clear();
        List<AreaPreset> builtins = AreaPresetStore.builtins();
        List<AreaPreset> mine = AreaPresetStore.user();
        if (!builtins.isEmpty()) {
            rows.add(new Row(get("area.preset.builtin"), null));
            for (AreaPreset p : builtins)
                rows.add(new Row(null, p));
        }
        rows.add(new Row(get("area.preset.mine"), null));
        for (AreaPreset p : mine)
            rows.add(new Row(null, p));
        Row sel = null;
        for (Row r : rows) {
            if (r.preset == null)
                continue;
            if (sel == null)
                sel = r;
            if (keep != null && keep.builtin == r.preset.builtin && Objects.equals(keep.file, r.preset.file)) {
                sel = r;
                break;
            }
        }
        list.reset();
        list.change(sel);
        selectionChanged();
    }

    private void placeSelected()
    {
        AreaPreset p = selected();
        if (p == null)
            return;
        NGameUI gui = (NGameUI) ui.gui;
        Runnable back = () -> {
            show();
            if (!gui.areas.visible())
                gui.areas.show();
            raise();
        };
        if (AreaPresetPlacer.start(gui, p, back)) {
            hide();
            gui.areas.hide();
        }
    }

    private void renameSelected()
    {
        AreaPreset p = selected();
        if (p == null || p.builtin)
            return;
        NAreaPresetNameDialog.open(ui, get("area.preset.name_title"), p.name, n -> {
            p.name = n;
            try {
                AreaPresetStore.write(p);
            } catch (IOException e) {
                ui.gui.error(get("area.preset.save_failed", e.getMessage()));
            }
            load(p);
        });
    }

    private void deleteSelected()
    {
        AreaPreset p = selected();
        if (p == null || p.builtin)
            return;
        try {
            AreaPresetStore.delete(p);
        } catch (IOException e) {
            ui.gui.error(get("area.preset.save_failed", e.getMessage()));
        }
        load(null);
    }

    private void exportSelected()
    {
        AreaPreset p = selected();
        if (p == null)
            return;
        java.awt.EventQueue.invokeLater(() -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("Area preset", "json"));
            fc.setSelectedFile(new File(p.name + ".json"));
            if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            File f = fc.getSelectedFile();
            if (!f.getName().toLowerCase().endsWith(".json"))
                f = new File(f.getPath() + ".json");
            try {
                AreaPresetStore.export(p, f.toPath());
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
            fc.setFileFilter(new FileNameExtensionFilter("Area preset", "json"));
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null)
                return;
            File f = fc.getSelectedFile();
            try {
                AreaPreset p = AreaPresetStore.importFile(f.toPath());
                pendingMsg = get("area.preset.imported", p.name);
            } catch (IOException | org.json.JSONException | IllegalArgumentException e) {
                pendingMsg = get("area.preset.import_failed", f.getName());
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
        AreaPreset p = selected();
        place.disable(p == null);
        export.disable(p == null);
        rename.disable(p == null || p.builtin);
        delete.disable(p == null || p.builtin);
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
        AreaPreset p = selected();
        thumb.set(p);
        info.settext((p == null) ? get("area.preset.empty") : get("area.preset.info", p.w, p.h, p.areas.size(), p.folderCount()));
        if (p == null)
            info.settip(get("area.preset.empty_tip"));
        else
            info.tooltip = null;
    }

    private class PresetList extends SListBox<Row, Widget>
    {
        PresetList(Coord sz)
        {
            super(sz, UI.scale(18));
        }

        @Override
        protected List<Row> items()
        {
            return rows;
        }

        @Override
        protected Widget makeitem(Row row, int idx, Coord sz)
        {
            return new RowWidget(this, sz, row);
        }

        @Override
        public void change(Row row)
        {
            /* Headings can't be picked. */
            if (row != null && row.preset == null)
                return;
            super.change(row);
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

    private class RowWidget extends SListWidget.ItemWidget<Row>
    {
        private final Text.Line text;
        private final Text.Line count;

        RowWidget(PresetList list, Coord sz, Row row)
        {
            super(list, sz, row);
            if (row.preset == null) {
                text = Text.std.render(row.header.toUpperCase(), new Color(233, 156, 84));
                count = null;
            } else {
                count = Text.std.render(String.valueOf(row.preset.areas.size()), new Color(143, 163, 164));
                text = Text.std.ellipsize(row.preset.name, sz.x - count.sz().x - UI.scale(16));
            }
        }

        @Override
        public void draw(GOut g)
        {
            int y = (sz.y - text.sz().y) / 2;
            if (item.preset == null) {
                g.chcolor(0, 0, 0, 60);
                g.frect(Coord.z, sz);
                g.chcolor();
                g.image(text.tex(), Coord.of(UI.scale(4), y));
                return;
            }
            g.image(text.tex(), Coord.of(UI.scale(10), y));
            g.image(count.tex(), Coord.of(sz.x - count.sz().x - UI.scale(4), y));
        }

        @Override
        public void dispose()
        {
            text.dispose();
            if (count != null)
                count.dispose();
            super.dispose();
        }
    }

    /** The selected preset's rectangles in their own colours, scaled to fit. */
    private static class Thumb extends Widget
    {
        private AreaPreset preset;
        private final List<Text.Line> names = new ArrayList<>();

        Thumb()
        {
            super(THUMB);
        }

        void set(AreaPreset p)
        {
            preset = p;
            for (Text.Line t : names)
                t.dispose();
            names.clear();
            if (p != null) {
                for (AreaPreset.Entry e : p.areas)
                    names.add(Text.std.render(e.name, Color.WHITE));
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
            if (preset == null || preset.w <= 0 || preset.h <= 0)
                return;
            int pad = UI.scale(6);
            double scale = Math.min((sz.x - 2.0 * pad) / preset.w, (sz.y - 2.0 * pad) / preset.h);
            Coord off = Coord.of((int) ((sz.x - preset.w * scale) / 2), (int) ((sz.y - preset.h * scale) / 2));
            for (int i = 0; i < preset.areas.size(); i++) {
                AreaPreset.Entry e = preset.areas.get(i);
                Coord ul = off.add((int) (e.x * scale), (int) (e.y * scale));
                Coord rsz = Coord.of(Math.max(2, (int) (e.w * scale)), Math.max(2, (int) (e.h * scale)));
                Color c = e.color;
                g.chcolor(c.getRed(), c.getGreen(), c.getBlue(), 170);
                g.frect(ul, rsz);
                g.chcolor(255, 255, 255, 140);
                g.rect(ul, rsz);
                g.chcolor();
                Text.Line t = names.get(i);
                if (t.sz().x <= rsz.x - 2 && t.sz().y <= rsz.y - 2)
                    g.image(t.tex(), ul.add(2, 1));
            }
        }

        @Override
        public void dispose()
        {
            for (Text.Line t : names)
                t.dispose();
            super.dispose();
        }
    }
}
