package nurgling.widgets;

import haven.*;
import nurgling.styles.GeneratedButtons;
import java.util.Locale;

/** New UI icon toggle for compact toolbars (the quest tracker): a flat glyph with a
 * square hover highlight, clickable across its whole square. */
public class NToolbarToggle extends ACheckBox {
    public enum Glyph { GROUP, NPC, CREDO, WORLD, SEARCH, SETTINGS }
    public static final Coord SIZE = UI.scale(21, 21);
    private final String glyph;
    private boolean hover;

    public NToolbarToggle(Glyph glyph, String tip) {
        super(SIZE);
        this.glyph = glyph.name().toLowerCase(Locale.ROOT);
        settip(tip);
    }

    /** Draws a toolbar glyph centred in a button of the given size. */
    public static void drawGlyph(GOut g, Coord sz, String glyph, boolean hover, boolean muted) {
        nurgling.styles.UITheme.iconHighlight(g, sz, hover, 0);
        int side = UI.scale(14);
        if(muted) GeneratedButtons.mutedIcon(g, glyph, sz.sub(side, side).div(2), side);
        else GeneratedButtons.icon(g, glyph, sz.sub(side, side).div(2), side);
    }

    @Override public void draw(GOut g) {
        drawGlyph(g, sz, glyph, hover, false);
        super.draw(g);
    }

    @Override public void mousemove(MouseMoveEvent ev) {
        hover = ev.c.isect(Coord.z, sz);
        super.mousemove(ev);
    }

    @Override public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1 && ev.c.isect(Coord.z, sz)) { click(); return true; }
        return super.mousedown(ev);
    }
}
