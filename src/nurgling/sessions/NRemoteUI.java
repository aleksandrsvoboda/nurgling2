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
        if (ui instanceof NUI) {
            NUI nui = (NUI) ui;
            SessionManager sm = SessionManager.getInstance();

            // Check if this session already exists (e.g., reconnecting after switch)
            SessionContext existing = sm.findBySession(sess);

            if (existing != null) {
                // Update the UI reference for existing session
                existing.ui = nui;
            } else if (sm.findByUI(ui) == null) {
                // New session - register it
                sm.addSession(sess, nui);
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
                    ctrl = SessionUIController.getInstance();
                }

                // Attach the UI controller to this UI
                if (ctrl != null) {
                    ctrl.attachToUI(nui);
                }
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
            SessionManager sm = SessionManager.getInstance();
            SessionContext ctx = sm.findByUI(ui);

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
                if (!promotedToVisual) {
                    // Session ended without promotion - clean up
                    SessionManager.getInstance().removeSession(ctx.sessionId);
                    sess.close();
                }
            }
        }, "RemoteUI-Background-" + ctx.sessionId);

        bgThread.setDaemon(true);
        bgThread.start();
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
