package nurgling.sessions.squad;

import nurgling.NGameUI;
import nurgling.sessions.SessionContext;

/**
 * Lets a plugin run squad orders itself. Handlers are offered every order a member receives, in that member's own
 * tick on its own UI thread, so they may touch that session's widgets and send its messages, but must not block.
 *
 * An order that needs the character has already passed the busy-bot check when it is offered, and the member's
 * running squad order is cancelled when a handler takes it. Stop is offered too, so a plugin can drop its own jobs,
 * but core stops the character whatever the handler returns.
 */
public interface SquadOrderHandler {
    /** @return true when this handler carried the order out and core should not. */
    boolean take(SessionContext member, NGameUI gui, SquadCommand order);
}
