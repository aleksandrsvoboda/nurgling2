package nurgling.widgets;

import haven.*;
import nurgling.NAlarmManager;
import nurgling.NConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Plays "alarm/curio" when a curiosity finishes studying: a study report item that leaves the
 * inventory after its study meter reached the end. Curiosities taken out by hand leave with a
 * lower meter and stay silent.
 * <p>
 * Inventory has no removal hook, so this polls: it remembers each item's last seen meter and
 * checks it once the item is gone. The final meter update can arrive together with the removal,
 * so the threshold allows for the last seen value being one percent short.
 */
public class CurioFinishedAlert extends Widget {
    private static final double FINISHED = 0.99;

    private final Inventory study;
    private Map<WItem, Double> meters = new HashMap<>();

    public CurioFinishedAlert(Inventory study) {
        super(Coord.z);
        this.study = study;
    }

    private static double meter(WItem w) {
        if (w.item.meter > 0)
            return w.item.meter / 100.0;
        Double m = w.itemmeter.get();
        return (m == null) ? 0 : m;
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        Map<WItem, Double> now = new HashMap<>();
        for (WItem w : study.children(WItem.class)) {
            try {
                now.put(w, meter(w));
            } catch (Loading l) {
                Double prev = meters.get(w);
                if (prev != null)
                    now.put(w, prev);
            }
        }
        boolean finished = false;
        for (Map.Entry<WItem, Double> e : meters.entrySet()) {
            if (!now.containsKey(e.getKey()) && e.getValue() >= FINISHED)
                finished = true;
        }
        meters = now;
        if (finished && (Boolean) NConfig.get(NConfig.Key.curioFinishedSound))
            NAlarmManager.play("alarm/curio");
    }
}
