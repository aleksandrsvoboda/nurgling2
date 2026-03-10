package nurgling;

import haven.*;
import java.util.HashMap;

public class NAttr {
    private static final HashMap<Integer, Text> baseCache = new HashMap<>();
    private static int numColW = -1;

    private static int numColW() {
        if(numColW < 0)
            numColW = CharWnd.attrf.render("0000").sz().x;
        return numColW;
    }

    public static void drawBorder(GOut g, Coord sz) {
        int bw = Math.max(1, UI.scale(1));
        g.chcolor(233, 156, 84, 255);  // #E99C54
        g.frect(Coord.z, new Coord(sz.x, bw));
        g.frect(new Coord(0, sz.y - bw), new Coord(sz.x, bw));
        g.frect(Coord.z, new Coord(bw, sz.y));
        g.frect(new Coord(sz.x - bw, 0), new Coord(bw, sz.y));
        g.chcolor();
    }

    public static void drawValue(GOut g, Coord cn, Coord sz, Text ct, int base, int comp) {
        int colW = numColW();
        int rightEdge = sz.x - UI.scale(7);
        if(base != comp) {
            // Bonus: comp (colored) right-aligned in right column
            g.aimage(ct.tex(), cn.add(rightEdge, 1), 1, 0.5);
            // Base (white) right-aligned in left column
            Text bt = baseCache.computeIfAbsent(base,
                v -> CharWnd.attrf.render(Integer.toString(v)));
            g.aimage(bt.tex(), cn.add(rightEdge - colW - UI.scale(5), 1), 1, 0.5);
        } else {
            // No bonus: white value right-aligned in left column
            g.aimage(ct.tex(), cn.add(rightEdge - colW - UI.scale(5), 1), 1, 0.5);
        }
    }
}
