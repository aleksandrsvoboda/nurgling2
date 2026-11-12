package nurgling.sessions.squad;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Which characters are selected and what the squad was last told to do. Held by {@link SessionManager}, so there is
 * one per client. It keeps session ids and immutable records only - never a game UI or a gob - so a closed session
 * can't be pinned by it.
 *
 * Commands are called on the viewpoint's UI thread. They only read the viewpoint's own map and the members'
 * published {@link MemberReport}s, and post orders to the members' inboxes.
 */
public class Squad implements SessionManager.SessionChangeListener {
    /** How long a squad Interact waits for its flower menu pick. */
    private static final long INTERACT_MS = 40000;

    private final SessionManager sm;
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private volatile boolean commander;
    private volatile Order order;
    /** Session id of the leader while a squad is selected; see {@link #leader()}. */
    private volatile String leaderId;
    private final List<SquadOrderHandler> handlers = new CopyOnWriteArrayList<>();
    private final List<SquadToggle> toggles = new CopyOnWriteArrayList<>();
    private final List<String> helpSections = new CopyOnWriteArrayList<>();
    private final List<SquadKeyHandler> keyHandlers = new CopyOnWriteArrayList<>();
    private volatile TargetPick pick;

    private long interactSeq;
    private volatile long interactUntil;
    private volatile Set<String> interactMembers = Collections.emptySet();
    private volatile long proxyShownSeq = -1;

    /** The last move order, kept for the map markers. Slots are per session id. */
    public static final class Order {
        public final WorldRef target;
        public final Map<String, WorldRef> slots;
        public final long time;

        Order(WorldRef target, Map<String, WorldRef> slots) {
            this.target = target;
            this.slots = Collections.unmodifiableMap(slots);
            this.time = System.currentTimeMillis();
        }
    }

    public Squad(SessionManager sm) {
        this.sm = sm;
        sm.addListener(this);
    }

    /* ---- Settings ---- */

    public static boolean pathfind() {
        return !Boolean.FALSE.equals(NConfig.getGlobal(NConfig.Key.squadPathfind));
    }

    public static void setPathfind(boolean on) {
        NConfig.set(NConfig.Key.squadPathfind, on);
    }

    public static boolean stopBots() {
        return Boolean.TRUE.equals(NConfig.getGlobal(NConfig.Key.squadStopBots));
    }

    public static void setStopBots(boolean on) {
        NConfig.set(NConfig.Key.squadStopBots, on);
    }

    /** Whether selecting switches the view to a selected character (else double-click one to view it). */
    public static boolean autoView() {
        return !Boolean.FALSE.equals(NConfig.getGlobal(NConfig.Key.squadAutoView));
    }

    public static void setAutoView(boolean on) {
        NConfig.set(NConfig.Key.squadAutoView, on);
    }

    /** Show this character: switch to its session, keeping the camera. */
    public void view(SessionContext ctx) {
        if (ctx != null && ctx != viewpoint())
            sm.switchToSession(ctx.sessionId, true);
    }

    public static Formation.Kind formation() {
        Object v = NConfig.getGlobal(NConfig.Key.squadFormation);
        if (v instanceof String) {
            try {
                return Formation.Kind.valueOf((String) v);
            } catch (IllegalArgumentException e) {
                /* Unknown name in an edited config: fall back to the default. */
            }
        }
        return Formation.Kind.GRID;
    }

    public static void setFormation(Formation.Kind k) {
        NConfig.set(NConfig.Key.squadFormation, k.name());
    }

    /* ---- Mode ---- */

    public boolean commander() {
        return commander;
    }

    public void setCommander(boolean on) {
        commander = on;
        if (!on)
            pick = null;
    }

    /* ---- Who can be selected ---- */

    public SessionContext viewpoint() {
        return sm.getActiveSession();
    }

    /**
     * Who the squad forms up on: rally gathers on it, follow follows it, and plugins take it as the one the others
     * copy. It is the character on screen when the first character is selected and then stays put when the view
     * switches, so looking at another character's inventory doesn't hand over the lead. With nothing selected (or
     * the leader logged out) it is the character on screen.
     */
    public SessionContext leader() {
        String id = leaderId;
        if (id != null && !isEmpty()) {
            SessionContext c = sm.getSession(id);
            if (c != null && c.isConnected())
                return c;
        }
        return viewpoint();
    }

    public void setLeader(SessionContext ctx) {
        if (ctx != null)
            leaderId = ctx.sessionId;
    }

    public boolean isLeader(SessionContext ctx) {
        return ctx != null && ctx == leader();
    }

    /** A selection starting from nothing takes the character on screen as its leader. */
    private void startedSelection(boolean wasEmpty) {
        if (wasEmpty && !isEmpty()) {
            SessionContext vp = viewpoint();
            leaderId = (vp == null) ? null : vp.sessionId;
        }
    }

    private String viewpointGenus() {
        SessionContext vp = viewpoint();
        if (vp == null)
            return null;
        MemberReport r = vp.squadReport;
        if (r != null && r.genus != null)
            return r.genus;
        NGameUI gui = vp.getGameUI();
        return (gui == null) ? null : gui.getGenus();
    }

    /** In the game, reporting, and on the viewpoint's world. */
    public boolean selectable(SessionContext ctx) {
        if (ctx == null || !ctx.isConnected())
            return false;
        MemberReport r = ctx.squadReport;
        String g = viewpointGenus();
        return r != null && r.fresh() && g != null && g.equals(r.genus);
    }

    /** Every selectable session, in session-bar order. */
    public List<SessionContext> candidates() {
        List<SessionContext> out = new ArrayList<>();
        for (SessionContext ctx : sm.getAllSessions())
            if (selectable(ctx))
                out.add(ctx);
        return out;
    }

    /** The selected sessions that can take orders right now, in session-bar order. */
    public List<SessionContext> members() {
        Set<String> sel = selectedIds();
        List<SessionContext> out = new ArrayList<>();
        for (SessionContext ctx : candidates())
            if (sel.contains(ctx.sessionId))
                out.add(ctx);
        return out;
    }

    public synchronized Set<String> selectedIds() {
        return new HashSet<>(selected);
    }

    public synchronized boolean isSelected(SessionContext ctx) {
        return ctx != null && selected.contains(ctx.sessionId);
    }

    public synchronized boolean isEmpty() {
        return selected.isEmpty();
    }

    public void toggle(SessionContext ctx) {
        if (ctx == null)
            return;
        synchronized (this) {
            if (selected.remove(ctx.sessionId))
                return;
        }
        if (selectable(ctx)) {
            boolean wasEmpty;
            synchronized (this) {
                wasEmpty = selected.isEmpty();
                selected.add(ctx.sessionId);
            }
            startedSelection(wasEmpty);
            followSelection(Collections.singletonList(ctx));
        }
    }

    public void select(Collection<SessionContext> ctxs, boolean add) {
        List<String> ids = new ArrayList<>();
        for (SessionContext c : ctxs)
            if (selectable(c))
                ids.add(c.sessionId);
        boolean wasEmpty;
        synchronized (this) {
            wasEmpty = selected.isEmpty();
            if (!add)
                selected.clear();
            selected.addAll(ids);
        }
        startedSelection(wasEmpty);
        followSelection(ctxs);
    }

    /**
     * After selecting, with auto view on: when the character on screen is not in the squad, switch to one that is - the
     * one just picked if possible - so selecting a character is also how you look at it (and its inventory).
     * Deselecting never switches, so the viewpoint can leave the squad and stay where it is. With auto view off the
     * player switches by double-clicking a character instead, which avoids the blink of a switch on every selection.
     */
    private void followSelection(Collection<SessionContext> picked) {
        if (!autoView())
            return;
        List<SessionContext> m = members();
        SessionContext vp = viewpoint();
        if (m.isEmpty() || m.contains(vp))
            return;
        SessionContext to = m.get(0);
        for (SessionContext p : picked) {
            if (m.contains(p)) {
                to = p;
                break;
            }
        }
        sm.switchToSession(to.sessionId, true);
    }

    public synchronized void clear() {
        selected.clear();
    }

    public void selectAll() {
        select(candidates(), false);
    }

    /* ---- Control groups, kept per character so they survive relogs ---- */

    /* Parsed groups, re-read only when the saved string changes: name tags look them up every frame. */
    private static String groupsSrc;
    private static JSONObject groupsParsed = new JSONObject();

    private static synchronized JSONObject groups() {
        Object v = NConfig.getGlobal(NConfig.Key.squadGroups);
        String src = (v instanceof String) ? (String) v : "";
        if (!src.equals(groupsSrc)) {
            groupsSrc = src;
            groupsParsed = new JSONObject();
            if (!src.isEmpty()) {
                try {
                    groupsParsed = new JSONObject(src);
                } catch (org.json.JSONException e) {
                    /* A hand-edited, broken value: start the groups over. */
                }
            }
        }
        return groupsParsed;
    }

    public void assignGroup(int n) {
        JSONArray keys = new JSONArray();
        for (SessionContext ctx : members())
            keys.put(ctx.squadReport.key());
        JSONObject g = groups();
        g.put(Integer.toString(n), keys);
        NConfig.set(NConfig.Key.squadGroups, g.toString());
    }

    /** Select group n; false when it is empty or none of its characters is here. */
    public boolean recallGroup(int n) {
        Set<String> keys = groupKeys(n);
        List<SessionContext> pick = new ArrayList<>();
        for (SessionContext ctx : candidates())
            if (keys.contains(ctx.squadReport.key()))
                pick.add(ctx);
        if (pick.isEmpty())
            return false;
        select(pick, false);
        return true;
    }

    private static Set<String> groupKeys(int n) {
        Set<String> out = new HashSet<>();
        JSONArray a = groups().optJSONArray(Integer.toString(n));
        if (a != null)
            for (int i = 0; i < a.length(); i++)
                out.add(a.optString(i));
        return out;
    }

    /** Lowest group the character is in, or 0. */
    public static int groupOf(MemberReport r) {
        if (r == null)
            return 0;
        JSONObject g = groups();
        for (int n = 1; n <= 9; n++) {
            JSONArray a = g.optJSONArray(Integer.toString(n));
            if (a == null)
                continue;
            for (int i = 0; i < a.length(); i++)
                if (r.key().equals(a.optString(i)))
                    return n;
        }
        return 0;
    }

    /* ---- Orders ---- */

    public Order order() {
        Order o = order;
        return (o != null && System.currentTimeMillis() - o.time < 10000) ? o : null;
    }

    private static NGameUI gui(SessionContext ctx) {
        return (ctx == null) ? null : ctx.getGameUI();
    }

    /** A member's position in the viewpoint's frame, or null when it can't be placed there. */
    public static Coord2d localOf(SessionContext member, NGameUI vp) {
        MemberReport r = member.squadReport;
        if (r == null || r.pos == null || vp == null || vp.map == null)
            return null;
        return r.pos.localIn(vp.map.glob.map, SquadExecutor.sessloc(vp));
    }

    private boolean announceEmpty(NGameUI vp, List<SessionContext> m) {
        if (!m.isEmpty())
            return false;
        if (vp != null)
            vp.msg(L10n.get("squad.msg.none"));
        return true;
    }

    /** Walk the squad to a point in the viewpoint's frame, in formation. */
    public void moveTo(Coord2d target) {
        moveTo(target, members());
    }

    private void moveTo(Coord2d target, List<SessionContext> m) {
        NGameUI vp = gui(viewpoint());
        if (vp == null || vp.map == null || announceEmpty(vp, m))
            return;
        MCache map = vp.map.glob.map;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        List<Coord2d> pos = new ArrayList<>();
        for (SessionContext c : m)
            pos.add(localOf(c, vp));
        List<Coord2d> slots = Formation.slots(pos, target, formation());
        boolean pf = pathfind(), sb = stopBots();
        Map<String, WorldRef> sent = new LinkedHashMap<>();
        for (int i = 0; i < m.size(); i++) {
            WorldRef ref = WorldRef.of(map, sl, slots.get(i));
            if (ref == null)
                continue;
            m.get(i).squadInbox.add(new SquadCommand.Move(ref, pf, sb));
            sent.put(m.get(i).sessionId, ref);
        }
        order = new Order(WorldRef.of(map, sl, target), sent);
    }

    /** Gather everyone else on the leader. */
    public void rally() {
        NGameUI vp = gui(viewpoint());
        SessionContext ld = leader();
        Coord2d at = (ld == viewpoint()) ? playerAt(vp) : (ld == null) ? null : localOf(ld, vp);
        if (at == null) {
            if (vp != null)
                vp.msg(L10n.get("squad.msg.noleader"));
            return;
        }
        moveTo(at, othersThanLeader());
    }

    private static Coord2d playerAt(NGameUI gui) {
        Gob pl = (gui == null || gui.map == null) ? null : gui.map.player();
        return (pl == null) ? null : pl.rc;
    }

    /** Everyone else follows the leader. */
    public void follow() {
        follow(othersThanLeader());
    }

    /** These characters follow the leader. */
    public void follow(Collection<SessionContext> who) {
        NGameUI vp = gui(viewpoint());
        MemberReport lr = leaderReport();
        List<SessionContext> m = new ArrayList<>(who);
        m.remove(leader());
        if (announceEmpty(vp, m))
            return;
        if (lr == null || lr.pos == null) {
            if (vp != null)
                vp.msg(L10n.get("squad.msg.noleader"));
            return;
        }
        boolean pf = pathfind(), sb = stopBots();
        for (SessionContext c : m)
            c.squadInbox.add(new SquadCommand.Follow(lr.plgob, lr.pos, pf, sb));
    }

    private MemberReport leaderReport() {
        SessionContext ld = leader();
        return (ld == null) ? null : ld.squadReport;
    }

    /** The selected characters other than the leader. */
    public List<SessionContext> othersThanLeader() {
        List<SessionContext> m = members();
        m.remove(leader());
        return m;
    }

    public void stop() {
        send(members(), new SquadCommand.Stop());
        order = null;
    }

    /** Post one order to each of these characters. Orders are immutable, so they can share it. */
    public void send(Collection<SessionContext> who, SquadCommand c) {
        for (SessionContext m : who)
            m.squadInbox.add(c);
    }

    /* ---- Plugin orders ---- */

    public void addOrderHandler(SquadOrderHandler h) {
        if (h != null && !handlers.contains(h))
            handlers.add(h);
    }

    public void removeOrderHandler(SquadOrderHandler h) {
        handlers.remove(h);
    }

    List<SquadOrderHandler> orderHandlers() {
        return handlers;
    }

    /** Add a plugin's button to the squad panel (one row, after the built-in ones). */
    public void addToggle(SquadToggle t) {
        if (t != null && !toggles.contains(t))
            toggles.add(t);
    }

    public void removeToggle(SquadToggle t) {
        toggles.remove(t);
    }

    public List<SquadToggle> toggles() {
        return toggles;
    }

    /** Add a section (Markdown, starting with a "# " heading) to the Squad Command help window. */
    public void addHelpSection(String markdown) {
        if (markdown != null && !helpSections.contains(markdown))
            helpSections.add(markdown);
    }

    public List<String> helpSections() {
        return helpSections;
    }

    /** Add a plugin's Commander-mode keys; they get the keys core doesn't use itself. */
    public void addKeyHandler(SquadKeyHandler h) {
        if (h != null && !keyHandlers.contains(h))
            keyHandlers.add(h);
    }

    public void removeKeyHandler(SquadKeyHandler h) {
        keyHandlers.remove(h);
    }

    List<SquadKeyHandler> keyHandlers() {
        return keyHandlers;
    }

    /**
     * A plugin asks the player to click something on the map: until the next click the map shows {@code cursor}
     * (null: the normal one) and the Commander banner shows {@code banner}. A left click on an object hands it to
     * {@code picked} on the viewpoint's UI thread; Esc, or any other click, cancels.
     */
    public void pickTarget(String banner, Resource cursor, java.util.function.Consumer<Gob> picked) {
        pick = (picked == null) ? null : new TargetPick(banner, cursor, picked);
    }

    public TargetPick pendingPick() {
        return pick;
    }

    public void cancelPick() {
        pick = null;
    }

    /** An outstanding {@link #pickTarget} request. */
    public static final class TargetPick {
        public final String banner;
        public final Resource cursor;
        public final java.util.function.Consumer<Gob> picked;

        TargetPick(String banner, Resource cursor, java.util.function.Consumer<Gob> picked) {
            this.banner = banner;
            this.cursor = cursor;
            this.picked = picked;
        }
    }

    public void setSpeed(int speed) {
        for (SessionContext c : members())
            c.squadInbox.add(new SquadCommand.SetSpeed(speed));
    }

    /** Every member right-clicks this gob of the viewpoint. */
    public void interact(Gob gob) {
        NGameUI vp = gui(viewpoint());
        List<SessionContext> m = members();
        if (vp == null || gob == null || gob.ngob == null || announceEmpty(vp, m))
            return;
        WorldRef at = WorldRef.of(vp.map.glob.map, SquadExecutor.sessloc(vp), gob.rc);
        long seq;
        synchronized (this) {
            seq = ++interactSeq;
        }
        Set<String> ids = new HashSet<>();
        boolean pf = pathfind(), sb = stopBots();
        List<Coord2d> sides = spreadAround(gob, m, vp);
        MCache map = vp.map.glob.map;
        MiniMap.Location sl = SquadExecutor.sessloc(vp);
        for (int i = 0; i < m.size(); i++) {
            SessionContext c = m.get(i);
            WorldRef side = (sides == null) ? null : WorldRef.of(map, sl, sides.get(i));
            c.squadInbox.add(new SquadCommand.Interact(gob.id, at, gob.ngob.name, seq, side, pf, sb));
            ids.add(c.sessionId);
        }
        interactMembers = Collections.unmodifiableSet(ids);
        interactUntil = System.currentTimeMillis() + INTERACT_MS;
    }

    /**
     * Each member's own side of an object, so several don't queue for the same spot by it: places on an arc just
     * outside its hitbox, on the squad's side, a tile and a half apart. Null for a single member, or when the
     * members can't be placed.
     */
    private static List<Coord2d> spreadAround(Gob gob, List<SessionContext> m, NGameUI vp) {
        if (m.size() < 2)
            return null;
        List<Coord2d> pos = new ArrayList<>();
        for (SessionContext c : m)
            pos.add(localOf(c, vp));
        if (Formation.centroid(pos) == null)
            return null;
        return Formation.aroundObject(pos, gob.rc, reach(gob), 1.5 * MCache.tilesz.x);
    }

    /** How far from its centre a character stands to touch this gob: its hitbox plus a little. */
    public static double reach(Gob gob) {
        return extent(gob) + 0.6 * MCache.tilesz.x;
    }

    /** Half the size of a gob's hitbox (its widest side), or 0 without one. */
    public static double extent(Gob gob) {
        if (gob.ngob == null || gob.ngob.hitBox == null)
            return 0;
        Coord2d b = gob.ngob.hitBox.begin, e = gob.ngob.hitBox.end;
        return Math.max(Math.max(Math.abs(b.x), Math.abs(e.x)), Math.max(Math.abs(b.y), Math.abs(e.y)));
    }

    private synchronized long currentInteract() {
        return (System.currentTimeMillis() < interactUntil) ? interactSeq : -1;
    }

    /** Send the chosen petal (null = close) to everyone in the current Interact except {@code except}. */
    public void pickFlower(String option, SessionContext except) {
        long seq = currentInteract();
        if (seq < 0)
            return;
        for (SessionContext c : sm.getAllSessions())
            if (c != except && interactMembers.contains(c.sessionId))
                c.squadInbox.add(new SquadCommand.PickFlower(option, seq));
        interactUntil = 0;
    }

    /**
     * The user picked a petal in a real flower menu. When that menu is the viewpoint's share of a squad Interact, the
     * same petal goes to the rest of the squad. The viewpoint's own squad thread choosing (on timeout) is not the user.
     */
    public void onPetal(UI ui, String option) {
        SessionContext vp = viewpoint();
        long seq = currentInteract();
        if (vp == null || vp.ui != ui || seq < 0 || !interactMembers.contains(vp.sessionId))
            return;
        SquadExecutor.State st = vp.squadState;
        if (st.flowerSeq != seq || st.flowerOptions == null || Thread.currentThread() == st.thread)
            return;
        pickFlower(option, vp);
    }

    /**
     * When the viewpoint is not part of the current Interact, the menu options the members got, merged, for a proxy
     * menu; null when there is nothing to show (or it was shown already).
     */
    public List<String> proxyOptions() {
        long seq = currentInteract();
        SessionContext vp = viewpoint();
        if (seq < 0 || proxyShownSeq == seq || vp == null || interactMembers.contains(vp.sessionId))
            return null;
        LinkedHashSet<String> opts = new LinkedHashSet<>();
        for (SessionContext c : sm.getAllSessions()) {
            MemberReport r = c.squadReport;
            if (r != null && r.flowerSeq == seq && r.flowerOptions != null && interactMembers.contains(c.sessionId))
                opts.addAll(r.flowerOptions);
        }
        if (opts.isEmpty())
            return null;
        proxyShownSeq = seq;
        return new ArrayList<>(opts);
    }

    /* ---- SessionChangeListener ---- */

    @Override
    public void onActiveSessionChanged(SessionContext oldSession, SessionContext newSession) {
    }

    @Override
    public void onSessionAdded(SessionContext session) {
    }

    @Override
    public synchronized void onSessionRemoved(SessionContext session) {
        selected.remove(session.sessionId);
    }
}
