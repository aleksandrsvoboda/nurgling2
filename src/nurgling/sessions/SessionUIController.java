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
        System.out.println("[SessionUIController] attachToUI called, ui=" + ui);
        this.currentUI = ui;

        // Add tab bar if we have sessions or want to show "+" button
        if (ui != null && ui.root != null) {
            System.out.println("[SessionUIController] ui.root=" + ui.root + ", root.sz=" + ui.root.sz);
            if (tabBar == null) {
                tabBar = new SessionTabBar();
                tabBar.setOnAddAccount(this::onAddAccountClicked);
                System.out.println("[SessionUIController] Created new SessionTabBar");
            }

            // Remove from old parent if any
            if (tabBar.parent != null) {
                System.out.println("[SessionUIController] Removing tabBar from old parent");
                tabBar.reqdestroy();
            }

            // Add to new UI
            tabBar.z(10000);
            ui.root.add(tabBar, new Coord(0, 0));
            tabBar.resize(ui.root.sz);
            System.out.println("[SessionUIController] Added tabBar to ui.root at (0,0), tabBar.sz=" + tabBar.sz);
        } else {
            System.out.println("[SessionUIController] ui or ui.root is null, not adding tabBar");
        }
    }

    /**
     * Detach from the current UI.
     */
    public void detachFromUI() {
        if (tabBar != null && tabBar.parent != null) {
            tabBar.reqdestroy();
        }
        currentUI = null;
    }

    /**
     * Called when "Add Account" is clicked.
     * Demotes the current session to headless mode, which triggers
     * the login flow for a new session.
     */
    private void onAddAccountClicked() {
        SessionManager sm = SessionManager.getInstance();
        SessionContext ctx = sm.findByUI(currentUI);

        if (ctx != null && !ctx.isHeadless()) {
            // Demote current session - this triggers the login flow
            ctx.demoteToHeadless();
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
        // Tab bar updates automatically via SessionManager queries when it redraws
        // Nothing special needed here
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
