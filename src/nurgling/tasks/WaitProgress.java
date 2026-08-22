package nurgling.tasks;

import nurgling.NGameUI;
import nurgling.NUtils;

/**
 * Waits for the character's action progress bar to start or to finish, bounded on wall-clock.
 *
 * <p>The same two-phase wait {@code LightObject.waitForProgress} performs, but bounded. Those are anonymous
 * {@link NTask}s, which default to {@code infinite}, so a progress bar that never appears hangs the bot
 * forever. That is the wrong trade for an action whose click may simply have missed its target: a miss is a
 * result to report, not a reason to stop. Kept {@code infinite} rather than finite on purpose - a finite
 * task that runs out of counter is flagged {@code criticalExit}, which {@code NCore.addTask} turns into an
 * {@code InterruptedException} that kills the whole bot.
 *
 * <p>Check {@link #isTimedOut()} afterwards to tell "it happened" from "it never did".
 */
public class WaitProgress extends NTask
{
    public enum Phase
    {
        /** The bar appeared and is advancing. */
        START,
        /** The bar is gone, i.e. the action finished or was interrupted. */
        FINISH
    }

    private final Phase phase;
    private final long deadline;
    private boolean timedOut = false;

    public WaitProgress(Phase phase, long timeoutMs)
    {
        this.phase = phase;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    public boolean isTimedOut()
    {
        return timedOut;
    }

    @Override
    public boolean check()
    {
        NGameUI gui = NUtils.getGameUI();
        if (gui != null)
        {
            boolean running = (gui.prog != null) && (gui.prog.prog > 0);
            if ((phase == Phase.START) == running)
                return true;
        }
        if (System.currentTimeMillis() >= deadline)
        {
            timedOut = true;
            return true;
        }
        return false;
    }
}
