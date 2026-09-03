package nurgling.guarding;

/** One configured guard: a {@link GuardTrigger} paired with the {@link GuardOutcome} to perform when it fires; see {@link GuardEntry#toGuard()}. */
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
