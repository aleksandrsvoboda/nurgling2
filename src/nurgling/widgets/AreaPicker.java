package nurgling.widgets;

import haven.*;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.i18n.L10n;
import nurgling.widgets.cookbook.HintTextEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * A saved-area dropdown with a search box above it. Typing narrows the list to areas whose name
 * or "ID n" contains the text; when the current choice drops out of the matches the first match
 * is picked, so typing a few letters is usually all it takes. Esc clears the search.
 */
public class AreaPicker extends Widget {
    public static class Entry {
        public final int id;
        public final String label;
        /** Always listed and never auto-picked by a search, e.g. "Select on map...". */
        public final boolean pinned;

        public Entry(int id, String label, boolean pinned) {
            this.id = id;
            this.label = label;
            this.pinned = pinned;
        }
    }

    private static final int MAX_ROWS = 12;

    private final List<Entry> all;
    private final List<Entry> shown = new ArrayList<>();
    private final IntConsumer onSelect;
    private final HintTextEntry search;
    private final NDropbox<Entry> box;

    /** Every saved area, sorted by name, labelled "Name [ID n]". */
    public static List<Entry> savedAreas() {
        List<NArea> areas = new ArrayList<>(NUtils.getGameUI().map.glob.map.areas.values());
        areas.sort(Comparator.comparing(a -> a.name));
        List<Entry> entries = new ArrayList<>();
        for (NArea area : areas)
            entries.add(new Entry(area.id, area.name + " [ID " + area.id + "]", false));
        return entries;
    }

    public AreaPicker(int w, List<Entry> entries, int selectedId, IntConsumer onSelect) {
        this.all = entries;
        this.onSelect = onSelect;
        shown.addAll(entries);
        search = add(new HintTextEntry(w, L10n.get("areapicker.search"), this::refilter), Coord.z);
        box = add(new NDropbox<Entry>(w, Math.min(Math.max(entries.size(), 1), MAX_ROWS), UI.scale(22)) {
            @Override
            protected Entry listitem(int i) { return shown.get(i); }
            @Override
            protected int listitems() { return shown.size(); }
            @Override
            protected void drawitem(GOut g, Entry item, int i) {
                g.text(item.label, Coord.z);
            }
            @Override
            public void change(Entry item) {
                super.change(item);
                if (item != null && AreaPicker.this.onSelect != null)
                    AreaPicker.this.onSelect.accept(item.id);
            }
        }, search.pos("bl").adds(0, 4));
        Entry initial = entries.isEmpty() ? null : entries.get(0);
        for (Entry e : entries) {
            if (e.id == selectedId) {
                initial = e;
                break;
            }
        }
        if (initial != null)
            box.change(initial);
        pack();
    }

    /** Selected area id, or null when there is nothing to pick. */
    public Integer selectedId() {
        return box.sel == null ? null : box.sel.id;
    }

    private void refilter() {
        String q = search.text().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        Entry firstMatch = null;
        boolean selShown = false;
        for (Entry e : all) {
            if (e.pinned || q.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(q)) {
                shown.add(e);
                // A pinned choice never counts as a search hit, so typing moves off it.
                if (e == box.sel && (!e.pinned || q.isEmpty()))
                    selShown = true;
                if (firstMatch == null && !e.pinned)
                    firstMatch = e;
            }
        }
        if (!selShown && firstMatch != null)
            box.change(firstMatch);
    }
}
