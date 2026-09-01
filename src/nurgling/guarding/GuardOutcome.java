package nurgling.guarding;

import nurgling.NGameUI;
import nurgling.actions.TravelToHearthFire;
import nurgling.actions.bots.CoracleBot;

/**
 * A guard's reaction - performs it and returns only once it's actually complete (e.g. TRAVEL_HEARTH
 * waits for arrival, not just for the click to be sent). Always run on the bot's own thread,
 * after it's already been interrupted and stopped moving/pathing - never on the watcher thread
 * itself, so a multi-second outcome can't race the bot's own movement and get cancelled by it
 * (this is exactly the bug the old, single-purpose "travel hearth" safety action had to be
 * fixed for - see Forager.run()'s interrupt handling, which this replaces the string-based
 * dispatch of but keeps the same two-phase detect-then-perform structure for).
 * <p>
 * BREAK deliberately does nothing beyond returning - the run always stops once any guard has
 * fired, regardless of which outcome matched, so BREAK is the explicit "just stop, no extra
 * action" choice. Whether a guard runs at all any more is controlled by its own enabled toggle
 * (see GuardEntry), not by picking a "do nothing" outcome (there used to be a "nothing" action
 * that meant "checked, but ignore it" - removed per direct correction, since a check the user
 * doesn't want should be turned off entirely, not left silently inert).
 */
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
                // Can't hearth-fire home while mounted on a coracle - the character has to
                // physically dismount first. Best-effort: if pickup fails (e.g. surrounded by
                // deep water), still fall through to hearthing home anyway - this is an
                // emergency escape action, and losing the coracle is far better than getting
                // stuck next to danger over it.
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
