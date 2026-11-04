package nurgling.widgets;

import haven.*;
import nurgling.NGameUI;
import nurgling.NWindowDeco;
import nurgling.discovery.DiscoveryCatalog;
import nurgling.discovery.DiscoveryLedger;
import nurgling.i18n.L10n;
import nurgling.styles.UITheme;
import nurgling.tools.ItemIcons;
import nurgling.tools.LpExplorer;
import nurgling.widgets.cookbook.HintTextEntry;

import java.awt.Color;
import java.text.SimpleDateFormat;
import java.util.*;

/** Every item the character has or has not discovered, from its DiscoveryLedger and DiscoveryCatalog. */
public class NDiscoveryAtlas extends Window {
    private enum Status { ALL, UNDISCOVERED, DISCOVERED, KNOWN }

    private static final Color C_DISCOVERED = new Color(47, 181, 168);
    private static final Color C_KNOWN = new Color(125, 139, 140);
    private static final Color C_UNDISCOVERED = UITheme.ACCENT;
    private static final Color C_HELD = new Color(176, 127, 224);
    private static final Color C_SEEN = new Color(215, 179, 61);
    private static final RichText.Foundry body = new RichText.Foundry(
        nurgling.styles.UIFont.regular.deriveFont((float)UI.scale(12)), java.awt.Color.WHITE);

    private final DiscoveryLedger ledger;
    /** The other characters' ledgers, read in the background: ~30 ms a file is a hitch on the UI thread. */
    private final java.util.concurrent.CompletableFuture<Map<String, DiscoveryLedger.Other>> othersLoad;
    private Map<String, DiscoveryLedger.Other> others = Collections.emptyMap();
    private List<DiscoveryCatalog.Row> catalog = Collections.emptyList();
    private final List<DiscoveryCatalog.Row> rows = new ArrayList<>();
    private final Map<String, Tex> rowText = new HashMap<>();
    private final EnumSet<DiscoveryCatalog.Category> categories = EnumSet.allOf(DiscoveryCatalog.Category.class);
    private Status status = Status.UNDISCOVERED;
    private final List<Chip> statusChips = new ArrayList<>(), categoryChips = new ArrayList<>();
    private final HintTextEntry search;
    private final Listbox<DiscoveryCatalog.Row> list;
    private final CheckBox unverified;
    private final Label summary;
    private final Scrollport details;
    private final Button mark;
    private String selected;
    private int seenVersion = -1;
    private int detailY;

    public NDiscoveryAtlas(DiscoveryLedger ledger) {
        super(clamp(Utils.getprefc("wndsz-discovery-atlas", UI.scale(760, 520))), L10n.get("discovery.title"));
        this.ledger = ledger;
        this.othersLoad = java.util.concurrent.CompletableFuture.supplyAsync(ledger::others);
        posmem("discovery-atlas");
        search = add(new HintTextEntry(UI.scale(320), L10n.get("discovery.search"), this::refresh), UI.scale(10, 10));
        for (Status s : Status.values())
            statusChips.add(add(new Chip(L10n.get("discovery.status." + s.name().toLowerCase(Locale.ROOT)), () -> { status = s; refresh(); }) {
                boolean on() { return status == s; }
            }, Coord.z));
        for (DiscoveryCatalog.Category c : DiscoveryCatalog.Category.values())
            categoryChips.add(add(new Chip(L10n.get("discovery.cat." + c.name().toLowerCase(Locale.ROOT)), () -> {
                if (!categories.remove(c)) categories.add(c);
                refresh();
            }) {
                boolean on() { return categories.contains(c); }
            }, Coord.z));
        list = add(new Listbox<DiscoveryCatalog.Row>(UI.scale(320), 12, UI.scale(26)) {
            protected DiscoveryCatalog.Row listitem(int i) { return rows.get(i); }
            protected int listitems() { return rows.size(); }
            protected void drawitem(GOut g, DiscoveryCatalog.Row row, int i) {
                if (row == sel) { g.chcolor(UITheme.SELECTED); g.frect(Coord.z, new Coord(sz.x, itemh)); }
                g.chcolor(color(row));
                g.frect(UI.scale(5, 9), UI.scale(8, 8));
                g.chcolor();
                ItemIcons.draw(g, ItemIcons.get(row.res, row.name, false), UI.scale(18, 1), UI.scale(24));
                Tex tex = rowText.computeIfAbsent(row.name, n -> Text.render(n, row.unverified ? UITheme.DISABLED : UITheme.TEXT).tex());
                g.image(tex, new Coord(UI.scale(48), (itemh - tex.sz().y) / 2));
            }
            public void change(DiscoveryCatalog.Row row) {
                super.change(row);
                selected = row == null ? null : row.name;
                rebuildDetails();
            }
        }, Coord.z);
        unverified = add(new CheckBox(L10n.get("discovery.unverified")) {
            public void changed(boolean value) { super.changed(value); rebuildCatalog(); }
        }, Coord.z);
        unverified.settip(L10n.get("discovery.unverified_tip"));
        summary = add(new Label(""), Coord.z);
        details = add(new Scrollport(UI.scale(380, 380)), Coord.z);
        mark = add(new Button(UI.scale(200), L10n.get("discovery.mark_known"), this::toggleMark), Coord.z);
        arrange();
    }

    @Override protected Deco makedeco() { return new NWindowDeco(false).dragsize(true); }

    private static Coord clamp(Coord size) {
        return new Coord(Math.max(UI.scale(660), size.x), Math.max(UI.scale(400), size.y));
    }

    @Override public void resize(Coord size) {
        super.resize(clamp(size));
        if (mark != null) { arrange(); rebuildDetails(); }
    }

    private void arrange() {
        Coord size = csz();
        int pad = UI.scale(10), left = UI.scale(320), gap = UI.scale(4);
        int y = search.c.y + search.sz.y + UI.scale(6);
        y = flow(statusChips, pad, y, left) + gap;
        y = flow(categoryChips, pad, y, left) + UI.scale(6);
        summary.move(new Coord(pad, size.y - pad - summary.sz.y));
        unverified.move(new Coord(pad, summary.c.y - UI.scale(6) - unverified.sz.y));
        list.move(new Coord(pad, y));
        list.resize(new Coord(left, Math.max(UI.scale(60), unverified.c.y - UI.scale(8) - y)));
        int dx = pad + left + UI.scale(14);
        mark.move(new Coord(dx, size.y - pad - mark.sz.y));
        details.move(new Coord(dx, pad));
        details.resize(new Coord(size.x - dx - pad, mark.c.y - UI.scale(8) - pad));
    }

    /** Places chips left to right, wrapping at width; returns the bottom. */
    private static int flow(List<Chip> chips, int x0, int y, int width) {
        int x = x0, bottom = y;
        for (Chip c : chips) {
            if (x > x0 && x + c.sz.x > x0 + width) { x = x0; y = bottom + UI.scale(4); }
            c.move(new Coord(x, y));
            x += c.sz.x + UI.scale(4);
            bottom = Math.max(bottom, y + c.sz.y);
        }
        return bottom;
    }

    private void rebuildCatalog() {
        NGameUI gui = ui != null && ui.gui instanceof NGameUI ? (NGameUI) ui.gui : null;
        Collection<nurgling.craft.AtlasCatalog.Recipe> recipes = gui != null && gui.atlas != null ? gui.atlas.catalog().recipes() : null;
        catalog = DiscoveryCatalog.build(ledger, others, recipes, unverified.a);
        seenVersion = ledger.version();
        refresh();
    }

    @Override protected void added() {
        super.added();
        // The craft atlas is only reachable once the window is in the UI.
        rebuildCatalog();
    }

    private void refresh() {
        if (list == null)
            return;
        String q = search.text().trim().toLowerCase(Locale.ROOT);
        rows.clear();
        int done = 0, total = 0;
        for (DiscoveryCatalog.Row r : catalog) {
            if (!r.unverified) {
                total++;
                if (ledger.isDone(r.name))
                    done++;
            }
            if (!categories.contains(r.category) || !matches(r))
                continue;
            if (!q.isEmpty() && !r.name.toLowerCase(Locale.ROOT).contains(q))
                continue;
            rows.add(r);
        }
        DiscoveryCatalog.Row sel = null;
        for (DiscoveryCatalog.Row r : rows)
            if (r.name.equals(selected)) sel = r;
        list.sel = sel;
        list.sb.val = Math.max(0, Math.min(list.sb.val, rows.size() - list.h));
        summary.settext(String.format(L10n.get("discovery.summary"), done, total, Utils.thformat(ledger.totalLp())));
        rebuildDetails();
    }

    private boolean matches(DiscoveryCatalog.Row r) {
        DiscoveryLedger.Entry e = ledger.get(r.name);
        boolean isDone = e != null && e.state.done;
        switch (status) {
            case UNDISCOVERED: return !isDone;
            case DISCOVERED: return isDone && e.state == DiscoveryLedger.State.DISCOVERED;
            case KNOWN: return isDone && e.state != DiscoveryLedger.State.DISCOVERED;
            default: return true;
        }
    }

    private Color color(DiscoveryCatalog.Row r) {
        DiscoveryLedger.Entry e = ledger.get(r.name);
        if (e == null) return C_UNDISCOVERED;
        switch (e.state) {
            case DISCOVERED: return C_DISCOVERED;
            case HELD: return C_HELD;
            case SEEN: return C_SEEN;
            default: return C_KNOWN;
        }
    }

    private DiscoveryCatalog.Row selectedRow() {
        for (DiscoveryCatalog.Row r : catalog)
            if (r.name.equals(selected)) return r;
        return null;
    }

    private void toggleMark() {
        DiscoveryCatalog.Row r = selectedRow();
        if (r == null)
            return;
        if (ledger.isDone(r.name)) {
            ledger.forget(r.name);
            // A resource cached as "nothing left to find" may have something again.
            LpExplorer.invalidateCaches();
        } else {
            ledger.record(r.name, DiscoveryLedger.State.MANUAL, r.res, 0, false, System.currentTimeMillis(), null);
        }
        refresh();
    }

    private void paragraph(String value, Color color) {
        final Tex text = body.render(RichText.Parser.quote(value), Math.max(UI.scale(100), details.cont.sz.x - UI.scale(20))).tex();
        details.cont.add(new Widget(text.sz()) {
            public void draw(GOut g) { g.chcolor(color); g.image(text, Coord.z); g.chcolor(); }
            public void dispose() { text.dispose(); super.dispose(); }
        }, new Coord(UI.scale(8), detailY));
        detailY += text.sz().y + UI.scale(8);
    }

    private void rebuildDetails() {
        if (mark == null)
            return;
        for (Widget child : new ArrayList<>(details.cont.children()))
            child.destroy();
        detailY = UI.scale(5);
        DiscoveryCatalog.Row r = selectedRow();
        mark.disable(r == null);
        if (r == null) {
            paragraph(L10n.get("discovery.empty"), UITheme.MUTED);
        } else {
            final DiscoveryCatalog.Row row = r;
            details.cont.add(new Widget(UI.scale(40, 40)) {
                public void draw(GOut g) { ItemIcons.draw(g, ItemIcons.get(row.res, row.name, false), Coord.z, UI.scale(40)); }
            }, new Coord(UI.scale(8), detailY));
            detailY += UI.scale(46);
            paragraph(r.name, UITheme.ACCENT);
            DiscoveryLedger.Entry e = ledger.get(r.name);
            paragraph(statusText(e), color(r));
            if (!r.gobs.isEmpty()) {
                List<String> gobs = new ArrayList<>();
                for (String g : r.gobs) gobs.add(DiscoveryCatalog.gobLabel(g));
                paragraph(L10n.get("discovery.from") + " " + String.join(", ", gobs), UITheme.TEXT);
            }
            if (!r.recipes.isEmpty())
                paragraph(L10n.get("discovery.made_by") + " " + String.join(", ", r.recipes), UITheme.TEXT);
            if (r.others > 0)
                paragraph(String.format(L10n.get("discovery.others"), r.others), UITheme.MUTED);
            if (r.unverified)
                paragraph(L10n.get("discovery.unverified_row"), UITheme.MUTED);
            mark.change(L10n.get(e != null && e.state.done ? "discovery.mark_undiscovered" : "discovery.mark_known"));
        }
        details.cont.update();
        details.bar.val = Math.min(details.bar.val, details.bar.max);
        details.cont.sy = details.bar.val;
    }

    private static String statusText(DiscoveryLedger.Entry e) {
        if (e == null)
            return L10n.get("discovery.st.undiscovered");
        String date = e.at > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(e.at)) : "";
        switch (e.state) {
            case DISCOVERED:
                String s = String.format(L10n.get("discovery.st.discovered"), e.lp, date);
                if (e.shared) s += " " + L10n.get("discovery.st.shared");
                if (e.src != null) s += " · " + DiscoveryCatalog.gobLabel(e.src);
                return s;
            case KNOWN: return L10n.get("discovery.st.known");
            case LEGACY: return L10n.get("discovery.st.legacy");
            case MANUAL: return L10n.get("discovery.st.manual");
            case HELD: return L10n.get("discovery.st.held");
            default: return L10n.get("discovery.st.seen");
        }
    }

    @Override public void tick(double dt) {
        super.tick(dt);
        if (othersLoad.isDone() && others.isEmpty()) {
            Map<String, DiscoveryLedger.Other> loaded = othersLoad.getNow(Collections.emptyMap());
            if (!loaded.isEmpty()) {
                others = loaded;
                rebuildCatalog();
            }
        }
        if (visible && seenVersion != ledger.version()) {
            seenVersion = ledger.version();
            rebuildCatalog();
        }
    }

    @Override public void wdgmsg(Widget sender, String msg, Object... args) {
        if (sender == this && msg.equals("close")) { hide(); return; }
        super.wdgmsg(sender, msg, args);
    }

    @Override public void hide() {
        Utils.setprefc("wndsz-discovery-atlas", csz());
        super.hide();
    }

    @Override public void dispose() {
        Utils.setprefc("wndsz-discovery-atlas", csz());
        rowText.values().forEach(Tex::dispose);
        rowText.clear();
        super.dispose();
    }

    /** A square toggle in the nurgling look: accent frame while on. */
    private abstract static class Chip extends Widget {
        private final Tex text;
        private final Runnable action;

        Chip(String label, Runnable action) {
            super(Coord.z);
            this.text = Text.render(label, UITheme.TEXT).tex();
            this.action = action;
            resize(text.sz().add(UI.scale(12, 6)));
        }

        abstract boolean on();

        public void draw(GOut g) {
            g.chcolor(on() ? UITheme.SELECTED : UITheme.ROW);
            g.frect(Coord.z, sz);
            g.chcolor(on() ? UITheme.ACCENT : UITheme.LINE);
            g.rect(Coord.z, sz);
            g.chcolor();
            g.image(text, UI.scale(6, 3));
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                action.run();
                return true;
            }
            return super.mousedown(ev);
        }

        public void dispose() {
            text.dispose();
            super.dispose();
        }
    }
}
