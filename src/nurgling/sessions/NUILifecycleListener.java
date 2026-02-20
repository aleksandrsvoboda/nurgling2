package nurgling.sessions;

import haven.*;
import nurgling.NUI;

/**
 * Implements GLPanel UI lifecycle hooks for multi-session support.
 *
 * This listener is registered with GLPanel.Loop to intercept UI creation
 * and destruction, enabling session reuse and headless demotion.
 */
public class NUILifecycleListener implements UILifecycleListener {

    /**
     * Called before creating a new UI.
     * If we're reconnecting to an existing session (e.g., after session switch),
     * we can reuse the existing UI instead of creating a new one.
     *
     * @param runner The runner that will use the UI (e.g., NRemoteUI)
     * @param currentUI The current UI (may be null)
     * @param panel The UIPanel creating the UI
     * @return non-null UI to reuse, null to create new
     */
    @Override
    public UI beforeNewUI(UI.Runner runner, UI currentUI, UIPanel panel) {
        if (runner instanceof NRemoteUI) {
            NRemoteUI nrui = (NRemoteUI) runner;
            SessionManager sm = SessionManager.getInstance();
            SessionContext ctx = sm.findBySession(nrui.sess);

            if (ctx != null && ctx.ui != null) {
                // Reuse existing session's UI instead of creating new
                NUI existingUI = ctx.ui;

                // Update the GL environment for rendering
                if (panel instanceof GLPanel) {
                    existingUI.env = ((GLPanel) panel).env();
                }

                // Set as the global UI instance
                UI.setInstance(existingUI);

                // Re-attach the session tab bar to this UI
                SessionUIController ctrl = SessionUIController.getInstance();
                if (ctrl != null) {
                    ctrl.attachToUI(existingUI);
                }

                return existingUI;
            }
        }
        return null; // Proceed with normal UI creation
    }

    /**
     * Called after new UI created but before old UI destroyed.
     * When switching sessions, we don't want to destroy the old UI -
     * we want to demote it to headless mode so it can continue running bots.
     *
     * @param newUI The newly created UI
     * @param oldUI The previous UI (may be null)
     * @return true to destroy oldUI, false to keep it (demote to headless)
     */
    @Override
    public boolean afterNewUI(UI newUI, UI oldUI) {
        // Always attach tab bar to the new UI (even login screen)
        if (newUI instanceof NUI) {
            SessionUIController ctrl = SessionUIController.getInstance();
            if (ctrl != null) {
                ctrl.attachToUI((NUI) newUI);
            }
        }

        SessionManager sm = SessionManager.getInstance();

        // Process any pending session close (from closing active session)
        sm.processPendingClose();

        if (oldUI == null) {
            return true; // Nothing to handle
        }

        SessionContext oldCtx = sm.findByUI(oldUI);

        if (oldCtx != null) {
            // The old UI belongs to a session - demote it to headless instead of destroying
            // (If it was already headless, demoteToHeadless() will just return early)
            if (!oldCtx.isHeadless()) {
                oldCtx.demoteToHeadless();
            }
            return false; // Don't destroy the old UI
        }

        return true; // Destroy normally (no session associated)
    }

    /**
     * Called when a new session is requested (e.g., "Add Account" button).
     * We demote the current session to headless to show the login screen.
     *
     * @param panel The UIPanel handling the request
     */
    @Override
    public void onNewSessionRequested(UIPanel panel) {
        SessionManager sm = SessionManager.getInstance();
        NUI currentUI = sm.getActiveUI();

        if (currentUI != null) {
            SessionContext ctx = sm.findByUI(currentUI);
            if (ctx != null && !ctx.isHeadless()) {
                ctx.demoteToHeadless();
            }
        }
    }
}
