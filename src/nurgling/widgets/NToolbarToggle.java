package nurgling.widgets;

import haven.*;
import nurgling.styles.GeneratedButtons;
import java.util.Locale;

/** Shared ImageGen artwork for inventory and quest toolbar controls. */
public class NToolbarToggle extends ACheckBox {
    public enum Glyph { GROUP, NPC, CREDO, WORLD, SEARCH, SETTINGS }
    private final String glyph;
    private boolean hover;
    protected boolean muted() { return false; }

    public NToolbarToggle(Glyph glyph, String tip) {
        super(UI.scale(21, 21));
        this.glyph = glyph.name().toLowerCase(Locale.ROOT);
        settip(tip);
    }

    @Override public void draw(GOut g) {
        nurgling.styles.UITheme.iconHighlight(g, sz, hover, 0);
        int side = UI.scale(14);
        if(muted()) GeneratedButtons.mutedIcon(g, glyph, sz.sub(side, side).div(2), side);
        else GeneratedButtons.icon(g, glyph, sz.sub(side, side).div(2), side);
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
