package nurgling.watchdog;

/**
 * Health record for one bot thread. Written from the bot thread (task enter/exit,
 * explicit stall reports) and from the UI thread (state transitions in
 * {@link BotWatchdog#tick()}), so every mutable field is volatile.
 */
public class BotHealth {
    public final Thread thread;
    public final String botName;
    public final long startedAt;

    /** Simple class name of the NTask this bot is parked on, or null. */
    public volatile String currentTask = null;
    /** When the current task started waiting, or 0 when not waiting. */
    public volatile long taskStartedAt = 0;
    /** Resolved stall threshold for the current task, in ms. */
    public volatile long taskThresholdMs = 0;
    /** Last time this bot did something observable (completed a task). */
    public volatile long lastProgressAt;

    public volatile BotState state = BotState.RUNNING;
    public volatile String stallReason = null;
    public volatile long stalledSince = 0;
    /** Set once the bot reaches a terminal state: result message or exception text. */
    public volatile String resultMsg = null;
    /** True once the automatic interrupt has fired, so it only fires once. */
    public volatile boolean autoInterrupted = false;

    public BotHealth(Thread thread, String botName) {
        this.thread = thread;
        this.botName = botName;
        this.startedAt = System.currentTimeMillis();
        this.lastProgressAt = this.startedAt;
    }

    public boolean isStalled() {
        return state == BotState.STALLED;
    }

    public long stalledForMs() {
        return (stalledSince == 0) ? 0 : System.currentTimeMillis() - stalledSince;
    }

    /** How long the bot has gone without observable progress. */
    public long idleForMs() {
        return System.currentTimeMillis() - lastProgressAt;
    }

    /** Human-readable duration, e.g. "2m14s" or "47s". */
    public static String fmtDuration(long ms) {
        long s = ms / 1000;
        if (s < 60)
            return s + "s";
        return (s / 60) + "m" + (s % 60) + "s";
    }

    /** One-line summary for tooltips and the debug overlay. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Bot: ").append(botName);
        sb.append(" | ").append(state);
        if (state == BotState.STALLED) {
            sb.append(' ').append(fmtDuration(stalledForMs()));
            if (stallReason != null)
                sb.append(" | ").append(stallReason);
        } else if (currentTask != null) {
            sb.append(" | ").append(currentTask)
              .append(' ').append(fmtDuration(System.currentTimeMillis() - taskStartedAt));
        }
        if (resultMsg != null)
            sb.append(" | ").append(resultMsg);
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
