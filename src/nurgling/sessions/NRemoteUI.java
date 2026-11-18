package nurgling.sessions;

import haven.*;
import nurgling.NUI;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Extended RemoteUI with multi-session support.
 *
 * This class overrides the hook methods added to RemoteUI to implement:
 * - Session registration with SessionManager
 * - DetachMessage handling for session switching
 * - Background message processing for headless sessions
 * - Lifecycle listener registration
 */
public class NRemoteUI extends RemoteUI {

    /** True for a session logged in in the background: it is registered without becoming active and never draws. */
    private final boolean background;

    public NRemoteUI(Session sess) {
        this(sess, false);
    }

    public NRemoteUI(Session sess, boolean background) {
        super(sess);
        this.background = background;
    }

    /**
     * Starts a background session's UI ({@code ui} was built with this runner, off the window's
     * loop): server messages go to it on a background thread and its game logic ticks headless,
     * exactly as for a session that was switched away from. Switching to it later works the same way too.
     */
    public SessionContext startBackground(NUI ui) {
        SessionContext ctx = SessionManager.getInstance().findBySession(sess);
        if (ctx == null)
            throw new IllegalStateException("background session was not registered");
        ui.setreceiver(this);
        sendua(ui);
        ctx.startHeadless();
        spawnBackgroundMessageLoop(ui, ctx);
        return ctx;
    }

    /** Installs the session lifecycle listener and the tab bar controller on the window's loop, once.
     *  Synchronized: background logins call it from several threads at once. */
    public static synchronized void ensureLoopHooks(UILoop loop) {
        if (loop == null)
            return;
        if (loop.getUILifecycleListener() == null) {
            loop.setUILifecycleListener(new NUILifecycleListener());
        }
        if (SessionUIController.getInstance() == null) {
            SessionUIController.initialize(loop);
        }
    }

    /**
     * Called during init() after the UI is set up.
     * Registers the session with SessionManager and sets up lifecycle hooks.
     */
    @Override
    protected void onInit(UI ui) {
        if (ui instanceof NUI) {
            NUI nui = (NUI) ui;
            SessionManager sm = SessionManager.getInstance();

            // Check if this session already exists (e.g., reconnecting after switch)
            SessionContext existing = sm.findBySession(sess);

            if (background) {
                String username = (sess != null && sess.user != null) ? sess.user.name : null;
                SessionContext duplicate = (username != null) ? sm.findByUsername(username) : null;
                if (duplicate != null)
                    sm.removeSessionSilently(duplicate.sessionId);
                sm.addBackgroundSession(sess, nui);
                return;
            }

            if (existing != null) {
                // Session already exists (e.g., promoting from headless) - update UI reference
                existing.ui = nui;

                // Make sure this session is active since we're now rendering it
                if (sm.getActiveSession() != existing) {
                    sm.setActiveSession(existing);
                }
            } else {
                // New session (fresh login)
                // Check for duplicate login (same username already logged in)
                String username = (sess != null && sess.user != null) ? sess.user.name : null;
                if (username != null) {
                    SessionContext duplicate = sm.findByUsername(username);
                    if (duplicate != null) {
                        // Same account logged in again - the server will/has kicked the old session
                        // Just remove it from our tracking (don't close - server already did)
                        sm.removeSessionSilently(duplicate.sessionId);
                    }
                }

                // Register the new session and make it active
                sm.addSessionAsActive(sess, nui);

                /* A fresh login's UI is empty and draws nothing until the server's first widgets
                 * arrive. Show the character screen's backdrop from its very first frame - this
                 * runs while the UI is being built, before it replaces the login screen - so the
                 * login art changes straight to the character art instead of blinking black. */
                nurgling.widgets.charsel.CharselBridge.show(nui);
            }

            // Register lifecycle listener and tab bar controller with the UI loop if not already done.
            // Note: attachToUI() is called by NUILifecycleListener.afterNewUI()
            // to avoid blocking during character selection initialization
            ensureLoopHooks(ui.getLoop());
        }
    }

    /**
     * Handle custom/injected messages.
     * When a DetachMessage is received, spawn a background thread to process
     * messages and return a new Bootstrap to show the login screen.
     *
     * @param msg The message to handle
     * @param ui The current UI
     * @return non-null Runner to exit message loop, null to continue
     */
    @Override
    protected UI.Runner handleCustomMessage(PMessage msg, UI ui) {
        if (msg instanceof DetachMessage) {
            nurgling.sessions.commander.SwitchTrace.mark("detached");
            SessionManager sm = SessionManager.getInstance();
            SessionContext ctx = sm.findByUI(ui);

            if (ctx != null) {
                // Spawn background thread to continue processing messages
                spawnBackgroundMessageLoop(ui, ctx);
            }

            /* Switching to another session that is in the game: hand this message loop straight to it. Going
             * through a Bootstrap would first put an empty login screen on the window (the blink of a switch). */
            SessionContext to = sm.consumePendingSwitchTo();
            if (to != null) {
                UI.Runner fast = switchDirect(to, ui);
                if (fast != null)
                    return fast;
                sm.restorePendingSwitchTo(to);
            }

            // Return a new Bootstrap to show login screen
            return new NBootstrap();
        }

        return null; // Continue normal processing
    }

    /**
     * Takes over {@code to} (headless, in the game) from this message loop: promotes it, waits for its background
     * message loop to let go of the session, and returns the runner that carries on with its messages. The window's
     * loop then reuses its game UI as it is ({@link NUILifecycleListener#beforeNewUI}). Null when it can't be done
     * this way (not in the game yet), and the switch takes the Bootstrap path instead.
     */
    private UI.Runner switchDirect(SessionContext to, UI from) {
        if (to.session == null || to.ui == null || to.ui.gui == null || !to.isHeadless() || !to.isConnected())
            return null;
        to.promoteToVisual(from.getenv());
        nurgling.sessions.commander.SwitchTrace.mark("promoted");
        try {
            to.awaitBackgroundLoop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        nurgling.sessions.commander.SwitchTrace.mark("loop-free");
        SessionManager.getInstance().applyPendingCameraState(to);
        return new NRemoteUI(to.session);
    }

    /**
     * Determine if the session should be cleaned up when the message loop exits.
     * For headless sessions, we skip cleanup because the background thread handles it.
     *
     * @param ui The UI being cleaned up
     * @return true to close session normally, false to skip (background handles it)
     */
    @Override
    protected boolean shouldCleanupSession(UI ui) {
        SessionManager sm = SessionManager.getInstance();
        SessionContext ctx = sm.findByUI(ui);

        if (ctx != null && ctx.isHeadless()) {
            // Headless sessions are cleaned up by the background thread
            return false;
        }

        // Normal cleanup - remove from SessionManager
        if (ctx != null) {
            sm.removeSession(ctx.sessionId);
        }

        return true;
    }

    /**
     * Spawn a background thread to process messages for a headless session.
     * This allows the session to continue receiving server updates while
     * another session is rendered.
     */
    private void spawnBackgroundMessageLoop(UI ui, SessionContext ctx) {
        Thread bgThread = new Thread(() -> {
            boolean promotedToVisual = false;
            /* Widgets built here (a background login's whole charsel and game UI) must see this
             * session as "the" UI, not the one on screen. */
            if (ui instanceof NUI)
                ThreadLocalUI.set((NUI) ui);

            try {
                while (ctx.isConnected() && ctx.isHeadless()) {
                    PMessage msg = sess.getuimsg();
                    if (msg == null) {
                        // Session closed
                        break;
                    }

                    if (msg instanceof PromotedMessage) {
                        // Session is being promoted back to visual mode
                        promotedToVisual = true;
                        break;
                    }

                    // Process the message in the background
                    processBackgroundMessage(msg, ui);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ThreadLocalUI.clear();
                if (!promotedToVisual) {
                    // Session ended without promotion - clean up
                    SessionManager.getInstance().removeSession(ctx.sessionId);
                    sess.close();
                }
            }
        }, "RemoteUI-Background-" + ctx.sessionId);

        bgThread.setDaemon(true);
        ctx.bgLoop = bgThread;
        bgThread.start();
    }

    /**
     * Override to create NRemoteUI when handling Return message (server-initiated session transfer).
     * This ensures multi-session support is maintained even after server session transfers.
     */
    @Override
    protected RemoteUI createReturnedRemoteUI(Session sess) {
        return new NRemoteUI(sess);
    }

    /**
     * Process a message in the background loop.
     * This mirrors the message handling in RemoteUI.run() but operates
     * in a background thread for headless sessions.
     */
    private void processBackgroundMessage(PMessage msg, UI ui) {
        try {
            if (msg.type == RMessage.RMSG_NEWWDG) {
                int id = msg.int32();
                String type = msg.string();
                int parent = msg.int32();
                Object[] pargs = msg.list(sess.resmapper);
                Object[] cargs = msg.list(sess.resmapper);
                synchronized (ui) {
                    ui.newwidgetp(id, type, parent, pargs, cargs);
                }
            } else if (msg.type == RMessage.RMSG_WDGMSG) {
                int id = msg.int32();
                String name = msg.string();
                synchronized (ui) {
                    ui.uimsg(id, name, msg.list(sess.resmapper));
                }
            } else if (msg.type == RMessage.RMSG_DSTWDG) {
                int id = msg.int32();
                synchronized (ui) {
                    ui.destroy(id);
                }
            } else if (msg.type == RMessage.RMSG_ADDWDG) {
                int id = msg.int32();
                int parent = msg.int32();
                Object[] pargs = msg.list(sess.resmapper);
                synchronized (ui) {
                    ui.addwidget(id, parent, pargs);
                }
            } else if (msg.type == RMessage.RMSG_WDGBAR) {
                Collection<Integer> deps = new ArrayList<>();
                while (!msg.eom()) {
                    int dep = msg.int32();
                    if (dep == -1)
                        break;
                    deps.add(dep);
                }
                Collection<Integer> bars = deps;
                if (!msg.eom()) {
                    bars = new ArrayList<>();
                    while (!msg.eom()) {
                        int bar = msg.int32();
                        if (bar == -1)
                            break;
                        bars.add(bar);
                    }
                }
                synchronized (ui) {
                    ui.wdgbarrier(deps, bars);
                }
            }
            // Other message types (RMSG_MAPIV, RMSG_GLOBLOB, RMSG_RESID) are handled
            // by Session.handlerel() before reaching the UI message queue
        } catch (Exception e) {
            // Ignore errors - don't crash the background loop
        }
    }
}
