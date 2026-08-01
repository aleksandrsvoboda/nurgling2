package nurgling.watchdog;

import nurgling.NAlarmManager;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.conf.NDiscordNotification;
import nurgling.tasks.NTask;

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
 * <p>Threading: {@link #register}, {@link #taskEnter}, {@link #taskExit},
 * {@link #progress} and {@link #reportStall} run on bot threads; {@link #tick}
 * runs on the UI thread. All shared state is volatile or in a concurrent map.
 */
public class BotWatchdog {
    private static final long DEFAULT_THRESHOLD_MS = 90_000;
    private static final long DEFAULT_AUTO_INTERRUPT_MS = 300_000;

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
     * Record observable progress. Clears a STALLED state, so a bot that
     * recovers on its own reports that it recovered.
     */
    public void progress(String what) {
        progress(current(), what);
    }

    private void progress(BotHealth h, String what) {
        if (h == null)
            return;
        h.lastProgressAt = System.currentTimeMillis();
        if (h.state == BotState.STALLED) {
            h.state = BotState.RUNNING;
            h.stallReason = null;
            h.stalledSince = 0;
            h.autoInterrupted = false;
            NGameUI gui = gui();
            if (gui != null)
                gui.msg("Bot " + h.botName + ": recovered" + (what != null ? " (" + what + ")" : ""));
        } else {
            h.state = BotState.RUNNING;
        }
    }

    /**
     * Explicit stall report from action code that can detect a hang itself
     * (e.g. PathFinder noticing the character isn't moving).
     */
    public void reportStall(String reason) {
        BotHealth h = current();
        if (h != null)
            markStalled(h, reason);
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
                        markStalled(h, "waiting on " + h.currentTask + " for "
                                + BotHealth.fmtDuration(now - taskStart));
                    }
                } else if (now - h.lastProgressAt > defThreshold) {
                    // Not parked on a task at all: an action loop that never finishes anything.
                    markStalled(h, "no progress for " + BotHealth.fmtDuration(now - h.lastProgressAt));
                }
            }

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
