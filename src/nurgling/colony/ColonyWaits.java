package nurgling.colony;

import java.util.function.BooleanSupplier;

/**
 * Waiting for things that happen in other sessions. An NCore task belongs to one UI's tick, and a colony run spans
 * several sessions that come and go (one logs in, another builds), so the run checks its conditions itself, at least
 * every 100 ms. Stop interrupts a wait at once. Same as the multi-login's waits.
 */
public final class ColonyWaits {
    private ColonyWaits() {}

    /** Waits until {@code cond} holds or {@code ms} pass; says whether it held. */
    public static boolean until(long ms, BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        Object lock = new Object();
        while (true) {
            if (cond.getAsBoolean())
                return true;
            long left = end - System.currentTimeMillis();
            if (left <= 0)
                return false;
            synchronized (lock) {
                lock.wait(Math.min(left, 100));
            }
        }
    }
}
