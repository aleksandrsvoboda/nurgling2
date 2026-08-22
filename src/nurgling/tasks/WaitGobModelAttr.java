package nurgling.tasks;

import haven.Gob;

public class WaitGobModelAttr extends NTask {
    Gob gob;
    int flag;
    private final long deadline;

    public WaitGobModelAttr(Gob gob, int flag) {
        this(gob, flag, 0);
    }

    /**
     * Bounded variant: gives up after {@code timeoutMs} instead of waiting forever.
     *
     * <p>Use this where the attribute may legitimately never arrive - a light that failed to take, say -
     * and the caller can tell the difference itself by re-reading the attribute afterwards. Stays
     * {@code infinite} rather than becoming a finite task, since a finite task that runs out of counter is
     * flagged {@code criticalExit} and {@code NCore.addTask} turns that into an {@code InterruptedException}
     * that kills the bot.
     *
     * @param timeoutMs milliseconds to wait, or 0 to wait indefinitely
     */
    public WaitGobModelAttr(Gob gob, int flag, long timeoutMs) {
        this.gob = gob;
        this.flag = flag;
        this.deadline = (timeoutMs > 0) ? System.currentTimeMillis() + timeoutMs : 0;
    }

    @Override
    public boolean check() {
        if ((gob.ngob != null) && (gob.ngob.getModelAttribute() & flag) != 0)
            return true;
        return (deadline > 0) && (System.currentTimeMillis() >= deadline);
    }
}
