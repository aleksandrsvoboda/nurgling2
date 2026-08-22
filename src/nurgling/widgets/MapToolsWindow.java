package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.conf.ProspectKind;
import nurgling.conf.ProspectMarkSettings;
import nurgling.i18n.L10n;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Single home for everything that controls what the map draws: tree/fish icon toggles,
 * the prospected-sample layer with an independent quality threshold per resource kind,
 * and the terrain/ore tile search.
 *
 * Opened from the gear button on the map window. All state lives in NConfig, so the
 * toolbar toggle buttons and these controls are two views of the same values.
 */
public class MapToolsWindow extends Window {
    private static final int MARGIN = UI.scale(5);
    private static final int OVERLAY_W = UI.scale(300);
    private static final int ROW_GAP = UI.scale(3);
    private static final int TAB_BTN_W = UI.scale(90);
    private static final int ENTRY_W = UI.scale(44);
    private static final int ENTRY_X = OVERLAY_W - UI.scale(102);
    private static final int COUNT_X = OVERLAY_W - UI.scale(50);
    private static final int COUNT_W = OVERLAY_W - COUNT_X;
    private static final int SEARCH_BTN_W = UI.scale(70);
    private static final double COUNT_INTERVAL = 0.5;

    private final List<KindRow> rows = new ArrayList<>();
    private TextEntry masterEntry;
    private double countTimer = COUNT_INTERVAL;

    public MapToolsWindow() {
        super(new Coord(OVERLAY_W, UI.scale(260)), L10n.get("maptools.title"), true);

        Tabs tabs = new Tabs(Coord.z, Coord.z, this) {
            @Override
            public void changed(Tab from, Tab to) {
                /* The two tabs are very different sizes; follow the visible one. */
                MapToolsWindow.this.pack();
            }
        };
        Tabs.Tab overlays = tabs.add();
        Tabs.Tab search = tabs.add();

        buildOverlays(overlays);
        search.add(new TerrainSearchPanel(), 0, 0);

        Widget tabBtn = add(tabs.new TabButton(TAB_BTN_W, L10n.get("maptools.tab_overlays"), overlays), 0, 0);
        add(tabs.new TabButton(TAB_BTN_W, L10n.get("maptools.tab_search"), search), TAB_BTN_W + MARGIN, 0);

        /* Place the tab bodies under the buttons, whatever height the buttons turned out to be. */
        tabs.c = new Coord(0, tabBtn.sz.y + MARGIN);
        overlays.c = tabs.c;
        search.c = tabs.c;

        tabs.showtab(overlays);
        pack();
    }

    private void buildOverlays(Widget tab) {
        int y = 0;

        tab.add(new Label(L10n.get("maptools.section_icons")), 0, y);
        y += UI.scale(17);

        y = addIconRow(tab, y, L10n.get("maptools.tree_icons"),
                () -> NMiniMap.showTreeIcons(), val -> NMiniMap.showTreeIcons(val), MapToolsWindow::openTreeSearch);
        y = addIconRow(tab, y, L10n.get("maptools.fish_icons"),
                () -> NMiniMap.showFishIcons(), val -> NMiniMap.showFishIcons(val), MapToolsWindow::openFishSearch);

        y += MARGIN;
        tab.add(new Label(L10n.get("maptools.section_samples")), 0, y);
        y += UI.scale(17);

        // Master row: hides the whole layer without losing the per-kind settings.
        CheckBox master = tab.add(new CheckBox(L10n.get("maptools.show_samples")), UI.scale(4), y);
        master.state(() -> settings().master);
        master.set(val -> {
            settings().master = val;
            store();
        });
        Label masterLbl = tab.add(new Label(L10n.get("maptools.threshold")), ENTRY_X - UI.scale(26), y);
        masterEntry = tab.add(new TextEntry(ENTRY_W, "0") {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    applyToAll();
                    return true;
                }
                return super.keydown(ev);
            }
        }, ENTRY_X, y);
        Button setAll = tab.add(new Button(COUNT_W, L10n.get("maptools.set_all")) {
            @Override
            public void click() {
                applyToAll();
            }
        }, COUNT_X, y);
        y += alignRow(y, master, masterLbl, masterEntry, setAll) + ROW_GAP;

        for(ProspectKind kind : ProspectKind.values()) {
            KindRow row = new KindRow(tab, kind, y);
            rows.add(row);
            y += row.height;
        }

        tab.pack();
    }

    private int addIconRow(Widget tab, int y, String label, java.util.function.Supplier<Boolean> state,
                           java.util.function.Consumer<Boolean> set, Runnable search) {
        CheckBox box = tab.add(new CheckBox(label), UI.scale(4), y);
        box.state(state);
        box.set(set);
        Button btn = tab.add(new Button(SEARCH_BTN_W, L10n.get("maptools.search_btn")) {
            @Override
            public void click() {
                search.run();
            }
        }, OVERLAY_W - SEARCH_BTN_W, y);
        return y + alignRow(y, box, btn) + ROW_GAP;
    }

    /**
     * Vertically centre a row of widgets against the tallest one and report its height.
     * Buttons, checkboxes and text entries all have image-derived heights, so a hardcoded
     * row height either overlaps them or leaves a gap.
     */
    private static int alignRow(int y, Widget... widgets) {
        int height = 0;
        for(Widget widget : widgets)
            height = Math.max(height, widget.sz.y);
        for(Widget widget : widgets)
            widget.c = new Coord(widget.c.x, y + ((height - widget.sz.y) / 2));
        return height;
    }

    /** One resource kind: enable flag, its own quality threshold, and a live shown/total count. */
    private class KindRow {
        private final ProspectKind kind;
        private final TextEntry entry;
        private final Label count;
        private final int height;

        KindRow(Widget tab, ProspectKind kind, int y) {
            this.kind = kind;
            CheckBox box = tab.add(new CheckBox(L10n.get(kind.l10nKey)), UI.scale(14), y);
            box.state(() -> settings().enabled(kind));
            box.set(val -> {
                settings().setEnabled(kind, val);
                store();
            });
            entry = tab.add(new TextEntry(ENTRY_W, String.valueOf(settings().threshold(kind))) {
                @Override
                public void changed() {
                    super.changed();
                    Integer val = parseThreshold(text());
                    if(val != null) {
                        settings().setThreshold(kind, val);
                        store();
                    }
                }

                @Override
                public boolean keydown(KeyDownEvent ev) {
                    if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                        sync();
                        return true;
                    }
                    return super.keydown(ev);
                }
            }, ENTRY_X, y);
            count = tab.add(new Label("-"), COUNT_X, y);
            height = alignRow(y, box, entry, count) + ROW_GAP;
        }

        /** Rewrite the field from the stored (clamped) value. */
        void sync() {
            String val = String.valueOf(settings().threshold(kind));
            if(!val.equals(entry.text()))
                entry.settext(val);
        }

        void setCount(int shown, int total) {
            count.settext((total == 0) ? "-" : (shown + "/" + total));
        }
    }

    private void applyToAll() {
        Integer val = parseThreshold(masterEntry.text());
        if(val == null)
            return;
        ProspectMarkSettings settings = settings();
        settings.setAllThresholds(val);
        store();
        masterEntry.settext(String.valueOf(ProspectMarkSettings.clamp(val)));
        for(KindRow row : rows)
            row.sync();
    }

    /** Lenient parse: blank counts as 0, anything unparseable leaves the stored value alone. */
    private static Integer parseThreshold(String text) {
        String trimmed = (text == null) ? "" : text.trim();
        if(trimmed.isEmpty())
            return 0;
        try {
            return ProspectMarkSettings.clamp(Integer.parseInt(trimmed));
        } catch(NumberFormatException e) {
            return null;
        }
    }

    private ProspectMarkSettings settings() {
        ProspectMarkSettings settings = NMiniMap.prospectSettings();
        if(settings == null) {
            settings = new ProspectMarkSettings();
            NConfig.set(NConfig.Key.prospectMarks, settings);
        }
        return settings;
    }

    /** The settings object is mutated in place; re-setting it flags the config as dirty. */
    private void store() {
        NConfig.set(NConfig.Key.prospectMarks, settings());
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if(!visible())
            return;
        countTimer += dt;
        if(countTimer < COUNT_INTERVAL)
            return;
        countTimer = 0;
        updateCounts();
    }

    private void updateCounts() {
        Map<ProspectKind, int[]> tally = new EnumMap<>(ProspectKind.class);
        NGameUI gui = NUtils.getGameUI();
        if(gui != null && gui.labeledMarkService != null && gui.mmap != null && gui.mmap.sessloc != null) {
            for(LabeledMinimapMark mark : gui.labeledMarkService.getMarksForSegment(gui.mmap.sessloc.seg.id)) {
                int[] counts = tally.computeIfAbsent(mark.kind, k -> new int[2]);
                counts[1]++;
                if(NMiniMap.markVisible(mark))
                    counts[0]++;
            }
        }
        for(KindRow row : rows) {
            int[] counts = tally.get(row.kind);
            if(counts == null)
                row.setCount(0, 0);
            else
                row.setCount(counts[0], counts[1]);
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    /** Toggle the panel, creating it on first use. */
    public static void toggle() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.mapToolsWindow != null) {
            if(gui.mapToolsWindow.visible()) {
                gui.mapToolsWindow.hide();
            } else {
                gui.mapToolsWindow.show();
                gui.mapToolsWindow.raise();
            }
        } else {
            gui.mapToolsWindow = new MapToolsWindow();
            gui.add(gui.mapToolsWindow, new Coord(100, 100));
            gui.mapToolsWindow.show();
        }
    }

    public static void openTreeSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.treeSearchWindow != null) {
            if(gui.treeSearchWindow.visible()) {
                gui.treeSearchWindow.hide();
            } else {
                gui.treeSearchWindow.show();
                gui.treeSearchWindow.raise();
            }
        } else {
            gui.treeSearchWindow = new TreeSearchWindow(gui);
            gui.add(gui.treeSearchWindow, new Coord(100, 100));
            gui.treeSearchWindow.show();
        }
    }

    public static void openFishSearch() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null)
            return;
        if(gui.fishSearchWindow != null) {
            if(gui.fishSearchWindow.visible()) {
                gui.fishSearchWindow.hide();
            } else {
                gui.fishSearchWindow.show();
                gui.fishSearchWindow.raise();
            }
        } else {
            gui.fishSearchWindow = new FishSearchWindow(gui);
            gui.add(gui.fishSearchWindow, new Coord(100, 100));
            gui.fishSearchWindow.show();
        }
    }
}
