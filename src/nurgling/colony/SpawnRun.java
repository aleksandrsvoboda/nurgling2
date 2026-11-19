package nurgling.colony;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.UILoop;
import haven.error.FileLogger;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.colony.actions.BuildBeacon;
import nurgling.colony.actions.LightBeacon;
import nurgling.colony.spawn.CreateColonist;
import nurgling.i18n.L10n;
import nurgling.sessions.BackgroundLogin;
import nurgling.sessions.BotExecutor;
import nurgling.sessions.SessionContext;
import nurgling.sessions.squad.SquadExecutor;
import nurgling.sessions.squad.WorldRef;
import nurgling.widgets.charsel.NCharselScreen;
import haven.Widget;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * One "Spawn colonist" run (Phase 2: a carpenter and the materials are already at hand). On its own thread:
 * <ol>
 *   <li>the builder builds a Wilderness Beacon by home, from what it carries and base storage;</li>
 *   <li>the chosen account logs in in the background and waits at character select;</li>
 *   <li>only then the builder lights the beacon with a fresh secret (it burns an hour, or until one spawn);</li>
 *   <li>the account creates a character with that secret, which lands at the beacon;</li>
 *   <li>the builder sees the new Hearth Fire where the beacon stood.</li>
 * </ol>
 * Stop interrupts the run and whatever the builder is doing for it. The secret is never logged; it is shown in the
 * Colony panel only when a run fails while the beacon burns, so the character can still be made by hand.
 */
public final class SpawnRun {
    public enum Phase { BUILD, LOGIN, LIGHT, CREATE, VERIFY, DONE, FAILED, STOPPED }

    private static final long CHARSEL_MS = 90000;
    private static final long VERIFY_MS = 60000;

    /** Everything a run needs, checked by {@link Colony#prepare} before it starts. */
    public static final class Plan {
        final SessionContext builder;
        final String account;
        final String world;
        final String name;
        final WorldRef home;

        Plan(SessionContext builder, String account, String world, String name, WorldRef home) {
            this.builder = builder;
            this.account = account;
            this.world = world;
            this.name = name;
            this.home = home;
        }
    }

    private final Colony colony;
    private final Plan plan;
    private final UILoop loop;
    private final Thread thread;
    private volatile Phase phase = Phase.BUILD;
    private volatile String text = "";
    private volatile String secretNote;
    private volatile Thread bot;
    private volatile Supplier<String> live;

    SpawnRun(Colony colony, Plan plan, UILoop loop) {
        this.colony = colony;
        this.plan = plan;
        this.loop = loop;
        this.thread = new Thread(this::main, "Colony spawn");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    public Phase phase() {
        return phase;
    }

    public boolean running() {
        return thread.isAlive();
    }

    /** What the run is doing or how it ended, for the panel. */
    public String text() {
        Supplier<String> l = live;
        String s = (l == null) ? null : l.get();
        return (s == null) ? text : text + " · " + s;
    }

    /** The lit beacon's secret, shown only after a failure while it burns. */
    public String secretNote() {
        return secretNote;
    }

    public void stop() {
        Thread b = bot;
        if (b != null)
            b.interrupt();
        thread.interrupt();
    }

    private void set(Phase p, String key, Object... args) {
        phase = p;
        text = L10n.get(key, args);
        FileLogger.log("[Colony spawn] " + p + ": " + text);
    }

    private void main() {
        try {
            run();
        } catch (InterruptedException e) {
            /* Stop: the end of this run's own thread. */
            Thread b = bot;
            if (b != null)
                b.interrupt();
            set(Phase.STOPPED, "colony.run.stopped");
        } catch (Failure f) {
            set(Phase.FAILED, "colony.run.failed", f.getMessage());
        } finally {
            live = null;
            colony.storage.stale();
            colony.invalidate();
        }
        String lit = litSecret;
        if (phase != Phase.DONE && lit != null)
            secretNote = L10n.get("colony.run.secret", lit);
    }

    /** A step failed; the message is shown as it is. */
    private static final class Failure extends Exception {
        Failure(String msg) {
            super(msg);
        }
    }

    /* The secret is kept here until lit, so a failure after lighting can show it. */
    private volatile String litSecret;

    private void run() throws InterruptedException, Failure {
        /* 1. The beacon. */
        set(Phase.BUILD, "colony.run.build", plan.builder.getDisplayName());
        BuildBeacon bb = new BuildBeacon(plan.home, colony.storage);
        live = () -> bb.step;
        onBuilder("colony_beacon", bb, () -> bb.problem);
        WorldRef site = bb.site;
        if (site == null)
            throw new Failure(L10n.get("colony.err.nosite"));

        /* 2. The account, at character select. */
        set(Phase.LOGIN, "colony.run.login", plan.account);
        live = null;
        SessionContext acc;
        try {
            acc = BackgroundLogin.login(loop, plan.account);
        } catch (BackgroundLogin.Failed f) {
            throw new Failure(L10n.get("colony.err.login", f.getMessage()));
        }
        if (!ColonyWaits.until(CHARSEL_MS, () -> charselReady(acc)))
            throw new Failure(L10n.get("colony.err.charsel"));

        /* 3. Light it, now that the account is ready: the beacon only burns for an hour. */
        set(Phase.LIGHT, "colony.run.light");
        String secret = secret();
        LightBeacon lb = new LightBeacon(site, secret);
        onBuilder("colony_light", lb, () -> lb.problem);
        litSecret = secret;

        /* 4. The character. */
        set(Phase.CREATE, "colony.run.create", plan.name, plan.account);
        String[] step = {null};
        live = () -> step[0];
        String made;
        try {
            made = new CreateColonist(acc.ui, plan.world, plan.name, secret, s -> step[0] = L10n.get("colony.cstep." + s)).run();
        } catch (CreateColonist.Failed f) {
            throw new Failure(L10n.get("colony.err.create", f.getMessage()));
        }
        live = null;

        /* 5. It landed where the beacon stood. */
        set(Phase.VERIFY, "colony.run.verify", made);
        if (!ColonyWaits.until(VERIFY_MS, () -> landed(site)))
            throw new Failure(L10n.get("colony.err.verify", made));
        NGameUI ng = acc.ui.gui;
        if (ng != null)
            colony.config.rememberWorld(ng.getGenus(), plan.world);
        colony.config.setNextName(ColonyConfig.following(made));
        set(Phase.DONE, "colony.run.done", made, plan.account);
    }

    /** Runs {@code a} on the builder's session, as a bot it can be stopped from, and waits for it. */
    private void onBuilder(String name, Action a, Supplier<String> problem) throws InterruptedException, Failure {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        Action wrapped = gui -> {
            Results r = a.run(gui);
            ok[0] = r.IsSuccess();
            return r;
        };
        Thread t = BotExecutor.runWithSupportsOn(plan.builder, name, wrapped, false, done::countDown);
        if (t == null)
            throw new Failure(L10n.get("colony.err.builder"));
        bot = t;
        try {
            done.await();
        } finally {
            bot = null;
        }
        if (!ok[0]) {
            String p = problem.get();
            throw new Failure((p != null) ? p : L10n.get("colony.err.builderstopped"));
        }
    }

    private static boolean charselReady(SessionContext ctx) {
        if (ctx.ui == null)
            return false;
        synchronized (ctx.ui) {
            for (Widget w = ctx.ui.root.child; w != null; w = w.next)
                if (w instanceof NCharselScreen && ((NCharselScreen) w).newCharButton() != null)
                    return true;
        }
        return false;
    }

    /** The builder sees a Hearth Fire where the beacon stood, and no beacon. */
    private boolean landed(WorldRef site) {
        NGameUI gui = plan.builder.getGameUI();
        if (gui == null || gui.map == null)
            return false;
        Coord2d at = site.localIn(gui.map.glob.map, SquadExecutor.sessloc(gui));
        if (at == null)
            return false;
        boolean fire = false, beacon = false;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g.ngob == null || g.ngob.name == null || g.rc.dist(at) > 2 * MCache.tilesz.x)
                    continue;
                if (g.ngob.name.equals("gfx/terobjs/pow"))
                    fire = true;
                else if (g.ngob.name.equals(BuildBeacon.BEACON))
                    beacon = true;
            }
        }
        return fire && !beacon;
    }

    private static String secret() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++)
            sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }
}
