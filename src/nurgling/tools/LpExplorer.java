package nurgling.tools;

import haven.Astronomy;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.widgets.NCharacterInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

// Tracks which LP-discoverable products (VSpec.object) the player has found for each gob type,
// including the Summer/Autumn "normal" vs Winter/Spring "Yesteryear's" split for fruit/berries.
public class LpExplorer {
    private static final String YESTERYEAR_PREFIX = "Yesteryear's ";

    // Products confirmed (via the -yester icon entries in VSpec) to actually spawn a "Yesteryear's "
    // variant in-game. For these, the normal and Yesteryear's pickup are tracked as two separate LP
    // discoveries instead of being merged into one. Everything else (catkins, pods, samaras, nuts, etc.)
    // isn't confirmed to have a Yesteryear variant, so it keeps the old merged behavior.
    private static final Set<String> yesteryearCapableProducts = new HashSet<>(Arrays.asList(
        "Cherry", "Fig", "Lemon", "Mulberry", "Orange", "Pear", "Persimmon", "Plum", "Quince",
        "Red Apple", "Wood Strawberry", "Blackberry", "Redcurrant", "Seaberries"
    ));

    // Off-season harvests are reported with a "Yesteryear's " prefix (e.g. "Yesteryear's Alder Catkin")
    // instead of the normal in-season product name; strip it to test membership against VSpec.object,
    // which only ever stores the normal (in-season) product names.
    private static String normalizeProductName(String name) {
        return name.startsWith(YESTERYEAR_PREFIX) ? name.substring(YESTERYEAR_PREFIX.length()) : name;
    }

    // Per the "Yesteryear's" patch: fruit trees/bushes carry fresh fruit through Summer and Autumn; unharvested
    // fruit then reads as "Yesteryear's " through Winter and Spring, until Summer replenishes it fresh again.
    private static Astronomy.Season currentSeason() {
        if (NUtils.getUI() == null || NUtils.getUI().sess == null || NUtils.getUI().sess.glob == null
            || NUtils.getUI().sess.glob.ast == null) {
            return null;
        }
        return NUtils.getUI().sess.glob.ast.season();
    }

    public static boolean hasUndiscoveredProduct(String gobResName) {
        if (gobResName == null || !VSpec.object.containsKey(gobResName))
            return false;
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.getCharInfo() == null)
            return false;

        NCharacterInfo info = gui.getCharInfo();
        Astronomy.Season season = currentSeason();
        boolean normalNow = season == Astronomy.Season.Summer || season == Astronomy.Season.Autumn;
        boolean yesteryearNow = season == Astronomy.Season.Winter || season == Astronomy.Season.Spring;
        for (String product : VSpec.object.get(gobResName)) {
            if (yesteryearCapableProducts.contains(product)) {
                if (normalNow && !info.IsLpExplorerContains(gobResName, product))
                    return true;
                if (yesteryearNow && !info.IsLpExplorerContains(gobResName, YESTERYEAR_PREFIX + product))
                    return true;
            } else if (!info.IsLpExplorerContains(gobResName, product)) {
                return true;
            }
        }
        return false;
    }

    public static void checkLpExplorer(Gob clickedGob, String name) {
        if (clickedGob == null)
            return;
        NGameUI gui = NUtils.getGameUI();
        try {
            String gobName = clickedGob.ngob.name;
            if (gui == null || gui.getCharInfo() == null || gobName == null || !VSpec.object.containsKey(gobName))
                return;

            String normalized = normalizeProductName(name);
            if (!VSpec.object.get(gobName).contains(normalized))
                return;

            // Kept distinct from the normal product for confirmed Yesteryear-capable products,
            // otherwise merged with the normal product as before.
            String key = (name.startsWith(YESTERYEAR_PREFIX) && yesteryearCapableProducts.contains(normalized))
                ? name : normalized;

            NCharacterInfo info = gui.getCharInfo();
            if (!info.IsLpExplorerContains(gobName, key)) {
                info.LpExplorerAdd(gobName, key);
                info.newLpExplorer = true;
            }
        } finally {
            if (gui != null && gui.map != null)
                gui.map.clickedGob = null;
        }
    }
}
