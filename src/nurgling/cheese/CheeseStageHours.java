package nurgling.cheese;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Real-life hours a tray spends on a rack for each cheese stage, keyed by the cheese the stage
 * produces and the place it ages in (same name/place pairs as {@link CheeseBranch}).
 * Values are the estimates from the cheese charts on https://ringofbrodgar.com/wiki/cheese.
 */
public class CheeseStageHours {
    private static final Map<String, Integer> HOURS;

    static {
        Map<String, Integer> h = new HashMap<>();
        // Cow's curd
        h.put(key("Creamy Camembert", CheeseBranch.Place.outside), 52);
        h.put(key("Tasty Emmentaler", CheeseBranch.Place.inside), 73);
        h.put(key("Musky Milben", CheeseBranch.Place.mine), 205);
        h.put(key("Cellar Cheddar", CheeseBranch.Place.cellar), 44);
        h.put(key("Brodgar Blue Cheese", CheeseBranch.Place.outside), 30);
        h.put(key("Jorbonzola", CheeseBranch.Place.mine), 52);
        h.put(key("Midnight Blue Cheese", CheeseBranch.Place.cellar), 154);
        h.put(key("Cave Cheddar", CheeseBranch.Place.mine), 15);
        h.put(key("Mothzarella", CheeseBranch.Place.mine), 30);
        h.put(key("Harmesan Cheese", CheeseBranch.Place.inside), 59);
        h.put(key("Sunlit Stilton", CheeseBranch.Place.outside), 52);
        // Sheep's curd
        h.put(key("Halloumi", CheeseBranch.Place.inside), 44);
        h.put(key("Feta", CheeseBranch.Place.cellar), 44);
        h.put(key("Caciotta", CheeseBranch.Place.inside), 146);
        h.put(key("Cabrales", CheeseBranch.Place.outside), 110);
        h.put(key("Pecorino", CheeseBranch.Place.outside), 37);
        h.put(key("Manchego", CheeseBranch.Place.mine), 88);
        h.put(key("Gbejna", CheeseBranch.Place.cellar), 110);
        h.put(key("Roncal", CheeseBranch.Place.mine), 146);
        h.put(key("Abbaye", CheeseBranch.Place.mine), 59);
        h.put(key("Zamorano", CheeseBranch.Place.cellar), 183);
        h.put(key("Brique", CheeseBranch.Place.inside), 219);
        h.put(key("Oscypki", CheeseBranch.Place.outside), 73);
        // Goat's curd
        h.put(key("Banon", CheeseBranch.Place.inside), 37);
        h.put(key("Robiola", CheeseBranch.Place.mine), 30);
        h.put(key("Bucheron", CheeseBranch.Place.outside), 22);
        h.put(key("Picodon", CheeseBranch.Place.cellar), 59);
        h.put(key("Graviera", CheeseBranch.Place.mine), 37);
        h.put(key("Gevrik", CheeseBranch.Place.inside), 52);
        h.put(key("Garrotxa", CheeseBranch.Place.mine), 117);
        h.put(key("Chabichou", CheeseBranch.Place.mine), 30);
        h.put(key("Chabis", CheeseBranch.Place.inside), 44);
        h.put(key("Formaela", CheeseBranch.Place.cellar), 66);
        h.put(key("Majorero", CheeseBranch.Place.outside), 73);
        h.put(key("Kasseri", CheeseBranch.Place.cellar), 59);
        HOURS = Collections.unmodifiableMap(h);
    }

    public static String key(String cheese, CheeseBranch.Place place) {
        return cheese + "@" + place.name();
    }

    /** Wiki hours for a stage, or -1 when the stage is unknown. */
    public static int defaultHours(String cheese, CheeseBranch.Place place) {
        Integer v = HOURS.get(key(cheese, place));
        return v == null ? -1 : v;
    }

    /** Hours for a stage, preferring a user override (keyed by {@link #key}). */
    public static int hours(String cheese, CheeseBranch.Place place, Map<String, Integer> overrides) {
        if (overrides != null) {
            Integer v = overrides.get(key(cheese, place));
            if (v != null && v > 0)
                return v;
        }
        return defaultHours(cheese, place);
    }
}
