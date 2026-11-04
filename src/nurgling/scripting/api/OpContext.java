package nurgling.scripting.api;

import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.actions.Results;
import nurgling.scripting.ScriptConnection;
import nurgling.scripting.ScriptRun;
import nurgling.tasks.NTask;

import java.util.function.BooleanSupplier;

/**
 * What an op handler gets besides its arguments: the run (and so the bot thread and session)
 * it executes on, and the connection that sent the request.
 */
public class OpContext {
    public final ScriptRun run;
    public final ScriptConnection conn;
    public final NUI ui;
    public final NGameUI gui;

    public OpContext(ScriptRun run, ScriptConnection conn) {
        this.run = run;
        this.conn = conn;
        this.ui = (run != null) ? run.ui : null;
        this.gui = (run != null) ? run.gui : null;
    }

    /** Turns a Java action's result into an ActionFailed error. */
    public void require(Results r, String what) {
        if (r == null || !r.IsSuccess())
            throw ApiError.failed(what + " failed");
    }

    /**
     * Blocks the bot thread until {@code cond} is true, checking once per client tick.
     * The condition runs on the UI thread and must be cheap. Throws Timeout after
     * {@code timeoutSec} seconds of wall-clock time (0 or less waits forever).
     */
    public void waitUntil(BooleanSupplier cond, double timeoutSec, String what) throws InterruptedException {
        DeadlineTask t = new DeadlineTask(cond, timeoutSec);
        ui.core.addTask(t);
        if (t.timedOut)
            throw new ApiError(Errors.TIMEOUT, "timed out after " + timeoutSec + " s waiting for " + what);
    }

    /** Like {@link #waitUntil} but returns false on timeout instead of throwing. */
    public boolean waitFor(BooleanSupplier cond, double timeoutSec) throws InterruptedException {
        DeadlineTask t = new DeadlineTask(cond, timeoutSec);
        ui.core.addTask(t);
        return !t.timedOut;
    }

    /** An NTask with a wall-clock deadline that never sets criticalExit, so a timeout is not mistaken for a stop. */
    static class DeadlineTask extends NTask {
        final BooleanSupplier cond;
        final long deadline;
        volatile boolean timedOut = false;

        DeadlineTask(BooleanSupplier cond, double timeoutSec) {
            this.cond = cond;
            this.deadline = timeoutSec > 0 ? System.currentTimeMillis() + (long) (timeoutSec * 1000) : Long.MAX_VALUE;
            this.infinite = true;
        }

        @Override
        public boolean check() {
            if (cond.getAsBoolean()) return true;
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true;
                return true;
            }
            return false;
        }
    }
}
