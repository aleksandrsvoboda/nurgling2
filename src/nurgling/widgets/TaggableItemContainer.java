package nurgling.widgets;

/** Implemented by a {@link BaseIngredientContainer} that wants an "Edit" entry in {@link IconItem}'s right-click menu, alongside the existing Barter/Barrel marks. */
public interface TaggableItemContainer {
    /** Opens the manual editor to correct an item's underlying match pattern/action directly. */
    void editItem(String itemName);

    /** Sets the target inventory quantity at which this container should stop acquiring more of the item; -1 means no cap. */
    void setMaintainQuantity(String itemName, int quantity);

    /** The currently saved maintain quantity for this item, or -1 if unset. */
    int getMaintainQuantity(String itemName);

    /** Sets which order this item should be checked in relative to others when foraging - lower first, -1 means unset/checked last. */
    void setPriority(String itemName, int priority);

    /** The currently saved priority for this item, or -1 if unset. */
    int getPriority(String itemName);
}
