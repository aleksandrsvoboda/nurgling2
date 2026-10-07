package nurgling.widgets;

import haven.*;
import nurgling.styles.GeneratedButtons;
import nurgling.styles.MenuIcons;
import nurgling.styles.UITheme;

/** Recipe book artwork with the same plate and dimensions as the adjacent menu toggles. */
public class NAtlasToggle extends ACheckBox {
    private boolean hover;
    private static Tex classic;
    public NAtlasToggle(Coord size) { super(size); }
    public void draw(GOut g) {
        if(UITheme.on()) {
            GeneratedButtons.plate(g, Coord.z, sz, GeneratedButtons.state(hover, false, state(), false));
            int side = Math.min(sz.x, sz.y) - UI.scale(2);
            GeneratedButtons.icon(g, "craft-atlas", sz.sub(side, side).div(2), side);
        } else {
            // The classic menu buttons have no atlas art: borrow a neighbour's frame and matte.
            if(classic == null)
                classic = new TexI(MenuIcons.emptyPlate(Resource.loadsimg("nurgling/hud/buttons/rbtn/opt/u")));
            g.image(classic, Coord.z, sz);
            int side = Math.min(sz.x, sz.y) * 5 / 8;
            GeneratedButtons.icon(g, "craft-atlas", sz.sub(side, side).div(2), side);
            if(state() || hover) {
                g.chcolor(state() ? 0 : 255, state() ? 0 : 255, state() ? 0 : 255, state() ? 70 : 30);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
        }
        super.draw(g);
    }
    public void mousemove(MouseMoveEvent ev) { hover = ev.c.isect(Coord.z, sz); }
    public boolean mousedown(MouseDownEvent ev) {
        if(ev.b == 1 && ev.c.isect(Coord.z, sz)) { click(); return true; }
        return false;
    }
}
