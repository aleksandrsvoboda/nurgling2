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

    public NRemoteUI(Session sess) {
        super(sess);
    }

    /**
     * Called during init() after the UI is set up.
     * Registers the session with SessionManager and sets up lifecycle hooks.
     */
    @Override
    protected void onInit(UI ui) {
        System.out.println("[NRemoteUI] onInit called, ui=" + ui + ", sess=" + sess);
        if (ui instanceof NUI) {
            NUI nui = (NUI) ui;
            SessionManager sm = SessionManager.getInstance();

            // Check if this session already exists (e.g., reconnecting after switch)
            SessionContext existing = sm.findBySession(sess);
            System.out.println("[NRemoteUI] findBySession result: " + (existing != null ? existing.sessionId : "null"));

            if (existing != null) {
                // Session already exists (e.g., promoting from headless) - update UI reference
                System.out.println("[NRemoteUI] Updating existing session UI reference");
                existing.ui = nui;

                // Make sure this session is active since we're now rendering it
                if (sm.getActiveSession() != existing) {
                    System.out.println("[NRemoteUI] Setting existing session as active");
                    sm.setActiveSession(existing);
                }
            } else {
                // New session (fresh login)
                // Check for duplicate login (same username already logged in)
                String username = (sess != null && sess.user != null) ? sess.user.name : null;
                System.out.println("[NRemoteUI] New session, username=" + username);
                if (username != null) {
                    SessionContext duplicate = sm.findByUsername(username);
                    if (duplicate != null) {
                        // Same account logged in again - the server will/has kicked the old session
                        // Just remove it from our tracking (don't close - server already did)
                        System.out.println("[NRemoteUI] Found duplicate session: " + duplicate.sessionId + ", removing silently");
                        sm.removeSessionSilently(duplicate.sessionId);
                    }
                }

                // Register the new session and make it active
                System.out.println("[NRemoteUI] Adding new session as active");
                sm.addSessionAsActive(sess, nui);
            }

            // Register lifecycle listener with GLPanel if not already done
            if (ui.getContext() instanceof GLPanel) {
                GLPanel panel = (GLPanel) ui.getContext();
                GLPanel.Loop loop = panel.getLoop();
                if (loop.getUILifecycleListener() == null) {
                    loop.setUILifecycleListener(new NUILifecycleListener());
                }

                // Initialize SessionUIController if needed
                SessionUIController ctrl = SessionUIController.getInstance();
                if (ctrl == null) {
                    SessionUIController.initialize(panel);
                }

                // Note: attachToUI() is called by NUILifecycleListener.afterNewUI()
                // to avoid blocking during character selection initialization
            }
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
            System.out.println("[NRemoteUI] handleCustomMessage: DetachMessage for ui=" + ui);
            SessionManager sm = SessionManager.getInstance();
            SessionContext ctx = sm.findByUI(ui);
            System.out.println("[NRemoteUI] handleCustomMessage: ctx=" + (ctx != null ? ctx.sessionId : "null"));

            if (ctx != null) {
                // Spawn background thread to continue processing messages
                spawnBackgroundMessageLoop(ui, ctx);
            }

            // Return a new Bootstrap to show login screen
            return new NBootstrap();
        }

        return null; // Continue normal processing
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
        System.out.println("[NRemoteUI] shouldCleanupSession called, ui=" + ui);
        SessionManager sm = SessionManager.getInstance();
        SessionContext ctx = sm.findByUI(ui);
        System.out.println("[NRemoteUI] shouldCleanupSession: ctx=" + (ctx != null ? ctx.sessionId : "null") + ", isHeadless=" + (ctx != null ? ctx.isHeadless() : "N/A"));

        if (ctx != null && ctx.isHeadless()) {
            // Headless sessions are cleaned up by the background thread
            System.out.println("[NRemoteUI] shouldCleanupSession: headless, returning false (background handles cleanup)");
            return false;
        }

        // Normal cleanup - remove from SessionManager
        if (ctx != null) {
            System.out.println("[NRemoteUI] shouldCleanupSession: calling removeSession for " + ctx.sessionId);
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
        System.out.println("[NRemoteUI] spawnBackgroundMessageLoop for " + ctx.sessionId);
        Thread bgThread = new Thread(() -> {
            boolean promotedToVisual = false;

            try {
                System.out.println("[NRemoteUI-BG] Background loop starting for " + ctx.sessionId);
                while (ctx.isConnected() && ctx.isHeadless()) {
                    PMessage msg = sess.getuimsg();
                    if (msg == null) {
                        // Session closed
                        System.out.println("[NRemoteUI-BG] Session " + ctx.sessionId + " received null message (closed)");
                        break;
                    }

                    if (msg instanceof PromotedMessage) {
                        // Session is being promoted back to visual mode
                        System.out.println("[NRemoteUI-BG] Session " + ctx.sessionId + " received PromotedMessage");
                        promotedToVisual = true;
                        break;
                    }

                    // Process the message in the background
                    processBackgroundMessage(msg, ui);
                }
                System.out.println("[NRemoteUI-BG] Loop exited for " + ctx.sessionId + ", isConnected=" + ctx.isConnected() + ", isHeadless=" + ctx.isHeadless());
            } catch (InterruptedException e) {
                System.out.println("[NRemoteUI-BG] Interrupted for " + ctx.sessionId);
                Thread.currentThread().interrupt();
            } finally {
                System.out.println("[NRemoteUI-BG] Finally block for " + ctx.sessionId + ", promotedToVisual=" + promotedToVisual);
                if (!promotedToVisual) {
                    // Session ended without promotion - clean up
                    System.out.println("[NRemoteUI-BG] Calling removeSession for " + ctx.sessionId);
                    SessionManager.getInstance().removeSession(ctx.sessionId);
                    sess.close();
                }
            }
        }, "RemoteUI-Background-" + ctx.sessionId);

        bgThread.setDaemon(true);
        bgThread.start();
    }

    /**
     * Override to create NRemoteUI when handling Return message (server-initiated session transfer).
     * This ensures multi-session support is maintained even after server session transfers.
     */
    @Override
    protected RemoteUI createReturnedRemoteUI(Session sess) {
        System.out.println("[NRemoteUI] createReturnedRemoteUI for session transfer");
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
