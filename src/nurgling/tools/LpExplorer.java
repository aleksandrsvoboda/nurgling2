package nurgling.tools;

import haven.Gob;
import nurgling.NUtils;
import nurgling.widgets.NCharacterInfo;

import java.util.ArrayList;
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

    // Season indices, matching haven.Astronomy.Season's declaration order (Spring, Summer, Autumn, Winter).
    private static final int SEASON_SPRING = 0, SEASON_SUMMER = 1, SEASON_AUTUMN = 2, SEASON_WINTER = 3;

    // Off-season harvests are reported with a "Yesteryear's " prefix (e.g. "Yesteryear's Alder Catkin")
    // instead of the normal in-season product name; strip it to test membership against VSpec.object,
    // which only ever stores the normal (in-season) product names.
    private static String normalizeProductName(String name) {
        return name.startsWith(YESTERYEAR_PREFIX) ? name.substring(YESTERYEAR_PREFIX.length()) : name;
    }

    // The key actually stored in/looked up from the LP explorer: kept distinct from the normal product
    // for confirmed Yesteryear-capable products, otherwise merged with the normal product as before.
    private static String lpExplorerKey(String name) {
        if (name.startsWith(YESTERYEAR_PREFIX) && yesteryearCapableProducts.contains(name.substring(YESTERYEAR_PREFIX.length()))) {
            return name;
        }
        return normalizeProductName(name);
    }

    // Total distinct discoveries expected for a gob type: one per product, plus one more for each
    // product that's confirmed to also spawn a separate Yesteryear's variant.
    private static int expectedDiscoveryCount(String gobResName) {
        ArrayList<String> products = VSpec.object.get(gobResName);
        int total = products.size();
        for (String product : products) {
            if (yesteryearCapableProducts.contains(product)) {
                total++;
            }
        }
        return total;
    }

    // Per the "Yesteryear's" patch: fruit trees/bushes carry fresh fruit through Summer and Autumn; unharvested
    // fruit then reads as "Yesteryear's " through Winter and Spring, until Summer replenishes it fresh again.
    private static boolean normalFruitObtainableNow() {
        int s = currentSeasonIndex();
        return s == SEASON_SUMMER || s == SEASON_AUTUMN;
    }

    private static boolean yesteryearFruitObtainableNow() {
        int s = currentSeasonIndex();
        return s == SEASON_WINTER || s == SEASON_SPRING;
    }

    private static int currentSeasonIndex() {
        if (NUtils.getUI() == null || NUtils.getUI().sess == null || NUtils.getUI().sess.glob == null
            || NUtils.getUI().sess.glob.ast == null) {
            return -1;
        }
        return NUtils.getUI().sess.glob.ast.is;
    }

    public static boolean hasUndiscoveredProduct(String gobResName) {
        if (gobResName == null || !gobResName.startsWith("gfx/terobjs") || !VSpec.object.containsKey(gobResName)
            || NUtils.getGameUI() == null || NUtils.getGameUI().getCharInfo() == null) {
            return false;
        }
        NCharacterInfo info = NUtils.getGameUI().getCharInfo();
        for (String product : VSpec.object.get(gobResName)) {
            if (yesteryearCapableProducts.contains(product)) {
                if (normalFruitObtainableNow() && !info.IsLpExplorerContains(gobResName, product)) {
                    return true;
                }
                if (yesteryearFruitObtainableNow() && !info.IsLpExplorerContains(gobResName, YESTERYEAR_PREFIX + product)) {
                    return true;
                }
            } else {
                if (!info.IsLpExplorerContains(gobResName, product)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void checkLpExplorer(Gob clickedGob, String name) {
        if (clickedGob != null) {
            String normalized = normalizeProductName(name);
            String key = lpExplorerKey(name);
            if (NUtils.getGameUI() != null && NUtils.getGameUI().getCharInfo() != null) {
                if (clickedGob.ngob.name != null && VSpec.object.containsKey(clickedGob.ngob.name)) {
                    if (VSpec.object.get(clickedGob.ngob.name).contains(normalized)) {
                        boolean objectExists = NUtils.getGameUI().getCharInfo().IsLpExplorerContains(clickedGob.ngob.name);

                        if (!objectExists) {
                            NUtils.getGameUI().getCharInfo().LpExplorerAdd(clickedGob.ngob.name, key);
                            NUtils.getGameUI().getCharInfo().newLpExplorer = true;

                        } else {
                            int currentSize = NUtils.getGameUI().getCharInfo().LpExplorerGetSize(clickedGob.ngob.name);
                            int totalSize = expectedDiscoveryCount(clickedGob.ngob.name);
                            boolean productExists = NUtils.getGameUI().getCharInfo().IsLpExplorerContains(clickedGob.ngob.name, key);

                            if (currentSize != totalSize) {
                                if (VSpec.object.get(clickedGob.ngob.name).contains(normalized) && !productExists) {
                                    NUtils.getGameUI().getCharInfo().LpExplorerAdd(clickedGob.ngob.name, key);
                                    NUtils.getGameUI().getCharInfo().newLpExplorer = true;
                                }
                            }
                        }
                    }
                }
            }
            if (NUtils.getGameUI() != null && NUtils.getGameUI().map != null) {
                NUtils.getGameUI().map.clickedGob = null;
            }
        }
    }
}
