package nurgling.widgets;

import haven.*;
import nurgling.conf.NCharTags;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for one character's tags and note, opened by right-clicking its box on the
 * character-selection screen. Changes apply immediately - the tag toggles write straight
 * through, the note is flushed on a short debounce and on close - so there is no save/cancel
 * state to get wrong.
 */
public class NCharTagsWnd extends Window {
    private static final int WIDTH = UI.scale(300);
    private static final int ROWH = UI.scale(18);
    private static final int MAXROWS = 10;
    private static final Text.Foundry chipf = new Text.Foundry(Text.sans, 11).aa(true);
    private static final Color chiptxt = new Color(18, 20, 16);
    private static final double FLUSH = 0.4;

    private static NCharTagsWnd instance = null;

    private final String acc, chr;
    private final List<String> cur;
    private final NTextArea note;
    private final Swatches swatch;
    private final TextEntry newtag;
    private double dirty = 0;

    public static void open(UI ui, String acc, String chr) {
        close();
        NCharTagsWnd w = new NCharTagsWnd(acc, chr);
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

    private NCharTagsWnd(String acc, String chr) {
        super(Coord.of(WIDTH, UI.scale(40)), L10n.get("chartag.title", chr));
        this.acc = acc;
        this.chr = chr;
        this.cur = new ArrayList<>(NCharTags.tags(acc, chr));

        Widget prev = add(new Label(L10n.get("chartag.note")), Coord.z);
        note = add(new NTextArea(Coord.of(WIDTH, UI.scale(80)), NCharTags.note(acc, chr)),
                   prev.pos("bl").adds(0, 2));
        note.onchange = () -> dirty = Utils.rtime();
        note.oncommit = this::flush;

        prev = add(new Label(L10n.get("chartag.tags")), note.pos("bl").adds(0, 8));
        List<String> all = NCharTags.alltags();
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

        add(new Button(UI.scale(80), L10n.get("chartag.close")).action(NCharTagsWnd::close),
            Coord.of(WIDTH - UI.scale(80), newtag.c.y + newtag.sz.y + UI.scale(8)));
        pack();
    }

    private void addtag() {
        String t = newtag.text().trim();
        if (t.isEmpty())
            return;
        flush();
        NCharTags.addtag(t, swatch.sel);
        if (!cur.contains(t)) {
            cur.add(t);
            NCharTags.set(acc, chr, cur, note.text());
        }
        /* The row list is built in the constructor, so the cheapest correct way to show a new
         * tag is to rebuild the window. */
        UI ui = this.ui;
        open(ui, acc, chr);
    }

    private void flush() {
        dirty = 0;
        NCharTags.set(acc, chr, cur, note.text());
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
            g.chcolor(NCharTags.color(tag));
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
            return ((c.x >= delx()) ? L10n.get("chartag.deltip") : null);
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
                NCharTags.deltag(tag);
                cur.remove(tag);
                open(ui, acc, chr);
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
