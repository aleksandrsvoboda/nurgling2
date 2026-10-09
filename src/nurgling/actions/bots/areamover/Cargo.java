package nurgling.actions.bots.areamover;

import haven.Indir;
import haven.Loading;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.NTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * What the Area Mover is carrying: the player inventory minus what was in it when the run started.
 * Counting per item name (not per widget) keeps the baseline correct when a taken item stacks onto
 * one the player already had, so the waterskin and tools you started with never get unloaded.
 */
class Cargo {
    private final Map<String, Integer> baseline;

    Cargo(NGameUI gui) throws InterruptedException {
        baseline = countByName(gui);
    }

    static Map<String, Integer> countByName(NGameUI gui) throws InterruptedException {
        Map<String, Integer> counts = new HashMap<>();
        for (WItem item : gui.getInventory().getItems()) {
            String name = ((NGItem) item.item).name();
            if (name != null)
                counts.merge(name, 1, Integer::sum);
        }
        return counts;
    }

    /** Carried count per item name; names with nothing carried are left out. */
    Map<String, Integer> current(NGameUI gui) throws InterruptedException {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Integer> e : countByName(gui).entrySet()) {
            int carried = e.getValue() - baseline.getOrDefault(e.getKey(), 0);
            if (carried > 0)
                result.put(e.getKey(), carried);
        }
        return result;
    }

    int count(NGameUI gui, String name) throws InterruptedException {
        return Math.max(0, items(gui, name).size() - baseline.getOrDefault(name, 0));
    }

    int total(NGameUI gui) throws InterruptedException {
        int total = 0;
        for (int c : current(gui).values())
            total += c;
        return total;
    }

    /** Every inventory item with exactly this name, carried or not - same-name items are interchangeable. */
    static ArrayList<WItem> items(NGameUI gui, String name) throws InterruptedException {
        ArrayList<WItem> result = new ArrayList<>();
        for (WItem item : gui.getInventory().getItems(name)) {
            if (name.equals(((NGItem) item.item).name()))
                result.add(item);
        }
        return result;
    }

    /** Resource name of an inventory item, waiting for it to load. */
    static String resName(WItem item) {
        return resName(item.item.res);
    }

    static String resName(Indir<Resource> res) {
        if (res == null)
            return null;
        try {
            return res.get().name;
        } catch (Loading l) {
            return null;
        }
    }

    static String waitResName(Indir<Resource> res) throws InterruptedException {
        if (res == null)
            return null;
        String[] out = {null};
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                out[0] = resName(res);
                return out[0] != null;
            }
        });
        return out[0];
    }
}
