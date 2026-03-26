package nurgling.widgets;

import haven.*;
import nurgling.NStyle;

public class NPopupWidget extends Widget {

    public static enum Type {
        LEFT,
        TOP,
        BOTTOM,
        RIGHT
    }

    Type type = Type.RIGHT;
    public Coord atl;

    public NPopupWidget(Coord sz, Type t) {
        super(sz);
        int pad = UI.scale(10);
        atl = new Coord(pad, pad);
        type = t;
        visible = false;
    }

    @Override
    public void draw(GOut g) {
        int bw = Math.max(2, UI.scale(2));

        // Background
        g.chcolor(NStyle.infoBg);
        g.frect(Coord.z, sz);
        g.chcolor();

        // Orange border on all edges
        g.chcolor(NStyle.border);
        g.frect(Coord.z, new Coord(sz.x, bw));                         // top
        g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));         // bottom
        g.frect(Coord.z, new Coord(bw, sz.y));                         // left
        g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));         // right
        g.chcolor();

        super.draw(g);
    }

    @Override
    public void resize(Coord sz) {
        super.resize(new Coord(sz.x + atl.x, sz.y + 2 * UI.scale(5) + atl.y / 2));
    }
}
