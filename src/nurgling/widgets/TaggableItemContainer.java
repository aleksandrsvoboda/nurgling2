package nurgling.widgets;

import java.util.List;
import java.util.function.Consumer;

/**
 * Implemented by a {@link BaseIngredientContainer} that wants its own set of per-item right-click
 * tags shown in {@link IconItem}'s flower menu, alongside (not instead of) the existing Barter/
 * Barrel marking area containers use. Kept generic so a new container type can define its own
 * small, growable tag vocabulary (e.g. Forager's "Pick Fruit"/"Pick Nuts") without IconItem itself
 * needing to know what the tags mean.
 */
public interface TaggableItemContainer {
    /** The tags offered in the right-click menu for the given item, in display order. */
    List<String> tagOptions(String itemName);

    /** Set (or clear, if tag is null) the tag currently applied to the named item. */
    void setTag(String itemName, String tag);

    /** The tag currently applied to the named item, or null if none/default. */
    String getTag(String itemName);

    /**
     * Opens whatever full manual editor this container uses to correct an item's underlying
     * match pattern/action directly (e.g. when automatic resolution guessed wrong or couldn't
     * resolve one at all). Optional - default no-op for containers with nothing to edit beyond
     * the tag.
     */
    default void editItem(String itemName) {}

    /**
     * Prompts for a brand new action label, adds it to the shared/growable vocabulary
     * {@link #tagOptions} draws from, and applies it to the named item - the right-click menu's
     * way of growing that list instead of it being a fixed set. {@code onApplied} lets the
     * calling IconItem update its own displayed tag once the new one is created; called with the
     * new label, or not at all if the prompt was cancelled. Optional - default no-op.
     */
    default void promptNewTag(String itemName, Consumer<String> onApplied) {}
}
