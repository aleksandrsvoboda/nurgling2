package nurgling.widgets.todo;

import haven.*;
import nurgling.widgets.cookbook.CookbookTheme;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A small pop-up list of choices: the right-click menus and the drop-downs of the To-Do window. It grabs
 * the mouse while open, so a click anywhere else just closes it.
 */
public class TodoMenu extends Widget {
    private static final int PADX = UI.scale(8);
    private static final int ROWH = UI.scale(18);
    private static final int MAXROWS = 14;

    private final List<String> options;
    private final IntConsumer onPick;
    private final Tex[] labels;
    private final int[] enabledMask;
    private int hover = -1;
    private int scroll = 0;
    private UI.Grab grab = null;
    private boolean closed = false;

    /** {@code disabled} options are drawn muted and cannot be picked; pass null for none. */
    public TodoMenu(List<String> options, boolean[] disabled, IntConsumer onPick) {
        super(Coord.z);
        this.options = options;
        this.onPick = onPick;
        this.labels = new Tex[options.size()];
        this.enabledMask = new int[options.size()];
        int w = UI.scale(90);
        for (int i = 0; i < options.size(); i++) {
            boolean off = disabled != null && i < disabled.length && disabled[i];
            enabledMask[i] = off ? 0 : 1;
            labels[i] = CookbookTheme.render(CookbookTheme.body, options.get(i), off ? CookbookTheme.muted : CookbookTheme.fg);
            w = Math.max(w, labels[i].sz().x + 2 * PADX);
        }
        resize(Coord.of(w, Math.min(options.size(), MAXROWS) * ROWH + 2));
    }

    @Override
    protected void added() {
        super.added();
        grab = ui.grabmouse(this);
        raise();
    }

    public void close() {
        if (closed)
            return;
        closed = true;
        if (grab != null) {
            grab.remove();
            grab = null;
        }
        destroy();
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.popBg);
        int rows = Math.min(options.size(), MAXROWS);
        for (int r = 0; r < rows; r++) {
            int i = r + scroll;
            int y = 1 + r * ROWH;
            if (i == hover && enabledMask[i] == 1)
                CookbookTheme.fill(g, Coord.of(1, y), Coord.of(sz.x - 2, ROWH), CookbookTheme.hover);
            g.image(labels[i], Coord.of(PADX, y + (ROWH - labels[i].sz().y) / 2));
        }
        CookbookTheme.frame(g, Coord.z, sz, CookbookTheme.accent);
    }

    private int rowAt(Coord c) {
        if (c.x < 0 || c.y < 1 || c.x >= sz.x || c.y >= sz.y - 1)
            return -1;
        int i = (c.y - 1) / ROWH + scroll;
        return i < options.size() ? i : -1;
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hover = rowAt(ev.c);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        int i = rowAt(ev.c);
        if (i < 0) {
            close();
            return true;
        }
        if (ev.b == 1 && enabledMask[i] == 1) {
            close();
            onPick.accept(i);
        }
        return true;
    }

    @Override
    public boolean mousewheel(MouseWheelEvent ev) {
        scroll = Utils.clip(scroll + ev.a, 0, Math.max(0, options.size() - MAXROWS));
        return true;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (key_esc.match(ev)) {
            close();
            return true;
        }
        return super.keydown(ev);
    }
}
