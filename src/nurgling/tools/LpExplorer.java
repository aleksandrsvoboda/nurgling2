package nurgling.tools;

import haven.Drawable;
import haven.Gob;
import haven.ResDrawable;
import haven.Resource;
import haven.TexI;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.overlays.NObjHarvestOl;
import nurgling.widgets.NCharacterInfo;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

// Tracks which LP-discoverable products (VSpec.object) the player has found for each gob type.
// Both the normal and "Yesteryear's " variant of seasonal fruit are literal, independently-tracked
// entries in VSpec.object (see HarvestState's season-aware icon resolution for the counterpart that
// decides which of the two is currently displayed) - so recording a discovery is a direct name
// match, no normalization needed.
public class LpExplorer {
    public static boolean isEnabled() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.lpassistent));
    }

    // True if this product is the one that could actually be picked up right now - i.e. it either
    // has no seasonal counterpart at all, or it's the variant matching the current season. Whether
    // a seasonal pair exists at all is derived from VSpec.object itself (does the sibling name
    // appear in this same resource's product list) rather than a separately hand-maintained
    // species list, so there's nothing to keep in sync when a new species is added.
    private static boolean isCurrentSeasonProduct(String gobResName, String product) {
        boolean isYesteryearVariant = product.startsWith(HarvestState.YESTERYEAR_PREFIX);
        String base = isYesteryearVariant ? product.substring(HarvestState.YESTERYEAR_PREFIX.length()) : product;
        String sibling = isYesteryearVariant ? base : (HarvestState.YESTERYEAR_PREFIX + base);
        List<String> products = VSpec.object.get(gobResName);
        if (products == null || !products.contains(sibling))
            return true;
        return isYesteryearVariant == HarvestState.isYesteryearSeason();
    }

    // Throws haven.Loading if the gob's sprite hasn't loaded yet - propagated to the caller,
    // same as HarvestState.hasHarvestableSeed() itself does.
    public static boolean hasUndiscoveredProduct(Gob gob) {
        return firstUndiscoveredProduct(gob) != null;
    }

    // Same check as hasUndiscoveredProduct(), but also returns which product it is - lets callers
    // that need both (e.g. MinimapDiscoveryRenderer, which gates on this and then resolves an
    // icon for the same product) avoid running the discovery scan twice per gob.
    public static String firstUndiscoveredProduct(Gob gob) {
        List<String> products = allUndiscoveredProducts(gob);
        return products.isEmpty() ? null : products.get(0);
    }

    // Every currently-undiscovered product this gob tracks, not just the first - lets markers
    // (e.g. NLPassistant) show every still-undiscovered icon at once instead of just one at a
    // time, matching how NObjHarvestOl stacks leaf/seed/bough/bark simultaneously.
    public static List<String> allUndiscoveredProducts(Gob gob) {
        if (gob == null || gob.ngob == null)
            return Collections.emptyList();
        String gobResName = gob.ngob.name;

        // Only flag gobs that currently, actually show something harvestable - not just any
        // instance of a resource type that's ever capable of producing an undiscovered product.
        if (HarvestState.isTreeOrBushRes(gobResName) && !HarvestState.hasHarvestableSeed(gob))
            return Collections.emptyList();

        return undiscoveredProductsMatching(gobResName, product -> true);
    }

    /** Which harvest categories (seed/leaf/bough) still have an undiscovered product for a resource. */
    public static class UndiscoveredCategories {
        public final boolean seed, leaf, bough;
        private UndiscoveredCategories(boolean seed, boolean leaf, boolean bough) {
            this.seed = seed;
            this.leaf = leaf;
            this.bough = bough;
        }
    }

    private static final UndiscoveredCategories NONE_UNDISCOVERED = new UndiscoveredCategories(false, false, false);

    // VSpec.object lists every trackable product for a resource with no category metadata at all
    // (e.g. figtree -> ["Fig Leaf", "Fig"]), so a blanket "is anything undiscovered" check can't
    // tell which of several simultaneously-shown icons (seed/leaf/bough) it actually applies to -
    // tinting the wrong one, or leaving the right one untinted, whenever a species tracks more
    // than one product. The data does follow a reliable naming convention though (confirmed by
    // grepping every multi-product entry in VSpec.java): leaf products always contain "Leaf" or
    // "Leaves" (e.g. "Fig Leaf", "Laurel Leaves"), bough products always contain "Bough" (e.g.
    // "Alder Bough"), and everything else is the seed/fruit/catkin product NObjHarvestOl's
    // "seed" icon represents. Classified in one pass over the product list (used by
    // TreeHarvestSpec/BushHarvestSpec) rather than one independent rescan per category.
    public static UndiscoveredCategories undiscoveredCategories(String gobResName) {
        if (gobResName == null || !VSpec.object.containsKey(gobResName))
            return NONE_UNDISCOVERED;
        NCharacterInfo info = charInfo();
        if (info == null)
            return NONE_UNDISCOVERED;

        boolean seed = false, leaf = false, bough = false;
        for (String product : VSpec.object.get(gobResName)) {
            if (!isCurrentSeasonProduct(gobResName, product) || info.IsLpExplorerContains(gobResName, product))
                continue;
            if (isLeafProduct(product)) leaf = true;
            else if (isBoughProduct(product)) bough = true;
            else seed = true;
        }
        return new UndiscoveredCategories(seed, leaf, bough);
    }

    // Bark isn't listed in VSpec.object at all (unlike seed/leaf/bough, which are literal product
    // entries there) - its item name is assumed from the species instead (see
    // HarvestState.getBarkProductName()), so this checks discovery directly rather than filtering
    // VSpec.object's product list like the other three hasUndiscovered*Product methods do.
    public static boolean hasUndiscoveredBarkProduct(String gobResName) {
        String barkProduct = HarvestState.getBarkProductName(gobResName);
        NCharacterInfo info = charInfo();
        if (barkProduct == null || info == null)
            return false;
        // Unlike seed/leaf/bough (uniquely named per species), several species share the exact
        // same bark item name ("Treebark", "Tough Bark") - confirmed in-game that picking it from
        // one species' tree also satisfies it for every other species sharing that name, so check
        // discovery globally rather than against just this one resource.
        return !info.IsLpExplorerContainsAnywhere(barkProduct);
    }

    private static boolean isLeafProduct(String product) {
        return product.contains("Leaf") || product.contains("Leaves");
    }

    private static boolean isBoughProduct(String product) {
        return product.contains("Bough");
    }

    private static List<String> undiscoveredProductsMatching(String gobResName, Predicate<String> category) {
        if (gobResName == null || !VSpec.object.containsKey(gobResName))
            return Collections.emptyList();
        NCharacterInfo info = charInfo();
        if (info == null)
            return Collections.emptyList();

        List<String> result = new ArrayList<>();
        for (String product : VSpec.object.get(gobResName)) {
            if (!category.test(product) || !isCurrentSeasonProduct(gobResName, product))
                continue;
            if (!info.IsLpExplorerContains(gobResName, product))
                result.add(product);
        }
        return result;
    }

    // The icon representing one of this gob's undiscovered LP products, if resolvable: for
    // trees/bushes, the harvest-category icon (seed/leaf/bough) matching this specific product,
    // matching what NObjHarvestOl itself would show; otherwise a name-based lookup via VSpec's
    // stacking-icon data (e.g. mineable "bumlings" ore/stone gobs, or a log's Board/Block, whose
    // names already have icons recorded there for stack-size purposes). Returns null if nothing
    // can be resolved (falls back to a generic marker at the call site).
    public static TexI getMarkerIcon(Gob gob, String knownUndiscoveredProduct) {
        if (gob == null || gob.ngob == null)
            return null;
        String product = knownUndiscoveredProduct != null ? knownUndiscoveredProduct : firstUndiscoveredProduct(gob);
        if (product == null)
            return null;
        BufferedImage img = resolveProductIcon(gob, product);
        return img != null ? new TexI(img) : null;
    }

    // A single combined, tinted icon showing every currently-undiscovered product for this gob at
    // once, stacked the same way NObjHarvestOl stacks leaf/seed/bough/bark - used by
    // NLPassistant's 3D-world marker so a log (Board + Block) or a multi-product tree/bush reads
    // the same way whether NObjHarvestOl or the fallback marker is showing it. Delegates the
    // actual tint+layout to NObjHarvestOl.compose(), the same step its own always-on overlay uses,
    // so the two displays never drift apart.
    public static TexI getMarkerIcon(Gob gob, List<String> knownUndiscoveredProducts) {
        if (gob == null || gob.ngob == null)
            return null;
        List<String> products = knownUndiscoveredProducts != null ? knownUndiscoveredProducts : allUndiscoveredProducts(gob);
        if (products.isEmpty())
            return null;

        List<HarvestSpec.Part> parts = new ArrayList<>(products.size());
        for (String product : products) {
            BufferedImage img = resolveProductIcon(gob, product);
            if (img != null)
                parts.add(new HarvestSpec.Part(product, img, true));
        }

        // Lay out the same direction the gob's own always-on harvest overlay would (e.g. a log's
        // Board+Block side by side), so the fallback marker and NObjHarvestOl read consistently.
        HarvestSpec spec = HarvestSpecs.forResource(gob.ngob.name);
        boolean horizontal = spec != null && spec.horizontal();
        return NObjHarvestOl.compose(horizontal, parts);
    }

    // Whether this exact product is still undiscovered for this gob - package-visible so
    // ProductListHarvestSpec (the log/stone always-on overlay) can tint individual icons the same
    // way the tree/bush categories already do.
    static boolean isProductUndiscovered(String gobResName, String product) {
        NCharacterInfo info = charInfo();
        if (info == null || gobResName == null || product == null)
            return false;
        return isCurrentSeasonProduct(gobResName, product) && !info.IsLpExplorerContains(gobResName, product);
    }

    // Resolves one product's own icon: the matching harvest-category icon (seed/leaf/bough) for
    // tree/bush species, so a leaf product shows its leaf icon rather than always falling back to
    // the seed icon; the generic VSpec name-based lookup otherwise. Package-visible so
    // ProductListHarvestSpec can resolve log/stone icons the same way.
    static BufferedImage resolveProductIcon(Gob gob, String product) {
        Drawable dr = gob.getattr(Drawable.class);
        if (dr instanceof ResDrawable) {
            Resource res = ((ResDrawable) dr).getres();
            if (HarvestState.isTreeOrBush(res)) {
                String type = isLeafProduct(product) ? "leaf" : isBoughProduct(product) ? "bough" : "seed";
                BufferedImage img = HarvestState.getIcon(res, type);
                if (img != null)
                    return img;
            }
        }
        return HarvestState.loadIcon(VSpec.getIconPath(product));
    }

    // Called for every newly-resolved item name near a gob - clickedGob is null if the pickup
    // can't be attributed to one (e.g. no gob was clicked recently enough). All the "should we
    // even consider this pickup" decisions (no gob, a tool rather than a product) live here too,
    // alongside the temporary diagnostic logging, so both stay in the one place instead of split
    // across this method and its caller.
    public static void checkLpExplorer(Gob clickedGob, String name) {
        if (clickedGob == null) {
            // TEMPORARY diagnostic - log every unattributed pickup. Remove once verification is complete.
            System.out.println("[LP-DEBUG] New item '" + name + "' resolved but map.clickedGob was null - can't attribute it to a gob.");
            return;
        }
        // Exclude tools from LP tracking - the player's own axe/saw resolving its name near a gob
        // isn't a harvested product.
        if (name.contains(" Axe") || name.contains(" Saw")) {
            // TEMPORARY diagnostic - log every tool exclusion. Remove once verification is complete.
            System.out.println("[LP-DEBUG] '" + name + "' excluded by the Axe/Saw tool filter - not passed to checkLpExplorer.");
            return;
        }
        NGameUI gui = NUtils.getGameUI();
        String gobName = clickedGob.ngob.name;
        if (gui == null || gui.getCharInfo() == null || gobName == null)
            return;

        boolean trackedProduct = VSpec.object.containsKey(gobName) && VSpec.object.get(gobName).contains(name);
        // Bark isn't in VSpec.object (see hasUndiscoveredBarkProduct()), so recognize it here
        // by the same species-based name assumption instead.
        boolean barkProduct = HarvestState.isTreeOrBushRes(gobName) && name.equals(HarvestState.getBarkProductName(gobName));
        if (!trackedProduct && !barkProduct)
        {
            // TEMPORARY diagnostic - log every non-match to help catch data mismatches. Remove once verification is complete.
            System.out.println("[LP-DEBUG] '" + name + "' didn't match clickedGob '" + gobName + "' - VSpec.object has: "
                + (VSpec.object.containsKey(gobName) ? VSpec.object.get(gobName) : "NO ENTRY for this resource")
                + ", assumed bark name: '" + HarvestState.getBarkProductName(gobName) + "'");
            // Not a product this gob is tracked for - leave clickedGob as-is rather than
            // clearing it. Some products (e.g. a log's Board/Block) only appear several seconds
            // after the click, via a crafting animation; if any other, unrelated item happens to
            // resolve its name for the first time during that window, clearing clickedGob here
            // unconditionally would discard the click before the actual product ever appears,
            // so it could never be recorded as discovered at all.
            return;
        }

        NCharacterInfo info = gui.getCharInfo();
        if (!info.IsLpExplorerContains(gobName, name)) {
            info.LpExplorerAdd(gobName, name);
            info.newLpExplorer = true;
            // TEMPORARY diagnostic - log every recorded discovery. Remove once verification is complete.
            System.out.println("[LP-DEBUG] Recorded '" + name + "' as discovered for gob '" + gobName + "'");
        }
        gui.map.clickedGob = null;
    }

    private static NCharacterInfo charInfo() {
        NGameUI gui = NUtils.getGameUI();
        return gui != null ? gui.getCharInfo() : null;
    }
}
