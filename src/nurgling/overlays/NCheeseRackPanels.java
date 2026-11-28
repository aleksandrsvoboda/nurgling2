package nurgling.overlays;

import haven.Coord;
import haven.GOut;
import haven.Tex;
import haven.TexI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lays out the cheese rack panels of one map view so they never cover each other. Racks stand a
 * tile apart in rows, so a fixed zoom threshold can't do it: each frame the racks hand in their
 * screen points, and after the 3D pass every rack in view is drawn in the richest style that fits
 * for all of them: full panel, then a column of tier chips, then three small squares.
 * Going back to a richer style needs a few pixels of clearance, so panning doesn't flicker.
 */
public class NCheeseRackPanels {
    public static final int FULL = 0, CHIPS = 1, PIPS = 2;
    private static final int RICHER_MARGIN = 6;

    private static final class Req {
        final NCheeseRackOverlay rack;
        final Coord sc;
        final Tex[] tex;
        int x, y, w, h;

        Req(NCheeseRackOverlay rack, Coord sc, Tex[] tex) {
            this.rack = rack;
            this.sc = sc;
            this.tex = tex;
        }

        void place(int mode) {
            Coord sz = tex[mode].sz();
            w = sz.x;
            h = sz.y;
            x = sc.x - w / 2;
            y = sc.y - h;
        }
    }

    /** Where a rack's panel was drawn last frame, for hover. */
    private static final class Drawn {
        final NCheeseRackOverlay rack;
        final int x, y, w, h;

        Drawn(Req r) {
            this.rack = r.rack;
            this.x = r.x;
            this.y = r.y;
            this.w = r.w;
            this.h = r.h;
        }
    }

    private final List<Req> frame = new ArrayList<>();
    private int mode = FULL;
    private volatile List<Drawn> drawn = Collections.emptyList();

    /** Called from a rack's 2D draw: draw me at {@code sc} (bottom centre), in one of these styles. */
    void add(NCheeseRackOverlay rack, Coord sc, Tex full, Tex chips, Tex pips) {
        // Never grow without bound if this map's draw stops running (e.g. a hidden session).
        if (frame.size() > 4000)
            frame.clear();
        frame.add(new Req(rack, sc, new Tex[]{full, chips, pips}));
    }

    /** Called by the map view after its 3D pass: lays out and draws this frame's panels. */
    public void draw(GOut g) {
        if (frame.isEmpty()) {
            if (!drawn.isEmpty())
                drawn = Collections.emptyList();
            return;
        }
        Coord sz = g.sz();
        List<Req> vis = new ArrayList<>(frame.size());
        for (Req r : frame) {
            r.place(PIPS);
            if (r.sc.x > -200 && r.sc.y > -200 && r.sc.x < sz.x + 200 && r.sc.y < sz.y + 400)
                vis.add(r);
        }
        frame.clear();
        int m = PIPS;
        for (int cand = FULL; cand < PIPS; cand++) {
            if (fits(vis, cand, cand < mode ? RICHER_MARGIN : 0)) {
                m = cand;
                break;
            }
        }
        mode = m;
        for (Req r : vis)
            r.place(m);
        vis.sort((a, b) -> Integer.compare(a.sc.y, b.sc.y));
        List<Drawn> out = new ArrayList<>(vis.size());
        for (Req r : vis) {
            g.image(r.tex[m], Coord.of(r.x, r.y));
            out.add(new Drawn(r));
        }
        drawn = out;
    }

    private static boolean fits(List<Req> vis, int mode, int margin) {
        for (Req r : vis)
            r.place(mode);
        for (int i = 0; i < vis.size(); i++) {
            Req a = vis.get(i);
            for (int j = i + 1; j < vis.size(); j++) {
                Req b = vis.get(j);
                if (a.x < b.x + b.w + margin && b.x < a.x + a.w + margin && a.y < b.y + b.h + margin && b.y < a.y + a.h + margin)
                    return false;
            }
        }
        return true;
    }

    /** The tooltip of the panel under {@code c}, or null. */
    public TexI tooltipAt(Coord c) {
        long now = System.currentTimeMillis();
        List<Drawn> list = drawn;
        for (int i = list.size() - 1; i >= 0; i--) {
            Drawn d = list.get(i);
            if (c.x >= d.x && c.x < d.x + d.w && c.y >= d.y && c.y < d.y + d.h)
                return d.rack.tooltip(now);
        }
        return null;
    }
}
