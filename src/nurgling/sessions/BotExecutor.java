package nurgling.sessions;

import nurgling.*;
import nurgling.actions.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Centralized executor for bot threads with automatic session binding.
 *
 * ALL bot/action thread creation should go through this class.
 * This is the ONLY place that calls ThreadLocalUI.set/clear.
 *
 * When a bot thread is created through this executor, it is automatically
 * bound to the current session. This ensures that even if the user switches
 * to a different session, the bot continues operating on its original session.
 */
public class BotExecutor {

    /**
     * Run an action asynchronously with session binding.
     *
     * @param name The name of the thread (for debugging/display)
     * @param action The action to run
     * @return The created thread, or null if no GUI is available
     */
    public static Thread runAsync(String name, Action action) {
        return runAsync(name, action, false);
    }

    /**
     * Run an action asynchronously with session binding.
     *
     * @param name The name of the thread (for debugging/display)
     * @param action The action to run
     * @param disableStacks Whether to disable equipment stacks during execution
     * @return The created thread, or null if no GUI is available
     */
    public static Thread runAsync(String name, Action action, boolean disableStacks) {
        NUI boundUI = NUtils.getUI();
        NGameUI gui = (boundUI != null) ? boundUI.gui : null;
        if (gui == null) return null;

        Thread t = new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                action.run(gui);
            } catch (InterruptedException e) {
                gui.msg(name + ": STOPPED");
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);

        if (disableStacks) {
            gui.biw.addObserve(t, true);
        } else {
            gui.biw.addObserve(t);
        }
        t.start();
        return t;
    }

    /**
     * Run an action with support threads.
     *
     * @param name The name of the main thread
     * @param action The main action to run (may have getSupp() returning support actions)
     * @param disableStacks Whether to disable equipment stacks during execution
     * @param onComplete Callback to run when the action completes (can be null)
     * @return The main thread, or null if no GUI is available
     */
    public static Thread runWithSupports(String name, Action action,
                                         boolean disableStacks, Runnable onComplete) {
        return runWithSupportsOn(NUtils.getUI(), name, action, disableStacks, onComplete);
    }

    /**
     * {@link #runWithSupports} on an explicit session instead of the caller's. For callers that are
     * not themselves bound to the target session, e.g. the scripting server. Must be called on that
     * session's UI thread, since it adds the bot's row to the Bot Status strip.
     */
    public static Thread runWithSupportsOn(NUI boundUI, String name, Action action,
                                           boolean disableStacks, Runnable onComplete) {
        NGameUI gui = (boundUI != null) ? boundUI.gui : null;
        if (gui == null) return null;

        Thread t = new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            List<Thread> supports = new ArrayList<>();
            try {
                // Start support threads
                for (Action sup : action.getSupp()) {
                    Thread st = createSupportThread(name, sup, boundUI, gui);
                    supports.add(st);
                    st.start();
                }
                // Run main action
                action.run(gui);
            } catch (InterruptedException e) {
                gui.msg(name + ": STOPPED");
            } finally {
                // Stop all support threads
                for (Thread st : supports) {
                    st.interrupt();
                }
                ThreadLocalUI.clear();
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, name);

        if (disableStacks) {
            gui.biw.addObserve(t, true);
        } else {
            gui.biw.addObserve(t);
        }
        t.start();
        return t;
    }

    /**
     * Starts an action's support threads bound to the calling thread's session, for code that runs
     * an action inline on an existing bot thread (scripts, scenario-like runners). The caller must
     * interrupt the returned threads when the action finishes.
     */
    public static List<Thread> startSupports(String name, Action action) {
        NUI boundUI = NUtils.getUI();
        NGameUI gui = (boundUI != null) ? boundUI.gui : null;
        List<Thread> res = new ArrayList<>();
        if (gui == null) return res;
        for (Action sup : action.getSupp()) {
            Thread st = createSupportThread(name, sup, boundUI, gui);
            res.add(st);
            st.start();
        }
        return res;
    }

    /**
     * Create a support thread for an action.
     */
    private static Thread createSupportThread(String baseName, Action action,
                                               NUI boundUI, NGameUI gui) {
        return new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                action.run(gui);
            } catch (InterruptedException e) {
                // Support stopped - normal
            } finally {
                ThreadLocalUI.clear();
            }
        }, baseName + "-Support");
    }

    /**
     * Run a simple task with session binding (for non-Action tasks).
     *
     * @param name The name of the thread
     * @param task The task to run
     * @return The created thread
     */
    public static Thread runTask(String name, Runnable task) {
        NUI boundUI = NUtils.getUI();
        Thread t = new Thread(() -> {
            if (boundUI != null) {
                ThreadLocalUI.set(boundUI);
            }
            try {
                task.run();
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);
        t.start();
        return t;
    }

    /**
     * Run a task that needs GUI, tracked in BotInterruptWidget.
     *
     * @param name The name of the thread
     * @param task The task to run, receives the NGameUI
     * @return The created thread, or null if no GUI is available
     */
    public static Thread runTracked(String name, Consumer<NGameUI> task) {
        NUI boundUI = NUtils.getUI();
        NGameUI gui = (boundUI != null) ? boundUI.gui : null;
        if (gui == null) return null;

        Thread t = new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                task.accept(gui);
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);
        gui.biw.addObserve(t);
        t.start();
        return t;
    }
}
