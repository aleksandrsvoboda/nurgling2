package nurgling.tools;

import haven.Buff;
import haven.GItem;
import haven.ItemInfo;
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
        return getScentOfTansyCount() >= 0;
    }

    /** Current Scent of Tansy stack count (the number overlaid on its buff icon - it rises on each
     *  application and falls on each midge bite), or -1 if the buff isn't active at all. */
    public static int getScentOfTansyCount() {
        try {
            haven.GameUI gui = NUtils.getGameUI();
            if (gui == null || gui.buffs == null) {
                return -1;
            }

            for (Widget w = gui.buffs.child; w != null; w = w.next) {
                if (!(w instanceof Buff)) continue;
                Buff buff = (Buff) w;
                try {
                    String resName = buff.res.get().name;
                    if (resName == null || !resName.toLowerCase().contains(SCENT_OF_TANSY_RES_HINT)) {
                        continue;
                    }
                    // Present but the numeric overlay isn't readable yet - treat as at least one stack
                    // rather than losing the match entirely.
                    int count = 1;
                    for (ItemInfo ii : buff.info()) {
                        if (ii instanceof GItem.NumberInfo) {
                            count = ((GItem.NumberInfo) ii).itemnum();
                            break;
                        }
                    }
                    return count;
                } catch (Loading l) {
                    // Resource/info not loaded yet, skip this buff widget
                } catch (Exception e) {
                    // Malformed info for this widget - skip it rather than fail the whole scan
                }
            }
        } catch (Exception e) {
            // Silently ignore errors
        }
        return -1;
    }
}
