package nurgling.sessions;

import nurgling.NUI;

/**
 * Thread-local UI storage for session binding.
 * Used internally by BotExecutor.
 *
 * When a bot thread starts, it gets the UI bound here so that
 * even when the user switches sessions, the bot continues to
 * operate on its original session.
 */
public class ThreadLocalUI {
    private static final ThreadLocal<NUI> storage = new ThreadLocal<>();

    /**
     * Set the UI for the current thread.
     * @param ui The UI to bind to this thread
     */
    public static void set(NUI ui) {
        storage.set(ui);
    }

    /**
     * Get the UI bound to the current thread.
     * @return The bound UI, or null if no UI is bound
     */
    public static NUI get() {
        return storage.get();
    }

    /**
     * Clear the UI binding for the current thread.
     */
    public static void clear() {
        storage.remove();
    }

    /**
     * Check if the current thread has a bound UI.
     * @return true if a UI is bound to this thread
     */
    public static boolean isBound() {
        return storage.get() != null;
    }
}
