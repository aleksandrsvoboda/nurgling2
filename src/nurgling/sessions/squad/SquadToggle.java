package nurgling.sessions.squad;

import nurgling.sessions.SessionContext;

import java.util.List;

/**
 * A button a plugin adds to the squad panel, acting on the selected characters. Called on the viewpoint's UI thread:
 * it may read the members' reports and post orders to them ({@link Squad#send}), but must not reach into their widgets.
 */
public interface SquadToggle {
    /** Off: nobody selected has it. */
    int OFF = 0;
    /** Some of the selection have it (drawn outlined). */
    int SOME = 1;
    /** All of them do (drawn filled). */
    int ON = 2;

    String label(List<SessionContext> members);

    int state(List<SessionContext> members);

    void click(List<SessionContext> members);
}
