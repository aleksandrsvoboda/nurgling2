package nurgling.widgets;

import haven.*;

import java.awt.Color;
import java.awt.event.KeyEvent;

import static haven.KeyMatch.C;
import static haven.KeyMatch.S;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-line text editor. Haven only ships the single-line {@link TextEntry}, so notes that
 * are meant to hold a few sentences need this.
 * <p>
 * The editing buffer is haven's own {@link ReadLine.PCLine} - it already implements selection,
 * word-wise motion, backspace/delete and clipboard against a flat char[]. {@link NReadArea}
 * only overrides the keys whose meaning changes once newlines are legal (enter, up/down,
 * home/end, page up/down, paste). Everything else - wrapping, caret placement, hit testing,
 * scrolling - lives in the widget.
 */
public class NTextArea extends Widget implements ReadLine.Owner {
    public static final Text.Foundry fnd = new Text.Foundry(Text.sans, 12).aa(true);
    public static final Color defcol = new Color(255, 205, 109);
    public static final Color selcol = new Color(24, 80, 192);
    public static final Color bgcol = new Color(22, 27, 29, 255);
    public static final Color brdcol = new Color(96, 100, 96);
    public static final int pad = UI.scale(3);
    public static final Tex caret = Resource.loadtex("nurgling/hud/text/caret");

    public final NReadArea buf;
    private final int lineh;
    private final List<Seg> lines = new ArrayList<>();
    private int lseq = -1, lwidth = -1;
    private int scroll = 0;
    private double focusstart = 0;
    private UI.Grab drag = null;
    /** Fired on every buffer change. */
    public Runnable onchange = null;
    /** Fired when the widget loses focus - the natural point to flush to disk. */
    public Runnable oncommit = null;

    public NTextArea(Coord sz, String text) {
        super(sz);
        this.lineh = fnd.height();
        this.buf = new NReadArea(this, text == null ? "" : text);
        setcanfocus(true);
    }

    /* ------------------------------------------------------------------ text */

    public String text() {
        return (buf.line());
    }

    public void settext(String text) {
        buf.setline(text == null ? "" : text);
        scroll = 0;
        relayout();
    }

    /* --------------------------------------------------------------- wrapping */

    private static class Seg {
        final int start, end;
        Text.Line rend = null;

        Seg(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private int textw() {
        return (Math.max(sz.x - (pad * 2), 1));
    }

    private int width(int s, int e) {
        return (fnd.m.charsWidth(buf.buf, s, e - s));
    }

    /** Largest index in (s, end] whose rendered width still fits, backed off to a word break. */
    private int fit(int s, int end, int tw) {
        if (s >= end)
            return (end);
        if (width(s, end) <= tw)
            return (end);
        int lo = s + 1, hi = end;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s, mid) <= tw)
                lo = mid;
            else
                hi = mid - 1;
        }
        int e = Math.max(lo, s + 1);
        int b = e;
        while ((b > s) && (buf.buf[b - 1] != ' '))
            b--;
        return ((b > s) ? b : e);
    }

    private void relayout() {
        /* Each relayout re-renders every visual line, so the previous ones have to go or the
         * widget leaks a texture per keystroke. */
        for (Seg g : lines) {
            if (g.rend != null)
                g.rend.dispose();
        }
        lines.clear();
        int tw = textw();
        int len = buf.length;
        int p = 0;
        while (true) {
            int nl = p;
            while ((nl < len) && (buf.buf[nl] != '\n'))
                nl++;
            int s = p;
            while (true) {
                int e = fit(s, nl, tw);
                lines.add(new Seg(s, e));
                s = e;
                if (s >= nl)
                    break;
            }
            if (nl >= len)
                break;
            p = nl + 1;
            if (p >= len) {
                lines.add(new Seg(p, p));
                break;
            }
        }
        lseq = buf.seq;
        lwidth = sz.x;
    }

    private void cklayout() {
        if ((lseq != buf.seq) || (lwidth != sz.x))
            relayout();
    }

    private Text.Line rend(Seg g) {
        if (g.rend == null)
            g.rend = fnd.render(new String(buf.buf, g.start, g.end - g.start), defcol);
        return (g.rend);
    }

    /* ------------------------------------------------------------ caret logic */

    private int lineat(int point) {
        int ret = 0;
        for (int i = 0; i < lines.size(); i++) {
            Seg g = lines.get(i);
            if ((point >= g.start) && (point <= g.end)) {
                ret = i;
                if (point < g.end)
                    break;
            }
        }
        return (ret);
    }

    int pagelines() {
        return (Math.max(1, (sz.y - (pad * 2)) / lineh));
    }

    void movevert(int d, boolean select) {
        cksel(select);
        int li = lineat(buf.point);
        Seg cur = lines.get(li);
        int col = width(cur.start, buf.point);
        int ni = Utils.clip(li + d, 0, lines.size() - 1);
        if (ni == li) {
            buf.point = (d < 0) ? 0 : buf.length;
            return;
        }
        Seg tgt = lines.get(ni);
        int off = rend(tgt).charat(col);
        buf.point = Utils.clip(tgt.start + off, tgt.start, tgt.end);
    }

    void movehome(boolean end, boolean select) {
        cksel(select);
        Seg g = lines.get(lineat(buf.point));
        buf.point = end ? g.end : g.start;
    }

    /** Layout check plus the shift-selection bookkeeping the motion keys share. */
    private void cksel(boolean select) {
        cklayout();
        if (select) {
            if (buf.mark < 0)
                buf.mark = buf.point;
        } else {
            buf.mark = -1;
        }
    }

    private void scrolltocaret() {
        cklayout();
        int li = lineat(buf.point);
        int top = li * lineh, bot = top + lineh;
        int vh = sz.y - (pad * 2);
        if (top < scroll)
            scroll = top;
        if (bot > scroll + vh)
            scroll = bot - vh;
        ckscroll();
    }

    private void ckscroll() {
        int max = Math.max(0, (lines.size() * lineh) - (sz.y - (pad * 2)));
        scroll = Utils.clip(scroll, 0, max);
    }

    /* -------------------------------------------------------------- rendering */

    public void draw(GOut g) {
        cklayout();
        ckscroll();
        g.chcolor(bgcol);
        g.frect(Coord.z, sz);
        g.chcolor(brdcol);
        g.rect(Coord.z, sz);
        g.chcolor();

        int point = buf.point(), mark = buf.mark();
        int sela = Math.min(point, mark), selb = Math.max(point, mark);
        int vh = sz.y - (pad * 2);
        int first = Math.max(0, scroll / lineh);
        int last = Math.min(lines.size() - 1, (scroll + vh) / lineh);
        for (int i = first; i <= last; i++) {
            Seg s = lines.get(i);
            int y = pad + (i * lineh) - scroll;
            Text.Line ln = rend(s);
            if ((mark >= 0) && (selb > s.start) && (sela <= s.end)) {
                int a = Utils.clip(sela, s.start, s.end) - s.start;
                int b = Utils.clip(selb, s.start, s.end) - s.start;
                if (b > a) {
                    g.chcolor(selcol);
                    g.frect2(Coord.of(pad + ln.advance(a), y), Coord.of(pad + ln.advance(b), y + lineh));
                    g.chcolor();
                }
            }
            g.image(ln.tex(), Coord.of(pad, y));
        }
        if (hasfocus) {
            int li = lineat(point);
            Seg s = lines.get(li);
            int cx = pad + rend(s).advance(point - s.start);
            int cy = pad + (li * lineh) - scroll;
            if (((Utils.rtime() - Math.max(focusstart, buf.mtime())) % 1.0) < 0.5)
                g.image(caret, Coord.of(cx - UI.scale(2), cy));
        }
        int total = lines.size() * lineh;
        if (total > vh) {
            int bw = UI.scale(3);
            int bh = Math.max(UI.scale(8), (vh * vh) / total);
            int by = pad + ((vh - bh) * scroll) / Math.max(1, total - vh);
            g.chcolor(brdcol);
            g.frect(Coord.of(sz.x - bw - 1, by), Coord.of(bw, bh));
            g.chcolor();
        }
    }

    /* ------------------------------------------------------------------ input */

    private int pointat(Coord c) {
        cklayout();
        if (lines.isEmpty())
            return (0);
        int li = Utils.clip((c.y - pad + scroll) / lineh, 0, lines.size() - 1);
        Seg s = lines.get(li);
        int off = rend(s).charat(c.x - pad);
        return (Utils.clip(s.start + off, s.start, s.end));
    }

    public boolean mousedown(MouseDownEvent ev) {
        parent.setfocus(this);
        if (ev.b == 1) {
            buf.point = pointat(ev.c);
            buf.mark = -1;
            drag = ui.grabmouse(this);
        }
        return (true);
    }

    public void mousemove(MouseMoveEvent ev) {
        if (drag != null) {
            if (buf.mark < 0)
                buf.mark = buf.point;
            buf.point = pointat(ev.c);
        }
    }

    public boolean mouseup(MouseUpEvent ev) {
        if ((ev.b == 1) && (drag != null)) {
            drag.remove();
            drag = null;
            return (true);
        }
        return (false);
    }

    public boolean mousewheel(MouseWheelEvent ev) {
        scroll += ev.a * lineh * 3;
        ckscroll();
        return (true);
    }

    public boolean keydown(KeyDownEvent ev) {
        cklayout();
        if (buf.key(ev.awt)) {
            scrolltocaret();
            return (true);
        }
        return (false);
    }

    public void gotfocus() {
        focusstart = Utils.rtime();
    }

    public void lostfocus() {
        buf.mark = -1;
        if (oncommit != null)
            oncommit.run();
    }

    public void resize(Coord sz) {
        super.resize(sz);
        relayout();
    }

    public void dispose() {
        for (Seg g : lines) {
            if (g.rend != null)
                g.rend.dispose();
        }
        lines.clear();
        super.dispose();
    }

    /* ------------------------------------------------------- ReadLine.Owner */

    public UI ui() {
        return (ui);
    }

    public void changed(ReadLine buf) {
        relayout();
        if (onchange != null)
            onchange.run();
    }

    public void done(ReadLine buf) {
    }

    /**
     * Editing buffer for {@link NTextArea}. Everything not overridden here comes straight
     * from haven's PCLine.
     */
    public static class NReadArea extends ReadLine.PCLine {
        private final NTextArea wdg;

        public NReadArea(NTextArea wdg, String init) {
            super(wdg, init);
            this.wdg = wdg;
        }

        public boolean key2(char c, KeyEvent ev, int mod) {
            int code = ev.getKeyCode();
            boolean sel = (mod & S) != 0;
            int m = mod & ~S;
            if (Widget.key_act.match(ev)) {
                /* Vanilla submits on enter; here it is just another character. */
                rmsel();
                ensure(point, 1)[point++] = '\n';
                return (true);
            } else if ((code == KeyEvent.VK_UP) && (m == 0)) {
                wdg.movevert(-1, sel);
                return (true);
            } else if ((code == KeyEvent.VK_DOWN) && (m == 0)) {
                wdg.movevert(1, sel);
                return (true);
            } else if ((code == KeyEvent.VK_PAGE_UP) && (m == 0)) {
                wdg.movevert(-wdg.pagelines(), sel);
                return (true);
            } else if ((code == KeyEvent.VK_PAGE_DOWN) && (m == 0)) {
                wdg.movevert(wdg.pagelines(), sel);
                return (true);
            } else if ((code == KeyEvent.VK_HOME) && (m == 0)) {
                wdg.movehome(false, sel);
                return (true);
            } else if ((code == KeyEvent.VK_END) && (m == 0)) {
                wdg.movehome(true, sel);
                return (true);
            } else if ((c == 'v') && (m == C)) {
                /* PCLine's own paste stops at the first character below 32, which drops
                 * everything after the first newline. */
                cliptext().map(this::mlpaste).report(wdg.ui, "Clipboard error");
                return (true);
            }
            return (super.key2(c, ev, mod));
        }

        private void mlpaste(CharSequence text) {
            if (text == null)
                return;
            synchronized (wdg.ui) {
                rmsel();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\r')
                        continue;
                    if ((c >= 32) || (c == '\n'))
                        sb.append(c);
                }
                char[] dst = ensure(point, sb.length());
                for (int i = 0; i < sb.length(); i++)
                    dst[point++] = sb.charAt(i);
                owner.changed(this);
            }
        }
    }
}
