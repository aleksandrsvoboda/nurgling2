package nurgling.sessions;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;

/**
 * Holds all state for a single game session.
 * A session can be in either visual mode (rendered on screen) or headless mode (bot only).
 */
public class SessionContext {
    /** Unique identifier for this session */
    public final String sessionId;

    /** The network session - handles server communication */
    public Session session;

    /** The UI instance for this session */
    public NUI ui;

    /** The game UI widget (available after character selection) */
    public NGameUI gameUI;

    /** Per-world configuration */
    public NConfig config;

    /** Character name for display */
    public String characterName;

    /** World identifier (genus) */
    public String genus;

    /** Username for this session */
    public String username;

    /** Whether this session is running in headless mode */
    private volatile boolean headless = false;

    /** The headless tick thread (when headless) */
    private Thread headlessThread;

    /** Whether this session is connected and active */
    private volatile boolean connected = false;

    /** Timestamp of last activity (for status display) */
    private volatile long lastActivityTime = System.currentTimeMillis();

    /** Current bot name if running (for status display) */
    private volatile String currentBotName = null;

    private static int sessionCounter = 0;

    /**
     * Create a new session context.
     */
    public SessionContext() {
        this.sessionId = "session-" + (++sessionCounter);
    }

    /**
     * Create a session context with existing session and UI.
     */
    public SessionContext(Session session, NUI ui) {
        this();
        this.session = session;
        this.ui = ui;
        if (session != null && session.user != null) {
            this.username = session.user.name;
        }
        this.connected = true;
    }

    /**
     * Check if this session is in headless mode.
     */
    public boolean isHeadless() {
        return headless;
    }

    /**
     * Check if this session is connected.
     */
    public boolean isConnected() {
        return connected && session != null;
    }

    /**
     * Mark this session as disconnected.
     */
    public void setDisconnected() {
        this.connected = false;
    }

    /**
     * Get the game UI if available.
     */
    public NGameUI getGameUI() {
        if (gameUI != null) {
            return gameUI;
        }
        if (ui != null) {
            return ui.gui;
        }
        return null;
    }

    /**
     * Get display name for this session.
     */
    public String getDisplayName() {
        if (characterName != null && !characterName.isEmpty()) {
            return characterName;
        }
        if (username != null && !username.isEmpty()) {
            return username;
        }
        return sessionId;
    }

    /**
     * Get the current bot name if any.
     */
    public String getCurrentBotName() {
        return currentBotName;
    }

    /**
     * Set the current bot name.
     */
    public void setCurrentBotName(String botName) {
        this.currentBotName = botName;
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Demote this session from visual to headless mode.
     * This stops the session from being rendered and starts a headless tick loop.
     * Note: We don't change ui.env because widgets still hold render tree slot
     * references that would become invalid. We just stop rendering this session.
     */
    public void demoteToHeadless() {
        if (headless) {
            return; // Already headless
        }

        headless = true;

        // If this was the active session, clear it so the next session becomes active
        SessionManager sm = SessionManager.getInstance();
        if (sm.getActiveSession() == this) {
            sm.clearActiveSession();
        }

        // Signal the RemoteUI message loop to detach to background
        // This wakes up getuimsg() and causes NRemoteUI to spawn background thread
        if (session != null) {
            session.injectMessage(new DetachMessage());
        }

        // Start headless tick thread for game logic (glob ticks, UI ticks)
        headlessThread = new Thread(() -> {
            try {
                runHeadlessLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Headless-" + sessionId);
        headlessThread.setDaemon(true);
        headlessThread.start();
    }

    /**
     * Promote this session from headless to visual mode.
     * This stops the headless tick loop and prepares for rendering.
     *
     * @param env The GL environment to use for rendering
     */
    public void promoteToVisual(haven.render.Environment env) {
        if (!headless) {
            return; // Already visual
        }

        // Stop headless thread
        if (headlessThread != null) {
            headlessThread.interrupt();
            try {
                headlessThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            headlessThread = null;
        }

        headless = false;

        // Signal background message loop to exit
        if (session != null) {
            session.injectMessage(new PromotedMessage());
        }

        // Set the GL environment for rendering
        if (ui != null && env != null) {
            ui.env = env;
        }
    }

    /**
     * Run the headless tick loop for this session.
     */
    private void runHeadlessLoop() throws InterruptedException {
        final double TICK_RATE = 20.0;
        final double TICK_DURATION = 1.0 / TICK_RATE;
        double lastTick = Utils.rtime();

        // Set thread-local UI for this headless thread so that bot actions
        // can access the correct session's UI via NUtils.getUI()/getGameUI().
        ThreadLocalUI.set(ui);

        try {
            while (!Thread.currentThread().isInterrupted() && headless && isConnected()) {
                double now = Utils.rtime();

                if (ui != null) {
                    synchronized (ui) {
                        try {
                            // Tick the session (network, glob, etc.)
                            if (ui.sess != null) {
                                ui.sess.glob.ctick();
                                // Send pending map requests
                                ui.sess.glob.map.sendreqs();
                            }

                            // Tick the UI (processes widget state, bot actions, etc.)
                            ui.tick();
                            ui.lastevent = now;
                        } catch (NullPointerException e) {
                            // Some widgets may throw NPE in headless mode - ignore and continue
                        }
                    }
                }

                lastActivityTime = System.currentTimeMillis();

                // Maintain tick rate
                double targetTime = lastTick + TICK_DURATION;
                double sleepTime = targetTime - Utils.rtime();
                if (sleepTime > 0) {
                    Thread.sleep((long)(sleepTime * 1000));
                }
                lastTick = targetTime;
            }
        } finally {
            // Clean up thread-local UI when the headless loop exits
            ThreadLocalUI.clear();
        }
    }

    /**
     * Close this session and clean up resources.
     */
    public void close() {
        connected = false;

        // Stop headless thread if running
        if (headlessThread != null) {
            headlessThread.interrupt();
            headlessThread = null;
        }

        // Close the network session
        if (session != null) {
            session.close();
        }

        // Destroy the UI
        if (ui != null) {
            synchronized (ui) {
                ui.destroy();
            }
        }
    }

    /**
     * Update character info when game UI becomes available.
     */
    public void updateFromGameUI() {
        NGameUI gui = getGameUI();
        if (gui != null) {
            // Extract character name from charname or chrid
            if (gui.chrid != null) {
                this.characterName = gui.chrid;
            }
            // Get genus for config
            if (gui.getGenus() != null) {
                this.genus = gui.getGenus();
                this.config = NConfig.getProfileInstance(this.genus);
            }
        }
    }
}
