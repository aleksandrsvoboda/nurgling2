package nurgling.sessions;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
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
        // First try cached character name
        if (characterName != null && !characterName.isEmpty()) {
            return characterName;
        }
        // Try to get character name from gameUI (dynamic lookup)
        NGameUI gui = getGameUI();
        if (gui != null && gui.chrid != null && !gui.chrid.isEmpty()) {
            this.characterName = gui.chrid; // Cache it
            return characterName;
        }
        // Fall back to username
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
     * Check if this session has any bots running.
     * Checks the BotsInterruptWidget's observed threads list.
     */
    public boolean isRunningBot() {
        NGameUI gui = getGameUI();
        if (gui != null && gui.biw != null) {
            return gui.biw.hasRunningBots();
        }
        return false;
    }

    /**
     * Check if this session is in combat.
     * Detects combat by checking if Fightview exists, is visible, and has active opponents.
     */
    public boolean isInCombat() {
        NGameUI gui = getGameUI();
        if (gui == null || gui.fv == null) {
            return false;
        }
        // Check if fightview is visible AND has actual opponents
        // lsrel (list of relations) contains the opponents - if empty, not in combat
        return gui.fv.tvisible() && gui.fv.lsrel != null && !gui.fv.lsrel.isEmpty();
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

        // Clear path line visualization - prevents frozen lines when switching back
        NGameUI gui = getGameUI();
        if (gui != null && gui.map instanceof NMapView) {
            ((NMapView)gui.map).clickDestination = null;
            // Clear path data and detach from render tree to prevent cross-session rendering
            if (gui.map.glob != null && gui.map.glob.oc != null) {
                gui.map.glob.oc.paths.clear();
                gui.map.glob.oc.paths.detachFromRenderTree();
            }
        }

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

        // Re-attach path visualizer to render tree
        NGameUI gui = getGameUI();
        if (gui != null && gui.map instanceof NMapView) {
            NMapView mapView = (NMapView)gui.map;
            // Only re-attach if not already attached
            if (mapView.glob != null && mapView.glob.oc != null &&
                !mapView.glob.oc.paths.isAttachedToRenderTree()) {
                mapView.basic.add(mapView.glob.oc.paths);
            }
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
                        } catch (haven.render.RenderTree.SlotRemoved e) {
                            // Widgets trying to update render state in headless mode - ignore
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
