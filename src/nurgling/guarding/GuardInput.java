package nurgling.guarding;

/**
 * One configurable numeric input a {@link GuardTrigger}'s factory reads to build itself -
 * declared by a {@link GuardSpec} so the settings UI can render an appropriately-labeled field
 * generically, without Forager Settings needing hand-written UI per guard type.
 */
public final class GuardInput {
    public enum Kind { PERCENT, TILES, SECONDS }

    public final String key;
    public final Kind kind;
    public final String suffixLabel; // drawn right after the entry, e.g. "%", "tiles in", "s"
    public final double defaultValue;

    public GuardInput(String key, Kind kind, String suffixLabel, double defaultValue) {
        this.key = key;
        this.kind = kind;
        this.suffixLabel = suffixLabel;
        this.defaultValue = defaultValue;
    }
}
