package nurgling.widgets.cookbook;

import haven.*;

/**
 * An outlined toolbar button: a label, an optional summary in the accent colour (a filter's
 * count, the spices applied) and optionally a drop-down caret. The border turns orange on hover
 * and while its popover is open.
 */
public class PillButton extends Widget {
    private static final int PAD = UI.scale(8);
    private static final int CARET = UI.scale(7);
    private static final int H = UI.scale(24);

    private final Tex label;
    private final boolean caret;
    private final Runnable action;
    private Tex suffix = null;
    private String suffixText = "";
    private int minWidth = 0;
    private boolean hover = false;
    /** Whether this button's popover is open. */
    public boolean expanded = false;

    public PillButton(String label, boolean caret, Runnable action) {
        super(Coord.z);
        this.label = CookbookTheme.render(CookbookTheme.bold, label, CookbookTheme.fg);
        this.caret = caret;
        this.action = action;
        relayout();
    }

    /** Sets the accent-coloured summary after the label; empty hides it. */
    public void suffix(String text) {
        String t = (text == null) ? "" : text;
        if(t.equals(suffixText))
            return;
        suffixText = t;
        suffix = t.isEmpty() ? null : CookbookTheme.render(CookbookTheme.bold, t, CookbookTheme.accent);
        relayout();
    }

    /** Makes the button at least this wide, content centred. */
    public PillButton minWidth(int w) {
        minWidth = w;
        relayout();
        return this;
    }

    private int contentWidth() {
        int w = label.sz().x;
        if(suffix != null)
            w += CookbookTheme.GAP + suffix.sz().x;
        if(caret)
            w += CookbookTheme.GAP + CARET;
        return w;
    }

    private void relayout() {
        resize(Coord.of(Math.max(contentWidth() + 2 * PAD, minWidth), H));
    }

    @Override
    public void draw(GOut g) {
        if(hover)
            CookbookTheme.fill(g, Coord.z, sz, CookbookTheme.hover);
        CookbookTheme.frame(g, Coord.z, sz, (hover || expanded) ? CookbookTheme.accent : CookbookTheme.outline);
        int x = (sz.x - contentWidth()) / 2;
        g.image(label, Coord.of(x, (sz.y - label.sz().y) / 2));
        x += label.sz().x;
        if(suffix != null) {
            x += CookbookTheme.GAP;
            g.image(suffix, Coord.of(x, (sz.y - suffix.sz().y) / 2));
            x += suffix.sz().x;
        }
        if(caret)
            CookbookTheme.triangle(g, Coord.of(x + CookbookTheme.GAP + CARET / 2, sz.y / 2), true, CookbookTheme.muted);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1) {
            action.run();
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
