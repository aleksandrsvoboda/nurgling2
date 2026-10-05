package nurgling.widgets;

import haven.*;
import nurgling.styles.GeneratedButtons;

/** Recipe book artwork with the same plate and dimensions as the adjacent menu toggles. */
public class NAtlasToggle extends ACheckBox {
    private boolean hover;
    public NAtlasToggle(Coord size) { super(size); }
    public void draw(GOut g) {
        GeneratedButtons.plate(g, Coord.z, sz, GeneratedButtons.state(hover, false, state(), false));
        int side = Math.min(sz.x, sz.y) - UI.scale(2);
        GeneratedButtons.icon(g, "craft-atlas", sz.sub(side, side).div(2), side);
        super.draw(g);
    }
    public void mousemove(MouseMoveEvent ev) { hover = ev.c.isect(Coord.z, sz); }
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1 && ev.c.isect(Coord.z, sz)) { click(); return true; }
        return false;
    }
}
