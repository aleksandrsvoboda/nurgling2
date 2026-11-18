package nurgling.sessions.squad;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.areas.NGlobalCoord;
import nurgling.i18n.L10n;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.sessions.BotExecutor;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.tasks.NTask;
import nurgling.widgets.MilestoneWindowExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * The member side of the squad. Runs in each session's own {@code NCore.tick}, so everything it touches belongs to
 * that session: it drains the session's inbox, starts the session's squad thread, and publishes its
 * {@link MemberReport}.
 */
public final class SquadExecutor {
    /** Player characters; used to find a character by position when its gob id doesn't match. */
    static final String PLAYER_RES = "gfx/borka/body";
    private static final long REPORT_MS = 200;
    private static final long FLOWER_WAIT_MS = 30000;
    /** How long a milestone window is held for the squad's road pick: reading the roads takes a while. */
    private static final long ROAD_WAIT_MS = 120000;
    /** A position jump this big is a teleport (as in MilestoneTracker). */
    private static final double TELEPORT_DIST = 100;
    /** How long an Unreachable / Skipped verdict stays on the panel. */
    private static final long VERDICT_MS = 10000;

    private SquadExecutor() {}

    /** Per-session squad state. Written by the session's own tick and its squad thread only. */
    public static final class State {
        volatile Thread thread;
        volatile MemberReport.Status status = MemberReport.Status.IDLE;
        volatile String detail;
        volatile long statusTime;
        volatile List<String> flowerOptions;
        volatile long flowerSeq;
        volatile boolean pickSet;
        volatile String pick;
        /** The Interact whose milestone window this session holds open, or -1. */
        volatile long roadSeq = -1;
        /** The latest road picked for the squad; it can arrive while this character is still walking. */
        volatile SquadCommand.PickRoad roadPick;
        volatile boolean dirty;
        long lastReport;

        void set(MemberReport.Status s, String d) {
            status = s;
            detail = d;
            statusTime = System.currentTimeMillis();
            dirty = true;
        }

        void set(MemberReport.Status s) {
            set(s, null);
        }

        /** The squad thread, while it runs. */
        public Thread thread() {
            Thread t = thread;
            return (t != null && t.isAlive()) ? t : null;
        }
    }

    public static void tick(NUI ui) {
        if (ui == null || ui.sess == null)
            return;
        SessionContext ctx = SessionManager.getInstance().findBySession(ui.sess);
        NGameUI gui = ui.gui;
        if (ctx == null || gui == null || gui.map == null)
            return;
        SquadCommand c;
        while ((c = ctx.squadInbox.poll()) != null)
            handle(ctx, gui, c);
        State st = ctx.squadState;
        long now = System.currentTimeMillis();
        if (st.dirty || now - st.lastReport >= REPORT_MS) {
            st.lastReport = now;
            st.dirty = false;
            ctx.squadReport = report(ctx, gui, st, now);
        }
    }

    private static MemberReport report(SessionContext ctx, NGameUI gui, State st, long now) {
        Gob pl = gui.map.player();
        WorldRef pos = (pl == null) ? null : WorldRef.of(gui.map.glob.map, sessloc(gui), pl.rc);
        IMeter.Meter hpm = gui.getmeter("hp", 0);
        double hp = (hpm == null) ? -1 : hpm.a;
        MemberReport.Status s;
        String detail = null;
        String bot = otherBot(gui, st);
        if (ctx.isInCombat()) {
            s = MemberReport.Status.COMBAT;
        } else if (st.thread() != null) {
            s = st.status;
            detail = st.detail;
        } else if (bot != null) {
            s = MemberReport.Status.BOT;
            detail = bot;
        } else if (moving(pl)) {
            s = MemberReport.Status.WALKING;
        } else if ((st.status == MemberReport.Status.UNREACHABLE || st.status == MemberReport.Status.SKIPPED)
                   && now - st.statusTime < VERDICT_MS) {
            s = st.status;
            detail = st.detail;
        } else {
            s = MemberReport.Status.IDLE;
        }
        String name = (gui.chrid == null || gui.chrid.isEmpty()) ? ctx.getDisplayName() : gui.chrid;
        int speed = (gui.speedget == null) ? -1 : gui.speedget.cur;
        return new MemberReport(gui.getGenus(), name, gui.map.plgob, pos, hp, speed, s, detail,
                                st.flowerOptions, st.flowerSeq, now);
    }

    static boolean moving(Gob g) {
        if (g == null)
            return false;
        Moving m = g.getattr(Moving.class);
        return (m instanceof LinMove || m instanceof Following) && m.getv() > 0.01;
    }

    /** Name of a bot this session runs other than its squad thread, or null. */
    static String otherBot(NGameUI gui, State st) {
        if (gui.biw == null)
            return null;
        return gui.biw.firstBotExcept(st.thread);
    }

    /** The session's own place in the shared map segment, from whichever of its minimaps has resolved it. */
    public static MiniMap.Location sessloc(NGameUI gui) {
        if (gui == null)
            return null;
        if (gui.mmap != null && gui.mmap.sessloc != null)
            return gui.mmap.sessloc;
        if (gui.mapfile != null && gui.mapfile.view != null)
            return gui.mapfile.view.sessloc;
        return null;
    }

    private static void handle(SessionContext ctx, NGameUI gui, SquadCommand c) {
        State st = ctx.squadState;
        if (c instanceof SquadCommand.PickFlower) {
            SquadCommand.PickFlower p = (SquadCommand.PickFlower) c;
            if (p.seq == st.flowerSeq) {
                st.pick = p.option;
                st.pickSet = true;
            }
            return;
        }
        if (c instanceof SquadCommand.PickRoad) {
            st.roadPick = (SquadCommand.PickRoad) c;
            return;
        }
        if (c instanceof SquadCommand.SetSpeed) {
            Speedget sg = gui.speedget;
            if (sg != null)
                sg.set(Math.max(0, Math.min(((SquadCommand.SetSpeed) c).speed, sg.max)));
            return;
        }
        if (c instanceof SquadCommand.Stop) {
            /* Plugins hear a stop too (to drop their own jobs), but can't keep core from stopping. */
            offer(ctx, gui, c);
            cancel(st);
            Gob pl = gui.map.player();
            if (pl != null)
                click(gui, pl.rc);
            st.set(MemberReport.Status.IDLE);
            return;
        }
        if (c.takesCharacter()) {
            String bot = otherBot(gui, st);
            if (bot != null) {
                if (!c.stopBots) {
                    st.set(MemberReport.Status.SKIPPED, bot);
                    return;
                }
                gui.biw.interruptAll();
            }
        }
        if (offer(ctx, gui, c)) {
            if (c.takesCharacter()) {
                cancel(st);
                st.set(MemberReport.Status.IDLE);
            }
            return;
        }
        if (c instanceof SquadCommand.Custom)
            return;
        cancel(st);
        if (c instanceof SquadCommand.Move) {
            SquadCommand.Move m = (SquadCommand.Move) c;
            if (!m.pathfind) {
                Coord2d local = m.target.localIn(gui.map.glob.map, sessloc(gui));
                if (local != null) {
                    click(gui, local);
                    st.set(MemberReport.Status.IDLE);
                    return;
                }
            }
            start(ctx, st, MemberReport.Status.PATHING, new MoveAction(m.target, st));
        } else if (c instanceof SquadCommand.Interact) {
            start(ctx, st, MemberReport.Status.INTERACTING, new InteractAction((SquadCommand.Interact) c, st, ctx.sessionId));
        } else if (c instanceof SquadCommand.Follow) {
            start(ctx, st, MemberReport.Status.FOLLOWING, new FollowAction((SquadCommand.Follow) c, st));
        }
    }

    /** Let the plugins' handlers have the order first; true when one carried it out. */
    private static boolean offer(SessionContext ctx, NGameUI gui, SquadCommand c) {
        for (SquadOrderHandler h : SessionManager.getInstance().squad().orderHandlers()) {
            try {
                if (h.take(ctx, gui, c))
                    return true;
            } catch (RuntimeException e) {
                /* A broken plugin must not stop this session's tick; core carries on with the order. */
                e.printStackTrace();
            }
        }
        return false;
    }

    private static void start(SessionContext ctx, State st, MemberReport.Status s, Action a) {
        st.set(s);
        st.thread = BotExecutor.runOn(ctx, "Squad order", a);
    }

    /** Drop the running squad order, if any. */
    private static void cancel(State st) {
        Thread t = st.thread;
        st.thread = null;
        if (t != null)
            t.interrupt();
        st.flowerOptions = null;
        st.dirty = true;
    }

    /**
     * Right-click a gob as NUtils.rclickGob does, but with the screen spot of the player's click: the server centres
     * the windows it opens on it, and NUtils sends (0, 0), the top-left corner, which puts them mostly off screen.
     */
    static void rclickGob(NGameUI gui, Gob gob, Coord screen) {
        Coord pc = (screen == null) ? gui.map.sz.div(2) : screen;
        gui.map.wdgmsg("click", pc, gob.rc.floor(posres), 3, 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
    }

    static void click(NGameUI gui, Coord2d local) {
        gui.map.wdgmsg("click", Coord.z, local.floor(posres), 1, 0);
    }

    /** The gob an order names, looked up in this session: by id, else the nearest gob of that resource at that spot. */
    static Gob resolveGob(NGameUI gui, long id, WorldRef pos, String res, double radius) {
        OCache oc = gui.map.glob.oc;
        Gob g = oc.getgob(id);
        if (g != null && (res == null || (g.ngob != null && res.equals(g.ngob.name))))
            return g;
        Coord2d at = (pos == null) ? null : pos.localIn(gui.map.glob.map, sessloc(gui));
        if (at == null || res == null)
            return null;
        Gob best = null;
        double bd = radius;
        synchronized (oc) {
            for (Gob o : oc) {
                if (o.ngob == null || !res.equals(o.ngob.name))
                    continue;
                double d = o.rc.dist(at);
                if (d <= bd) {
                    bd = d;
                    best = o;
                }
            }
        }
        return best;
    }

    /** Walk to a point: pathfinder when the spot is loaded here, chunk nav when it is not, a direct click last. */
    static final class MoveAction implements Action {
        private final WorldRef target;
        private final State st;

        MoveAction(WorldRef target, State st) {
            this.target = target;
            this.st = st;
        }

        @Override
        public Results run(NGameUI gui) throws InterruptedException {
            MCache map = gui.map.glob.map;
            Coord2d local = target.localIn(map, sessloc(gui));
            if (local != null && PathFinder.isAvailable(local)) {
                st.set(MemberReport.Status.PATHING);
                new PathFinder(local).run(gui);
                st.set(MemberReport.Status.IDLE);
                return Results.SUCCESS();
            }
            NGlobalCoord g = target.global();
            ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
            if (g != null && nav != null && nav.isInitialized()) {
                ChunkPath path = nav.planToGridCoord(g.getGridId(), g.getLocalTile());
                if (path != null) {
                    st.set(MemberReport.Status.CHUNKNAV);
                    nav.navigateWithPath(path, null, gui);
                    Coord2d after = target.localIn(map, sessloc(gui));
                    if (after != null && PathFinder.isAvailable(after))
                        new PathFinder(after).run(gui);
                    st.set(MemberReport.Status.IDLE);
                    return Results.SUCCESS();
                }
            }
            if (local != null) {
                /* No path the pathfinder accepts (the slot may sit in an obstacle): let the server walk straight. */
                click(gui, local);
                st.set(MemberReport.Status.IDLE);
                return Results.SUCCESS();
            }
            st.set(MemberReport.Status.UNREACHABLE, L10n.get("squad.why.notmap"));
            return Results.FAIL();
        }
    }

    /**
     * Right-click a gob, then hold the flower menu or milestone window it opens until the squad picks a petal or a
     * road. A milestone opens from wherever the character stands, so a member walks up to one only when it can't see
     * it, and then stops a few tiles out: the post has one travel spot, which must stay free.
     */
    static final class InteractAction implements Action {
        /** From pressing Travel to the road preview: the walk to the post and the loading. */
        private static final long SCOUT_PREVIEW_MS = 30000, MEMBER_PREVIEW_MS = 15000;
        /** How long the player may look at the scout's preview before deciding. */
        private static final long DECIDE_MS = 5 * 60 * 1000;
        /** A follower's confirming click is repeated this often while its preview stays open. */
        private static final long CONFIRM_RETRY_MS = 3000;
        private static final int CONFIRM_TRIES = 5;
        private static final long ARRIVE_MS = 20000;

        private final SquadCommand.Interact cmd;
        private final State st;
        private final String me;

        InteractAction(SquadCommand.Interact cmd, State st, String me) {
            this.cmd = cmd;
            this.st = st;
            this.me = me;
        }

        @Override
        public Results run(NGameUI gui) throws InterruptedException {
            try {
                return interact(gui);
            } finally {
                /* However this ends, the next member of a milestone trip may go. */
                RoadTrip t = SessionManager.getInstance().squad().trip(cmd.seq);
                if (t != null)
                    t.leave(me);
            }
        }

        private Results interact(NGameUI gui) throws InterruptedException {
            boolean post = MilestoneWindowExtension.isPost(cmd.resName);
            Gob gob = resolveGob(gui, cmd.gobId, cmd.gobPos, cmd.resName, MCache.tilesz.x);
            if (gob == null && post && cmd.pathfind && cmd.approach != null) {
                new MoveAction(cmd.approach, st).run(gui);
                st.set(MemberReport.Status.INTERACTING);
                gob = resolveGob(gui, cmd.gobId, cmd.gobPos, cmd.resName, MCache.tilesz.x);
            }
            if (gob == null) {
                st.set(MemberReport.Status.UNREACHABLE, L10n.get("squad.why.noobject"));
                return Results.FAIL();
            }
            if (cmd.pathfind && !post) {
                /* Its own side of the object first, when it was given one, so the squad doesn't queue for the same
                 * spot; the right-click then walks the last step. */
                Coord2d side = (cmd.approach == null) ? null : cmd.approach.localIn(gui.map.glob.map, sessloc(gui));
                if (side != null && PathFinder.isAvailable(side))
                    new PathFinder(side).run(gui);
                else
                    new PathFinder(gob).run(gui);
            }
            st.flowerSeq = cmd.seq;
            st.pickSet = false;
            st.pick = null;
            FindMenuOrRoad find = new FindMenuOrRoad((NUI) gui.ui, roadWindows(gui));
            rclickGob(gui, gob, cmd.screen);
            NUtils.addTask(find);
            if (find.road != null)
                return pickRoad(gui, find.road, gob);
            NFlowerMenu fm = find.menu;
            if (fm == null) {
                st.set(MemberReport.Status.IDLE);
                return Results.SUCCESS();
            }
            List<String> opts = new ArrayList<>();
            for (NFlowerMenu.NPetal p : fm.nopts)
                opts.add(p.name);
            st.flowerOptions = Collections.unmodifiableList(opts);
            st.dirty = true;
            try {
                NUtils.addTask(new WaitPick(st, (NUI) gui.ui, fm, System.currentTimeMillis() + FLOWER_WAIT_MS));
                boolean open = ((NUI) gui.ui).findInRoot(NFlowerMenu.class) == fm;
                if (open) {
                    String pick = st.pickSet ? st.pick : null;
                    if (pick == null || !fm.chooseOpt(pick)) {
                        if (pick != null)
                            st.set(MemberReport.Status.SKIPPED, L10n.get("squad.why.nooption", pick));
                        else if (!st.pickSet)
                            fm.chooseOpt("");
                        /* chooseOpt with no such petal already closed the menu. */
                    }
                }
            } finally {
                st.flowerOptions = null;
                st.dirty = true;
            }
            if (st.status == MemberReport.Status.INTERACTING)
                st.set(MemberReport.Status.IDLE);
            return Results.SUCCESS();
        }

        /** Hold a milestone window open until the squad picks a road, then take that road from here. */
        private Results pickRoad(NGameUI gui, Window wnd, Gob post) throws InterruptedException {
            st.roadSeq = cmd.seq;
            try {
                NUtils.addTask(new WaitRoad(st, cmd.seq, wnd, System.currentTimeMillis() + ROAD_WAIT_MS));
            } finally {
                st.roadSeq = -1;
            }
            SquadCommand.PickRoad p = st.roadPick;
            if (p == null || p.seq != cmd.seq) {
                closeHidden(gui, wnd);
            } else if (p.trip != null && p.trip.scout.equals(me)) {
                scout(gui, p.trip, post);
            } else if (p.trip != null) {
                travelInTurn(gui, wnd, post, p.trip);
            } else if (p.button != null && wnd.parent != null) {
                Button b = MilestoneWindowExtension.button(wnd, p.road, p.n, p.button);
                if (b != null)
                    b.click();
                else
                    st.set(MemberReport.Status.SKIPPED, L10n.get("squad.why.noroad", p.road));
            }
            if (st.status == MemberReport.Status.INTERACTING || st.status == MemberReport.Status.QUEUED
                || st.status == MemberReport.Status.TRAVELLING)
                st.set(MemberReport.Status.IDLE);
            return Results.SUCCESS();
        }

        /**
         * The player pressed Travel here and decides in this character's preview: go or stay. Watch which it was, so
         * the rest of the squad follows only when this one went.
         */
        private void scout(NGameUI gui, RoadTrip trip, Gob post) throws InterruptedException {
            st.set(MemberReport.Status.TRAVELLING);
            Coord2d from = post.rc;
            long body = gui.map.plgob;
            WaitPreview preview = new WaitPreview(gui, body, from, System.currentTimeMillis() + SCOUT_PREVIEW_MS);
            NUtils.addTask(preview);
            if (!preview.started)
                return;
            NUtils.addTask(new WaitBody(gui, System.currentTimeMillis() + DECIDE_MS));
            trip.scouted(teleported(gui, from));
        }

        /** Wait for this member's turn, then travel the road and confirm the preview on its own. */
        private void travelInTurn(NGameUI gui, Window wnd, Gob post, RoadTrip trip) throws InterruptedException {
            st.set(MemberReport.Status.QUEUED);
            NUtils.addTask(new WaitTurn(trip, me));
            if (trip.turn(me) != RoadTrip.Turn.GO) {
                closeHidden(gui, wnd);
                return;
            }
            st.set(MemberReport.Status.TRAVELLING);
            Window w = (wnd.parent != null) ? wnd : reopen(gui, post);
            Button travel = (w == null) ? null : MilestoneWindowExtension.button(w, trip.road, trip.n, "Travel");
            if (travel == null) {
                st.set(MemberReport.Status.SKIPPED, L10n.get("squad.why.noroad", trip.road));
                closeHidden(gui, w);
                return;
            }
            Coord2d from = post.rc;
            long body = gui.map.plgob;
            travel.click();
            WaitPreview preview = new WaitPreview(gui, body, from, System.currentTimeMillis() + MEMBER_PREVIEW_MS);
            NUtils.addTask(preview);
            if (!preview.started) {
                /* Someone is on the travel spot. Stop trying to get there, so this one doesn't block the next. */
                Gob pl = gui.map.player();
                if (pl != null)
                    click(gui, pl.rc);
                st.set(MemberReport.Status.SKIPPED, L10n.get("squad.why.blocked"));
                closeHidden(gui, w);
                return;
            }
            /* The player approved this road in the scout's preview, so confirm it here: a left-click anywhere does it.
             * Click at the camera's point, the road ball, and again while the preview stays open. */
            for (int i = 0; i < CONFIRM_TRIES && gui.map.plgob < 0; i++) {
                Coord2d at = gui.map.cc;
                click(gui, (at == null) ? Coord2d.z : at);
                NUtils.addTask(new WaitBody(gui, System.currentTimeMillis() + CONFIRM_RETRY_MS));
            }
            NUtils.addTask(new WaitBody(gui, System.currentTimeMillis() + ARRIVE_MS));
            if (!teleported(gui, from))
                st.set(MemberReport.Status.SKIPPED, L10n.get("squad.why.blocked"));
            closeHidden(gui, w);
        }

        /** Right-click the post again for a fresh window; null when none opens. */
        private Window reopen(NGameUI gui, Gob post) throws InterruptedException {
            Gob g = resolveGob(gui, post.id, cmd.gobPos, cmd.resName, MCache.tilesz.x);
            if (g == null)
                return null;
            FindMenuOrRoad find = new FindMenuOrRoad((NUI) gui.ui, roadWindows(gui));
            rclickGob(gui, g, cmd.screen);
            NUtils.addTask(find);
            if (find.menu != null)
                find.menu.chooseOpt("");
            return find.road;
        }

        private static boolean teleported(NGameUI gui, Coord2d from) {
            Gob pl = gui.map.player();
            return pl != null && isBody(pl) && pl.rc.dist(from) > TELEPORT_DIST;
        }
    }

    static boolean isBody(Gob g) {
        return g.ngob != null && PLAYER_RES.equals(g.ngob.name);
    }

    /** Close a milestone window nobody can see, so it can't confuse a later milestone bot. One on screen is the player's. */
    static void closeHidden(NGameUI gui, Window wnd) {
        if (wnd != null && wnd.parent != null && SessionManager.getInstance().getActiveUI() != gui.ui)
            wnd.reqclose();
    }

    /**
     * The road preview has started. Seen in game: about 4 s after Travel the character's gob goes away and the
     * session's player gob becomes -1, so the camera follows the map's own point, the road ball. Confirming brings
     * the same gob id back at the road's end. Another id, resource or a jump away from the post count too.
     */
    static final class WaitPreview extends NTask {
        private final NGameUI gui;
        private final long body;
        private final Coord2d post;
        private final long until;
        boolean started;

        WaitPreview(NGameUI gui, long body, Coord2d post, long until) {
            this.gui = gui;
            this.body = body;
            this.post = post;
            this.until = until;
        }

        @Override
        public boolean check() {
            long id = gui.map.plgob;
            Gob pl = gui.map.player();
            started = id < 0 || id != body || (pl != null && (!isBody(pl) || pl.rc.dist(post) > TELEPORT_DIST));
            return started || System.currentTimeMillis() > until;
        }
    }

    /** The preview is over: the session's player gob is a character again (at the road's end, or still at the post). */
    static final class WaitBody extends NTask {
        private final NGameUI gui;
        private final long until;

        WaitBody(NGameUI gui, long until) {
            this.gui = gui;
            this.until = until;
        }

        @Override
        public boolean check() {
            Gob pl = gui.map.player();
            return (gui.map.plgob >= 0 && pl != null && isBody(pl)) || System.currentTimeMillis() > until;
        }
    }

    static final class WaitTurn extends NTask {
        private final RoadTrip trip;
        private final String me;

        WaitTurn(RoadTrip trip, String me) {
            this.trip = trip;
            this.me = me;
        }

        @Override
        public boolean check() {
            return trip.turn(me) != RoadTrip.Turn.WAIT;
        }
    }

    /** The milestone windows open in this session. */
    static Set<Window> roadWindows(NGameUI gui) {
        Set<Window> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Widget w = gui.child; w != null; w = w.next)
            if (w instanceof Window && MilestoneWindowExtension.isRoadWindow(w))
                out.add((Window) w);
        return out;
    }

    /** Waits, like FindNFlowerMenu, for the flower menu a right-click opens, or for a new milestone window. */
    static final class FindMenuOrRoad extends NTask {
        private final NUI ui;
        private final Set<Window> before;
        private final long startTime = System.currentTimeMillis();
        private final long startFrame = NUtils.getTickId();
        NFlowerMenu menu;
        Window road;

        FindMenuOrRoad(NUI ui, Set<Window> before) {
            this.ui = ui;
            this.before = before;
        }

        @Override
        public boolean check() {
            menu = (NFlowerMenu) ui.findInRoot(NFlowerMenu.class);
            road = null;
            if (menu == null && ui.gui != null) {
                for (Window w : roadWindows(ui.gui)) {
                    if (!before.contains(w)) {
                        road = w;
                        break;
                    }
                }
            }
            return menu != null || road != null || (NUtils.getTickId() - startFrame) > 240
                   || System.currentTimeMillis() - startTime > 4000;
        }
    }

    static final class WaitRoad extends NTask {
        private final State st;
        private final long seq;
        private final Window wnd;
        private final long until;

        WaitRoad(State st, long seq, Window wnd, long until) {
            this.st = st;
            this.seq = seq;
            this.wnd = wnd;
            this.until = until;
        }

        @Override
        public boolean check() {
            SquadCommand.PickRoad p = st.roadPick;
            return (p != null && p.seq == seq) || wnd.parent == null || System.currentTimeMillis() > until;
        }
    }

    static final class WaitPick extends NTask {
        private final State st;
        private final NUI ui;
        private final NFlowerMenu fm;
        private final long until;

        WaitPick(State st, NUI ui, NFlowerMenu fm, long until) {
            this.st = st;
            this.ui = ui;
            this.fm = fm;
            this.until = until;
        }

        @Override
        public boolean check() {
            return st.pickSet || ui.findInRoot(NFlowerMenu.class) != fm || System.currentTimeMillis() > until;
        }
    }

    /** Follow the leader with the game's own Follow petal. */
    static final class FollowAction implements Action {
        private final SquadCommand.Follow cmd;
        private final State st;

        FollowAction(SquadCommand.Follow cmd, State st) {
            this.cmd = cmd;
            this.st = st;
        }

        @Override
        public Results run(NGameUI gui) throws InterruptedException {
            Gob leader = resolveGob(gui, cmd.leaderGob, cmd.leaderPos, PLAYER_RES, 2 * MCache.tilesz.x);
            if (leader == null || leader.id == gui.map.plgob) {
                st.set(MemberReport.Status.UNREACHABLE, L10n.get("squad.why.noleader"));
                return Results.FAIL();
            }
            Results r = new SelectFlowerAction("Follow", leader).run(gui);
            st.set(MemberReport.Status.IDLE);
            return r;
        }
    }
}
