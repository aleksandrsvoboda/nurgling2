package nurgling.areas;

import java.util.Locale;

/**
 * The order in which a zone is worked through: both the corner new objects are
 * placed from and the corner existing objects are visited from.
 *
 * {@link #DEFAULT} is not a direction — it is "whatever the code did before this
 * setting existed", and the two paths it feeds have different legacy behaviour:
 *
 *   - placement ({@link nurgling.tools.Finder#getFreePlace}) already scanned
 *     x ascending then y ascending, so DEFAULT resolves to {@link #LEFT_TO_RIGHT}
 *     there and nothing changes;
 *   - enumeration ({@link nurgling.tools.Finder#sort}) picked its primary axis
 *     from the cluster's aspect ratio and sorted *descending*, which is not any
 *     of the four directions, so DEFAULT keeps that comparator instead.
 *
 * Because of that asymmetry the field must default to DEFAULT and not to
 * LEFT_TO_RIGHT: defaulting to a real direction would silently reverse container
 * fill order in every existing zone on first launch.
 */
public enum PileFillDirection {
    /** Legacy behaviour; see the class comment. Also what the JSON fallback returns. */
    DEFAULT,
    /** Columns left to right, top to bottom within a column. */
    LEFT_TO_RIGHT,
    /** Columns right to left, top to bottom within a column. */
    RIGHT_TO_LEFT,
    /** Rows top to bottom, left to right within a row. */
    TOP_TO_BOTTOM,
    /** Rows bottom to top, left to right within a row. */
    BOTTOM_TO_TOP;

    /**
     * Read a stored value. Missing, blank and unrecognised values all fall back to
     * {@link #DEFAULT} so a zone written by an older client - or by a future one
     * that grew a sixth direction - still loads instead of throwing mid-sync.
     */
    public static PileFillDirection fromStored(Object value) {
        if (value == null)
            return DEFAULT;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty())
            return DEFAULT;
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    /** The direction the placement scan should use; DEFAULT means the legacy scan order. */
    public PileFillDirection forPlacement() {
        return this == DEFAULT ? LEFT_TO_RIGHT : this;
    }
}
