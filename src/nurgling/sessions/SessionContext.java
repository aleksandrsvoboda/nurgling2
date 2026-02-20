package nurgling.sessions;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.headless.HeadlessEnvironment;
import nurgling.headless.HeadlessPanel;

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

    /** The headless panel for background processing (when headless) */
    private HeadlessPanel headlessPanel;

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

        // Create headless panel for background processing
        headlessPanel = new HeadlessPanel();

        // DON'T change ui.env - widgets hold render tree slot references
        // that would become invalid. The headless loop only ticks game logic.

        // Wake up the RemoteUI message loop so it can detach to background
        if (session != null) {
            session.requestDetach();
        }

        // Start headless tick thread
        headlessThread = new Thread(() -> {
            try {
                runHeadlessLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Headless-" + sessionId);
        headlessThread.setDaemon(true);
        headlessThread.start();

        System.out.println("[SessionManager] Session " + getDisplayName() + " demoted to headless mode");
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

        headlessPanel = null;
        headless = false;

        // Wake up any blocked message readers (background thread checking isHeadless)
        if (session != null) {
            session.wakeupMessageQueue();
        }

        // Set the GL environment for rendering
        if (ui != null && env != null) {
            ui.env = env;
        }

        System.out.println("[SessionManager] Session " + getDisplayName() + " promoted to visual mode");
    }

    /**
     * Run the headless tick loop for this session.
     */
    private void runHeadlessLoop() throws InterruptedException {
        final double TICK_RATE = 20.0;
        final double TICK_DURATION = 1.0 / TICK_RATE;
        double lastTick = Utils.rtime();

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
                        // Wrap in try-catch as some widgets may have render-specific code
                        // that fails in headless mode
                        ui.tick();
                        ui.lastevent = now;
                    } catch (NullPointerException e) {
                        // Some widgets may throw NPE in headless mode due to
                        // render-related code - ignore and continue
                    } catch (Exception e) {
                        // Log other exceptions but continue ticking
                        System.err.println("[Headless] Tick error in session " + sessionId + ": " + e.getMessage());
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

        System.out.println("[SessionManager] Session " + getDisplayName() + " closed");
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
