package nurgling.tools;

import haven.Buff;
import haven.Loading;
import haven.Widget;
import nurgling.NUtils;

/**
 * Utility class for checking active buffs in the buff bar.
 */
public class NBuffChecker {

    // Matched against Buff's underlying resource path (case-insensitive substring) rather than a
    // full exact path, since the precise resource path for this buff hasn't been confirmed against
    // the live resource server yet - "tansy" is distinctive enough that a substring match is safe.
    private static final String SCENT_OF_TANSY_RES_HINT = "tansy";

    /** True if the "Scent of Tansy" buff (which suppresses midge bites) is currently active. */
    public static boolean hasScentOfTansy() {
        try {
            haven.GameUI gui = NUtils.getGameUI();
            if (gui == null || gui.buffs == null) {
                return false;
            }

            for (Widget w = gui.buffs.child; w != null; w = w.next) {
                if (!(w instanceof Buff)) continue;
                try {
                    String resName = ((Buff) w).res.get().name;
                    if (resName != null && resName.toLowerCase().contains(SCENT_OF_TANSY_RES_HINT)) {
                        return true;
                    }
                } catch (Loading l) {
                    // Resource not loaded yet, skip
                }
            }
        } catch (Exception e) {
            // Silently ignore errors
        }
        return false;
    }
}
