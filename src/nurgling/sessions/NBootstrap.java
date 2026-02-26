package nurgling.sessions;

import haven.*;

/**
 * Extended Bootstrap with multi-session support.
 *
 * This class overrides:
 * - preRun() to check for pending session switches
 * - createRemoteUI() to return NRemoteUI for multi-session support
 *
 * When a session switch is pending, it skips normal bootstrap (login screen)
 * and directly returns a NRemoteUI connected to the target session.
 */
public class NBootstrap extends Bootstrap {

    /**
     * Create a new NBootstrap with the default server.
     * This should be used instead of Bootstrap.create() to ensure
     * multi-session support works correctly.
     */
    public static NBootstrap create() {
        return new NBootstrap();
    }

    /**
     * Override to create NRemoteUI instead of RemoteUI.
     * This enables multi-session support by using NRemoteUI which:
     * - Registers sessions with SessionManager
     * - Attaches SessionTabBar to the UI
     * - Handles session switching via DetachMessage
     */
    @Override
    protected RemoteUI createRemoteUI(Session sess) {
        return new NRemoteUI(sess);
    }

    /**
     * Hook called before normal bootstrap runs.
     * Checks if we're switching to an existing session instead of starting a new one.
     *
     * @param ui The UI instance
     * @return non-null Runner to skip bootstrap, null to continue normally
     */
    @Override
    protected UI.Runner preRun(UI ui) throws InterruptedException {
        System.out.println("[NBootstrap] preRun called, ui=" + ui);
        SessionManager sm = SessionManager.getInstance();
        SessionContext switchTo = sm.consumePendingSwitchTo();
        System.out.println("[NBootstrap] consumePendingSwitchTo: " + (switchTo != null ? switchTo.sessionId : "null"));

        if (switchTo != null && switchTo.session != null) {
            System.out.println("[NBootstrap] Promoting session to visual: " + switchTo.sessionId);
            // Promote the session from headless to visual mode
            switchTo.promoteToVisual(ui.getenv());

            // Apply pending camera state if camera sync is enabled
            sm.applyPendingCameraState(switchTo);

            // Give the background message loop a moment to exit cleanly
            // The PromotedMessage will cause it to exit, but we need to wait
            Thread.sleep(100);

            // Return a NRemoteUI connected to the existing session
            // This skips the login screen and goes straight to the game
            System.out.println("[NBootstrap] Returning NRemoteUI for session: " + switchTo.sessionId);
            return new NRemoteUI(switchTo.session);
        }

        // No pending switch - proceed with normal bootstrap (login screen)
        System.out.println("[NBootstrap] No pending switch, showing login screen");
        return null;
    }
}
