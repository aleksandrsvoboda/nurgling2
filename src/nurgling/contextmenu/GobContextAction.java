package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;

public interface GobContextAction {
    boolean appliesTo(Gob gob);
    String label();
    Action create(Gob gob);

    /**
     * True for entries that only touch the interface (opening a window, toggling a setting) and
     * so must not be handed to the bot executor. {@link #create} is never called for those;
     * {@link #performUi} runs on the UI thread instead.
     */
    default boolean isUiAction() {
        return false;
    }

    /** Runs on the UI thread when {@link #isUiAction} is true. */
    default void performUi(Gob gob) {}
}
