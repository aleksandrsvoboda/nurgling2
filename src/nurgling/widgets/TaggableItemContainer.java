package nurgling.widgets;

/**
 * Implemented by a {@link BaseIngredientContainer} that wants an "Edit" entry in {@link IconItem}'s
 * right-click menu, alongside (not instead of) the existing Barter/Barrel marking area containers
 * use - e.g. Forager's pickup list, where an item's automatically-resolved match pattern/flower-
 * menu action can be wrong and needs a direct fix.
 */
public interface TaggableItemContainer {
    /**
     * Opens whatever full manual editor this container uses to correct an item's underlying
     * match pattern/action directly (e.g. when automatic resolution guessed wrong).
     */
    void editItem(String itemName);
}
