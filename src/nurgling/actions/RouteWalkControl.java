package nurgling.actions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared state between the Route Walker window (UI thread) and the {@link FollowRoute} action running
 * on its bot thread. One instance per run - nothing here outlives the walk, so there is nothing to reset.
 */
public class RouteWalkControl {

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile String status = "idle";
    private volatile int waypoint = 0;
    private volatile int total = 0;
    // The bot thread, so Stop can go through BotsInterruptWidget.removeObserve() (interrupt + drop its bar).
    private volatile Thread thread = null;

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    public String status() {
        return status;
    }

    public void status(String status) {
        this.status = status;
    }

    public int waypoint() {
        return waypoint;
    }

    public int total() {
        return total;
    }

    public void progress(int waypoint, int total) {
        this.waypoint = waypoint;
        this.total = total;
    }

    public Thread thread() {
        return thread;
    }

    public void thread(Thread thread) {
        this.thread = thread;
    }

    /** True while the walk is still on its thread - the window polls this to fall back to idle. */
    public boolean running() {
        Thread t = thread;
        return t != null && t.isAlive();
    }
}
