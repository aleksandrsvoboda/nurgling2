package nurgling.widgets.todo;

import haven.*;
import nurgling.widgets.cookbook.CookbookTheme;

/** A drop-down field: shows the current value with a caret, and asks the window to open a menu on click. */
public class TodoChoice extends Widget {
    private static final int PAD = UI.scale(5);
    private final Runnable onOpen;
    private String value = "";
    private Tex tex = null;
    private boolean hover = false;
    public boolean enabled = true;

    public TodoChoice(int w, Runnable onOpen) {
        super(Coord.of(w, UI.scale(19)));
        this.onOpen = onOpen;
    }

    public void set(String v) {
        String s = v == null ? "" : v;
        if (s.equals(value) && tex != null)
            return;
        value = s;
        tex = CookbookTheme.render(CookbookTheme.body,
            CookbookTheme.ellipsize(CookbookTheme.body, s, sz.x - 2 * PAD - UI.scale(10)), CookbookTheme.fg);
    }

    @Override
    public void draw(GOut g) {
        CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.bg);
        CookbookTheme.frame(g, Coord.z, sz, (hover && enabled) ? CookbookTheme.accent : CookbookTheme.outline);
        if (tex != null)
            g.image(tex, Coord.of(PAD, (sz.y - tex.sz().y) / 2));
        if (enabled)
            CookbookTheme.triangle(g, Coord.of(sz.x - PAD - UI.scale(3), sz.y / 2), true, CookbookTheme.accent);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 1 && enabled) {
            onOpen.run();
            return true;
        }
        return super.mousedown(ev);
    }

    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        hover = hovering;
        return hovering;
    }
}
