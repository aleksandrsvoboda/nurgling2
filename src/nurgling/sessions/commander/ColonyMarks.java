package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.colony.ColonyGeo;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.WorldRef;

import java.awt.Color;
import java.util.function.Function;

/**
 * The colony's home on the Commander screen: a marker and the circle of its reach, on the minimap, in the map window,
 * and on the ground in the world. Drawn only in Commander mode, where the colony lives.
 */
public final class ColonyMarks {
    private static final Color HOME = CommanderView.ORANGE;
    private static final Color REACH = new Color(0xE9, 0x9C, 0x54, 150);
    private static final int SEGMENTS = 72;
    private static Text.Foundry font;
    private static Tex label;
    private static String labelText;

    private ColonyMarks() {}

    /** Home in the frame of {@code vp}, placed with {@code sessloc}; null when unset or not placeable there. */
    private static Coord2d homeIn(NGameUI vp, MiniMap.Location sessloc) {
        WorldRef home = SessionManager.getInstance().colony().config.home();
        if (home == null || vp == null || vp.map == null)
            return null;
        return home.localIn(vp.map.glob.map, sessloc);
    }

    private static double reach() {
        int r = SessionManager.getInstance().colony().config.radius();
        return r * ColonyGeo.GRID_TILES * MCache.tilesz.x;
    }

    private static Tex label() {
        String t = L10n.get("colony.mark.home");
        if (label == null || !t.equals(labelText)) {
            if (font == null)
                font = new Text.Foundry(Text.sans.deriveFont(java.awt.Font.BOLD), 11).aa(true);
            labelText = t;
            label = font.render(t, Color.WHITE).tex();
        }
        return label;
    }

    /** On the viewpoint's minimap or map window: the reach as a circle, home as a marker. */
    public static void minimap(MiniMap mm, GOut g, NGameUI vp) {
        Coord2d home = homeIn(vp, mm.sessloc);
        if (home == null)
            return;
        double r = reach();
        g.chcolor(REACH);
        Coord prev = null;
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = 2 * Math.PI * i / SEGMENTS;
            Coord c = mm.p2c(home.add(Math.cos(a) * r, Math.sin(a) * r));
            if (prev != null)
                g.line(prev, c, 1);
            prev = c;
        }
        marker(g, mm.p2c(home));
    }

    /** A house: a square with a roof, outlined in black so it reads on any terrain. */
    private static void marker(GOut g, Coord c) {
        int s = UI.scale(5);
        Coord[] house = {
            c.add(-s, 0), c.add(0, -s), c.add(s, 0), c.add(s, s), c.add(-s, s)
        };
        g.chcolor(Color.BLACK);
        poly(g, house, 3);
        g.chcolor(HOME);
        poly(g, house, 1);
        g.frect(c.add(-s + UI.scale(2), UI.scale(1)), new Coord(2 * s - UI.scale(3), s - UI.scale(1)));
        g.chcolor();
    }

    private static void poly(GOut g, Coord[] p, double w) {
        for (int i = 0; i < p.length; i++)
            g.line(p[i], p[(i + 1) % p.length], w);
    }

    /**
     * In the world: a pin with "Home" over the spot and the reach as a line on the ground. {@code screen} puts a local
     * ground point on screen (null when it's behind the camera or not loaded).
     */
    public static void world(GOut g, NGameUI vp, Coord sz, Function<Coord2d, Coord> screen) {
        MiniMap.Location sl = nurgling.sessions.squad.SquadExecutor.sessloc(vp);
        Coord2d home = homeIn(vp, sl);
        if (home == null)
            return;
        double r = reach();
        g.chcolor(REACH);
        Coord prev = null;
        for (int i = 0; i <= SEGMENTS * 2; i++) {
            double a = Math.PI * i / SEGMENTS;
            Coord c = screen.apply(home.add(Math.cos(a) * r, Math.sin(a) * r));
            if (prev != null && c != null && (onScreen(prev, sz) || onScreen(c, sz)))
                g.line(prev, c, 2);
            prev = c;
        }
        Coord p = screen.apply(home);
        if (p != null && onScreen(p, sz)) {
            int up = UI.scale(46);
            Coord top = p.sub(0, up);
            g.chcolor(Color.BLACK);
            g.line(p, top, 4);
            g.chcolor(HOME);
            g.line(p, top, 2);
            Tex t = label();
            Coord bsz = t.sz().add(UI.scale(10), UI.scale(4));
            Coord bul = top.sub(bsz.x / 2, bsz.y);
            g.chcolor(0x25, 0x2B, 0x29, 0xE5);
            g.frect(bul, bsz);
            g.chcolor(HOME);
            g.rect(bul, bsz);
            g.chcolor();
            g.aimage(t, bul.add(bsz.div(2)), 0.5, 0.5);
            marker(g, p);
        }
        g.chcolor();
    }

    private static boolean onScreen(Coord c, Coord sz) {
        return c.x >= -sz.x && c.y >= -sz.y && c.x < 2 * sz.x && c.y < 2 * sz.y;
    }

    /** Whether the marks are drawn now: Commander mode on and a home set. */
    public static boolean shown() {
        SessionManager sm = SessionManager.getInstance();
        return sm.squad().commander() && sm.colony().config.home() != null;
    }
}
