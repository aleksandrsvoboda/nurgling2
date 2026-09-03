package nurgling.guarding;

/** A guard's condition - reads live state via {@link GuardContext} and reports whether it currently holds; must never itself perform an action (that's {@link GuardOutcome}, see {@link Guard}). */
public interface GuardTrigger {
    boolean check(GuardContext ctx) throws InterruptedException;

    /** Short human-readable description of what fired, valid only right after {@link #check} returns true. */
    String describe();
}
