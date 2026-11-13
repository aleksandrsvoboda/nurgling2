package nurgling.actions.bots.boatfishing;

import nurgling.NGItem;
import nurgling.conf.NBoatFishingSettings;
import nurgling.tools.NAlias;
import nurgling.tools.VSpec;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One kind of rod part: a chosen item, or "any" item of its category. */
final class Part {
    final String label;
    final List<String> names;
    /** Bait is only taken from the inventory: the pole re-baits from there and bait can't go in a creel. */
    final boolean inventoryOnly;

    Part(String label, List<String> names, boolean inventoryOnly) {
        this.label = label;
        this.names = names;
        this.inventoryOnly = inventoryOnly;
    }

    /** Line, hook, then bait (pole) or lure (casting rod). */
    static List<Part> of(NBoatFishingSettings prop) {
        List<Part> res = new ArrayList<>();
        res.add(make(prop.anyLine, prop.fishline, "Fishline", "any fishline", false));
        res.add(make(prop.anyHook, prop.hook, "Hooks", "any hook", false));
        if (prop.isPole())
            res.add(make(prop.anyBait, prop.bait, "Bait", "any bait", true));
        else
            res.add(make(prop.anyBait, prop.bait, "Lures", "any lure", false));
        return res;
    }

    private static Part make(boolean any, String chosen, String category, String anyLabel, boolean inventoryOnly) {
        if (!any)
            return new Part(chosen, Collections.singletonList(chosen), inventoryOnly);
        List<String> names = new ArrayList<>();
        List<JSONObject> cat = VSpec.categories.get(category);
        if (cat != null)
            for (JSONObject o : cat) {
                String n = o.optString("name", null);
                if (n != null && !n.isEmpty())
                    names.add(n);
            }
        return new Part(anyLabel, names, inventoryOnly);
    }

    boolean onRod(NGItem rod) {
        for (String n : names)
            if (rod.findContent(n))
                return true;
        return false;
    }

    NAlias alias() {
        return new NAlias(new ArrayList<>(names));
    }
}
