package nurgling.sessions.commander;

import haven.*;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.sessions.SessionManager;
import nurgling.widgets.NDraggableWidget;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A piece of the Commander screen: drawn only in Commander mode on the session on screen (and in drag mode, so it can
 * be placed), in the squad panel's look. It sizes itself to its content; clickable spots are rebuilt every frame as
 * {@link Cell}s, so a panel is just a draw method.
 */
abstract class HudPanel extends Widget {
    static final Color PANEL = new Color(0x25, 0x2B, 0x29, 0xE5);
    static final Color FRAME = new Color(0x91, 0x60, 0x2E);
    static final Color TEXT = new Color(220, 220, 220);
    static final Color DIM = new Color(150, 150, 150);
    static final Color ORANGE = CommanderView.ORANGE;
    static final Color TEAL = CommanderView.TEAL;
    static final Color RED = new Color(0xD0, 0x44, 0x3C);
    static final Color HP = new Color(0xB0, 0x3A, 0x3A);
    static final Color STAM = new Color(0x4A, 0x9C, 0xD6);
    static final Color NRJ = new Color(0xC8, 0xA8, 0x3C);
    static final Color ON_TEXT = new Color(0x25, 0x2B, 0x29);
    static final int PAD = UI.scale(5);
    private static final long DOUBLE_MS = 400;

    private static Text.Foundry font, bold;
    private final Map<String, Tex> texts = new HashMap<>();
    protected final List<Cell> cells = new ArrayList<>();
    private Object lastKey;
    private long lastAt;

    /** A clickable spot: {@code click} gets the press and whether it is the second of a double click. */
    static final class Cell {
        final Area a;
        final Object key;
        final Clicker click;
        final String tip;

        Cell(Area a, Object key, Clicker click, String tip) {
            this.a = a;
            this.key = key;
            this.click = click;
            this.tip = tip;
        }
    }

    interface Clicker {
        void click(MouseDownEvent ev, boolean dbl);
    }

    HudPanel(Coord sz) {
        super(sz);
    }

    static Commander cmd() {
        return SessionManager.getInstance().commander();
    }

    NGameUI gui() {
        return (ui == null) ? null : ((NUI) ui).gui;
    }

    boolean dragMode() {
        return ui != null && ui.core != null && ui.core.mode == NCore.Mode.DRAG;
    }

    boolean shown() {
        if (ui == null || gui() == null)
            return false;
        if (dragMode())
            return cmd().active();
        /* Its session being drawn (not in the background) is what counts: "active" lags a switch by a frame. */
        nurgling.sessions.SessionContext me = SessionManager.getInstance().findByUI(ui);
        return cmd().active() && me != null && (!me.isHeadless() || CommanderPrime.priming) && visibleNow();
    }

    /** Whether the panel has anything to show right now (in Commander mode). */
    boolean visibleNow() {
        return true;
    }

    Tex text(String s, Color col) {
        return text(s, col, false);
    }

    Tex text(String s, Color col, boolean b) {
        String k = (b ? "b" : "p") + col.getRGB() + s;
        Tex t = texts.get(k);
        if (t == null) {
            if (font == null) {
                font = new Text.Foundry(Text.sans.deriveFont(Font.PLAIN), 11).aa(true);
                bold = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 11).aa(true);
            }
            if (texts.size() > 300)
                texts.clear();
            t = (b ? bold : font).render(s, col).tex();
            texts.put(k, t);
        }
        return t;
    }

    /** Follow the content's size; the draggable wrapper is sized around it. */
    void fit(Coord want) {
        if (sz.equals(want))
            return;
        if (parent instanceof NDraggableWidget)
            parent.resize(want.add(NDraggableWidget.delta));
        else
            resize(want);
    }

    void background(GOut g) {
        g.chcolor(PANEL);
        g.frect(Coord.z, sz);
        g.chcolor(FRAME);
        g.rect(Coord.z, sz);
        g.chcolor();
    }

    static void bar(GOut g, Coord ul, int w, int h, double frac, Color c) {
        g.chcolor(0, 0, 0, 160);
        g.frect(ul, new Coord(w, h));
        if (frac >= 0) {
            g.chcolor(c);
            g.frect(ul, new Coord((int) Math.round(w * Math.max(0, Math.min(1, frac))), h));
        }
        g.chcolor();
    }

    /** A square button in the squad panel's style: filled when on, double outline when only partly on. */
    void button(GOut g, Coord ul, Coord bsz, String label, String hotkey, int state, Object key, Runnable act, String tip) {
        boolean on = state == 2;
        g.chcolor(on ? ORANGE : PANEL);
        g.frect(ul, bsz);
        g.chcolor(state == 0 ? FRAME : ORANGE);
        g.rect(ul, bsz);
        if (state == 1)
            g.rect(ul.add(1, 1), bsz.sub(2, 2));
        g.chcolor();
        /* The hotkey sits at the right end; the label is centred in what is left. */
        int room = bsz.x;
        if (hotkey != null) {
            Tex hk = text(hotkey, on ? ON_TEXT : DIM);
            room -= hk.sz().x + UI.scale(4);
            g.aimage(hk, ul.add(bsz.x - UI.scale(3), bsz.y / 2), 1, 0.5);
        }
        g.aimage(text(label, on ? ON_TEXT : TEXT), ul.add(room / 2, bsz.y / 2), 0.5, 0.5);
        cells.add(new Cell(Area.sized(ul, bsz), key, (ev, dbl) -> act.run(), tip));
    }

    static String clip(String s, int n) {
        return Theater.clip(s, n);
    }

    abstract void render(GOut g);

    @Override
    public void draw(GOut g) {
        cells.clear();
        if (!shown())
            return;
        render(g);
        super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!shown())
            return false;
        if (ev.propagate(this))
            return true;
        for (Cell cl : cells) {
            if (!cl.a.contains(ev.c))
                continue;
            long now = System.currentTimeMillis();
            boolean dbl = ev.b == 1 && cl.key != null && cl.key.equals(lastKey) && now - lastAt < DOUBLE_MS;
            if (ev.b == 1) {
                lastKey = dbl ? null : cl.key;
                lastAt = now;
            }
            cl.click.click(ev, dbl);
            return true;
        }
        return true;
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        for (Cell cl : cells)
            if (cl.tip != null && cl.a.contains(c))
                return cl.tip;
        return super.tooltip(c, prev);
    }

    @Override
    public boolean checkhit(Coord c) {
        return shown() && super.checkhit(c);
    }

    /** Wrap a panel for a game UI and place it on first use. */
    static NDraggableWidget install(NGameUI gui, HudPanel p, String name, Consumer<NDraggableWidget> place) {
        boolean placed = nurgling.conf.NDragProp.get(name).c != Coord.z;
        NDraggableWidget w = gui.add(new NDraggableWidget(p, name, p.sz.add(NDraggableWidget.delta)));
        if (!placed)
            place.accept(w);
        /* Outside Commander mode the panel is not there at all (CommanderScreen shows it when the mode starts). */
        if (!gui.commanderScreen.on())
            w.hide();
        return w;
    }
}
