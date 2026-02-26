package nurgling.sessions;

import haven.*;
import haven.render.Environment;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUI;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages multiple game sessions within a single client.
 *
 * One session is the "active" session and is rendered visually.
 * All other sessions run in headless mode (bots only, no rendering).
 *
 * This enables running multiple characters simultaneously with minimal
 * memory overhead (headless sessions use ~4MB vs ~200MB for visual).
 */
public class SessionManager {
    /** Singleton instance */
    private static SessionManager instance;

    /** All sessions, keyed by session ID */
    private final Map<String, SessionContext> sessions = new LinkedHashMap<>();

    /** The currently active (visual) session */
    private volatile SessionContext activeSession;

    /** Session to switch to (set during session switch, consumed by Bootstrap) */
    private volatile SessionContext pendingSwitchTo;

    /** Session to close after switch completes (for closing active session) */
    private volatile SessionContext pendingClose;

    /** Pending camera state to apply after session switch */
    private volatile CameraState pendingCameraState;

    /** Listeners for session changes */
    private final List<SessionChangeListener> listeners = new ArrayList<>();

    /** Lock for session operations */
    private final Object sessionsLock = new Object();

    /**
     * Get the singleton SessionManager instance.
     */
    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    /**
     * Interface for session change listeners.
     */
    public interface SessionChangeListener {
        /** Called when the active session changes */
        void onActiveSessionChanged(SessionContext oldSession, SessionContext newSession);

        /** Called when a session is added */
        void onSessionAdded(SessionContext session);

        /** Called when a session is removed */
        void onSessionRemoved(SessionContext session);
    }

    /**
     * Add a session change listener.
     */
    public void addListener(SessionChangeListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * Remove a session change listener.
     */
    public void removeListener(SessionChangeListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    /**
     * Add a new session.
     * If this is the first session, it becomes the active session.
     * Otherwise, it starts in headless mode.
     *
     * @param session The network session
     * @param ui The UI instance
     * @return The created SessionContext
     */
    public SessionContext addSession(Session session, NUI ui) {
        SessionContext ctx = new SessionContext(session, ui);

        synchronized (sessionsLock) {
            sessions.put(ctx.sessionId, ctx);

            if (activeSession == null) {
                // First session - make it active
                activeSession = ctx;
            } else {
                // Additional session - start in headless mode
                ctx.demoteToHeadless();
            }
        }

        // Notify listeners (SessionUIController will add tab bar)
        notifySessionAdded(ctx);

        return ctx;
    }

    /**
     * Add a new session and make it the active session.
     * Used for fresh logins where the new session should take priority.
     * Any existing active session will be demoted to headless.
     *
     * @param session The network session
     * @param ui The UI instance
     * @return The created SessionContext
     */
    public SessionContext addSessionAsActive(Session session, NUI ui) {
        SessionContext ctx = new SessionContext(session, ui);

        synchronized (sessionsLock) {
            // Demote any existing active session to headless
            SessionContext oldActive = activeSession;
            if (oldActive != null && !oldActive.isHeadless()) {
                oldActive.demoteToHeadless();
            }

            sessions.put(ctx.sessionId, ctx);
            activeSession = ctx;
        }

        // Notify listeners (SessionUIController will add tab bar)
        notifySessionAdded(ctx);

        return ctx;
    }

    /**
     * Remove a session.
     *
     * @param sessionId The session ID to remove
     */
    public void removeSession(String sessionId) {
        SessionContext ctx;
        boolean wasActive = false;

        synchronized (sessionsLock) {
            ctx = sessions.remove(sessionId);
            if (ctx == null) {
                return;
            }

            wasActive = (ctx == activeSession);
            if (wasActive) {
                activeSession = null;
                // Switch to another session if available
                if (!sessions.isEmpty()) {
                    SessionContext next = sessions.values().iterator().next();
                    switchToSessionInternal(next);
                }
            }
        }

        // Close the session
        ctx.close();

        // Notify listeners
        notifySessionRemoved(ctx);
    }

    /**
     * Request to close a session. If it's the active session, switches to another
     * session first and closes after the switch completes.
     *
     * @param sessionId The session ID to close
     */
    public void requestCloseSession(String sessionId) {
        synchronized (sessionsLock) {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx == null) {
                return;
            }

            // If closing the active session and there are other sessions
            if (ctx == activeSession && sessions.size() > 1) {
                // Mark for pending close
                pendingClose = ctx;

                // Switch to another session first
                for (SessionContext other : sessions.values()) {
                    if (other != ctx) {
                        switchToSessionInternal(other);
                        break;
                    }
                }
                // The actual close will happen in processPendingClose()
            } else {
                // Not active or only session - close directly
                removeSession(sessionId);
            }
        }
    }

    /**
     * Process any pending session close. Called after a session switch completes.
     */
    public void processPendingClose() {
        SessionContext toClose = pendingClose;
        pendingClose = null;
        if (toClose != null) {
            removeSession(toClose.sessionId);
        }
    }

    /**
     * Switch to a different session (make it active/visual).
     *
     * @param sessionId The session ID to switch to
     */
    public void switchToSession(String sessionId) {
        synchronized (sessionsLock) {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx == null || ctx == activeSession) {
                return;
            }
            switchToSessionInternal(ctx);
        }
    }

    /**
     * Switch to the next session in the list (wraps around).
     */
    public void switchToNextSession() {
        synchronized (sessionsLock) {
            if (sessions.size() <= 1) {
                return; // Only one or no sessions
            }

            List<SessionContext> sessionList = new ArrayList<>(sessions.values());
            int currentIndex = sessionList.indexOf(activeSession);
            int nextIndex = (currentIndex + 1) % sessionList.size();
            switchToSessionInternal(sessionList.get(nextIndex));
        }
    }

    /**
     * Switch to the previous session in the list (wraps around).
     */
    public void switchToPreviousSession() {
        synchronized (sessionsLock) {
            if (sessions.size() <= 1) {
                return; // Only one or no sessions
            }

            List<SessionContext> sessionList = new ArrayList<>(sessions.values());
            int currentIndex = sessionList.indexOf(activeSession);
            int prevIndex = (currentIndex - 1 + sessionList.size()) % sessionList.size();
            switchToSessionInternal(sessionList.get(prevIndex));
        }
    }

    /**
     * Switch to a session by its index in the list (0-based).
     *
     * @param index The index of the session (0-9 for Alt+1-0)
     */
    public void switchToSessionByIndex(int index) {
        synchronized (sessionsLock) {
            if (index < 0 || index >= sessions.size()) {
                return; // Index out of range
            }

            List<SessionContext> sessionList = new ArrayList<>(sessions.values());
            switchToSessionInternal(sessionList.get(index));
        }
    }

    /**
     * Internal method to switch sessions.
     * Must be called with sessionsLock held.
     */
    private void switchToSessionInternal(SessionContext newActive) {
        SessionContext oldActive = activeSession;

        // Capture camera state from old session if sync is enabled
        pendingCameraState = null;
        if (oldActive != null && oldActive != newActive) {
            try {
                Object syncCamera = NConfig.get(NConfig.Key.sync_camera);
                if (syncCamera != null && (Boolean) syncCamera) {
                    NGameUI gui = oldActive.getGameUI();
                    if (gui != null && gui.map != null && gui.map.camera != null) {
                        pendingCameraState = gui.map.camera.captureState();
                    }
                }
            } catch (Exception e) {
                // Ignore errors in camera state capture
                System.err.println("[SessionManager] Error capturing camera state: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // If new session is headless, mark it for Bootstrap to pick up
        if (newActive.isHeadless()) {
            pendingSwitchTo = newActive;
        }

        // Demote old active to headless (this triggers Bootstrap via requestDetach)
        if (oldActive != null && oldActive != newActive) {
            oldActive.demoteToHeadless();
        }

        // Set new session as active
        activeSession = newActive;

        // Notify listeners
        notifyActiveSessionChanged(oldActive, newActive);
    }

    /**
     * Apply pending camera state to the given session.
     * Called after promoting a session to visual mode.
     */
    public void applyPendingCameraState(SessionContext session) {
        if (session != null && pendingCameraState != null) {
            try {
                NGameUI gui = session.getGameUI();
                if (gui != null && gui.map != null && gui.map.camera != null) {
                    gui.map.camera.applyState(pendingCameraState);
                }
            } catch (Exception e) {
                // Ignore errors in camera state apply
                System.err.println("[SessionManager] Error applying camera state: " + e.getMessage());
                e.printStackTrace();
            } finally {
                pendingCameraState = null;
            }
        }
    }

    /**
     * Get the active (visual) session.
     */
    public SessionContext getActiveSession() {
        return activeSession;
    }

    /**
     * Clear the active session reference.
     * Used when demoting a session to headless to allow the next session to become active.
     */
    public void clearActiveSession() {
        synchronized (sessionsLock) {
            activeSession = null;
        }
    }

    /**
     * Set a session as the active session.
     * Used when a session is promoted or when ensuring the rendering session is active.
     */
    public void setActiveSession(SessionContext session) {
        SessionContext oldActive = null;
        boolean changed = false;
        synchronized (sessionsLock) {
            if (session == null || !sessions.containsValue(session)) {
                return;
            }
            if (activeSession != session) {
                oldActive = activeSession;
                activeSession = session;
                changed = true;
            }
        }
        if (changed) {
            notifyActiveSessionChanged(oldActive, session);
        }
    }

    /**
     * Remove a session from tracking without calling close() on it.
     * Used when the server has already disconnected the session (e.g., duplicate login).
     */
    public void removeSessionSilently(String sessionId) {
        SessionContext ctx;
        synchronized (sessionsLock) {
            ctx = sessions.remove(sessionId);
            if (ctx == null) {
                return;
            }
            if (ctx == activeSession) {
                activeSession = null;
            }
        }
        // Don't call ctx.close() - server already closed it
        // Just notify listeners
        notifySessionRemoved(ctx);
    }

    /**
     * Clear and return the pending session to switch to.
     * Returns null if no switch is pending.
     */
    public SessionContext consumePendingSwitchTo() {
        SessionContext pending = pendingSwitchTo;
        pendingSwitchTo = null;
        return pending;
    }

    /**
     * Get all sessions.
     */
    public Collection<SessionContext> getAllSessions() {
        synchronized (sessionsLock) {
            return new ArrayList<>(sessions.values());
        }
    }

    /**
     * Get a session by ID.
     */
    public SessionContext getSession(String sessionId) {
        synchronized (sessionsLock) {
            return sessions.get(sessionId);
        }
    }

    /**
     * Get the number of sessions.
     */
    public int getSessionCount() {
        synchronized (sessionsLock) {
            return sessions.size();
        }
    }

    /**
     * Check if there are any sessions.
     */
    public boolean hasSessions() {
        synchronized (sessionsLock) {
            return !sessions.isEmpty();
        }
    }

    /**
     * Find a session by its UI instance.
     */
    public SessionContext findByUI(UI ui) {
        synchronized (sessionsLock) {
            for (SessionContext ctx : sessions.values()) {
                if (ctx.ui == ui) {
                    return ctx;
                }
            }
        }
        return null;
    }

    /**
     * Find a session by its network Session instance.
     * Used during session switching to find existing sessions.
     */
    public SessionContext findBySession(Session sess) {
        synchronized (sessionsLock) {
            for (SessionContext ctx : sessions.values()) {
                if (ctx.session == sess) {
                    return ctx;
                }
            }
        }
        return null;
    }

    /**
     * Find a session by username.
     * Used to detect duplicate logins (same account logged in twice).
     */
    public SessionContext findByUsername(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        synchronized (sessionsLock) {
            for (SessionContext ctx : sessions.values()) {
                if (username.equals(ctx.username)) {
                    return ctx;
                }
            }
        }
        return null;
    }

    /**
     * Get the currently active UI.
     * This is the primary method for code that needs "the current UI".
     */
    public NUI getActiveUI() {
        SessionContext active = activeSession;
        return (active != null) ? active.ui : null;
    }

    /**
     * Close all sessions and clean up.
     */
    public void closeAll() {
        List<SessionContext> toClose;
        synchronized (sessionsLock) {
            toClose = new ArrayList<>(sessions.values());
            sessions.clear();
            activeSession = null;
        }

        for (SessionContext ctx : toClose) {
            ctx.close();
            notifySessionRemoved(ctx);
        }
    }

    // Notification helpers

    private void notifyActiveSessionChanged(SessionContext oldSession, SessionContext newSession) {
        List<SessionChangeListener> listenersCopy;
        synchronized (listeners) {
            listenersCopy = new ArrayList<>(listeners);
        }
        for (SessionChangeListener listener : listenersCopy) {
            try {
                listener.onActiveSessionChanged(oldSession, newSession);
            } catch (Exception e) {
                System.err.println("[SessionManager] Listener error: " + e.getMessage());
            }
        }
    }

    private void notifySessionAdded(SessionContext session) {
        List<SessionChangeListener> listenersCopy;
        synchronized (listeners) {
            listenersCopy = new ArrayList<>(listeners);
        }
        for (SessionChangeListener listener : listenersCopy) {
            try {
                listener.onSessionAdded(session);
            } catch (Exception e) {
                System.err.println("[SessionManager] Listener error: " + e.getMessage());
            }
        }
    }

    private void notifySessionRemoved(SessionContext session) {
        List<SessionChangeListener> listenersCopy;
        synchronized (listeners) {
            listenersCopy = new ArrayList<>(listeners);
        }
        for (SessionChangeListener listener : listenersCopy) {
            try {
                listener.onSessionRemoved(session);
            } catch (Exception e) {
                System.err.println("[SessionManager] Listener error: " + e.getMessage());
            }
        }
    }
}
