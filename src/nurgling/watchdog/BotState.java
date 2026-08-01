package nurgling.watchdog;

/**
 * Lifecycle state of a single bot thread, as tracked by {@link BotWatchdog}.
 */
public enum BotState {
    /** Thread is alive and executing action code (not parked on an NTask). */
    RUNNING,
    /** Thread is parked in NCore.addTask waiting for an NTask to complete. */
    WAITING,
    /** No progress for longer than the configured threshold. Non-fatal by default. */
    STALLED,
    /** Action returned successfully, or was interrupted by the user. */
    FINISHED,
    /** Action returned a non-success Results. */
    FAILED,
    /** Action threw an unhandled exception. */
    ERROR;

    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == ERROR;
    }
}
