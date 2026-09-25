package nurgling.widgets;

import haven.*;
import nurgling.NAlarmManager;
import nurgling.NConfig;

/**
 * Plays "alarm/curio" when a curiosity finishes studying: a study report item removed while its
 * study meter is at the end. Curiosities taken out by hand leave with a lower meter and stay silent.
 * <p>
 * Runs from the study inventory's item-removal hook, like Ard/Hurricane's InventoryStudy.cdestroy,
 * so it also catches the curiosities the server removes right after login: they arrive finished
 * and are taken away within the same burst of messages.
 */
public class CurioFinishedAlert {
    private static final double FINISHED = 0.99;

    private CurioFinishedAlert() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void removed(WItem w) {
        if (!(Boolean) NConfig.get(NConfig.Key.curioFinishedSound))
            return;
        double meter;
        if (w.item.meter > 0) {
            meter = w.item.meter / 100.0;
        } else {
            Double m;
            try {
                m = w.itemmeter.get();
            } catch (Loading l) {
                return;
            }
            meter = (m == null) ? 0 : m;
        }
        if (meter >= FINISHED)
            NAlarmManager.play("alarm/curio");
    }
}
