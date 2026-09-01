package nurgling.guarding;

/**
 * One configured guard: a {@link GuardTrigger} (what to watch for) paired with the
 * {@link GuardOutcome} to perform when it fires. Built from a {@link GuardEntry} (persisted
 * settings) against its {@link GuardSpec} - see {@link GuardEntry#toGuard()}.
 */
public final class Guard {
    public final String label;
    public final GuardTrigger trigger;
    public final GuardOutcome outcome;

    public Guard(String label, GuardTrigger trigger, GuardOutcome outcome) {
        this.label = label;
        this.trigger = trigger;
        this.outcome = outcome;
    }
}
