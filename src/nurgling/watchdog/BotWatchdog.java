package nurgling.watchdog;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import nurgling.NAlarmManager;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.conf.NDiscordNotification;
import nurgling.tasks.NTask;
import nurgling.tools.NParser;

import static haven.OCache.posres;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session registry that notices when a bot stops making progress.
 *
 * <p>Bots hang in two ways: they park in {@code NCore.addTask} on an NTask whose
 * condition will never become true, or they spin in an action loop that never
 * completes anything (a character physically stuck against geometry, for
 * instance). Both show up here as "no progress for longer than the threshold".
 *
 * <p>Detection is deliberately non-fatal: a stalled bot keeps running and is only
 * flagged, unless {@link NConfig.Key#botStallAutoInterrupt} is enabled. A false
 * positive therefore costs a red gear, not a broken bot.
 *
 * <p>One stall shape is fixable from here: a character wedged against geometry
 * while trying to walk. Those get a few quiet nudges before the alarm - see
 * {@link #reportMovementStall}. Everything else is a logic bug in the bot, which
 * shaking the character cannot fix and can make worse by disturbing UI state, so
 * it raises the alarm immediately.
 *
 * <p>Threading: {@link #register}, {@link #taskEnter}, {@link #taskExit},
 * {@link #progress} and {@link #reportStall} run on bot threads; {@link #tick}
 * runs on the UI thread. All shared state is volatile or in a concurrent map.
 */
public class BotWatchdog {
    private static final long DEFAULT_THRESHOLD_MS = 90_000;
    private static final long DEFAULT_AUTO_INTERRUPT_MS = 300_000;

    /** Time given to one nudge to take effect before the next one is tried. */
    private static final long RECOVERY_GRACE_MS = 8_000;
    /** Grace before the very first nudge, so a false alarm can be withdrawn. */
    private static final long FIRST_NUDGE_DELAY_MS = 3_000;
    /** Nudges made without saying anything at all. */
    private static final int QUIET_ATTEMPTS = 3;
    private static final int DEFAULT_MAX_ATTEMPTS = 10;
    /** How far the character must leave the nudged-from spot to count as freed. */
    private static final double MOVED_DELTA = 3.0;

    private final NCore core;
    private final Map<Thread, BotHealth> health = new ConcurrentHashMap<>();

    public BotWatchdog(NCore core) {
        this.core = core;
    }

    // ------------------------------------------------------------------ config

    /** Global stall threshold in ms, used for tasks that don't override it. */
    public static long defaultThresholdMs() {
        Object v = NConfig.get(NConfig.Key.botStallTimeout);
        if (v instanceof Number) {
            long ms = ((Number) v).longValue() * 1000L;
            if (ms > 0)
                return ms;
        }
        return DEFAULT_THRESHOLD_MS;
    }

    private static long autoInterruptAfterMs() {
        Object v = NConfig.get(NConfig.Key.botStallAutoInterruptDelay);
        if (v instanceof Number) {
            long ms = ((Number) v).longValue() * 1000L;
            if (ms > 0)
                return ms;
        }
        return DEFAULT_AUTO_INTERRUPT_MS;
    }

    /** How many times the watchdog may try to shake a stuck character loose. */
    private static int maxRecoveryAttempts() {
        Object v = NConfig.get(NConfig.Key.botStallRecoveryAttempts);
        if (v instanceof Number) {
            int n = ((Number) v).intValue();
            if (n >= 0)
                return n;
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private static boolean flag(NConfig.Key key, boolean fallback) {
        Object v = NConfig.get(key);
        return (v instanceof Boolean) ? (Boolean) v : fallback;
    }

    // ---------------------------------------------------------------- registry

    public BotHealth register(Thread t, String botName) {
        BotHealth h = new BotHealth(t, botName);
        health.put(t, h);
        return h;
    }

    public void unregister(Thread t, BotState finalState, String msg) {
        BotHealth h = health.remove(t);
        if (h != null) {
            h.state = finalState;
            h.resultMsg = msg;
        }
    }

    public BotHealth of(Thread t) {
        return health.get(t);
    }

    /** Health of the calling thread, or null when the caller isn't a tracked bot. */
    public BotHealth current() {
        return health.get(Thread.currentThread());
    }

    public Collection<BotHealth> all() {
        return Collections.unmodifiableCollection(health.values());
    }

    public boolean hasStalledBots() {
        for (BotHealth h : health.values()) {
            if (h.isStalled())
                return true;
        }
        return false;
    }

    // ------------------------------------------------------- bot-thread hooks

    /** Called from the bot thread just before it parks on {@code task}. */
    public void taskEnter(NTask task) {
        BotHealth h = current();
        if (h == null)
            return;
        long threshold = task.stallTimeoutMs();
        if (threshold < 0)
            threshold = defaultThresholdMs();
        h.currentTask = simpleName(task);
        h.taskStartedAt = System.currentTimeMillis();
        h.taskThresholdMs = threshold;
        if (h.state == BotState.RUNNING)
            h.state = BotState.WAITING;
    }

    /** Called from the bot thread once it is done waiting on {@code task}. */
    public void taskExit(NTask task) {
        BotHealth h = current();
        if (h == null)
            return;
        h.currentTask = null;
        h.taskStartedAt = 0;
        progress(h, null);
    }

    /**
     * Record observable progress. Ends a stall or recovery episode once the
     * stall signals have stopped, so a bot that gets free reports it.
     */
    public void progress(String what) {
        progress(current(), what);
    }

    private void progress(BotHealth h, String what) {
        if (h == null)
            return;
        long now = System.currentTimeMillis();
        h.lastProgressAt = now;

        boolean wasStuck = h.state == BotState.STALLED || h.state == BotState.RECOVERING;
        if (wasStuck) {
            if (!looksFree(h, now))
                return;
            int attempts = h.recoveryAttempts;
            boolean loud = h.state == BotState.STALLED || attempts > QUIET_ATTEMPTS;
            clearStall(h);
            NGameUI gui = gui();
            if (gui != null && loud) {
                gui.msg("Bot " + h.botName + ": recovered"
                        + (attempts > 0 ? " after " + attempts + " nudge(s)" : "")
                        + (what != null ? " (" + what + ")" : ""));
            }
        }
        h.state = BotState.RUNNING;
    }

    /**
     * Whether a stuck bot can be declared free again.
     *
     * <p>Silence alone is not evidence: when the movement code gives up, the stall
     * reports stop simply because nobody is left to make them, and a wedged
     * character keeps completing tasks either way. So the character also has to
     * have physically left the spot it was nudged from.
     */
    private boolean looksFree(BotHealth h, long now) {
        if (now - h.lastStallSignalAt < RECOVERY_GRACE_MS)
            return false;
        if (h.lastProgressAt <= h.lastStallSignalAt)
            return false;
        Coord2d from = h.nudgeFrom;
        if (from == null)
            return true; // never nudged: nothing to compare against
        NGameUI gui = gui();
        if (gui == null || gui.map == null)
            return false;
        try {
            Gob pl = gui.map.player();
            return (pl != null) && pl.rc.dist(from) > MOVED_DELTA;
        } catch (Loading e) {
            return false;
        }
    }

    private static void clearStall(BotHealth h) {
        h.state = BotState.RUNNING;
        h.stallReason = null;
        h.stalledSince = 0;
        h.autoInterrupted = false;
        h.recoveryAttempts = 0;
        h.nextNudgeAt = 0;
        h.recoveryReason = null;
        h.nudgeFrom = null;
    }

    /**
     * Explicit stall report from action code that detects a hang itself. Raises
     * the alarm straight away: only a character wedged while walking can be
     * helped by the watchdog, see {@link #reportMovementStall}.
     */
    public void reportStall(String reason) {
        BotHealth h = current();
        if (h != null)
            signalStuck(h, reason, false, true);
    }

    /**
     * Report a character that is trying to walk and not getting anywhere - the
     * classic case of being wedged in a bad hitbox. This is the only stall shape
     * self-recovery is offered for. Safe to call repeatedly while the condition
     * holds; it keeps the episode alive.
     */
    public void reportMovementStall(String reason) {
        BotHealth h = current();
        if (h != null)
            signalStuck(h, reason, true, true);
    }

    /**
     * The movement code gave up for good. Recovery failed, so stop pretending
     * otherwise and raise the alarm - silence from here on is not success.
     */
    public void reportRecoveryFailed(String reason) {
        BotHealth h = current();
        if (h == null)
            return;
        h.lastStallSignalAt = System.currentTimeMillis();
        markStalled(h, reason);
    }

    /**
     * Drop an ongoing recovery without a word. For movement code that decides the
     * situation was not a stall after all - the character is already where it
     * needs to be - and that a nudge would now do harm rather than good.
     */
    public void cancelRecovery() {
        BotHealth h = current();
        if (h != null && h.isRecovering()) {
            clearStall(h);
            h.lastStallSignalAt = 0;
        }
    }

    /** True when the calling bot is currently being nudged by the watchdog. */
    public boolean isRecovering() {
        BotHealth h = current();
        return (h != null) && h.isRecovering();
    }

    /**
     * Entry point for every stall detector.
     *
     * <p>{@code recoverable} means the character is stuck walking, which a nudge
     * can plausibly fix. Every other hang - a task waiting on something that will
     * never arrive - is a logic bug that shaking the character does not fix and
     * may make worse, so those go straight to the alarm.
     *
     * <p>{@code keepAlive} marks the reports that are evidence the condition still
     * holds, and so may refresh the signal timestamp of an episode that is already
     * open. The {@link #tick} detectors are not: they fire every frame for as long
     * as the episode lasts, and refreshing from there would keep {@link #looksFree}
     * permanently false, so a nudge that actually worked could never be noticed.
     */
    private void signalStuck(BotHealth h, String reason, boolean recoverable, boolean keepAlive) {
        boolean open = h.state == BotState.STALLED || h.state == BotState.RECOVERING;
        if (!open || keepAlive)
            h.lastStallSignalAt = System.currentTimeMillis();
        if (open)
            return;
        if (recoverable && maxRecoveryAttempts() > 0 && canNudge()) {
            h.state = BotState.RECOVERING;
            h.recoveryReason = reason;
            h.recoveryAttempts = 0;
            // Hold off on the first nudge: the movement code gets a moment to
            // look at the situation and call it off if shaking would be wrong.
            h.nextNudgeAt = h.lastStallSignalAt + FIRST_NUDGE_DELAY_MS;
        } else {
            markStalled(h, reason);
        }
    }

    private static String simpleName(NTask task) {
        String name = task.getClass().getSimpleName();
        if (name.isEmpty()) // anonymous task: fall back to the enclosing class
            name = task.getClass().getName();
        return name;
    }

    // ----------------------------------------------------------- UI-thread tick

    /** Evaluates every tracked bot. Called once per frame from {@link NCore#tick}. */
    public void tick() {
        if (health.isEmpty())
            return;
        long now = System.currentTimeMillis();
        long defThreshold = defaultThresholdMs();

        for (Iterator<Map.Entry<Thread, BotHealth>> it = health.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Thread, BotHealth> e = it.next();
            BotHealth h = e.getValue();

            // Safety net: a thread that died without unregistering.
            if (!h.thread.isAlive()) {
                it.remove();
                continue;
            }
            if (h.state.isTerminal())
                continue;

            if (h.state != BotState.STALLED) {
                long taskStart = h.taskStartedAt;
                if (taskStart != 0) {
                    long threshold = h.taskThresholdMs;
                    if (threshold > 0 && threshold != Long.MAX_VALUE && now - taskStart > threshold) {
                        signalStuck(h, "waiting on " + h.currentTask + " for "
                                + BotHealth.fmtDuration(now - taskStart), false, false);
                    }
                } else if (now - h.lastProgressAt > defThreshold) {
                    // Not parked on a task at all: an action loop that never finishes anything.
                    signalStuck(h, "no progress for "
                            + BotHealth.fmtDuration(now - h.lastProgressAt), false, false);
                }
            }

            if (h.state == BotState.RECOVERING && now >= h.nextNudgeAt)
                stepRecovery(h, now);

            if (h.isStalled() && !h.autoInterrupted
                    && flag(NConfig.Key.botStallAutoInterrupt, false)
                    && h.stalledForMs() > autoInterruptAfterMs()) {
                h.autoInterrupted = true;
                NGameUI gui = gui();
                if (gui != null) {
                    gui.error("Bot " + h.botName + ": auto-interrupted after "
                            + BotHealth.fmtDuration(h.stalledForMs()) + " stalled");
                    gui.biw.removeObserve(h.thread);
                } else {
                    h.thread.interrupt();
                }
            }
        }
    }

    // -------------------------------------------------------- self-recovery

    /**
     * One step of the recovery ladder, run on the UI thread: check whether the
     * previous nudge worked, then either nudge again or give up and call for help.
     */
    private void stepRecovery(BotHealth h, long now) {
        // Nothing has reported this bot as stuck for a while and it did finish
        // something in the meantime: it is moving again.
        if (looksFree(h, now)) {
            int attempts = h.recoveryAttempts;
            clearStall(h);
            NGameUI gui = gui();
            if (gui != null && attempts > QUIET_ATTEMPTS)
                gui.msg("Bot " + h.botName + ": recovered after " + attempts + " nudge(s)");
            return;
        }

        int max = maxRecoveryAttempts();
        if (h.recoveryAttempts >= max || !canNudge()) {
            markStalled(h, h.recoveryReason
                    + (h.recoveryAttempts > 0 ? " (still stuck after " + h.recoveryAttempts + " nudges)" : ""));
            return;
        }

        nudge(h);
        h.recoveryAttempts++;
        h.nextNudgeAt = now + RECOVERY_GRACE_MS;

        // The first few tries stay completely silent - most wedges come free on
        // their own and there is nothing worth interrupting the user for.
        if (h.recoveryAttempts > QUIET_ATTEMPTS) {
            NGameUI gui = gui();
            if (gui != null)
                gui.msg("Bot " + h.botName + ": stuck, trying to shake loose ("
                        + h.recoveryAttempts + "/" + max + ")");
        }
    }

    /** True when nudging the character is possible and safe to do right now. */
    private boolean canNudge() {
        NGameUI gui = gui();
        if (gui == null || gui.map == null)
            return false;
        try {
            if (gui.map.player() == null)
                return false;
        } catch (Loading e) {
            return false;
        }
        // An item on the cursor would be dropped on the ground by the nudge click,
        // and nothing would ever pick it back up.
        if (gui.vhand != null)
            return false;
        // A non-default cursor means the character is in a mode - placing a
        // building, aiming - where a map click carries out that action instead of
        // just walking. Leave those alone entirely.
        String curs = (core.ui.root != null) ? core.ui.root.cursorRes : null;
        if (curs != null && !NParser.checkName(curs, "arw"))
            return false;
        // Never walk the character around during a fight; that is a situation
        // where the user genuinely has to look at the screen.
        return gui.fv == null || gui.fv.current == null;
    }

    /**
     * Click a short distance away from the character, the way a player pokes a
     * stuck character loose by hand. Direction rotates and distance grows with
     * each attempt, so a blocked side does not get retried forever.
     */
    private void nudge(BotHealth h) {
        NGameUI gui = gui();
        if (gui == null || gui.map == null || !canNudge())
            return;
        Gob pl;
        try {
            pl = gui.map.player();
        } catch (Loading e) {
            return;
        }
        if (pl == null)
            return;

        int n = h.recoveryAttempts;
        Coord2d from = pl.rc;
        // Eight compass directions, offset by half a step on each full turn.
        double ang = (Math.PI / 4) * (n % 8) + (Math.PI / 8) * (n / 8);
        double dist = MCache.tilesz.x * (1 + Math.min(n, 4) * 0.5);
        Coord2d dst = from.add(Math.cos(ang) * dist, Math.sin(ang) * dist);
        h.nudgeFrom = from;

        // From the fourth attempt on, cancel whatever the character is doing
        // first - a queued action can be what is holding it in place.
        if (n >= QUIET_ATTEMPTS)
            gui.map.wdgmsg("click", Coord.z, from.floor(posres), 3, 0);
        gui.map.wdgmsg("click", Coord.z, dst.floor(posres), 1, 0);
    }

    /** Transition into STALLED, firing the configured notifications exactly once. */
    private void markStalled(BotHealth h, String reason) {
        if (h.isStalled())
            return;
        h.state = BotState.STALLED;
        h.stallReason = reason;
        h.stalledSince = System.currentTimeMillis();
        h.autoInterrupted = false;

        String msg = "Bot " + h.botName + " STALLED: " + reason;
        NGameUI gui = gui();
        if (gui != null)
            gui.error(msg);

        if (flag(NConfig.Key.botStallAlarm, true))
            NAlarmManager.play("alarm/alarm");

        if (flag(NConfig.Key.botStallDiscord, false) && gui != null) {
            NDiscordNotification discord = NDiscordNotification.get("general");
            if (discord != null && discord.webhookUrl != null && !discord.webhookUrl.isEmpty())
                gui.msgToDiscord(discord, msg);
        }

        // Revive the previously unused SessionContext status fields so the
        // session bar and any future consumers can see which bot is stuck.
        try {
            nurgling.sessions.SessionManager sm = nurgling.sessions.SessionManager.getInstance();
            if (sm != null && core.ui != null) {
                nurgling.sessions.SessionContext ctx = sm.findByUI(core.ui);
                if (ctx != null)
                    ctx.setCurrentBotName(h.botName);
            }
        } catch (Exception ignored) {
            // Session bookkeeping is best-effort; never let it break detection.
        }
    }

    private NGameUI gui() {
        return (core.ui != null) ? core.ui.gui : null;
    }
}
