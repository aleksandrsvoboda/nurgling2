package nurgling.tasks;

import haven.Gob;
import nurgling.tools.Finder;

/**
 * Waits for a worked bellows to register, i.e. for the boost bit to appear in the furnace's model
 * attribute.
 *
 * <p>Deliberately self-bounded rather than finite: {@link NTask} marks a finite task that runs out of
 * counter as {@code criticalExit}, and {@code NCore.addTask} turns that into an {@code InterruptedException}
 * that kills the whole bot. A pump that fails to land - out of stamina, out of range, server refused - must
 * not do that, since the furnace still smelts unboosted. So this stays {@code infinite} and simply returns
 * true once the deadline passes; the caller re-reads the marker to find out whether it actually worked.
 *
 * <p>Bounded on wall-clock rather than tick count because tasks are polled from {@code NCore.tick()},
 * whose rate follows the frame rate.
 */
public class WaitBellows extends NTask
{
    private final String hash;
    private final int mask;
    private final long deadline;

    public WaitBellows(String hash, int mask)
    {
        this(hash, mask, 10000);
    }

    public WaitBellows(String hash, int mask, long timeoutMs)
    {
        this.hash = hash;
        this.mask = mask;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public boolean check()
    {
        Gob gob = Finder.findGob(hash);
        if (gob != null && gob.ngob != null && (gob.ngob.getModelAttribute() & mask) != 0)
            return true;
        return System.currentTimeMillis() >= deadline;
    }
}
