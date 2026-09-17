package nurgling.widgets;

import haven.*;
import nurgling.conf.NCharTags;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for the tags and note on one character or one saved account, opened by right-clicking its
 * row on the character-selection or login screen. What is being edited only shows through a
 * {@link NCharTags.Store}, so the same window serves both - characters and accounts simply carry
 * separate tag palettes.
 * <p>
 * Changes apply immediately - the tag toggles write straight through, the note is flushed on a
 * short debounce and on close - so there is no save/cancel state to get wrong.
 */
public class NTagsWnd extends Window {
    private static final int WIDTH = UI.scale(300);
    private static final int ROWH = UI.scale(18);
    private static final int MAXROWS = 10;
    private static final Text.Foundry chipf = new Text.Foundry(Text.sans, 11).aa(true);
    private static final Color chiptxt = new Color(18, 20, 16);
    private static final double FLUSH = 0.4;

    private static NTagsWnd instance = null;

    private final String title, deltip;
    private final NCharTags.Store store;
    private final List<String> cur;
    private final NTextArea note;
    private final Swatches swatch;
    private final TextEntry newtag;
    private double dirty = 0;

    /** Opens the editor for one character. */
    public static void open(UI ui, String acc, String chr) {
        open(ui, L10n.get("chartag.title", chr), L10n.get("chartag.deltip"), NCharTags.charStore(acc, chr));
    }

    /** Opens the editor for one saved account. */
    public static void openacc(UI ui, String login) {
        open(ui, L10n.get("acctag.title", login), L10n.get("acctag.deltip"), NCharTags.accStore(login));
    }

    public static void open(UI ui, String title, String deltip, NCharTags.Store store) {
        close();
        NTagsWnd w = new NTagsWnd(title, deltip, store);
        instance = w;
        ui.root.add(w, ui.root.sz.div(2).sub(w.sz.div(2)));
        w.raise();
    }

    public static void close() {
        if (instance != null) {
            instance.flush();
            instance.reqdestroy();
            instance = null;
        }
    }

    private NTagsWnd(String title, String deltip, NCharTags.Store store) {
        super(Coord.of(WIDTH, UI.scale(40)), title);
        this.title = title;
        this.deltip = deltip;
        this.store = store;
        this.cur = new ArrayList<>(store.tags());

        Widget prev = add(new Label(L10n.get("chartag.note")), Coord.z);
        note = add(new NTextArea(Coord.of(WIDTH, UI.scale(80)), store.note()),
                   prev.pos("bl").adds(0, 2));
        note.onchange = () -> dirty = Utils.rtime();
        note.oncommit = this::flush;

        prev = add(new Label(L10n.get("chartag.tags")), note.pos("bl").adds(0, 8));
        List<String> all = store.alltags();
        if (all.isEmpty()) {
            prev = add(new Label(L10n.get("chartag.notags")), prev.pos("bl").adds(0, 2));
        } else {
            Pane pane = new Pane(Coord.of(WIDTH, ROWH * Math.min(MAXROWS, all.size())));
            int y = 0;
            for (String t : all) {
                pane.inner.add(new TagRow(t, WIDTH), Coord.of(0, y));
                y += ROWH;
            }
            pane.inner.resize(Coord.of(WIDTH, y));
            prev = add(pane, prev.pos("bl").adds(0, 2));
        }

        prev = add(new Label(L10n.get("chartag.newtag")), prev.pos("bl").adds(0, 8));
        newtag = add(new TextEntry(UI.scale(140), "") {
            public void activate(String text) {
                addtag();
            }
        }, prev.pos("bl").adds(0, 2));
        swatch = add(new Swatches(), newtag.pos("ur").adds(6, 2));
        add(new Button(UI.scale(60), L10n.get("chartag.add")).action(this::addtag),
            swatch.pos("ur").adds(6, -3));

        add(new Button(UI.scale(80), L10n.get("chartag.close")).action(NTagsWnd::close),
            Coord.of(WIDTH - UI.scale(80), newtag.c.y + newtag.sz.y + UI.scale(8)));
        pack();
    }

    /* The row list is built in the constructor, so the cheapest correct way to show the palette
     * having changed is to rebuild the window. */
    private void reopen() {
        /* open() destroys this window, so the UI reference is taken while it is still ours. */
        UI ui = this.ui;
        open(ui, title, deltip, store);
    }

    private void addtag() {
        String t = newtag.text().trim();
        if (t.isEmpty())
            return;
        flush();
        store.addtag(t, swatch.sel);
        if (!cur.contains(t)) {
            cur.add(t);
            store.set(cur, note.text());
        }
        reopen();
    }

    private void flush() {
        dirty = 0;
        store.set(cur, note.text());
    }

    public void tick(double dt) {
        super.tick(dt);
        if ((dirty > 0) && (Utils.rtime() - dirty > FLUSH))
            flush();
    }

    public void destroy() {
        if (dirty > 0)
            flush();
        if (instance == this)
            instance = null;
        super.destroy();
    }

    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close"))
            close();
        else
            super.wdgmsg(msg, args);
    }

    /* -------------------------------------------------------------- tag rows */

    private class TagRow extends Widget {
        private final String tag;
        private final Text.Line label;

        TagRow(String tag, int w) {
            super(Coord.of(w, ROWH));
            this.tag = tag;
            this.label = chipf.render(tag, chiptxt);
        }

        private int delx() {
            return (sz.x - UI.scale(16));
        }

        public void draw(GOut g) {
            Tex box = CheckBox.sbox;
            g.image(box, Coord.of(0, (sz.y - box.sz().y) / 2));
            if (cur.contains(tag))
                g.image(CheckBox.smark, Coord.of(0, (sz.y - CheckBox.smark.sz().y) / 2));
            int cx = box.sz().x + UI.scale(6);
            int cw = label.sz().x + UI.scale(8);
            int ch = label.sz().y + UI.scale(2);
            int cy = (sz.y - ch) / 2;
            g.chcolor(store.color(tag));
            g.frect(Coord.of(cx, cy), Coord.of(cw, ch));
            g.chcolor(Color.BLACK);
            g.rect(Coord.of(cx, cy), Coord.of(cw, ch));
            g.chcolor();
            g.image(label.tex(), Coord.of(cx + UI.scale(4), cy + UI.scale(1)));
            g.chcolor(new Color(190, 120, 120));
            g.atext("x", Coord.of(delx() + UI.scale(4), sz.y / 2), 0.5, 0.5);
            g.chcolor();
        }

        public Object tooltip(Coord c, Widget prev) {
            return ((c.x >= delx()) ? deltip : null);
        }

        public void dispose() {
            label.dispose();
            super.dispose();
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b != 1)
                return (false);
            if (ev.c.x >= delx()) {
                flush();
                store.deltag(tag);
                cur.remove(tag);
                reopen();
                return (true);
            }
            if (cur.contains(tag))
                cur.remove(tag);
            else
                cur.add(tag);
            flush();
            return (true);
        }
    }

    /* --------------------------------------------------------------- palette */

    private static class Swatches extends Widget {
        static final int CELL = UI.scale(14);
        int sel = 1;

        Swatches() {
            super(Coord.of(CELL * NCharTags.tagcol.length, CELL));
        }

        public void draw(GOut g) {
            for (int i = 0; i < NCharTags.tagcol.length; i++) {
                Coord c = Coord.of(i * CELL, 0);
                if (i == sel) {
                    g.chcolor(Color.WHITE);
                    g.frect(c, Coord.of(CELL, CELL));
                }
                g.chcolor(NCharTags.tagcol[i]);
                g.frect(c.add(UI.scale(2), UI.scale(2)), Coord.of(CELL - UI.scale(4), CELL - UI.scale(4)));
                g.chcolor();
            }
        }

        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) {
                int i = ev.c.x / CELL;
                if ((i >= 0) && (i < NCharTags.tagcol.length))
                    sel = i;
                return (true);
            }
            return (false);
        }
    }

    /* ------------------------------------------------------------ scroll pane */

    /** Minimal clipping viewport - child widgets outside the pane are clipped by the normal
     *  {@code Widget.draw} reclip, and never see pointer events because the pane does not. */
    private static class Pane extends Widget {
        final Widget inner;
        int scroll = 0;

        Pane(Coord sz) {
            super(sz);
            inner = add(new Widget(sz), Coord.z);
        }

        private void ckscroll() {
            scroll = Utils.clip(scroll, 0, Math.max(0, inner.sz.y - sz.y));
            inner.c = Coord.of(0, -scroll);
        }

        public boolean mousewheel(MouseWheelEvent ev) {
            scroll += ev.a * ROWH * 2;
            ckscroll();
            return (true);
        }

        public void draw(GOut g) {
            ckscroll();
            super.draw(g);
        }
    }
}
