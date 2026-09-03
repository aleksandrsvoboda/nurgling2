package nurgling.tasks;

import haven.Gob;

/**
 * Waits for a gate's open/closed state to change, bounded on wall-clock rather than blocking on
 * the task queue indefinitely - given a right-click isn't certain to have actually registered
 * server-side, ChunkNavExecutor would rather find out and log it than risk the bot hanging
 * forever on a gate that never changed state. Kept {@code infinite} (the NTask default) rather
 * than finite on purpose, same reasoning as {@link WaitProgress}: a finite task that runs out of
 * counter is flagged {@code criticalExit}, which {@code NCore.addTask} turns into an
 * {@code InterruptedException} that kills the whole bot - a gate that never opens/closes is a
 * result to report and route around, not a reason to stop.
 */
public class WaitForGateState extends NTask {
    private final Gob gate;
    private final boolean wantOpen;
    private final long deadline;

    public WaitForGateState(Gob gate, boolean wantOpen, long timeoutMs) {
        this.gate = gate;
        this.wantOpen = wantOpen;
        this.deadline = System.currentTimeMillis() + timeoutMs;
    }

    @Override
    public boolean check() {
        if (gate.ngob != null && GateDetector.isDoorOpen(gate) == wantOpen) {
            return true;
        }
        return System.currentTimeMillis() >= deadline;
    }
}
