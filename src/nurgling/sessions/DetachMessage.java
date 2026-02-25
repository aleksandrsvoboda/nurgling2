package nurgling.sessions;

import haven.PMessage;

/**
 * Marker message indicating that a session should detach to headless mode.
 * When this message is injected into a Session's message queue via
 * Session.injectMessage(), the RemoteUI will spawn a background thread
 * and return Bootstrap to start a new login flow.
 */
public class DetachMessage extends PMessage {
    public DetachMessage() {
        super(-2);
    }
}
