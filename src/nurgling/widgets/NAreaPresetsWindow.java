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

import nurgling.i18n.L10n;

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
    /** The selected preset's description: what it is for, which bots use it, what to build in each area. */
    private final RichTextBox about;
    private final Button place, rename, delete, export, describeBtn;
    /* Set by the file choosers, which run on the AWT thread; picked up in tick(). */
    private volatile boolean reload = false;
    private volatile String pendingMsg = null;

    /** Built-in category open in the list, or null for the top level. */
    private String openCategory = null;

    /** Category order: the bot menu's, then any others A-Z. */
    private static final List<String> CATEGORY_ORDER = Arrays.asList(
            "resources", "productions", "battle", "farming", "farming_quality", "animals", "utils", "build", "tools");

    private enum Kind { HEADER, FOLDER, UP, PRESET }

    private static class Row
    {
        final Kind kind;
        final String text;
        /** The category a FOLDER row opens. */
        final String category;
        final int count;
        final AreaPreset preset;

        Row(Kind kind, String text, String category, int count, AreaPreset preset)
        {
            this.kind = kind;
            this.text = text;
            this.category = category;
            this.count = count;
            this.preset = preset;
        }

        static Row header(String text)
        {
            return new Row(Kind.HEADER, text, null, 0, null);
        }

        static Row preset(AreaPreset p)
        {
            return new Row(Kind.PRESET, p.name, p.category, p.areas.size(), p);
        }
    }

    /** Shown name of a category: the bot menu's name for it when there is one. */
    static String categoryName(String category)
    {
        String key = "bot.type." + category;
        if (L10n.hasKey(key))
            return get(key);
        return category.isEmpty() ? category : Character.toUpperCase(category.charAt(0)) + category.substring(1);
    }

    private static int categoryRank(String category)
    {
        int i = CATEGORY_ORDER.indexOf(category);
        return (i < 0) ? CATEGORY_ORDER.size() : i;
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
        Button importBtn = add(new Button(UI.scale(70), get("area.preset.import")) {
            @Override
            public void click() {
                importFile();
            }
        }, export.pos("ur").adds(5, 0));
        describeBtn = add(new Button(UI.scale(70), get("area.preset.edit_description")) {
            @Override
            public void click() {
                describeSelected();
            }
        }, importBtn.pos("ur").adds(5, 0));
        describeBtn.settip(get("area.preset.edit_description_tip"));
        int top = Math.max(list.pos("bl").y, export.pos("bl").y) + UI.scale(8);
        about = add(new RichTextBox(Coord.of(thumb.pos("ur").x - list.c.x, UI.scale(190)), "", RichText.stdf), Coord.of(list.c.x, top));
        about.bg = new Color(30, 40, 40, 160);
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
        /* Show where the kept preset lives: its category, or the top level for the player's own. */
        if (keep != null)
            openCategory = (keep.builtin && !keep.category.isEmpty()) ? keep.category : null;
        rows.clear();
        List<AreaPreset> builtins = AreaPresetStore.builtins();
        Map<String, List<AreaPreset>> byCategory = new TreeMap<>(
                Comparator.comparingInt(NAreaPresetsWindow::categoryRank).thenComparing(String.CASE_INSENSITIVE_ORDER));
        List<AreaPreset> loose = new ArrayList<>();
        for (AreaPreset p : builtins) {
            if (p.category.isEmpty())
                loose.add(p);
            else
                byCategory.computeIfAbsent(p.category, k -> new ArrayList<>()).add(p);
        }
        if (openCategory != null && !byCategory.containsKey(openCategory))
            openCategory = null;
        if (openCategory != null) {
            rows.add(Row.header(get("area.preset.builtin") + ": " + categoryName(openCategory)));
            rows.add(new Row(Kind.UP, "..", null, 0, null));
            for (AreaPreset p : byCategory.get(openCategory))
                rows.add(Row.preset(p));
        } else {
            if (!builtins.isEmpty()) {
                rows.add(Row.header(get("area.preset.builtin")));
                for (Map.Entry<String, List<AreaPreset>> e : byCategory.entrySet())
                    rows.add(new Row(Kind.FOLDER, categoryName(e.getKey()), e.getKey(), e.getValue().size(), null));
                for (AreaPreset p : loose)
                    rows.add(Row.preset(p));
            }
            rows.add(Row.header(get("area.preset.mine")));
            for (AreaPreset p : AreaPresetStore.user())
                rows.add(Row.preset(p));
        }
        Row sel = null;
        for (Row r : rows) {
            if (r.kind != Kind.PRESET)
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

    private void describeSelected()
    {
        AreaPreset p = selected();
        if (p == null || p.builtin)
            return;
        NAreaPresetDescriptionDialog.open(ui, p, text -> {
            p.description = text;
            try {
                AreaPresetStore.write(p);
            } catch (IOException e) {
                ui.gui.error(get("area.preset.save_failed", e.getMessage()));
            }
            load(p);
        });
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
        describeBtn.disable(p == null || p.builtin);
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
        about.settext((p == null) ? "" : describe(p));
    }

    /**
     * Turns a description into rich text: a line ending in ':' is a section heading, and a "• Area: text" bullet
     * shows the area name in bold. Everything else is quoted, so a player's own description is shown as typed.
     */
    static String describe(AreaPreset p)
    {
        if (p.description.isEmpty())
            return RichText.Parser.quote(get("area.preset.no_description"));
        return describe(p.description);
    }

    /** Rich text for any preset description, by the rules above. */
    static String describe(String description)
    {
        StringBuilder sb = new StringBuilder();
        for (String line : description.split("\n", -1)) {
            if (sb.length() > 0)
                sb.append('\n');
            if (line.endsWith(":") && !line.startsWith("\u2022")) {
                sb.append("$col[233,156,84]{$b{").append(RichText.Parser.quote(line)).append("}}");
            } else if (line.startsWith("\u2022 ") && line.indexOf(": ") > 2) {
                int c = line.indexOf(": ");
                sb.append("  \u2022 $b{").append(RichText.Parser.quote(line.substring(2, c + 1))).append("} ")
                  .append(RichText.Parser.quote(line.substring(c + 2)));
            } else if (line.startsWith("\u2022 ")) {
                sb.append("  ").append(RichText.Parser.quote(line));
            } else {
                sb.append(RichText.Parser.quote(line));
            }
        }
        return sb.toString();
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
            /* Headings can't be picked; a category row opens it and ".." goes back up. */
            if (row != null && row.kind == Kind.FOLDER) {
                openCategory = row.category;
                load(null);
                return;
            }
            if (row != null && row.kind == Kind.UP) {
                openCategory = null;
                load(null);
                return;
            }
            if (row != null && row.kind == Kind.HEADER)
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
            if (row.kind == Kind.HEADER) {
                text = Text.std.render(row.text.toUpperCase(), new Color(233, 156, 84));
                count = null;
            } else if (row.kind == Kind.UP) {
                text = Text.std.render(row.text);
                count = null;
            } else {
                count = Text.std.render(String.valueOf(row.count), new Color(143, 163, 164));
                text = Text.std.ellipsize(row.text, sz.x - count.sz().x - textX() - UI.scale(8));
            }
        }

        private int textX()
        {
            return (item.kind == Kind.PRESET) ? UI.scale(10) : UI.scale(24);
        }

        @Override
        public void draw(GOut g)
        {
            int y = (sz.y - text.sz().y) / 2;
            if (item.kind == Kind.HEADER) {
                g.chcolor(0, 0, 0, 60);
                g.frect(Coord.z, sz);
                g.chcolor();
                g.image(text.tex(), Coord.of(UI.scale(4), y));
                return;
            }
            if (item.kind != Kind.PRESET) {
                Coord icon = UI.scale(16, 16);
                g.image((item.kind == Kind.UP) ? NAreasWidget.openfolderIcon : NAreasWidget.folderIcon,
                        Coord.of(UI.scale(4), (sz.y - icon.y) / 2), icon);
            }
            g.image(text.tex(), Coord.of(textX(), y));
            if (count != null)
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
