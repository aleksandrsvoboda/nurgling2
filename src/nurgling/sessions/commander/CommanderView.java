package nurgling.sessions.commander;

import haven.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUI;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * The commander camera as seen from one map view: panning (screen edges, arrow keys), putting the camera where the
 * commander says, markers for characters this view's own session can't see, queued waypoints, and the keep-visible
 * paint brush. Every NMapView owns one; only the view on screen acts.
 */
public class CommanderView {
    public static final Color TEAL = new Color(0x5F, 0xD3, 0xC6);
    public static final Color ORANGE = new Color(0xE9, 0x9C, 0x54);
    private static final double GRID = MCache.cmaps.x * MCache.tilesz.x;
    /** Panning speed: a screen width in this many seconds, whatever the zoom. */
    private static final double SCREEN_S = 1.1;

    private final NMapView map;
    private final Set<Integer> held = new HashSet<>();
    private boolean wasViewpoint;
    /* Paint stroke: whether it adds or removes, and the grids it has done. */
    private Boolean paintAdd;
    private final Set<Long> stroke = new HashSet<>();
    private UI.Grab paintGrab;
    private static Text.Furnace tagFont;
    private final Map<String, Tex> tags = new HashMap<>();
    /** Where the markers of characters this session has no gob for were drawn: session id to screen point. */
    private Map<String, Coord> ghosts = Collections.emptyMap();

    /** Screen points of the characters drawn as markers (this session can't see them), for selecting. */
    public Map<String, Coord> ghosts() {
        return ghosts;
    }

    public CommanderView(NMapView map) {
        this.map = map;
    }

    private static Commander cmd() {
        return SessionManager.getInstance().commander();
    }

    private boolean viewpoint() {
        if (map.ui == null)
            return false;
        SessionManager sm = SessionManager.getInstance();
        if (sm.getActiveUI() != map.ui)
            return false;
        SessionContext ctx = (map.ui.sess == null) ? null : sm.findBySession(map.ui.sess);
        return ctx == null || !ctx.isHeadless();
    }

    private NGameUI gui() {
        return (map.ui == null) ? null : ((NUI) map.ui).gui;
    }

    private SessionContext self() {
        return (map.ui == null || map.ui.sess == null) ? null : SessionManager.getInstance().findBySession(map.ui.sess);
    }

    /* ---- Tick ---- */

    public void tick(double dt) {
        boolean vp = viewpoint();
        Commander c = cmd();
        if (!vp) {
            wasViewpoint = false;
            held.clear();
            endPaint();
            return;
        }
        NGameUI gui = gui();
        SessionContext me = self();
        if (gui == null || me == null)
            return;
        if (!c.active()) {
            if (map.camfocus != null)
                map.camfocus = null;
            held.clear();
            endPaint();
            c.tick(gui, me, false);
            wasViewpoint = true;
            return;
        }
        if (!wasViewpoint) {
            /* Just taken over (a hand-over or a jump): stand on the spot at once, don't glide in from where this
             * session's camera last was. */
            wasViewpoint = true;
            map.camera.snap();
            me.camTarget = null;
        }
        if (c.takeInventory(me))
            gui.commanderScreen.showInventory();
        pan(dt, gui);
        /* Host first: it says whether this view must hold still for a jump another session will show. */
        c.tick(gui, me, busy());
        MiniMap.Location sl = SquadExecutor.sessloc(gui);
        Focus f = c.focus();
        if (f == null) {
            if (map.camfocus != null)
                map.camfocus = null;
        } else if (!c.holding()) {
            Coord2d local = f.localIn(sl);
            if (local != null)
                map.camfocus = local;
        }
    }

    private boolean busy() {
        return paintGrab != null || buildGrab != null || map.squadView.dragging();
    }

    /** Arrow keys (held) and the screen edges move the camera, at a screen width a second at any zoom. */
    private void pan(double dt, NGameUI gui) {
        int vx = 0, vy = 0;
        if (held.contains(KeyEvent.VK_LEFT)) vx--;
        if (held.contains(KeyEvent.VK_RIGHT)) vx++;
        if (held.contains(KeyEvent.VK_UP)) vy--;
        if (held.contains(KeyEvent.VK_DOWN)) vy++;
        haven.iosys.tk.Windeye wnd = (map.ui.getLoop() == null) ? null : map.ui.getLoop().wnd;
        /* Keys held while the window lost focus never see their release. */
        if (wnd != null && !wnd.focused())
            held.clear();
        boolean edges = (wnd == null) || (wnd.focused() && wnd.hovered());
        if (edges && vx == 0 && vy == 0 && edgeScroll()) {
            Coord mc = map.ui.mc.sub(map.rootpos());
            int band = UI.scale(6);
            if (mc.x >= 0 && mc.y >= 0 && mc.x < map.sz.x && mc.y < map.sz.y) {
                if (mc.x < band) vx = -1;
                else if (mc.x >= map.sz.x - band) vx = 1;
                if (mc.y < band) vy = -1;
                else if (mc.y >= map.sz.y - band) vy = 1;
            }
        }
        if (vx == 0 && vy == 0)
            return;
        Commander c = cmd();
        Focus at = c.cameraAt(gui);
        MiniMap.Location sl = SquadExecutor.sessloc(gui);
        if (at == null || sl == null)
            return;
        Coord2d local = at.localIn(sl);
        if (local == null)
            return;
        double z = map.getcc().z;
        Coord ctr = map.sz.div(2);
        int step = UI.scale(100);
        Coord2d p0 = GroundPick.pick(map, ctr, local, z);
        Coord2d px = GroundPick.pick(map, ctr.add(step, 0), local, z);
        Coord2d py = GroundPick.pick(map, ctr.add(0, step), local, z);
        if (p0 == null || px == null || py == null)
            return;
        double px_s = map.sz.x / SCREEN_S * Math.min(dt, 0.1);
        double len = Math.hypot(vx, vy);
        Coord2d ex = px.sub(p0).mul(1.0 / step), ey = py.sub(p0).mul(1.0 / step);
        Coord2d d = ex.mul(vx * px_s / len).add(ey.mul(vy * px_s / len));
        c.setFocus(at.add(d));
    }

    public static boolean edgeScroll() {
        return !Boolean.FALSE.equals(nurgling.NConfig.getGlobal(nurgling.NConfig.Key.commanderEdgeScroll));
    }

    /* ---- Keys the map view gets first ---- */

    /** Arrow keys pan in Commander mode (they turn and zoom the camera otherwise). */
    public boolean keydown(int code) {
        if (!cmd().active() || !viewpoint() || !isArrow(code))
            return false;
        held.add(code);
        return true;
    }

    public boolean keyup(int code) {
        return held.remove(code) && isArrow(code);
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN;
    }

    /* ---- Keep-visible paint brush ---- */

    /* The Build order's rectangle, in screen coordinates, while it is dragged. */
    private Coord buildFrom, buildTo;
    private UI.Grab buildGrab;

    /** True when the press started a paint stroke (paint mode) or a Build rectangle (Build armed). */
    public boolean mousedown(Widget.MouseDownEvent ev) {
        Commander c = cmd();
        if (c.active() && c.buildArmed() && viewpoint()) {
            if (ev.b == 3) {
                c.setBuildArmed(false);
                return true;
            }
            if (ev.b == 1) {
                buildFrom = buildTo = ev.c;
                if (buildGrab == null)
                    buildGrab = map.ui.grabmouse(map);
                return true;
            }
            return false;
        }
        if (!c.active() || !c.painting() || !viewpoint())
            return false;
        if (ev.b == 3) {
            c.setPainting(false);
            return true;
        }
        if (ev.b != 1)
            return false;
        Long id = gridAt(ev.c);
        if (id == null)
            return true;
        paintAdd = !c.visible.contains(id);
        stroke.clear();
        paintAt(ev.c);
        if (paintGrab == null)
            paintGrab = map.ui.grabmouse(map);
        return true;
    }

    public boolean mousemove(Coord c) {
        if (buildGrab != null) {
            buildTo = c;
            return true;
        }
        if (paintGrab == null)
            return false;
        paintAt(c);
        return true;
    }

    public boolean mouseup(Widget.MouseUpEvent ev) {
        if (buildGrab != null && ev.b == 1) {
            Coord a = buildFrom, b = ev.c;
            buildGrab.remove();
            buildGrab = null;
            buildFrom = buildTo = null;
            cmd().setBuildArmed(false);
            buildIn(a, b);
            return true;
        }
        if (paintGrab == null || ev.b != 1)
            return false;
        endPaint();
        cmd().visible.save();
        return true;
    }

    /** The ground under a screen rectangle (all four corners, the camera may be turned) to the Build order. */
    private void buildIn(Coord a, Coord b) {
        NGameUI gui = gui();
        if (gui == null || a == null || b == null)
            return;
        Coord[] sc = {a, b, Coord.of(a.x, b.y), Coord.of(b.x, a.y)};
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (Coord s : sc) {
            Coord2d p = groundAt(s);
            if (p == null)
                return;
            x0 = Math.min(x0, p.x);
            y0 = Math.min(y0, p.y);
            x1 = Math.max(x1, p.x);
            y1 = Math.max(y1, p.y);
        }
        CommanderActions.build(gui, Coord2d.of(x0, y0), Coord2d.of(x1, y1));
    }

    private void endPaint() {
        if (paintGrab != null) {
            paintGrab.remove();
            paintGrab = null;
        }
        paintAdd = null;
        stroke.clear();
    }

    private void paintAt(Coord sc) {
        Long id = gridAt(sc);
        if (id == null || paintAdd == null || !stroke.add(id))
            return;
        cmd().visible.set(id, paintAdd);
    }

    /** The recorded grid under a screen point, by global id, or null. */
    private Long gridAt(Coord sc) {
        NGameUI gui = gui();
        MiniMap.Location sl = SquadExecutor.sessloc(gui);
        Coord2d at = groundAt(sc);
        if (sl == null || at == null)
            return null;
        Coord g = Focus.gridOf(WorldRef.toSegment(sl.tc, at));
        return sl.seg.map.get(g);
    }

    /** The local ground point under a screen point, wherever it is drawn (live, remembered or black). */
    public Coord2d groundAt(Coord sc) {
        try {
            Coord3f cc = map.getcc();
            return GroundPick.pick(map, sc, Coord2d.of(cc.x, cc.y), cc.z);
        } catch (Loading l) {
            return null;
        }
    }

    /* ---- Overlay ---- */

    private static Text.Furnace tagFont() {
        if (tagFont == null)
            tagFont = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 11, Color.WHITE).aa(true),
                                          UI.scale(1), UI.scale(1), Color.BLACK);
        return tagFont;
    }

    private Tex tag(String s) {
        Tex t = tags.get(s);
        if (t == null) {
            if (tags.size() > 64)
                tags.clear();
            t = tagFont().render(s).tex();
            tags.put(s, t);
        }
        return t;
    }

    private Coord screen(Coord2d local) {
        double z = GroundPick.height(map, local);
        if (Double.isNaN(z)) {
            try {
                z = map.getcc().z;
            } catch (Loading l) {
                return null;
            }
        }
        Coord3f s = map.screenxf(new Coord3f((float) local.x, (float) local.y, (float) z));
        return (s == null) ? null : s.round2();
    }

    public void draw(GOut g) {
        Commander c = cmd();
        if (!c.active() || !viewpoint()) {
            ghosts = Collections.emptyMap();
            return;
        }
        NGameUI gui = gui();
        MiniMap.Location sl = SquadExecutor.sessloc(gui);
        if (gui == null || sl == null)
            return;
        if (c.painting())
            drawPaint(g, c, sl);
        if (buildFrom != null && buildTo != null && !buildFrom.equals(buildTo)) {
            Coord ul = Coord.of(Math.min(buildFrom.x, buildTo.x), Math.min(buildFrom.y, buildTo.y));
            Coord sz = Coord.of(Math.abs(buildFrom.x - buildTo.x), Math.abs(buildFrom.y - buildTo.y));
            g.chcolor(ORANGE.getRed(), ORANGE.getGreen(), ORANGE.getBlue(), 40);
            g.frect(ul, sz);
            g.chcolor(ORANGE);
            g.rect(ul, sz);
            g.chcolor();
        }
        Squad sq = SessionManager.getInstance().squad();
        SessionContext me = self();
        Set<String> seen = map.squadView.seenIds();
        Map<String, Coord> gs = new HashMap<>();
        for (SessionContext ctx : sq.candidates()) {
            MemberReport r = ctx.squadReport;
            if (r == null || r.pos == null)
                continue;
            Focus f = Focus.of(r.pos);
            Coord2d local = (f == null) ? null : f.localIn(sl);
            if (local == null)
                continue;
            boolean sel = sq.isSelected(ctx);
            if (sel)
                drawQueue(g, local, r, sl);
            if (ctx == me || seen.contains(ctx.sessionId))
                continue;
            /* Another session's character this session has no gob for: a marker where its own session says it is. */
            Coord p = screen(local);
            if (p == null || p.x < 0 || p.y < 0 || p.x >= map.sz.x || p.y >= map.sz.y)
                continue;
            int s = UI.scale(6);
            g.chcolor(Color.BLACK);
            g.frect(p.sub(s + 1, s + 1), new Coord(2 * s + 3, 2 * s + 3));
            g.chcolor(sel ? ORANGE : TEAL);
            g.frect(p.sub(s, s), new Coord(2 * s + 1, 2 * s + 1));
            g.chcolor();
            g.aimage(tag(r.name), p.sub(0, s + UI.scale(7)), 0.5, 1);
            hpBar(g, p.sub(0, s + UI.scale(5)), r.hp);
            gs.put(ctx.sessionId, p);
        }
        ghosts = gs;
        g.chcolor();
    }

    /** A small health bar centred on {@code c}, as RTS units have; nothing when health is unknown. */
    public static void hpBar(GOut g, Coord c, double hp) {
        if (hp < 0)
            return;
        int w = UI.scale(32), h = UI.scale(4);
        Coord ul = c.sub(w / 2, 0);
        g.chcolor(0, 0, 0, 180);
        g.frect(ul.sub(1, 1), new Coord(w + 2, h + 2));
        double f = Math.max(0, Math.min(1, hp));
        g.chcolor((f > 0.5) ? new Color(0x5C, 0xC0, 0x4C) : (f > 0.25) ? ORANGE : new Color(0xD0, 0x44, 0x3C));
        g.frect(ul, new Coord((int) Math.round(w * f), h));
        g.chcolor();
    }

    /** A selected character's queued waypoints: a dashed path from it through each, numbered. */
    private void drawQueue(GOut g, Coord2d from, MemberReport r, MiniMap.Location sl) {
        if (r.queue == null || r.queue.isEmpty())
            return;
        Coord prev = screen(from);
        int n = 1;
        g.chcolor(ORANGE);
        for (WorldRef w : r.queue) {
            Focus f = Focus.of(w);
            Coord2d l = (f == null) ? null : f.localIn(sl);
            Coord p = (l == null) ? null : screen(l);
            if (p == null)
                continue;
            if (prev != null)
                dashed(g, prev, p);
            g.fellipse(p, UI.scale(new Coord(4, 3)));
            g.chcolor();
            g.aimage(tag(Integer.toString(n++)), p.sub(0, UI.scale(5)), 0.5, 1);
            g.chcolor(ORANGE);
            prev = p;
        }
        g.chcolor();
    }

    private static void dashed(GOut g, Coord a, Coord b) {
        double len = a.dist(b), dash = UI.scale(6), gap = UI.scale(4);
        if (len < 1)
            return;
        double dx = (b.x - a.x) / len, dy = (b.y - a.y) / len;
        for (double d = 0; d < len; d += dash + gap) {
            double e = Math.min(len, d + dash);
            g.line(new Coord((int) (a.x + dx * d), (int) (a.y + dy * d)), new Coord((int) (a.x + dx * e), (int) (a.y + dy * e)), 1.5);
        }
    }

    /** Paint mode: the marked grids around the camera filled teal, the one under the cursor outlined orange. */
    private void drawPaint(GOut g, Commander c, MiniMap.Location sl) {
        Coord3f cc;
        try {
            cc = map.getcc();
        } catch (Loading l) {
            return;
        }
        Coord cg = Focus.gridOf(WorldRef.toSegment(sl.tc, Coord2d.of(cc.x, cc.y)));
        Coord2d hov = groundAt(map.ui.mc.sub(map.rootpos()));
        Coord hg = (hov == null) ? null : Focus.gridOf(WorldRef.toSegment(sl.tc, hov));
        int r = 4;
        for (int x = cg.x - r; x <= cg.x + r; x++) {
            for (int y = cg.y - r; y <= cg.y + r; y++) {
                Coord sc = Coord.of(x, y);
                Long id = sl.seg.map.get(sc);
                if (id == null)
                    continue;
                boolean on = c.visible.contains(id), bub = c.inBubble(sl.seg.id, sc);
                if (!on && !bub && !sc.equals(hg))
                    continue;
                Coord[] q = corners(sc, sl);
                if (q == null)
                    continue;
                if (on) {
                    g.chcolor(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue(), 50);
                    g.fquad(q[0], q[1], q[2], q[3]);
                }
                g.chcolor(sc.equals(hg) ? ORANGE : on ? TEAL : new Color(255, 255, 255, 60));
                for (int i = 0; i < 4; i++)
                    g.line(q[i], q[(i + 1) % 4], sc.equals(hg) ? 2.5 : 1.5);
            }
        }
        g.chcolor();
    }

    private Coord[] corners(Coord sc, MiniMap.Location sl) {
        Coord[] out = new Coord[4];
        int[][] k = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            Coord2d seg = Coord2d.of((sc.x + k[i][0]) * GRID, (sc.y + k[i][1]) * GRID);
            out[i] = screen(WorldRef.fromSegment(sl.tc, seg));
            if (out[i] == null)
                return null;
        }
        return out;
    }
}
