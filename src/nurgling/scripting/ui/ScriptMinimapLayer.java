package nurgling.scripting.ui;

import haven.*;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One run's minimap markers: a diamond with a label, drawn on every minimap of the run's session
 * (the corner map and the Map window). Registered as an {@link NMiniMap.Layer} while it has
 * markers; never saved.
 */
public class ScriptMinimapLayer implements NMiniMap.Layer {
    private static final Text.Foundry FND = new Text.Foundry(Text.sans, 10).aa(true);

    private static class Mark {
        final Coord2d pos;
        final String text;
        final Color color;
        Tex tex;

        Mark(Coord2d pos, String text, Color color) {
            this.pos = pos;
            this.text = text;
            this.color = color;
        }
    }

    private final UI ui;
    private final Map<Integer, Mark> marks = new ConcurrentHashMap<>();

    public ScriptMinimapLayer(UI ui) {
        this.ui = ui;
    }

    public void put(int id, Coord2d pos, String text, Color color) {
        marks.put(id, new Mark(pos, text, color));
    }

    public void remove(int id) {
        Mark m = marks.remove(id);
        if (m != null && m.tex != null) m.tex.dispose();
    }

    public boolean isEmpty() {
        return marks.isEmpty();
    }

    @Override
    public void draw(NMiniMap map, GOut g) {
        if (map.ui != ui || map.sessloc == null || map.dloc == null || map.sessloc.seg != map.dloc.seg) return;
        int r = UI.scale(5);
        for (Mark m : marks.values()) {
            Coord c;
            try {
                c = map.p2c(m.pos);
            } catch (Loading l) {
                continue;
            }
            if (!c.isect(Coord.z.sub(r, r), map.sz.add(r * 2, r * 2))) continue;
            // Dark casing first so the marker reads on both snow and forest.
            g.chcolor(0, 0, 0, 200);
            diamond(g, c, r + 1);
            g.chcolor(m.color);
            diamond(g, c, r);
            g.chcolor();
            if (m.text != null && !m.text.isEmpty()) {
                if (m.tex == null) m.tex = Text.renderstroked(m.text, Color.WHITE, Color.BLACK, FND).tex();
                g.aimage(m.tex, c.add(0, -r - UI.scale(2)), 0.5, 1.0);
            }
        }
    }

    private static void diamond(GOut g, Coord c, int r) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.frect(Coord.of(c.x - w, c.y + dy), Coord.of(w * 2 + 1, 1));
        }
    }
}
