package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.forage.ForageFind;
import nurgling.i18n.L10n;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Search the forageables recorded by {@link nurgling.forage.ForageRecorder}, this player's and, with the
 * database on, the village's.
 *
 * <p>Same shape as {@link MineralSearchWindow}: type, minimum quality, results best first, a click centres
 * the map, the cross deletes. On top of that a name filter and the date the find was picked.
 */
public class ForageSearchWindow extends Window {
    private static final int WINDOW_WIDTH = UI.scale(420);
    private static final int WINDOW_HEIGHT = UI.scale(560);
    private static final String ANY = "Any";

    private enum Range {
        ANY("forage.range.any", -1),
        TODAY("forage.range.today", 0),
        WEEK("forage.range.week", 6),
        MONTH("forage.range.month", 29),
        QUARTER("forage.range.quarter", 89),
        CUSTOM("forage.range.custom", -1);

        final String key;
        /** Days back from today, inclusive of today; -1 when not a fixed window. */
        final int days;

        Range(String key, int days) {
            this.key = key;
            this.days = days;
        }
    }

    private final NGameUI gui;

    private NDropbox<String> typeDropdown;
    private final NDropbox<Range> rangeDropdown;
    private final TextEntry nameEntry;
    private final TextEntry minQualityEntry;
    private final TextEntry fromEntry;
    private final TextEntry beforeEntry;
    private final ForageResultsList resultsList;

    private List<String> types = new ArrayList<>();
    private final int controlX;
    private final int typeDropdownY;

    public ForageSearchWindow(NGameUI gui) {
        super(new Coord(WINDOW_WIDTH, WINDOW_HEIGHT), L10n.get("forage.search_title"), true);
        this.gui = gui;

        int y = UI.scale(10);
        int labelX = UI.scale(10);
        controlX = UI.scale(120);
        int lineHeight = UI.scale(30);

        add(new Label(L10n.get("forage.type")), labelX, y + UI.scale(5));
        typeDropdownY = y;
        refreshTypeDropdown();
        y += lineHeight;

        add(new Label(L10n.get("forage.name")), labelX, y + UI.scale(5));
        nameEntry = add(searchEntry(UI.scale(250), ""), controlX, y);
        y += lineHeight;

        add(new Label(L10n.get("forage.min_quality")), labelX, y + UI.scale(5));
        minQualityEntry = add(searchEntry(UI.scale(100), "0"), controlX, y);
        y += lineHeight;

        add(new Label(L10n.get("forage.picked")), labelX, y + UI.scale(5));
        final Range[] ranges = Range.values();
        rangeDropdown = add(new NDropbox<Range>(UI.scale(250), ranges.length, UI.scale(20)) {
            @Override
            protected Range listitem(int i) {
                return ranges[i];
            }

            @Override
            protected int listitems() {
                return ranges.length;
            }

            @Override
            protected void drawitem(GOut g, Range item, int i) {
                g.text(L10n.get(item.key), Coord.z);
            }

            @Override
            public void change(Range item) {
                super.change(item);
                if(ui != null)
                    performSearch();
            }
        }, controlX, y);
        rangeDropdown.sel = Range.ANY;
        y += lineHeight;

        add(new Label(L10n.get("forage.from")), controlX, y + UI.scale(5));
        fromEntry = add(searchEntry(UI.scale(90), ""), controlX + UI.scale(40), y);
        add(new Label(L10n.get("forage.before")), controlX + UI.scale(140), y + UI.scale(5));
        beforeEntry = add(searchEntry(UI.scale(90), ""), controlX + UI.scale(190), y);
        y += UI.scale(22);
        add(new Label(L10n.get("forage.date_hint")), controlX, y);
        y += lineHeight;

        add(new Button(UI.scale(150), L10n.get("common.search")) {
            @Override
            public void click() {
                performSearch();
            }
        }, UI.scale(135), y);
        y += lineHeight + UI.scale(10);

        add(new Label(L10n.get("common.results")), labelX, y);
        Button deleteShown = add(new Button(UI.scale(160), L10n.get("forage.delete_shown")) {
            @Override
            public void click() {
                confirmDeleteShown();
            }
        }, WINDOW_WIDTH - UI.scale(170), y - UI.scale(4));
        deleteShown.settip(L10n.get("forage.delete_shown_tip"));
        y += UI.scale(25);

        Coord resultsSize = new Coord(WINDOW_WIDTH - UI.scale(20), WINDOW_HEIGHT - y - UI.scale(10));
        resultsList = add(new ForageResultsList(resultsSize), labelX, y);

        pack();
    }

    /** A text field that searches on Enter. */
    private TextEntry searchEntry(int w, String text) {
        return new TextEntry(w, text) {
            @Override
            public boolean keydown(KeyDownEvent ev) {
                if(ev.code == java.awt.event.KeyEvent.VK_ENTER) {
                    performSearch();
                    return true;
                }
                return super.keydown(ev);
            }
        };
    }

    private List<ForageFind> allFinds() {
        if(gui == null || gui.forageStore == null)
            return new ArrayList<>();
        return gui.forageStore.finds();
    }

    /** Offer only the item types actually recorded. */
    private void refreshTypeDropdown() {
        String previous = (typeDropdown == null) ? ANY : typeDropdown.sel;
        if(typeDropdown != null) {
            // ui is null until this window joins the tree; destroy() itself is null-safe.
            if(ui != null)
                ui.destroy(typeDropdown);
            else
                typeDropdown.destroy();
            typeDropdown = null;
        }
        types = allFinds().stream()
                .map(f -> f.itemName)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        types.add(0, ANY);

        typeDropdown = add(new NDropbox<String>(UI.scale(250), Math.min(types.size(), 10), UI.scale(20)) {
            @Override
            protected String listitem(int i) {
                return types.get(i);
            }

            @Override
            protected int listitems() {
                return types.size();
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }
        }, controlX, typeDropdownY);
        typeDropdown.sel = types.contains(previous) ? previous : ANY;
    }

    private void performSearch() {
        double minQuality;
        try {
            String txt = minQualityEntry.text().trim();
            minQuality = txt.isEmpty() ? 0 : Double.parseDouble(txt.replace(',', '.'));
        } catch(NumberFormatException e) {
            minQuality = 0;
        }
        final double min = minQuality;
        final String type = (typeDropdown.sel == null) ? ANY : typeDropdown.sel;
        final String name = nameEntry.text().trim().toLowerCase();

        // From is included, Before is not: "before 2026-09-29" keeps the 28th and everything earlier.
        LocalDate from = null, before = null;
        Range range = (rangeDropdown.sel == null) ? Range.ANY : rangeDropdown.sel;
        if(range == Range.CUSTOM) {
            from = parseDate(fromEntry.text());
            before = parseDate(beforeEntry.text());
        } else if(range.days >= 0) {
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            from = today.minusDays(range.days);
        }
        final LocalDate dFrom = from, dBefore = before;

        List<ForageFind> results = allFinds().stream()
                .filter(f -> ANY.equals(type) || type.equals(f.itemName))
                .filter(f -> name.isEmpty() || f.itemName.toLowerCase().contains(name))
                // Compared as displayed, like the map threshold: q29.6 shows as q30 and passes 30.
                .filter(f -> Math.round(f.quality) >= min)
                .filter(f -> dFrom == null || !f.foundDate().isBefore(dFrom))
                .filter(f -> dBefore == null || f.foundDate().isBefore(dBefore))
                // Best first: the point of recording quality is finding the best spot.
                .sorted(Comparator.comparingDouble((ForageFind f) -> f.quality).reversed()
                        .thenComparing(Comparator.comparingLong((ForageFind f) -> f.foundAt).reversed()))
                .collect(Collectors.toList());

        resultsList.setResults(results);
    }

    /** Ask first: with the database on, this removes the finds for the whole village. */
    private void confirmDeleteShown() {
        final List<ForageFind> shown = new ArrayList<>(resultsList.results);
        if(shown.isEmpty() || gui == null || gui.forageStore == null)
            return;
        gui.adda(new ConfirmDelete(shown.size(), () -> {
            List<String> ids = new ArrayList<>(shown.size());
            for(ForageFind f : shown)
                ids.add(f.id);
            gui.forageStore.removeAll(ids);
            gui.msg(L10n.get("forage.deleted_n", shown.size()), java.awt.Color.YELLOW);
            refreshTypeDropdown();
            performSearch();
        }), gui.sz.div(2), 0.5, 0.5);
    }

    private static class ConfirmDelete extends Window {
        ConfirmDelete(int count, Runnable go) {
            super(UI.scale(new Coord(380, 105)), L10n.get("forage.delete_confirm_title"), true);
            add(new Label(L10n.get("forage.delete_confirm_line1", count)), UI.scale(new Coord(10, 5)));
            add(new Label(L10n.get("forage.delete_confirm_line2")), UI.scale(new Coord(10, 27)));
            adda(new Button(UI.scale(110), L10n.get("forage.delete"), false, () -> {
                destroy();
                go.run();
            }), UI.scale(new Coord(110, 62)), 0.5, 0.0);
            adda(new Button(UI.scale(110), L10n.get("common.cancel"), false, this::destroy),
                 UI.scale(new Coord(250, 62)), 0.5, 0.0);
        }

        @Override
        public void wdgmsg(String msg, Object... args) {
            if(msg.equals("close"))
                destroy();
            else
                super.wdgmsg(msg, args);
        }
    }

    /** A YYYY-MM-DD date, or null when blank or unreadable (no bound on that side). */
    private static LocalDate parseDate(String text) {
        String t = (text == null) ? "" : text.trim();
        if(t.isEmpty())
            return null;
        try {
            return LocalDate.parse(t);
        } catch(DateTimeParseException e) {
            return null;
        }
    }

    private class ForageResultsList extends SListBox<ForageFind, Widget> {
        private List<ForageFind> results = new ArrayList<>();

        ForageResultsList(Coord sz) {
            super(sz, UI.scale(25));
        }

        void setResults(List<ForageFind> results) {
            this.results = results;
        }

        @Override
        protected List<ForageFind> items() {
            return results;
        }

        @Override
        protected Widget makeitem(ForageFind find, int idx, Coord sz) {
            return new ItemWidget<ForageFind>(this, sz, find) {
                {
                    int deleteButtonWidth = UI.scale(22);
                    int panButtonWidth = sz.x - deleteButtonWidth - UI.scale(4);
                    final String line = String.format("%s - q%d x%d - %s%s", find.itemName,
                            Math.round(find.quality), find.amount, find.foundDate(),
                            find.foundBy.isEmpty() ? "" : " - " + find.foundBy);

                    add(new Button(panButtonWidth, "") {
                        @Override
                        public void draw(GOut g) {
                            g.text(line, Coord.z);
                        }

                        @Override
                        public void click() {
                            panMapTo(find);
                        }
                    }, Coord.z);

                    add(new IButton(nurgling.NStyle.crossSquare[0].back,
                                    nurgling.NStyle.crossSquare[1].back,
                                    nurgling.NStyle.crossSquare[2].back) {
                        @Override
                        public void click() {
                            if(gui != null && gui.forageStore != null) {
                                gui.forageStore.remove(find.id);
                                gui.msg(L10n.get("forage.removed", find.itemName), java.awt.Color.YELLOW);
                                performSearch();
                            }
                        }
                    }, new Coord(panButtonWidth + UI.scale(2), (sz.y - UI.scale(22)) / 2));
                }
            };
        }
    }

    private void panMapTo(ForageFind find) {
        if(gui == null || gui.mapfile == null || gui.mapfile.view == null)
            return;
        NMapWnd mapWnd = gui.mapfile;
        if(!mapWnd.visible())
            gui.togglewnd(mapWnd);
        if(gui.mmap == null || gui.mmap.file == null)
            return;
        MapFile file = gui.mmap.file;
        MiniMap.Location loc = null;
        try(Locked lk = new Locked(file.lock.readLock())) {
            MapFile.GridInfo info = file.gridinfo.get(find.gridId);
            MapFile.Segment seg = (info == null) ? null : file.segments.get(info.seg);
            if(seg != null)
                loc = new MiniMap.Location(seg, info.sc.mul(MCache.cmaps).add(find.ox, find.oy));
        }
        if(loc == null) {
            gui.msg(L10n.get("forage.not_here"), java.awt.Color.YELLOW);
            return;
        }
        mapWnd.view.center(loc);
        mapWnd.view.follow(null);
        gui.msg(L10n.get("forage.centered", find.itemName), java.awt.Color.GREEN);
    }

    @Override
    public void show() {
        // Finds accumulate while the window is closed, so rebuild what is on offer.
        refreshTypeDropdown();
        performSearch();
        super.show();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if(msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }
}
