package nurgling.guarding;

import nurgling.NGameUI;
import nurgling.actions.TravelToHearthFire;
import nurgling.actions.bots.CoracleBot;

/** A guard's reaction - performs it and returns only once actually complete; always run on the bot's own thread after it's stopped moving, never the watcher thread, so a multi-second outcome can't race the bot's own movement. BREAK just stops (a guard the user doesn't want should be disabled via GuardEntry, not given a "do nothing" outcome). */
public enum GuardOutcome {
    BREAK,
    LOGOUT,
    TRAVEL_HEARTH;

    public void perform(NGameUI gui) throws InterruptedException {
        switch (this) {
            case LOGOUT:
                gui.act("lo");
                break;
            case TRAVEL_HEARTH:
                // Can't hearth-fire while mounted - dismount first, best-effort (fall through and hearth anyway if it fails).
                if (CoracleBot.isPlayerInCoracle(gui)) {
                    new CoracleBot().run(gui);
                }
                new TravelToHearthFire().run(gui);
                break;
            case BREAK:
            default:
                break;
        }
    }

    public String id() {
        switch (this) {
            case LOGOUT: return "logout";
            case TRAVEL_HEARTH: return "travel hearth";
            case BREAK:
            default: return "break";
        }
    }

    public static GuardOutcome fromId(String id) {
        if ("logout".equals(id)) return LOGOUT;
        if ("travel hearth".equals(id)) return TRAVEL_HEARTH;
        return BREAK;
    }

    public static final String[] ALL_IDS = {"break", "logout", "travel hearth"};
}
