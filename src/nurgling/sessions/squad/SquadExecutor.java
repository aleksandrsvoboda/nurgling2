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
import nurgling.tasks.FindNFlowerMenu;
import nurgling.tasks.NTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        return new MemberReport(gui.getGenus(), name, gui.map.plgob, pos, hp, s, detail,
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
        if (c instanceof SquadCommand.SetSpeed) {
            Speedget sg = gui.speedget;
            if (sg != null)
                sg.set(Math.max(0, Math.min(((SquadCommand.SetSpeed) c).speed, sg.max)));
            return;
        }
        if (c instanceof SquadCommand.Stop) {
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
            start(ctx, st, MemberReport.Status.INTERACTING, new InteractAction((SquadCommand.Interact) c, st));
        } else if (c instanceof SquadCommand.Follow) {
            start(ctx, st, MemberReport.Status.FOLLOWING, new FollowAction((SquadCommand.Follow) c, st));
        }
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

    /** Right-click a gob, then hold any flower menu it opens until the squad picks a petal. */
    static final class InteractAction implements Action {
        private final SquadCommand.Interact cmd;
        private final State st;

        InteractAction(SquadCommand.Interact cmd, State st) {
            this.cmd = cmd;
            this.st = st;
        }

        @Override
        public Results run(NGameUI gui) throws InterruptedException {
            Gob gob = resolveGob(gui, cmd.gobId, cmd.gobPos, cmd.resName, MCache.tilesz.x);
            if (gob == null) {
                st.set(MemberReport.Status.UNREACHABLE, L10n.get("squad.why.noobject"));
                return Results.FAIL();
            }
            if (cmd.pathfind)
                new PathFinder(gob).run(gui);
            st.flowerSeq = cmd.seq;
            st.pickSet = false;
            st.pick = null;
            NUtils.rclickGob(gob);
            FindNFlowerMenu find = new FindNFlowerMenu();
            NUtils.addTask(find);
            NFlowerMenu fm = find.getResult();
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
