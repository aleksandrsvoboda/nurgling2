package nurgling.timers;

import nurgling.NConfig;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which character names are "me": every character logged in on this client, plus the alts the player
 * listed in Settings → Timers. Tasks and timers name characters, and the client cannot tell on its own
 * that two characters belong to one player, so a task for an alt would otherwise never reach them.
 */
public final class MyCharacters {
    private MyCharacters() {
    }

    public static boolean contains(String name) {
        if(name == null || name.isEmpty())
            return false;
        for(SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if(name.equals(ctx.characterName))
                return true;
        }
        for(String alt : listed()) {
            if(alt.equalsIgnoreCase(name))
                return true;
        }
        return false;
    }

    /** The alts from the settings, as typed: separated by commas or new lines. */
    public static List<String> listed() {
        Object v = NConfig.get(NConfig.Key.timerMyCharacters);
        if(!(v instanceof String) || ((String) v).trim().isEmpty())
            return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for(String part : ((String) v).split("[,\\n]")) {
            String n = part.trim();
            if(!n.isEmpty())
                out.add(n);
        }
        return out;
    }
}
