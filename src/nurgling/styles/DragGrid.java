package nurgling.styles;

import haven.Coord;
import haven.GOut;
import haven.UI;
import java.awt.Color;

/** Transparent layout guides in the same coordinate system as widget snapping. */
public final class DragGrid {
    private DragGrid() {}

    public static final int STEP = Math.max(1, UI.scale(8));
    public static final int OFFSET = UI.scale(4);
    private static final Color LINE = new Color(210, 215, 218, 55);

    public static Coord snap(Coord position) {
        return position.div(STEP).mul(STEP).sub(OFFSET, OFFSET);
    }

    public static void draw(GOut g, Coord size) {
        if(size.x <= 0 || size.y <= 0) return;
        int phase = Math.floorMod(-OFFSET, STEP);
        int width = Math.max(1, UI.scale(1));
        g.chcolor(LINE);
        for(int x = phase; x < size.x; x += STEP)
            g.frect(new Coord(x, 0), new Coord(Math.min(width, size.x - x), size.y));
        for(int y = phase; y < size.y; y += STEP)
            g.frect(new Coord(0, y), new Coord(size.x, Math.min(width, size.y - y)));
        g.chcolor(UITheme.ACCENT);
        g.frect(new Coord(size.x / 2, 0), new Coord(width, size.y));
        g.frect(new Coord(0, size.y / 2), new Coord(size.x, width));
        g.chcolor();
    }
}
