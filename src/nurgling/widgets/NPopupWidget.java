package nurgling.widgets;

import haven.*;
import java.awt.Color;

public class NPopupWidget extends Widget {
    private static final Color BG = new Color(0x1C, 0x25, 0x26);
    private static final Color BORDER = new Color(233, 156, 84, 255); // #E99C54

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
        g.chcolor(BG);
        g.frect(Coord.z, sz);
        g.chcolor();

        // Orange border — skip the edge that connects to the parent
        g.chcolor(BORDER);
        if(type != Type.TOP)
            g.frect(Coord.z, new Coord(sz.x, bw));                         // top
        if(type != Type.BOTTOM)
            g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));         // bottom
        if(type != Type.LEFT)
            g.frect(Coord.z, new Coord(bw, sz.y));                         // left
        if(type != Type.RIGHT)
            g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));         // right
        g.chcolor();

        super.draw(g);
    }

    @Override
    public void resize(Coord sz) {
        super.resize(new Coord(sz.x + atl.x, sz.y + 2 * UI.scale(5) + atl.y / 2));
    }
}
