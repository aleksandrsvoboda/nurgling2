package nurgling.sessions.multilogin;

import haven.Bootstrap;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.UI;
import haven.UILoop;
import haven.Widget;
import nurgling.NCharlist;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.conf.NCharTags;
import nurgling.sessions.BackgroundLogin;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.widgets.charsel.NCharselScreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * One "Log in to several" run: every account logs in at the same time, in the background (see
 * {@link BackgroundLogin}), plays its picked character there, and the window switches to the first
 * account in the list as soon as that one is in game. The others keep loading in their session tabs.
 *
 * A run can also only read character lists: it logs in, waits for the list, remembers it and closes
 * the session again - nothing on screen changes.
 *
 * Every failure is per account and leaves the others alone. Stop interrupts every login; sessions
 * already logged in are kept.
 */
public final class LoginRun {

    /** Where an account is. */
    public enum State { WAITING, CONNECTING, LIST, PLAYING, LOADING, IN_GAME, READ, FAILED, STOPPED }

    /** One account of the run. */
    public static final class Entry {
        public final String account;
        /** The character to play, or null to only read the character list. */
        public final String character;
        private volatile State state = State.WAITING;
        private volatile String why = null;
        private volatile SessionContext ctx;

        Entry(String account, String character) {
            this.account = account;
            this.character = character;
        }

        public State state() {
            return state;
        }

        /** Why it failed, or null. */
        public String why() {
            return why;
        }

        boolean done() {
            State s = state;
            return s == State.IN_GAME || s == State.READ || s == State.FAILED || s == State.STOPPED;
        }
    }

    private static final long STAGGER_MS = 500, RETRY_MS = 3_000;
    private static final long LIST_MS = 60_000, LIST_STEADY_MS = 1_000, LIST_SETTLE_MS = 15_000;
    private static final long GAME_MS = 90_000, BUILD_MS = 30_000, SETTLE_MS = 5_000;
    /* One game UI is built at a time. Logging in, the character lists and loading the world all run
     * in parallel, but the game UI's widgets were written for one being built at a time (a normal
     * login never builds two at once) and share state such as the config's preset lists. */
    private final Semaphore building = new Semaphore(1);

    private final UILoop loop;
    private final List<Entry> entries;
    private final int maxAtOnce;
    private final List<Thread> workers = Collections.synchronizedList(new ArrayList<>());
    private final Thread main;
    private volatile boolean switched = false;
    /* The session to show, handed to the login screen's own tick (see takeShow). */
    private volatile SessionContext show;
    private volatile long showAt;
    private static final long SHOW_MS = 5_000;

    /**
     * @param picks     account -> character (null character: only read its list), in run order
     * @param maxAtOnce most logins in flight at once; 0 = all
     */
    LoginRun(UILoop loop, List<Entry> picks, int maxAtOnce) {
        this.loop = loop;
        this.entries = Collections.unmodifiableList(new ArrayList<>(picks));
        this.maxAtOnce = (maxAtOnce <= 0) ? Math.max(1, picks.size()) : maxAtOnce;
        this.main = new Thread(this::runMain, "LoginRun");
        this.main.setDaemon(true);
    }

    static Entry entry(String account, String character) {
        return new Entry(account, character);
    }

    void start() {
        main.start();
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean running() {
        return main.isAlive();
    }

    /** True when the run only reads character lists. */
    public boolean readOnly() {
        for (Entry e : entries) {
            if (e.character != null)
                return false;
        }
        return true;
    }

    /**
     * The session the window should switch to now, once; called from the login screen's tick, so the
     * switch happens on the UI thread exactly as a click on its session tab would. A request the
     * login screen did not pick up within a few seconds (the user went elsewhere) lapses.
     */
    public SessionContext takeShow() {
        SessionContext s = show;
        if (s == null)
            return null;
        show = null;
        return ((System.currentTimeMillis() - showAt < SHOW_MS) && connected(s)) ? s : null;
    }

    public void stop() {
        main.interrupt();
        synchronized (workers) {
            for (Thread t : workers)
                t.interrupt();
        }
    }

    /* ---------------------------------------------------------------- the run */

    private void runMain() {
        Semaphore slots = new Semaphore(maxAtOnce);
        try {
            boolean first = true;
            for (Entry e : entries) {
                slots.acquire();
                if (!first)
                    Waits.pause(STAGGER_MS);
                first = false;
                Thread t = new Thread(() -> {
                    try {
                        account(e);
                    } finally {
                        slots.release();
                    }
                }, "LoginRun-" + e.account);
                t.setDaemon(true);
                workers.add(t);
                t.start();
                showFirstReady();
            }
            Waits.until(Long.MAX_VALUE / 2, () -> {
                showFirstReady();
                return allDone();
            });
            showFirstReady();
            System.out.println("[LoginRun] done: " + summary());
        } catch (InterruptedException e) {
            /* Stop: the workers were interrupted too and mark their own accounts. */
            for (Entry en : entries) {
                if (en.state == State.WAITING)
                    en.state = State.STOPPED;
            }
            System.out.println("[LoginRun] stopped: " + summary());
        }
    }

    private boolean allDone() {
        for (Entry e : entries) {
            if (!e.done())
                return false;
        }
        return true;
    }

    /**
     * Shows the first account in list order that made it into the game, once every account before it
     * has failed - but only while the window still shows the login screen this run started from, so a
     * session the user switched to by hand meanwhile is never taken away.
     */
    private void showFirstReady() {
        if (switched || readOnly())
            return;
        for (Entry e : entries) {
            State s = e.state;
            if (s == State.FAILED || s == State.STOPPED)
                continue;
            if (s != State.IN_GAME || e.ctx == null)
                return;
            switched = true;
            UI cur = loop.ui;
            if (cur != null && cur.rcvr instanceof Bootstrap) {
                showAt = System.currentTimeMillis();
                show = e.ctx;
            }
            return;
        }
    }

    /** One account, on its own thread. */
    private void account(Entry e) {
        try {
            if (SessionManager.getInstance().findByUsername(e.account) != null) {
                fail(e, "multilogin.why.online");
                return;
            }
            e.state = State.CONNECTING;
            SessionContext ctx = login(e);
            e.ctx = ctx;
            e.state = State.LIST;
            NCharlist list = charlist(ctx);
            if (list == null) {
                close(ctx);
                fail(e, connected(ctx) ? "multilogin.why.nolist" : "multilogin.why.closed");
                return;
            }
            List<String> names = settledNames(list);
            if (!names.isEmpty())
                NCharTags.setKnown(e.account, names);
            if (e.character == null) {
                close(ctx);
                e.state = State.READ;
                return;
            }
            if (!names.contains(e.character)) {
                close(ctx);
                fail(e, "multilogin.why.notlisted");
                return;
            }
            e.state = State.PLAYING;
            building.acquire();
            try {
                synchronized (ctx.ui) {
                    list.wdgmsg("play", e.character);
                }
                /* Built once the map view is in and the UI has worked through what the server sent. */
                Waits.until(BUILD_MS, () -> gameBuilt(ctx) || !connected(ctx));
                Waits.until(SETTLE_MS, () -> ctx.ui.queue.isIdle() || !connected(ctx));
            } finally {
                building.release();
            }
            e.state = State.LOADING;
            if (!Waits.until(GAME_MS, () -> inGame(ctx) || !connected(ctx))) {
                fail(e, "multilogin.why.nogame");
                e.why = e.why + " (" + missing(ctx) + ")";
                System.out.println("[LoginRun] " + e.account + ": still missing " + missing(ctx));
                return;
            }
            if (!connected(ctx)) {
                fail(e, "multilogin.why.closed");
                return;
            }
            e.state = State.IN_GAME;
        } catch (BackgroundLogin.Failed f) {
            e.why = f.getMessage();
            e.state = State.FAILED;
        } catch (InterruptedException ie) {
            /* Stop, at the top of this account's own thread, which ends here. A session already
             * logged in is kept; the user can close its tab. */
            if (!e.done())
                e.state = State.STOPPED;
        } finally {
            /* Anything unexpected (it is printed by the thread) still ends the account as failed. */
            if (!e.done()) {
                e.why = nurgling.i18n.L10n.get("multilogin.why.error");
                e.state = State.FAILED;
            }
        }
    }

    /** Logs in, trying once more after a moment unless the server refused the saved login. */
    private SessionContext login(Entry e) throws BackgroundLogin.Failed, InterruptedException {
        try {
            return BackgroundLogin.login(loop, e.account);
        } catch (BackgroundLogin.Failed f) {
            if (f.refused || Bootstrap.gettoken(e.account, BackgroundLogin.confname()) == null)
                throw f;
            System.out.println("[LoginRun] " + e.account + ": " + f.getMessage() + "; trying again");
            Waits.pause(RETRY_MS);
            return BackgroundLogin.login(loop, e.account);
        }
    }

    private void fail(Entry e, String key) {
        e.why = nurgling.i18n.L10n.get(key);
        e.state = State.FAILED;
        System.out.println("[LoginRun] " + e.account + ": " + e.why);
    }

    private static boolean connected(SessionContext ctx) {
        return ctx.isConnected() && SessionManager.getInstance().getSession(ctx.sessionId) != null;
    }

    private static void close(SessionContext ctx) {
        SessionManager.getInstance().removeSession(ctx.sessionId);
    }

    /** The session's character list once it shows, or null. */
    private static NCharlist charlist(SessionContext ctx) throws InterruptedException {
        NCharlist[] found = {null};
        Waits.until(LIST_MS, () -> {
            if (!connected(ctx))
                return true;
            NUI ui = ctx.ui;
            if (ui == null)
                return false;
            synchronized (ui) {
                found[0] = find(ui.root, NCharlist.class);
            }
            return found[0] != null;
        });
        return found[0];
    }

    /** The names on the list once it has stopped growing for a second (at most 15 s). */
    private static List<String> settledNames(NCharlist list) throws InterruptedException {
        long[] since = {System.currentTimeMillis()};
        int[] count = {-1};
        Waits.until(LIST_SETTLE_MS, () -> {
            int n = NCharselScreen.characterNames(list).size();
            if (n != count[0]) {
                count[0] = n;
                since[0] = System.currentTimeMillis();
            }
            return (n > 0) && (System.currentTimeMillis() - since[0] >= LIST_STEADY_MS);
        });
        return NCharselScreen.characterNames(list);
    }

    private static boolean gameBuilt(SessionContext ctx) {
        NUI ui = ctx.ui;
        NGameUI g = (ui == null) ? null : ui.gui;
        return (g != null) && (g.map != null);
    }

    /** True once the session's game UI is up with the player and the map grid it stands on. */
    private static boolean inGame(SessionContext ctx) {
        NUI ui = ctx.ui;
        NGameUI g = (ui == null) ? null : ui.gui;
        if (g == null || g.map == null)
            return false;
        Gob pl = g.map.player();
        if (pl == null)
            return false;
        try {
            g.ui.sess.glob.map.getgridt(pl.rc.floor(MCache.tilesz));
            return true;
        } catch (Loading l) {
            return false;
        }
    }

    /** Which part of "in game" has not come yet, for the failure message. */
    private static String missing(SessionContext ctx) {
        NUI ui = ctx.ui;
        NGameUI g = (ui == null) ? null : ui.gui;
        if (g == null)
            return "no game UI";
        if (g.map == null)
            return "no map view";
        Gob pl = g.map.player();
        if (pl == null)
            return "no player";
        try {
            g.ui.sess.glob.map.getgridt(pl.rc.floor(MCache.tilesz));
            return "nothing";
        } catch (Loading l) {
            return "map grid under the player not loaded";
        }
    }

    private static <T extends Widget> T find(Widget w, Class<T> cl) {
        if (w == null)
            return null;
        for (Widget c = w.child; c != null; c = c.next) {
            if (cl.isInstance(c))
                return cl.cast(c);
            T r = find(c, cl);
            if (r != null)
                return r;
        }
        return null;
    }

    /** "3 in game, 1 failed" - for the console and the panel. */
    public String summary() {
        int[] n = new int[State.values().length];
        for (Entry e : entries)
            n[e.state.ordinal()]++;
        List<String> parts = new ArrayList<>();
        State[] shown = {State.IN_GAME, State.READ, State.FAILED, State.STOPPED};
        for (State s : shown) {
            if (n[s.ordinal()] > 0)
                parts.add(nurgling.i18n.L10n.get("multilogin.count." + s.name().toLowerCase(java.util.Locale.ROOT), n[s.ordinal()]));
        }
        int busy = entries.size() - n[State.IN_GAME.ordinal()] - n[State.READ.ordinal()] - n[State.FAILED.ordinal()] - n[State.STOPPED.ordinal()];
        if (busy > 0)
            parts.add(nurgling.i18n.L10n.get("multilogin.count.busy", busy));
        return String.join(", ", parts);
    }
}
