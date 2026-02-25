package nurgling.sessions;

import haven.PMessage;

/**
 * Marker message indicating that a session is being promoted back to visual mode.
 * When this message is injected into a Session's message queue via
 * Session.injectMessage(), the background message loop will exit cleanly
 * so the session can be taken over by the foreground RemoteUI.
 */
public class PromotedMessage extends PMessage {
    public PromotedMessage() {
        super(-3);
    }
}
