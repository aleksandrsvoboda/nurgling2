package nurgling.sessions.multilogin;

import java.util.function.BooleanSupplier;

/**
 * Waiting for things that happen in another session's UI. An {@code NCore} task belongs to one UI's
 * tick and a login run spans several UIs that come and go, so a run checks its conditions itself,
 * at least every 100 ms. Stop interrupts a wait at once.
 */
final class Waits {

    private Waits() {
    }

    /** Waits until {@code cond} holds or {@code ms} pass; says whether it held. */
    static boolean until(long ms, BooleanSupplier cond) throws InterruptedException {
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

    /** Waits {@code ms}, ending early only on an interrupt. */
    static void pause(long ms) throws InterruptedException {
        until(ms, () -> false);
    }
}
