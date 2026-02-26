package nurgling.sessions;

import haven.*;
import nurgling.NUI;

/**
 * Manages session UI elements externally.
 * This allows NUI to remain unchanged - no session-related fields or methods needed.
 *
 * The controller:
 * - Listens for session changes from SessionManager
 * - Attaches/detaches SessionTabBar to the current UI
 * - Handles "Add Account" button clicks
 */
public class SessionUIController implements SessionManager.SessionChangeListener {

    private static SessionUIController instance;

    private SessionTabBar tabBar;
    private NUI currentUI;
    private UIPanel panel;

    /**
     * Initialize the SessionUIController singleton.
     * Should be called once during application startup.
     */
    public static void initialize(UIPanel panel) {
        if (instance == null) {
            instance = new SessionUIController();
            instance.panel = panel;
            SessionManager.getInstance().addListener(instance);
        }
    }

    /**
     * Get the singleton instance.
     */
    public static SessionUIController getInstance() {
        return instance;
    }

    /**
     * Attach to a UI - adds tab bar if needed.
     * Called when a new UI is created or when switching sessions.
     */
    public void attachToUI(NUI ui) {
        System.out.println("[SessionUIController] attachToUI called with ui=" + ui + ", ui.root=" + (ui != null ? ui.root : "null"));
        System.out.println("[SessionUIController] Current tabBar=" + tabBar + ", tabBar.parent=" + (tabBar != null ? tabBar.parent : "null"));

        this.currentUI = ui;

        // Add tab bar if we have sessions or want to show "+" button
        if (ui != null && ui.root != null) {
            // Always create a fresh tab bar for each UI to avoid state issues
            if (tabBar != null && tabBar.parent != null) {
                System.out.println("[SessionUIController] Unlinking old tabBar from parent " + tabBar.parent);
                tabBar.unlink();
            }
            tabBar = new SessionTabBar();
            tabBar.setOnAddAccount(this::onAddAccountClicked);

            // Add to new UI at saved position (widget manages its own position)
            tabBar.z(10000);
            ui.root.add(tabBar);
            System.out.println("[SessionUIController] Added new tabBar to ui.root=" + ui.root);
        }
    }

    /**
     * Detach from the current UI.
     */
    public void detachFromUI() {
        if (tabBar != null && tabBar.parent != null) {
            tabBar.unlink();
        }
        tabBar = null;
        currentUI = null;
    }

    /**
     * Called when "Add Account" is clicked.
     * Demotes the current session to headless mode, which triggers
     * the login flow for a new session.
     */
    private void onAddAccountClicked() {
        SessionManager sm = SessionManager.getInstance();
        SessionContext ctx = sm.getActiveSession();

        // Only allow adding new session if current session is fully loaded (has GameUI)
        // This prevents issues when clicking "+" during character selection
        if (ctx != null && !ctx.isHeadless()) {
            NUI ui = ctx.ui;
            if (ui != null && ui.gui != null) {
                // Demote current session - this triggers the login flow
                ctx.demoteToHeadless();
            }
        }
    }

    /**
     * Get the current UI attached to this controller.
     */
    public NUI getCurrentUI() {
        return currentUI;
    }

    /**
     * Get the panel this controller was initialized with.
     */
    public UIPanel getPanel() {
        return panel;
    }

    // SessionChangeListener implementation

    @Override
    public void onActiveSessionChanged(SessionContext oldSession, SessionContext newSession) {
        System.out.println("[SessionUIController] onActiveSessionChanged: old=" + (oldSession != null ? oldSession.sessionId : "null")
            + ", new=" + (newSession != null ? newSession.sessionId : "null"));
        System.out.println("[SessionUIController] newSession.ui=" + (newSession != null ? newSession.ui : "null"));

        // When active session changes, we need to move the tab bar to the new session's UI
        // so it can receive click events (widgets only receive events from rendered UI)
        if (newSession != null && newSession.ui != null) {
            attachToUI(newSession.ui);
        } else {
            System.out.println("[SessionUIController] WARNING: newSession or newSession.ui is null, not attaching tab bar");
        }
    }

    @Override
    public void onSessionAdded(SessionContext session) {
        // Ensure tab bar is visible when we have sessions
        if (currentUI != null && tabBar == null) {
            attachToUI(currentUI);
        }
    }

    @Override
    public void onSessionRemoved(SessionContext session) {
        // Tab bar updates automatically
        // Could hide tab bar if only one session left, but keeping it
        // for the "+" button is probably better UX
    }
}
