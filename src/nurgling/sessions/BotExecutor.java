package nurgling.sessions;

import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.watchdog.BotState;
import nurgling.watchdog.BotWatchdog;

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
 *
 * Every thread started here is also registered with the session's
 * {@link BotWatchdog}, so a bot that hangs or crashes gets reported instead of
 * silently disappearing.
 */
public class BotExecutor {

    private static BotWatchdog watchdog(NUI boundUI) {
        return (boundUI != null && boundUI.core != null) ? boundUI.core.watchdog : null;
    }

    /** Register a bot thread with the session watchdog before it is started. */
    public static void register(NUI boundUI, Thread t, String name) {
        BotWatchdog wd = watchdog(boundUI);
        if (wd != null)
            wd.register(t, name);
    }

    /** Record the outcome of an action run and report crashes to the user. */
    private static void finish(NUI boundUI, NGameUI gui, String name, BotState state, String msg) {
        BotWatchdog wd = watchdog(boundUI);
        if (wd != null)
            wd.unregister(Thread.currentThread(), state, msg);
        if (state == BotState.ERROR && gui != null)
            gui.error(name + ": CRASHED: " + msg);
    }

    private static BotState stateOf(Results res) {
        // Some actions return null; treat that as a normal completion.
        return (res == null || res.IsSuccess()) ? BotState.FINISHED : BotState.FAILED;
    }

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
                finish(boundUI, gui, name, stateOf(action.run(gui)), null);
            } catch (InterruptedException e) {
                gui.msg(name + ": STOPPED");
                finish(boundUI, gui, name, BotState.FINISHED, "stopped");
            } catch (Throwable e) {
                e.printStackTrace();
                finish(boundUI, gui, name, BotState.ERROR, String.valueOf(e));
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);

        register(boundUI, t, name);
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
        NUI boundUI = NUtils.getUI();
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
                finish(boundUI, gui, name, stateOf(action.run(gui)), null);
            } catch (InterruptedException e) {
                gui.msg(name + ": STOPPED");
                finish(boundUI, gui, name, BotState.FINISHED, "stopped");
            } catch (Throwable e) {
                e.printStackTrace();
                finish(boundUI, gui, name, BotState.ERROR, String.valueOf(e));
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

        register(boundUI, t, name);
        if (disableStacks) {
            gui.biw.addObserve(t, true);
        } else {
            gui.biw.addObserve(t);
        }
        t.start();
        return t;
    }

    /**
     * Create a support thread for an action.
     */
    private static Thread createSupportThread(String baseName, Action action,
                                               NUI boundUI, NGameUI gui) {
        String name = baseName + "-Support";
        Thread t = new Thread(() -> {
            ThreadLocalUI.set(boundUI);
            try {
                action.run(gui);
                finish(boundUI, gui, name, BotState.FINISHED, null);
            } catch (InterruptedException e) {
                // Support stopped - normal
                finish(boundUI, gui, name, BotState.FINISHED, "stopped");
            } catch (Throwable e) {
                e.printStackTrace();
                finish(boundUI, gui, name, BotState.ERROR, String.valueOf(e));
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);
        register(boundUI, t, name);
        return t;
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
                finish(boundUI, gui, name, BotState.FINISHED, null);
            } catch (Throwable e) {
                e.printStackTrace();
                finish(boundUI, gui, name, BotState.ERROR, String.valueOf(e));
            } finally {
                ThreadLocalUI.clear();
            }
        }, name);

        register(boundUI, t, name);
        gui.biw.addObserve(t);
        t.start();
        return t;
    }
}
