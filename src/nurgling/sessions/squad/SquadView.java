package nurgling.sessions.squad;

import haven.*;
import nurgling.*;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * The squad as seen from one map view: Commander-mode mouse and keys, the rings under squad characters, and the
 * screen overlay (mode frame, selection box, name tags, order markers, off-screen arrows).
 *
 * Every NMapView owns one, but only the viewpoint's acts; a view that stops being the viewpoint takes its rings down
 * on its next tick. All state here belongs to this view, so nothing is shared between sessions' render paths.
 */
public class SquadView {
    public static final KeyBinding kb_mode = KeyBinding.get("squad-mode", KeyMatch.forcode(KeyEvent.VK_G, KeyMatch.M));
    public static final KeyBinding kb_stop = KeyBinding.get("squad-stop", KeyMatch.forcode(KeyEvent.VK_S, 0));
    public static final KeyBinding kb_rally = KeyBinding.get("squad-rally", KeyMatch.forcode(KeyEvent.VK_R, 0));
    public static final KeyBinding kb_follow = KeyBinding.get("squad-follow", KeyMatch.forcode(KeyEvent.VK_F, 0));
    public static final KeyBinding kb_all = KeyBinding.get("squad-all", KeyMatch.forcode(KeyEvent.VK_A, KeyMatch.C));

    static final Color ORANGE = new Color(0xE9, 0x9C, 0x54);
    static final Color TEAL = new Color(0x5F, 0xD3, 0xC6);
    static final Color PANEL = new Color(0x25, 0x2B, 0x29, 0xE5);
    private static final int PICK_PX = UI.scale(22);
    private static final long DOUBLE_MS = 400;
    private static final long REMAP_MS = 250;

    private final NMapView map;

    /** Squad characters this view can see: session id to the gob in this view. */
    private Map<String, Gob> seen = Collections.emptyMap();
    private long lastRemap;
    private final Map<Long, Ring> rings = new HashMap<>();

    private UI.Grab grab;
    private Coord boxFrom, boxTo;
    private String lastClickId;
    private long lastClickTime;
    private int lastDigit;
    private long lastDigitTime;

    private static Text.Furnace tagFont, bannerFont;
    private final Map<String, Tex> tags = new HashMap<>();
    private Tex banner;
    private String bannerText;

    private static final class Ring {
        final Gob gob;
        final Gob.Overlay ol;
        final SquadRing.Kind kind;

        Ring(Gob gob, Gob.Overlay ol, SquadRing.Kind kind) {
            this.gob = gob;
            this.ol = ol;
            this.kind = kind;
        }
    }

    public SquadView(NMapView map) {
        this.map = map;
    }

    private static Squad squad() {
        return SessionManager.getInstance().squad();
    }

    private boolean viewpoint() {
        return map.ui != null && SessionManager.getInstance().getActiveUI() == map.ui;
    }

    private NGameUI gui() {
        return (map.ui == null) ? null : ((NUI) map.ui).gui;
    }

    /* ---- Tick: which gob is whom, and the rings ---- */

    public void tick() {
        if (!viewpoint()) {
            if (!rings.isEmpty())
                clearRings();
            seen = Collections.emptyMap();
            endBox();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRemap >= REMAP_MS) {
            lastRemap = now;
            seen = remap();
        }
        updateRings();
        Squad sq = squad();
        List<String> proxy = sq.proxyOptions();
        if (proxy != null)
            showProxy(proxy);
        if (!sq.commander())
            endBox();
    }

    private Map<String, Gob> remap() {
        Map<String, Gob> out = new HashMap<>();
        NGameUI vp = gui();
        if (vp == null)
            return out;
        SessionContext self = squad().viewpoint();
        for (SessionContext ctx : squad().candidates()) {
            Gob g = (ctx == self) ? map.player() : find(ctx, vp);
            if (g != null)
                out.put(ctx.sessionId, g);
        }
        return out;
    }

    /** Another session's character in this view: by gob id when it checks out, else the player body at its spot. */
    private Gob find(SessionContext ctx, NGameUI vp) {
        MemberReport r = ctx.squadReport;
        if (r == null)
            return null;
        Coord2d at = Squad.localOf(ctx, vp);
        Gob g = map.glob.oc.getgob(r.plgob);
        if (g != null && isPlayer(g) && (at == null || g.rc.dist(at) < 3 * MCache.tilesz.x))
            return g;
        if (at == null)
            return null;
        Gob best = null;
        double bd = 2 * MCache.tilesz.x;
        synchronized (map.glob.oc) {
            for (Gob o : map.glob.oc) {
                if (!isPlayer(o))
                    continue;
                double d = o.rc.dist(at);
                if (d < bd) {
                    bd = d;
                    best = o;
                }
            }
        }
        return best;
    }

    private static boolean isPlayer(Gob g) {
        return g.ngob != null && SquadExecutor.PLAYER_RES.equals(g.ngob.name);
    }

    private void updateRings() {
        Squad sq = squad();
        Map<Long, SquadRing.Kind> want = new HashMap<>();
        Map<Long, Gob> gobs = new HashMap<>();
        for (Map.Entry<String, Gob> e : seen.entrySet()) {
            SessionContext ctx = SessionManager.getInstance().getSession(e.getKey());
            if (ctx == null)
                continue;
            SquadRing.Kind k = null;
            if (sq.isSelected(ctx)) {
                MemberReport r = ctx.squadReport;
                if (r != null && r.status == MemberReport.Status.COMBAT)
                    k = SquadRing.Kind.COMBAT;
                else
                    k = sq.commander() ? SquadRing.Kind.SELECTED : SquadRing.Kind.DIM;
            } else if (sq.commander()) {
                k = SquadRing.Kind.MEMBER;
            }
            if (k != null) {
                want.put(e.getValue().id, k);
                gobs.put(e.getValue().id, e.getValue());
            }
        }
        for (Iterator<Map.Entry<Long, Ring>> it = rings.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Ring> e = it.next();
            Ring r = e.getValue();
            if (want.get(e.getKey()) != r.kind || gobs.get(e.getKey()) != r.gob) {
                r.ol.remove();
                it.remove();
            }
        }
        for (Map.Entry<Long, SquadRing.Kind> e : want.entrySet()) {
            if (rings.containsKey(e.getKey()))
                continue;
            Gob g = gobs.get(e.getKey());
            Gob.Overlay ol = new Gob.Overlay(g, new SquadRing(g, e.getValue()));
            g.addol(ol);
            rings.put(e.getKey(), new Ring(g, ol, e.getValue()));
        }
    }

    private void clearRings() {
        for (Ring r : rings.values())
            r.ol.remove();
        rings.clear();
    }

    /* ---- Mouse (Commander mode only) ---- */

    /** True when Commander mode took the press. */
    public boolean mousedown(Widget.MouseDownEvent ev) {
        if (!squad().commander() || !viewpoint())
            return false;
        if (ev.b == 1) {
            boxFrom = boxTo = ev.c;
            if (grab == null)
                grab = map.ui.grabmouse(map);
            return true;
        }
        if (ev.b == 3) {
            map.new Hittest(ev.c) {
                @Override
                protected void hit(Coord pc, Coord2d mc, ClickData inf) {
                    Gob g = clickedGob(inf);
                    /* Characters (yours or not) are walked to; anything else is used. */
                    if (g != null && !isPlayer(g))
                        squad().interact(g);
                    else
                        squad().moveTo(mc);
                }
            }.run();
            return true;
        }
        return false;
    }

    private static Gob clickedGob(ClickData inf) {
        if (inf == null)
            return null;
        if (inf.ci instanceof Gob.GobClick)
            return ((Gob.GobClick) inf.ci).gob;
        if (inf.ci instanceof Composited.CompositeClick)
            return ((Composited.CompositeClick) inf.ci).gi.gob;
        return null;
    }

    public boolean mousemove(Coord c) {
        if (grab == null)
            return false;
        boxTo = c;
        return true;
    }

    public boolean mouseup(Widget.MouseUpEvent ev) {
        if (grab == null || ev.b != 1)
            return false;
        Coord from = boxFrom, to = ev.c;
        endBox();
        if (from == null)
            return true;
        boolean add = map.ui.modshift;
        Squad sq = squad();
        if (Math.abs(from.x - to.x) <= 5 && Math.abs(from.y - to.y) <= 5) {
            SessionContext hit = memberAt(to);
            long now = System.currentTimeMillis();
            if (hit == null) {
                if (!add)
                    sq.clear();
            } else if (hit.sessionId.equals(lastClickId) && now - lastClickTime < DOUBLE_MS) {
                sq.select(onScreen(), add);
            } else if (add) {
                sq.toggle(hit);
            } else {
                sq.select(Collections.singletonList(hit), false);
            }
            lastClickId = (hit == null) ? null : hit.sessionId;
            lastClickTime = now;
        } else {
            Coord ul = new Coord(Math.min(from.x, to.x), Math.min(from.y, to.y));
            Coord br = new Coord(Math.max(from.x, to.x), Math.max(from.y, to.y));
            List<SessionContext> in = new ArrayList<>();
            for (Map.Entry<String, Coord> e : screenPositions().entrySet()) {
                Coord p = e.getValue();
                if (p.x >= ul.x && p.x <= br.x && p.y >= ul.y && p.y <= br.y) {
                    SessionContext ctx = SessionManager.getInstance().getSession(e.getKey());
                    if (ctx != null)
                        in.add(ctx);
                }
            }
            sq.select(in, add);
        }
        return true;
    }

    private void endBox() {
        if (grab != null) {
            grab.remove();
            grab = null;
        }
        boxFrom = boxTo = null;
    }

    /** Screen position (feet) of every squad character in view. */
    private Map<String, Coord> screenPositions() {
        Map<String, Coord> out = new HashMap<>();
        for (Map.Entry<String, Gob> e : seen.entrySet()) {
            Coord p = screen(e.getValue(), 0);
            if (p != null)
                out.put(e.getKey(), p);
        }
        return out;
    }

    private Coord screen(Gob g, float lift) {
        try {
            Coord3f c = g.getc();
            Coord3f s = map.screenxf(new Coord3f(c.x, c.y, c.z + lift));
            return (s == null) ? null : s.round2();
        } catch (Loading l) {
            return null;
        }
    }

    private Coord screen(Coord2d local) {
        try {
            Coord3f s = map.screenxf(local);
            return (s == null) ? null : s.round2();
        } catch (Loading l) {
            return null;
        }
    }

    private SessionContext memberAt(Coord c) {
        SessionContext best = null;
        int bd = PICK_PX * PICK_PX;
        for (Map.Entry<String, Gob> e : seen.entrySet()) {
            for (float lift : new float[]{0, 10, 18}) {
                Coord p = screen(e.getValue(), lift);
                if (p == null)
                    continue;
                int dx = p.x - c.x, dy = p.y - c.y, d = dx * dx + dy * dy;
                if (d < bd) {
                    bd = d;
                    best = SessionManager.getInstance().getSession(e.getKey());
                }
            }
        }
        return best;
    }

    private List<SessionContext> onScreen() {
        List<SessionContext> out = new ArrayList<>();
        for (Map.Entry<String, Coord> e : screenPositions().entrySet()) {
            Coord p = e.getValue();
            if (p.x >= 0 && p.y >= 0 && p.x < map.sz.x && p.y < map.sz.y) {
                SessionContext ctx = SessionManager.getInstance().getSession(e.getKey());
                if (ctx != null)
                    out.add(ctx);
            }
        }
        return out;
    }

    /* ---- Keys ---- */

    /** Squad keys for the active UI, checked after focused widgets had their chance and before global keys. */
    public static boolean key(NUI ui, KeyEvent ev) {
        if (ui.gui == null || !(ui.gui.map instanceof NMapView) || SessionManager.getInstance().getActiveUI() != ui)
            return false;
        Squad sq = squad();
        if (!sq.commander())
            return false;
        return ((NMapView) ui.gui.map).squadView.commanderKey(ev);
    }

    private boolean commanderKey(KeyEvent ev) {
        Squad sq = squad();
        if (ev.getKeyCode() == KeyEvent.VK_ESCAPE) {
            sq.setCommander(false);
            return true;
        }
        if (kb_stop.key().match(ev)) {
            sq.stop();
            return true;
        }
        if (kb_rally.key().match(ev)) {
            sq.rally();
            return true;
        }
        if (kb_follow.key().match(ev)) {
            sq.follow();
            return true;
        }
        if (kb_all.key().match(ev)) {
            sq.selectAll();
            return true;
        }
        int code = ev.getKeyCode();
        if (code >= KeyEvent.VK_1 && code <= KeyEvent.VK_9 && !ev.isAltDown() && !ev.isShiftDown()) {
            int n = code - KeyEvent.VK_0;
            NGameUI gui = gui();
            if (ev.isControlDown()) {
                sq.assignGroup(n);
                if (gui != null)
                    gui.msg(L10n.get("squad.msg.group_set", n, sq.members().size()));
                return true;
            }
            long now = System.currentTimeMillis();
            boolean twice = (n == lastDigit) && (now - lastDigitTime < DOUBLE_MS);
            lastDigit = n;
            lastDigitTime = now;
            if (!sq.recallGroup(n)) {
                if (gui != null)
                    gui.msg(L10n.get("squad.msg.group_empty", n));
                return true;
            }
            if (twice) {
                List<SessionContext> m = sq.members();
                if (!m.isEmpty() && !m.contains(sq.viewpoint()))
                    SessionManager.getInstance().switchToSession(m.get(0).sessionId);
            }
            return true;
        }
        return false;
    }

    /* ---- Proxy flower menu ---- */

    private void showProxy(List<String> opts) {
        NUI ui = (NUI) map.ui;
        NFlowerMenu menu = new NFlowerMenu(opts.toArray(new String[0])) {
            @Override
            public boolean mousedown(MouseDownEvent ev) {
                if (super.mousedown(ev))
                    nchoose(null);
                return true;
            }

            @Override
            public void nchoose(NPetal option) {
                squad().pickFlower((option == null) ? null : option.name, null);
                uimsg("cancel");
            }
        };
        ui.root.add(menu, ui.mc);
    }

    /* ---- Screen overlay ---- */

    private static Text.Furnace tagFont() {
        if (tagFont == null)
            tagFont = new PUtils.BlurFurn(new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 11, Color.WHITE).aa(true),
                                          UI.scale(1), UI.scale(1), Color.BLACK);
        return tagFont;
    }

    private static Text.Furnace bannerFont() {
        if (bannerFont == null)
            bannerFont = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 12, ORANGE).aa(true);
        return bannerFont;
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

    public void draw(GOut g) {
        if (!viewpoint())
            return;
        Squad sq = squad();
        NGameUI vp = gui();
        if (vp == null)
            return;
        drawOrder(g, sq, vp);
        for (SessionContext ctx : sq.members()) {
            Gob gob = seen.get(ctx.sessionId);
            MemberReport r = ctx.squadReport;
            Coord p = (gob == null) ? null : screen(gob, 22);
            if (p != null && p.x >= 0 && p.y >= 0 && p.x < map.sz.x && p.y < map.sz.y) {
                if (sq.commander() && r != null) {
                    int grp = Squad.groupOf(r);
                    g.aimage(tag((grp > 0) ? (grp + " " + r.name) : r.name), p, 0.5, 1);
                }
            } else {
                drawArrow(g, ctx, vp);
            }
        }
        if (sq.commander()) {
            g.chcolor(ORANGE);
            g.rect(Coord.z, map.sz);
            g.rect(new Coord(1, 1), map.sz.sub(2, 2));
            String txt = L10n.get("squad.banner", sq.members().size());
            if (!txt.equals(bannerText)) {
                bannerText = txt;
                banner = bannerFont().render(txt).tex();
            }
            Coord bsz = banner.sz().add(UI.scale(16), UI.scale(8));
            Coord bul = new Coord((map.sz.x - bsz.x) / 2, UI.scale(10));
            g.chcolor(PANEL);
            g.frect(bul, bsz);
            g.chcolor(ORANGE);
            g.rect(bul, bsz);
            g.chcolor();
            g.aimage(banner, bul.add(bsz.div(2)), 0.5, 0.5);
            if (boxFrom != null && boxTo != null && !boxFrom.equals(boxTo)) {
                Coord ul = new Coord(Math.min(boxFrom.x, boxTo.x), Math.min(boxFrom.y, boxTo.y));
                Coord sz = new Coord(Math.abs(boxFrom.x - boxTo.x), Math.abs(boxFrom.y - boxTo.y));
                g.chcolor(TEAL.getRed(), TEAL.getGreen(), TEAL.getBlue(), 30);
                g.frect(ul, sz);
                g.chcolor(TEAL);
                g.rect(ul, sz);
            }
        }
        g.chcolor();
    }

    private void drawOrder(GOut g, Squad sq, NGameUI vp) {
        Squad.Order o = sq.order();
        if (o == null || o.target == null)
            return;
        MCache mc = vp.map.glob.map;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        Coord2d t = o.target.localIn(mc, sl);
        Coord ts = (t == null) ? null : screen(t);
        g.chcolor(ORANGE);
        for (Map.Entry<String, WorldRef> e : o.slots.entrySet()) {
            Coord2d s = e.getValue().localIn(mc, sl);
            Coord ss = (s == null) ? null : screen(s);
            if (ss == null)
                continue;
            Gob gob = seen.get(e.getKey());
            if (gob != null && gob.rc.dist(s) < MCache.tilesz.x)
                continue;
            g.fellipse(ss, UI.scale(new Coord(3, 2)));
            Coord from = (gob == null) ? null : screen(gob, 0);
            if (from != null)
                dashed(g, from, ss);
        }
        if (ts != null) {
            int r = UI.scale(6);
            g.line(ts.add(-r, -r), ts.add(r, r), 3);
            g.line(ts.add(-r, r), ts.add(r, -r), 3);
        }
        g.chcolor();
    }

    private static void dashed(GOut g, Coord a, Coord b) {
        double len = a.dist(b);
        int dash = UI.scale(6), gap = UI.scale(4);
        if (len < 1)
            return;
        double dx = (b.x - a.x) / len, dy = (b.y - a.y) / len;
        for (double d = 0; d < len; d += dash + gap) {
            double e = Math.min(len, d + dash);
            g.line(new Coord((int) (a.x + dx * d), (int) (a.y + dy * d)),
                   new Coord((int) (a.x + dx * e), (int) (a.y + dy * e)), 1.5);
        }
    }

    /** An arrow on the screen edge towards a member out of view, with its name and distance. */
    private void drawArrow(GOut g, SessionContext ctx, NGameUI vp) {
        MemberReport r = ctx.squadReport;
        Gob pl = map.player();
        Coord2d at = Squad.localOf(ctx, vp);
        if (r == null || pl == null || at == null)
            return;
        Coord2d d = at.sub(pl.rc);
        double dist = d.abs();
        if (dist < 1)
            return;
        Coord s0 = screen(pl, 0);
        Coord s1 = screen(pl.rc.add(d.norm(3 * MCache.tilesz.x)));
        if (s0 == null || s1 == null || s0.equals(s1))
            return;
        double vx = s1.x - s0.x, vy = s1.y - s0.y, vl = Math.hypot(vx, vy);
        vx /= vl;
        vy /= vl;
        int m = UI.scale(28);
        Coord ctr = map.sz.div(2);
        double tx = (vx == 0) ? Double.MAX_VALUE : ((vx > 0 ? map.sz.x - m : m) - ctr.x) / vx;
        double ty = (vy == 0) ? Double.MAX_VALUE : ((vy > 0 ? map.sz.y - m : m) - ctr.y) / vy;
        double tt = Math.min(Math.abs(tx), Math.abs(ty));
        Coord tip = new Coord((int) (ctr.x + vx * tt), (int) (ctr.y + vy * tt));
        int a = UI.scale(12), w = UI.scale(7);
        Coord base = new Coord((int) (tip.x - vx * a), (int) (tip.y - vy * a));
        Coord l = new Coord((int) (base.x - vy * w), (int) (base.y + vx * w));
        Coord rr = new Coord((int) (base.x + vy * w), (int) (base.y - vx * w));
        g.chcolor(ORANGE);
        g.line(tip, l, 3);
        g.line(tip, rr, 3);
        g.line(l, rr, 3);
        g.chcolor();
        String label = L10n.get("squad.arrow", r.name, (int) Math.round(dist / MCache.tilesz.x));
        g.aimage(tag(label), new Coord((int) (base.x - vx * UI.scale(14)), (int) (base.y - vy * UI.scale(14))), 0.5, 0.5);
    }

    /* ---- Minimap ---- */

    private static boolean ownMinimap(MiniMap mm) {
        if (mm.ui == null || SessionManager.getInstance().getActiveUI() != mm.ui)
            return false;
        NGameUI gui = ((NUI) mm.ui).gui;
        return gui != null && (mm == gui.mmap || (gui.mapfile != null && mm == gui.mapfile.view));
    }

    /** Right-click on the viewpoint's minimap or map window in Commander mode: a squad move there. */
    public static boolean minimapClick(MiniMap mm, MiniMap.Location loc, int button) {
        if (button != 3 || loc == null || !squad().commander() || !ownMinimap(mm))
            return false;
        MiniMap.Location sl = mm.sessloc;
        if (sl == null || sl.seg.id != loc.seg.id)
            return false;
        Coord2d local = Coord2d.of(loc.tc.sub(sl.tc)).mul(MCache.tilesz).add(MCache.tilesz.x / 2, MCache.tilesz.y / 2);
        squad().moveTo(local);
        return true;
    }

    /** Selected members as orange squares on the viewpoint's minimap. */
    public static void drawMinimap(MiniMap mm, GOut g) {
        if (squad().isEmpty() || !ownMinimap(mm))
            return;
        NGameUI vp = ((NUI) mm.ui).gui;
        int s = UI.scale(3);
        for (SessionContext ctx : squad().members()) {
            Coord2d at = Squad.localOf(ctx, vp);
            if (at == null)
                continue;
            Coord c = mm.p2c(at);
            g.chcolor(Color.BLACK);
            g.frect(c.sub(s + 1, s + 1), new Coord(2 * s + 3, 2 * s + 3));
            g.chcolor(ORANGE);
            g.frect(c.sub(s, s), new Coord(2 * s + 1, 2 * s + 1));
        }
        g.chcolor();
    }
}
