package nurgling.tools;

import haven.Drawable;
import haven.Gob;
import haven.Loading;
import haven.ResDrawable;
import haven.Resource;
import haven.Sprite;
import haven.TexI;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.overlays.NObjHarvestOl;
import nurgling.widgets.NCharacterInfo;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

// Which LP-discoverable products (VSpec.object) each gob type still has for the player. Whether a
// product is discovered comes from the character's DiscoveryLedger, which records an item when it
// arrives together with an LP rise (see DiscoveryTracker). Both the normal and "Yesteryear's "
// variant of seasonal fruit are separate VSpec.object entries, matched by name like any other.
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

    // Discovery is recorded per item name, so bark (several species share one bark name) needs no special case.
    private static boolean isProductDiscovered(NCharacterInfo info, String product) {
        return info.ledger().isDone(product);
    }

    // Resources confirmed to have every product (including bark, for trees) already discovered -
    // checked before any of the per-tick scanning below (VSpec.object iteration, and for
    // trees/bushes the live bitmask decode too), so a fully-explored species stops paying that
    // cost every tick for every one of its gobs, forever. Deliberately independent of the live
    // per-instance bitmask (whether a product is CURRENTLY visible on a given gob) - discovery
    // status is a per-RESOURCE fact, the same regardless of which instance or moment you look at,
    // so it's what's safe to cache and share across every gob of that resource.
    //
    // Invalidated whole-cache on every season change rather than per-entry, since that's simpler
    // and seasons change only a few times a year: a Yesteryear's-capable resource can be "fully
    // discovered" while its off-season variant is out of season, then stop being once that
    // variant's season arrives and it's still unfound, so the cache can't be permanent for those.
    //
    // Kept per character, not as one shared set, because what it caches is derived entirely from
    // NCharacterInfo - which is per-character. A single set would let a second character (or a
    // second session) inherit the first one's "already found everything" and silently suppress
    // every marker it should still be showing. Keying by character rather than clearing on change
    // also matters because NUtils.getUI() resolves to whichever UI is currently active: with two
    // sessions running, a shared set guarded by "has the character changed?" would be cleared and
    // rebuilt on alternating ticks and never actually cache anything.
    private static final Map<String, Set<String>> fullyDiscoveredByChr = new ConcurrentHashMap<>();
    private static volatile int fullyDiscoveredCacheSeason = -1;

    /** The set of resources with nothing left to find, for this character in the current season. */
    private static Set<String> fullyDiscovered(NCharacterInfo info) {
        int season = HarvestState.isYesteryearSeason() ? 1 : 0;
        if (season != fullyDiscoveredCacheSeason) {
            fullyDiscoveredByChr.clear();
            // Both of these key on the season already, so this isn't needed for correctness - it's
            // here to stop the previous season's entries sitting in memory for the rest of a
            // long-running session.
            NObjHarvestOl.clearLabelCache();
            MARKER_ICON_CACHE.clear();
            fullyDiscoveredCacheSeason = season;
        }
        return fullyDiscoveredByChr.computeIfAbsent(cacheScope(info), k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Identity of the character whose discovery state any cached tint decision reflects. Public so
     * NObjHarvestOl can key its own label cache by it - those labels bake in which products are
     * still undiscovered, which is a per-character fact.
     */
    public static String cacheScope() {
        return cacheScope(charInfo());
    }

    private static String cacheScope(NCharacterInfo info) {
        return (info == null || info.chrid == null) ? "" : info.chrid;
    }

    // Package-visible so ProductListHarvestSpec (the log/stone/old-trunk always-on overlay) can
    // skip its own per-product undiscovered checks the same way once nothing's left to find.
    static boolean isFullyDiscovered(String gobResName) {
        if (gobResName == null)
            return false;
        NCharacterInfo info = charInfo();
        if (info == null)
            return false;
        Set<String> known = fullyDiscovered(info);
        if (known.contains(gobResName))
            return true;

        List<String> products = VSpec.object.get(gobResName);
        if (products != null) {
            for (String product : products) {
                if (isCurrentSeasonProduct(gobResName, product) && !isProductDiscovered(info, product))
                    return false;
            }
        }

        known.add(gobResName);
        return true;
    }

    // Throws haven.Loading if the gob's sprite hasn't loaded yet - propagated to the caller,
    // same as HarvestState.hasHarvestableSeed() itself does.
    public static boolean hasUndiscoveredProduct(Gob gob) {
        return !allUndiscoveredProducts(gob).isEmpty();
    }

    // Every currently-undiscovered product this gob tracks, not just the first - lets markers
    // (e.g. NLPassistant) show every still-undiscovered icon at once instead of just one at a
    // time, matching how NObjHarvestOl stacks leaf/seed/bough/bark simultaneously.
    public static List<String> allUndiscoveredProducts(Gob gob) {
        if (gob == null || gob.ngob == null)
            return Collections.emptyList();
        String gobResName = gob.ngob.name;
        if (isFullyDiscovered(gobResName))
            return Collections.emptyList();

        if (!HarvestState.isTreeOrBushRes(gobResName))
            return undiscoveredProductsMatching(gobResName, product -> true);

        Drawable dr = gob.getattr(Drawable.class);
        if (!(dr instanceof ResDrawable))
            return Collections.emptyList();
        ResDrawable d = (ResDrawable) dr;
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return Collections.emptyList();

        // Seed/leaf are gated by their own live bit; bough/bark aren't bit-gated at all.
        int sdt = Sprite.decnum(d.sdt.clone());
        boolean seedPresent = HarvestState.hasSeedBit(sdt);
        boolean leafPresent = HarvestState.hasLeafBit(sdt);

        return undiscoveredProductsMatching(gobResName, product -> {
            if (isLeafProduct(product)) return leafPresent;
            if (isBoughProduct(product)) return true;
            if (HarvestState.isBarkProductName(product)) return true;
            return seedPresent;
        });
    }

    /** Which harvest categories (seed/leaf/bough/bark) still have an undiscovered product for a resource. */
    public static class UndiscoveredCategories {
        public final boolean seed, leaf, bough, bark;
        private UndiscoveredCategories(boolean seed, boolean leaf, boolean bough, boolean bark) {
            this.seed = seed;
            this.leaf = leaf;
            this.bough = bough;
            this.bark = bark;
        }
    }

    private static final UndiscoveredCategories NONE_UNDISCOVERED = new UndiscoveredCategories(false, false, false, false);

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
        if (isFullyDiscovered(gobResName))
            return NONE_UNDISCOVERED;
        NCharacterInfo info = charInfo();
        if (info == null)
            return NONE_UNDISCOVERED;

        boolean seed = false, leaf = false, bough = false, bark = false;
        for (String product : VSpec.object.get(gobResName)) {
            if (!isCurrentSeasonProduct(gobResName, product) || isProductDiscovered(info, product))
                continue;
            if (isLeafProduct(product)) leaf = true;
            else if (isBoughProduct(product)) bough = true;
            else if (HarvestState.isBarkProductName(product)) bark = true;
            else seed = true;
        }
        return new UndiscoveredCategories(seed, leaf, bough, bark);
    }

    public static boolean isLeafProduct(String product) {
        return product.contains("Leaf") || product.contains("Leaves");
    }

    public static boolean isBoughProduct(String product) {
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
            if (!isProductDiscovered(info, product))
                result.add(product);
        }
        return result;
    }

    // Composed marker images, keyed by everything they depend on: the gob's resource (which icon
    // set), the season (Yesteryear's fruit uses a different icon), and the exact product list.
    // Memoized because the minimap redraws every marker every frame, and a TexI is not a cheap
    // thing to throw away - each one lazily allocates and uploads its own GL texture the first
    // time it's drawn (TexI.st()), so a fresh instance per frame means a fresh texture per frame.
    // Nothing here depends on the character - every part is composed as undiscovered - so unlike
    // fullyDiscoveredByChr this needs no per-character scoping.
    private static final Map<String, TexI> MARKER_ICON_CACHE = new ConcurrentHashMap<>();

    // A single combined, tinted icon showing every currently-undiscovered product for this gob at
    // once, stacked the same way NObjHarvestOl stacks leaf/seed/bough/bark - used by
    // NLPassistant's 3D-world marker and by the minimap, so a log (Board + Block) or a
    // multi-product tree/bush reads the same way in both places. Delegates the actual tint+layout
    // to NObjHarvestOl.compose(), the same step its own always-on overlay uses, so the two
    // displays never drift apart.
    public static TexI getMarkerIcon(Gob gob, List<String> knownUndiscoveredProducts) {
        return getMarkerIcon(gob, knownUndiscoveredProducts, true, true);
    }

    /**
     * As getMarkerIcon(), but blocking=false never waits on an icon fetch - it composes from
     * whatever is already loaded and returns null if nothing is. For the render thread.
     * background=false drops the dark backing square (the minimap wants bare icons; the in-world
     * markers keep the backing for legibility) - it's part of the cache key so the two variants
     * of the same gob/products don't share one composed image.
     */
    public static TexI getMarkerIcon(Gob gob, List<String> knownUndiscoveredProducts, boolean blocking, boolean background) {
        if (gob == null || gob.ngob == null)
            return null;
        List<String> products = knownUndiscoveredProducts != null ? knownUndiscoveredProducts : allUndiscoveredProducts(gob);
        if (products.isEmpty())
            return null;

        String key = gob.ngob.name + '|' + (HarvestState.isYesteryearSeason() ? 'y' : 'n')
            + '|' + (background ? 'b' : 't') + '|' + String.join(",", products);
        TexI cached = MARKER_ICON_CACHE.get(key);
        if (cached != null)
            return cached;

        List<HarvestSpec.Part> parts = new ArrayList<>(products.size());
        boolean pending = false;
        for (String product : products) {
            BufferedImage img;
            try {
                img = resolveProductIcon(gob, product, blocking);
            } catch (Loading l) {
                // Non-blocking, and this icon hasn't arrived yet. A null return would instead mean
                // the resource genuinely doesn't exist - a final answer we're happy to bake in.
                pending = true;
                continue;
            }
            if (img != null)
                parts.add(new HarvestSpec.Part(product, img, true));
        }
        // Nothing until every icon is in: a partial composition isn't memoized, so a caller
        // retrying each frame would build a fresh texture every frame.
        if (pending || parts.isEmpty())
            return null;

        // Lay out the same direction the gob's own always-on harvest overlay would (e.g. a log's
        // Board+Block side by side), so the fallback marker and NObjHarvestOl read consistently.
        HarvestSpec spec = HarvestSpecs.forResource(gob.ngob.name);
        TexI tex = NObjHarvestOl.compose(spec != null && spec.horizontal(), background, parts);
        // A permanently-absent icon isn't pending, so this composition is final and cached.
        if (tex != null)
            MARKER_ICON_CACHE.put(key, tex);
        return tex;
    }

    // Whether this exact product is still undiscovered for this gob - package-visible so
    // ProductListHarvestSpec (the log/stone always-on overlay) can tint individual icons the same
    // way the tree/bush categories already do.
    public static boolean isProductUndiscovered(String gobResName, String product) {
        NCharacterInfo info = charInfo();
        if (info == null || gobResName == null || product == null)
            return false;
        return isCurrentSeasonProduct(gobResName, product) && !isProductDiscovered(info, product);
    }

    // Resolves one product's own icon: the matching harvest-category icon (seed/leaf/bough) for
    // tree/bush species, so a leaf product shows its leaf icon rather than always falling back to
    // the seed icon; the generic VSpec name-based lookup otherwise. Package-visible so
    // ProductListHarvestSpec can resolve log/stone icons the same way.
    static BufferedImage resolveProductIcon(Gob gob, String product) {
        return resolveProductIcon(gob, product, true);
    }

    // blocking=false never waits on a resource fetch; see HarvestState.loadIcon().
    static BufferedImage resolveProductIcon(Gob gob, String product, boolean blocking) {
        Drawable dr = gob.getattr(Drawable.class);
        if (dr instanceof ResDrawable) {
            Resource res = ((ResDrawable) dr).getres();
            if (HarvestState.isTreeOrBush(res)) {
                String type = isLeafProduct(product) ? "leaf"
                    : isBoughProduct(product) ? "bough"
                    : product.equals(HarvestState.getBarkProductName(res.name)) ? "bark"
                    : "seed";
                BufferedImage img = HarvestState.getIcon(res, type, blocking);
                if (img != null)
                    return img;
            }
        }
        return HarvestState.loadIcon(VSpec.getIconPath(product), blocking);
    }

    /** Drops every cached "nothing left to find" decision, after the Atlas marks something undiscovered again. */
    public static void invalidateCaches() {
        fullyDiscoveredByChr.clear();
        NObjHarvestOl.clearLabelCache();
        MARKER_ICON_CACHE.clear();
    }

    private static NCharacterInfo charInfo() {
        NGameUI gui = NUtils.getGameUI();
        return gui != null ? gui.getCharInfo() : null;
    }
}
