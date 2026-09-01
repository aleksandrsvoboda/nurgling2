package nurgling.guarding;

/**
 * A guard's condition - reads live state via {@link GuardContext} and reports whether it
 * currently holds. Must not perform any action or block on anything long-running; that's
 * {@link GuardOutcome}'s job, run separately (and on a different thread) once a trigger fires -
 * see {@link Guard}.
 */
public interface GuardTrigger {
    boolean check(GuardContext ctx) throws InterruptedException;

    /** Short human-readable description of what fired, for the watchdog's chat log message.
     *  Safe to call only after {@link #check} has just returned true - implementations that
     *  need to include live details (e.g. the exact energy percentage) capture them during
     *  check() itself rather than recomputing them here. */
    String describe();
}
