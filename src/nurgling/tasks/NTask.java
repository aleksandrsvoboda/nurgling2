package nurgling.tasks;

public abstract class NTask
{
    public boolean baseCheck()
    {
        if(!infinite)
        {
            if(counter++ >=maxCounter)
            {
                criticalExit = true;
                return true;
            }
        }
        return check();
    }
    public abstract boolean check();

    /**
     * How long (ms) this task may block a bot before the watchdog flags it as
     * stalled. Return -1 to use the global default (NConfig.Key.botStallTimeout),
     * or Long.MAX_VALUE for tasks that legitimately wait indefinitely.
     *
     * Only override this on tasks that are known to wait a long time for real
     * game timers (growth, firing, travel) or for user input. Being flagged is
     * non-fatal by default, so a stray false positive only colours the gear.
     */
    public long stallTimeoutMs() { return -1; }

    /**
     * Set by NCore right before notify(). The bot thread waits in a bounded loop
     * on this flag so it can wake periodically for the watchdog, and so that a
     * spurious wakeup cannot be mistaken for completion.
     */
    public volatile boolean notified = false;

    public boolean criticalExit = false;
    protected int counter = 0;
    protected int maxCounter = 200;
    protected boolean infinite = true;
}
