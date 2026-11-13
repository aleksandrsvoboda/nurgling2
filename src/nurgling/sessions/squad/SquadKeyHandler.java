package nurgling.sessions.squad;

import java.awt.event.KeyEvent;

/** A plugin's keys in Commander mode, offered the keys core doesn't use itself, on the viewpoint's UI thread. */
public interface SquadKeyHandler {
    /** @return true when the key was used. */
    boolean key(KeyEvent ev);
}
