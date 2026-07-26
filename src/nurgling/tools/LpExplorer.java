package nurgling.tools;

import haven.Drawable;
import haven.Gob;
import haven.Loading;
import haven.MapView;
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
                if (isCurrentSeasonProduct(gobResName, product) && !info.IsLpExplorerContains(gobResName, product))
                    return false;
            }
        }
        if (HarvestSpecs.TREE.matches(gobResName) && hasUndiscoveredBarkProduct(gobResName))
            return false;

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

        // Seed/leaf are gated by their own live bit; bough (a fixed per-species trait, already
        // implied by the product simply existing in VSpec.object) and bark (assumed always
        // available on a mature tree/bush) aren't bit-gated at all - matching TreeHarvestSpec/
        // BushHarvestSpec's own per-category availability model, which this used to not follow
        // (it previously hid every category, not just seed, whenever the seed bit was clear, and
        // never considered bark at all since bark isn't a VSpec.object entry).
        int sdt = Sprite.decnum(d.sdt.clone());
        boolean seedPresent = HarvestState.hasSeedBit(sdt);
        boolean leafPresent = HarvestState.hasLeafBit(sdt);

        List<String> products = undiscoveredProductsMatching(gobResName, product -> {
            if (isLeafProduct(product)) return leafPresent;
            if (isBoughProduct(product)) return true;
            return seedPresent;
        });

        if (HarvestSpecs.TREE.matches(gobResName) && hasUndiscoveredBarkProduct(gobResName)) {
            products = new ArrayList<>(products);
            products.add(HarvestState.getBarkProductName(gobResName));
        }
        return products;
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
        if (isFullyDiscovered(gobResName))
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
        return getMarkerIcon(gob, knownUndiscoveredProducts, true);
    }

    /**
     * As getMarkerIcon(), but blocking=false never waits on an icon fetch - it composes from
     * whatever is already loaded and returns null if nothing is. For the render thread.
     */
    public static TexI getMarkerIcon(Gob gob, List<String> knownUndiscoveredProducts, boolean blocking) {
        if (gob == null || gob.ngob == null)
            return null;
        List<String> products = knownUndiscoveredProducts != null ? knownUndiscoveredProducts : allUndiscoveredProducts(gob);
        if (products.isEmpty())
            return null;

        String key = gob.ngob.name + '|' + (HarvestState.isYesteryearSeason() ? 'y' : 'n')
            + '|' + String.join(",", products);
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
        if (parts.isEmpty())
            return null;

        // Lay out the same direction the gob's own always-on harvest overlay would (e.g. a log's
        // Board+Block side by side), so the fallback marker and NObjHarvestOl read consistently.
        HarvestSpec spec = HarvestSpecs.forResource(gob.ngob.name);
        TexI tex = NObjHarvestOl.compose(spec != null && spec.horizontal(), parts);
        // Don't memoize while an icon is still in flight - the next call re-composes once it
        // arrives. A permanently-absent icon isn't pending, so that composition does get cached
        // and we stop rebuilding it every frame.
        if (tex != null && !pending)
            MARKER_ICON_CACHE.put(key, tex);
        return tex;
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

    // Reverse index: product name -> the one resource that tracks it in VSpec.object. Built
    // lazily (VSpec's own static data is populated by class-init order this class shouldn't
    // assume has already run) and cached, mirroring VSpec.getIconPath's existing reverse-index
    // pattern. Confirmed exactly one resource per product name across the whole of VSpec.object
    // (no two species share a seed/leaf/bough/board/block/ore name) except bark, which is handled
    // separately below since it isn't a VSpec.object entry at all.
    private static Map<String, String> productToResource;

    private static synchronized Map<String, String> productToResource() {
        if (productToResource == null) {
            Map<String, String> index = new HashMap<>();
            for (Map.Entry<String, ArrayList<String>> e : VSpec.object.entrySet()) {
                for (String product : e.getValue()) {
                    String prev = index.putIfAbsent(product, e.getKey());
                    // The one-resource-per-product-name assumption this index rests on isn't
                    // enforced anywhere in VSpec, so say so loudly rather than silently filing
                    // that product's discoveries under whichever resource happened to be first.
                    if (prev != null) {
                        System.out.println("LpExplorer: VSpec.object lists product \"" + product
                            + "\" under both " + prev + " and " + e.getKey()
                            + "; discoveries for it will be tracked against " + prev + " only.");
                    }
                }
            }
            productToResource = index;
        }
        return productToResource;
    }

    // Called for every newly-resolved item name. Discovery is tracked per RESOURCE (any gob of a
    // species satisfies the same discovery, not just the specific instance that produced it), so
    // this looks up which resource the name belongs to directly from VSpec.object rather than
    // needing to know exactly which gob produced it. All the "should we even consider this
    // pickup" decisions (a tool rather than a product, an unrecognized name, not actually a
    // harvest) live here too.
    public static void checkLpExplorer(String name) {
        // Exclude tools from LP tracking - the player's own axe/saw resolving its name isn't a
        // harvested product (VSpec.object wouldn't have an entry for it anyway, but this avoids
        // relying on that alone).
        if (name.contains(" Axe") || name.contains(" Saw"))
            return;
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.getCharInfo() == null)
            return;
        NCharacterInfo info = gui.getCharInfo();

        String gobName = productToResource().get(name);
        if (gobName == null && HarvestState.isBarkProductName(name))
            // Bark isn't a VSpec.object entry and several species share the exact same bark item
            // name - the resource key here is just a stable, synthetic storage key; discovery for
            // bark is always checked globally (IsLpExplorerContainsAnywhere), never against a
            // specific resource, so which key it's filed under doesn't matter.
            gobName = "bark:" + name;

        if (gobName == null)
            return;

        if (!recentHarvestClick(gui.map))
            return;

        if (!info.IsLpExplorerContains(gobName, name)) {
            info.LpExplorerAdd(gobName, name);
            info.newLpExplorer = true;
        }
    }

    // How long after clicking a harvestable gob a resolving item name is still attributed to that
    // harvest. Only needs to cover a server round-trip plus the item's own resource load.
    private static final long HARVEST_CLICK_WINDOW = 10_000;

    // MapView hands out a new ClickedGob instance per click, so identity is what tells us a click
    // is a *new* one rather than the same one we already timed.
    private static MapView.ClickedGob timedClick = null;
    private static long timedClickAt = 0;

    /**
     * Whether the player right-clicked a harvestable gob recently enough for a product name
     * resolving now to plausibly have come from it.
     *
     * Without any such gate, merely opening a cupboard full of undiscovered items would mark them
     * all discovered the moment their names resolve. The gob is matched by resource TYPE rather
     * than exact identity, because name resolution needs a server round-trip and the player may
     * click a second harvestable gob before a delayed product (a sawn board, say) comes back -
     * productToResource() already tells us which resource the product belongs to, so the click
     * only has to confirm that *a* harvest just happened.
     *
     * The time bound is what keeps that looseness honest. MapView only reassigns clickedGob on the
     * next map click, so a type check alone would leave the gate open indefinitely: one click on a
     * tree would credit every item name that resolved afterwards, however much later.
     */
    private static synchronized boolean recentHarvestClick(MapView map) {
        MapView.ClickedGob clicked = (map == null) ? null : map.clickedGob;
        if (clicked == null || clicked.gob.ngob == null)
            return false;
        if (HarvestSpecs.forResource(clicked.gob.ngob.name) == null)
            return false;
        long now = System.currentTimeMillis();
        if (clicked != timedClick) {
            timedClick = clicked;
            timedClickAt = now;
        }
        return (now - timedClickAt) < HARVEST_CLICK_WINDOW;
    }

    private static NCharacterInfo charInfo() {
        NGameUI gui = NUtils.getGameUI();
        return gui != null ? gui.getCharInfo() : null;
    }
}
