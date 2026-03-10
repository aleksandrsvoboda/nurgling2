package nurgling;

import haven.*;
import java.util.HashMap;

public class NAttr {
    private static final HashMap<Integer, Text> baseCache = new HashMap<>();

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
        Tex ctx = ct.tex();
        g.aimage(ctx, cn.add(sz.x - UI.scale(7), 1), 1, 0.5);
        if(base != comp) {
            Text bt = baseCache.computeIfAbsent(base,
                v -> CharWnd.attrf.render(Integer.toString(v)));
            g.aimage(bt.tex(), cn.add(sz.x - UI.scale(7) - ctx.sz().x - UI.scale(5), 1), 1, 0.5);
        }
    }
}
