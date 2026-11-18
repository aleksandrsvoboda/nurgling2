package nurgling.sessions.commander;

import haven.*;
import haven.error.FileLogger;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.render.remembered.RememberedGround;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.MemberReport;
import nurgling.sessions.squad.Squad;
import nurgling.sessions.squad.SquadExecutor;

import java.util.*;

/**
 * The RTS layer over the squad, one per client (held by {@link SessionManager}). It owns the commander camera: a point
 * on a map segment ({@link Focus}), panned freely, that is not tied to any character. Whatever is under it is drawn by
 * the session whose character sees that spot (its <i>host</i>): when the camera moves into another character's live
 * area, the window switches to that session, after warming its ground up so the switch shows nothing new. Pieces of
 * map that don't touch are separate {@link Theater}s; the camera jumps between them.
 *
 * <p>The rule of the squad holds: the viewpoint publishes (a camera target on each session it wants warm), and each
 * session acts on it in its own tick ({@link CommanderTick}). Everything here runs on the viewpoint's UI thread.
 */
public final class Commander {
    /** A switch waits this long at most for the target's ground to be built. */
    private static final long WARM_WAIT_MS = 1500;
    /** Sessions kept warm around the camera besides the viewpoint. */
    private static final int WARM_MAX = 2;
    private static final long THEATERS_MS = 250;
    private static final long DOUBLE_MS = 350;

    private final SessionManager sm;
    public final Alerts alerts = new Alerts();
    public final VisibleGrids visible = new VisibleGrids();

    /** Where the camera looks; null while it rides on the viewpoint's character. */
    private volatile Focus focus;
    private volatile List<Theater> theaters = Collections.emptyList();
    private long theatersAt;
    private final Map<Long, Focus> lastIn = new HashMap<>();
    private final Focus[] marks = new Focus[4];
    private SessionContext pending;
    private long pendingSince;
    private volatile boolean painting;
    private boolean wasActive;
    /** Live grids around every character, per segment, for the remembered ground's filter. */
    private volatile Map<Long, Set<Coord>> bubbles = Collections.emptyMap();
    private int bubbleVersion = 0;
    private long lastKeyAt;
    private int lastKey;

    private final RememberedGround.Filter filter = new RememberedGround.Filter() {
        @Override
        public boolean show(long seg, Coord sc, long gridid) {
            if (visible.contains(gridid))
                return true;
            Set<Coord> b = bubbles.get(seg);
            return b != null && b.contains(sc);
        }

        @Override
        public int version() {
            return visible.version() * 7919 + bubbleVersion;
        }
    };

    public Commander(SessionManager sm) {
        this.sm = sm;
    }

    private Squad squad() {
        return sm.squad();
    }

    public boolean active() {
        return squad().commander();
    }

    /* ---- Camera ---- */

    public Focus focus() {
        return focus;
    }

    /** Point the camera here (null: back on the viewpoint's character). */
    public void setFocus(Focus f) {
        focus = f;
        followId = null;
        preferId = null;
        jumping = false;
    }

    /**
     * A jump (not a pan): the camera goes there at once. When another session has to show the spot, the view on screen
     * holds still until that session takes over, rather than showing its own empty ground there for a moment.
     */
    private void jump(Focus f) {
        setFocus(f);
        jumping = true;
    }

    private volatile boolean jumping;
    private volatile boolean holding;

    /** The view should stay where it is: a jump waits for its session to take over. */
    public boolean holding() {
        return holding;
    }

    /** Ride on the viewpoint's character again. */
    public void follow() {
        focus = null;
        followId = null;
    }

    /** Session to ride on once it is the viewpoint (C twice): the camera goes there first. */
    private volatile String followId;

    /** C twice: the camera rides on the first selected character, like a normal camera. */
    public void followSelection() {
        List<SessionContext> m = squad().members();
        if (m.isEmpty())
            return;
        SessionContext c = m.get(0);
        if (c == sm.getActiveSession()) {
            follow();
            return;
        }
        jumpTo(c);
        followId = c.sessionId;
    }

    /** Where the camera is, as a focus, whether or not it rides on the character. */
    public Focus cameraAt(NGameUI vp) {
        Focus f = focus;
        if (f != null)
            return f;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        Gob pl = (vp == null || vp.map == null) ? null : vp.map.player();
        return (pl == null) ? null : Focus.of(sl, pl.rc);
    }

    /** Look at this character: the camera goes to it (in its theater) and its session takes the view. */
    public void jumpTo(SessionContext ctx) {
        MemberReport r = (ctx == null) ? null : ctx.squadReport;
        Focus f = (r == null) ? null : Focus.of(r.pos);
        if (f != null) {
            jump(f);
            /* Asked for by name (card, tab): its session shows, even where another character sees the spot too. */
            preferId = ctx.sessionId;
        }
    }

    /** A character asked for by name: host while the camera stays in its live area. */
    private volatile String preferId;
    /** Session whose inventory to show once it is on screen (card's inventory count). */
    private volatile String inventoryId;

    /** The camera to this character, its session on screen, and its inventory shown. */
    public void openInventory(SessionContext ctx) {
        if (ctx == null)
            return;
        jumpTo(ctx);
        inventoryId = ctx.sessionId;
    }

    /** The session on screen asks whether to show its inventory now (once). */
    boolean takeInventory(SessionContext vp) {
        String id = inventoryId;
        if (id != null && vp != null && id.equals(vp.sessionId)) {
            inventoryId = null;
            return true;
        }
        return false;
    }

    public void jumpTo(Focus f) {
        if (f != null)
            jump(f);
    }

    /** The camera to the middle of the selection, on the camera's theater when some of it is there. */
    public void centerSelection() {
        List<SessionContext> m = squad().members();
        Focus at = focus;
        long seg = (at != null) ? at.seg : -1;
        List<Focus> fs = new ArrayList<>();
        for (SessionContext c : m) {
            Focus f = (c.squadReport == null) ? null : Focus.of(c.squadReport.pos);
            if (f != null)
                fs.add(f);
        }
        if (fs.isEmpty())
            return;
        boolean any = false;
        for (Focus f : fs)
            any |= (f.seg == seg);
        long use = any ? seg : fs.get(0).seg;
        double x = 0, y = 0;
        int n = 0;
        for (Focus f : fs) {
            if (f.seg != use)
                continue;
            x += f.pos.x;
            y += f.pos.y;
            n++;
        }
        jump(new Focus(use, Coord2d.of(x / n, y / n)));
    }

    /** Camera bookmarks (F5-F8): store where the camera is. */
    public void mark(int i, NGameUI vp) {
        if (i >= 0 && i < marks.length)
            marks[i] = cameraAt(vp);
    }

    public boolean recallMark(int i) {
        if (i < 0 || i >= marks.length || marks[i] == null)
            return false;
        jump(marks[i]);
        return true;
    }

    /** Space: the camera to the newest alert, then to older ones on each further press. */
    public Alerts.Alert jumpToAlert() {
        Alerts.Alert a = alerts.nextForJump();
        if (a != null && a.where != null)
            jump(a.where);
        return a;
    }

    /** A key pressed twice quickly (a group number to jump to it, C to follow). */
    public boolean doubled(int key) {
        long now = System.currentTimeMillis();
        boolean d = (key == lastKey) && (now - lastKeyAt < DOUBLE_MS);
        lastKey = key;
        lastKeyAt = d ? 0 : now;
        return d;
    }

    /* ---- Theaters ---- */

    public List<Theater> theaters() {
        return theaters;
    }

    /** The theater the camera is in, or null. */
    public Theater current(NGameUI vp) {
        Focus f = cameraAt(vp);
        if (f == null)
            return null;
        for (Theater t : theaters)
            if (t.seg == f.seg)
                return t;
        return null;
    }

    /** The camera to this theater: where it last looked there, else at its first character. */
    public void jumpTo(Theater t) {
        if (t == null)
            return;
        Focus f = lastIn.get(t.seg);
        if (f == null && !t.members.isEmpty())
            f = Focus.of(t.members.get(0).squadReport.pos);
        if (f != null)
            jump(f);
    }

    /** Tab: the next theater. */
    public void nextTheater(NGameUI vp) {
        List<Theater> ts = theaters;
        if (ts.size() < 2)
            return;
        Theater cur = current(vp);
        int i = (cur == null) ? -1 : ts.indexOf(cur);
        jumpTo(ts.get((i + 1) % ts.size()));
    }

    private void updateTheaters(long now) {
        if (now - theatersAt < THEATERS_MS)
            return;
        theatersAt = now;
        List<Theater> nt = Theater.of(squad().candidates());
        List<Theater> ot = theaters;
        boolean same = nt.size() == ot.size();
        for (int i = 0; same && i < nt.size(); i++)
            same = nt.get(i).same(ot.get(i));
        if (!same) {
            theaters = Collections.unmodifiableList(nt);
            if (nt.size() != ot.size())
                FileLogger.log("[Commander] theaters: " + nt.size());
        }
        updateBubbles();
    }

    private void updateBubbles() {
        Map<Long, Set<Coord>> nb = new HashMap<>();
        for (Theater t : theaters) {
            Set<Coord> s = new HashSet<>();
            for (SessionContext c : t.members) {
                MemberReport r = c.squadReport;
                if (r == null || r.pos == null || r.pos.segPos == null)
                    continue;
                Coord g = Focus.gridOf(r.pos.segPos);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        s.add(g.add(dx, dy));
            }
            nb.put(t.seg, s);
        }
        if (!nb.equals(bubbles)) {
            bubbles = nb;
            bubbleVersion++;
        }
    }

    /** True when this segment grid is in some character's live area. */
    public boolean inBubble(long seg, Coord sc) {
        Set<Coord> b = bubbles.get(seg);
        return b != null && b.contains(sc);
    }

    /* ---- Build order: a rectangle over planner ghosts ---- */

    private volatile boolean buildArmed;

    public boolean buildArmed() {
        return buildArmed;
    }

    public void setBuildArmed(boolean on) {
        buildArmed = on;
        if (on)
            painting = false;
    }

    /* ---- Keep-visible painting ---- */

    public boolean painting() {
        return painting;
    }

    public void setPainting(boolean on) {
        painting = on;
        if (on)
            buildArmed = false;
        if (!on)
            visible.save();
    }

    /* ---- The viewpoint's tick ---- */

    /**
     * Once a frame on the viewpoint's UI thread: theaters, alerts, which sessions to keep warm around the camera, and
     * the host switch. {@code busy}: the player is dragging something, so the view must not change under the mouse.
     */
    void tick(NGameUI vp, SessionContext vpctx, boolean busy) {
        long now = System.currentTimeMillis();
        if (!active()) {
            if (wasActive)
                deactivate();
            return;
        }
        if (!wasActive) {
            wasActive = true;
            alerts.restart();
            RememberedGround.filter(filter);
            FileLogger.log("[Commander] on");
        }
        updateTheaters(now);
        alerts.tick(sm.getAllSessions());
        String fid = followId;
        if (fid != null && fid.equals(vpctx.sessionId)) {
            followId = null;
            focus = null;
        }
        Focus f = focus;
        if (f != null)
            lastIn.put(f.seg, f);
        List<SessionContext> cands = squad().candidates();
        SessionContext host = (f == null) ? vpctx : hostFor(f, vpctx, cands);
        String pid = preferId;
        if (f != null && pid != null) {
            SessionContext p = sm.getSession(pid);
            MemberReport pr = (p == null) ? null : p.squadReport;
            if (pr != null && pr.pos != null && pr.pos.segPos != null && pr.pos.segId == f.seg
                && near(Focus.gridOf(pr.pos.segPos), f.grid(), 1) && cands.contains(p))
                host = p;
            else
                preferId = null;
        }
        Set<SessionContext> warm = (f == null) ? Collections.<SessionContext>emptySet() : warmFor(f, vpctx, cands, host);
        /* One character at a time that has never been shown is warmed at its own spot and drawn off screen
         * (CommanderPrime), so even the first jump to it shows a finished view at once. */
        SessionContext pre = (pending == null) ? unprimed(cands, vpctx, warm) : null;
        for (SessionContext c : sm.getAllSessions()) {
            if (c == vpctx)
                continue;
            if (warm.contains(c))
                c.camTarget = f;
            else if (c == pre)
                c.camTarget = Focus.of(c.squadReport.pos);
            else
                c.camTarget = null;
        }
        if (host == null || host == vpctx) {
            pending = null;
            jumping = false;
            holding = false;
            return;
        }
        holding = jumping;
        if (pending != host) {
            pending = host;
            pendingSince = now;
        }
        if (busy)
            return;
        if ((host.warmReady && host.primeReady) || now - pendingSince > WARM_WAIT_MS) {
            /* The old viewpoint stays warm (the camera is just past it), so its ground isn't dropped and rebuilt. */
            vpctx.camTarget = f;
            FileLogger.log(String.format("[Commander] host %s -> %s (%s, %d ms warm-up)", vpctx.getDisplayName(),
                                         host.getDisplayName(), (host.warmReady && host.primeReady) ? "ready"
                                         : (host.warmReady ? "ground only" : "cold"), now - pendingSince));
            pending = null;
            sm.switchToSession(host.sessionId, true);
        }
    }

    /**
     * The session that should draw what is under the camera: the viewpoint while the camera is in its character's live
     * area, else the character whose live area holds it (the nearest, when several do). On another theater, its
     * character nearest to the camera. Null (stay) when nobody sees the spot.
     */
    private static SessionContext hostFor(Focus f, SessionContext vp, List<SessionContext> cands) {
        Coord fg = f.grid();
        MemberReport vr = (vp == null) ? null : vp.squadReport;
        boolean vpHere = vr != null && vr.pos != null && vr.pos.segPos != null && vr.pos.segId == f.seg;
        if (vpHere && near(Focus.gridOf(vr.pos.segPos), fg, 1))
            return vp;
        SessionContext best = null, nearest = null;
        double bd = Double.MAX_VALUE, nd = Double.MAX_VALUE;
        for (SessionContext c : cands) {
            if (c == vp)
                continue;
            MemberReport r = c.squadReport;
            if (r == null || r.pos == null || r.pos.segPos == null || r.pos.segId != f.seg || r.plgob < 0)
                continue;
            double d = r.pos.segPos.dist(f.pos);
            if (near(Focus.gridOf(r.pos.segPos), fg, 1) && d < bd) {
                bd = d;
                best = c;
            }
            if (d < nd) {
                nd = d;
                nearest = c;
            }
        }
        if (best != null)
            return best;
        return vpHere ? vp : nearest;
    }

    /** The sessions whose ground should be ready because the camera is at or near their character. */
    private static Set<SessionContext> warmFor(Focus f, SessionContext vp, List<SessionContext> cands, SessionContext host) {
        Coord fg = f.grid();
        List<SessionContext> near = new ArrayList<>();
        for (SessionContext c : cands) {
            if (c == vp)
                continue;
            MemberReport r = c.squadReport;
            if (r == null || r.pos == null || r.pos.segPos == null || r.pos.segId != f.seg)
                continue;
            if (c == host || near(Focus.gridOf(r.pos.segPos), fg, 2))
                near.add(c);
        }
        near.sort(Comparator.comparingDouble(c -> c.squadReport.pos.segPos.dist(f.pos)));
        Set<SessionContext> out = new HashSet<>();
        if (host != null && host != vp)
            out.add(host);
        for (SessionContext c : near) {
            if (out.size() >= WARM_MAX)
                break;
            out.add(c);
        }
        return out;
    }

    private static SessionContext unprimed(List<SessionContext> cands, SessionContext vp, Set<SessionContext> warm) {
        for (SessionContext c : cands) {
            if (c != vp && !warm.contains(c) && !c.primeReady && c.squadReport != null && Focus.of(c.squadReport.pos) != null)
                return c;
        }
        return null;
    }

    private static boolean near(Coord a, Coord b, int r) {
        return Math.abs(a.x - b.x) <= r && Math.abs(a.y - b.y) <= r;
    }

    private void deactivate() {
        wasActive = false;
        focus = null;
        pending = null;
        painting = false;
        visible.save();
        for (SessionContext c : sm.getAllSessions())
            c.camTarget = null;
        RememberedGround.filter(null);
        FileLogger.log("[Commander] off");
    }

    /** One line for :commander status. */
    public String describe(NGameUI vp) {
        Focus f = focus;
        Theater t = current(vp);
        return String.format("commander %s; camera %s; %d theater(s), in %s; %d keep-visible grids; %d alerts",
                             active() ? "on" : "off", (f == null) ? "on the character" : f.toString(), theaters.size(),
                             (t == null) ? "-" : t.label(), visible.size(), alerts.all().size());
    }

    static String msg(String key, Object... args) {
        return L10n.get(key, args);
    }
}
