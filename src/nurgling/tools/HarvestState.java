package nurgling.tools;

import haven.*;
import nurgling.NUtils;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads a tree/bush gob's live harvest state (maturity, currently-visible seed/leaf layers) and
 * resolves harvestable-product icons. Pure gob/resource facts with no LP-discovery awareness -
 * extracted out of NObjHarvestOl so that class (the always-visible harvest overlay) and
 * LpExplorer (LP-discovery tracking) can both depend on this instead of on each other.
 */
public class HarvestState {
    private static final int TREE_START = 10;
    private static final int BUSH_START = 30;
    private static final double TREE_MULT = 100.0 / (100.0 - TREE_START);
    private static final double BUSH_MULT = 100.0 / (100.0 - BUSH_START);

    private static final Map<String, String> SEEDS_MAP;
    private static final Map<String, String> LEAVES_MAP;
    private static final Map<String, String> BOUGHS_MAP;
    private static final Map<String, String> BARKS_MAP;

    private static final Map<String, Optional<BufferedImage>> ICON_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> YESTERYEAR_CAPABLE_SEEDS;
    private static final Map<String, String> YESTERYEAR_ICON_OVERRIDE;

    static {
        Map<String, String> seeds = new HashMap<>();
        seeds.put("almondtree", "gfx/invobjs/almond");
        seeds.put("appletree", "gfx/invobjs/apple");
        seeds.put("appletreegreen", "gfx/invobjs/applegreen");
        seeds.put("birdcherrytree", "gfx/invobjs/birdcherry");
        seeds.put("carobtree", "gfx/invobjs/carobfruit");
        seeds.put("cherry", "gfx/invobjs/cherry");
        seeds.put("chestnuttree", "gfx/invobjs/chestnut");
        seeds.put("corkoak", "gfx/invobjs/cork");
        seeds.put("figtree", "gfx/invobjs/fig");
        seeds.put("hazel", "gfx/invobjs/hazelnut");
        seeds.put("lemontree", "gfx/invobjs/lemon");
        seeds.put("medlartree", "gfx/invobjs/medlar");
        seeds.put("mulberry", "gfx/invobjs/mulberry");
        seeds.put("olivetree", "gfx/invobjs/olive");
        seeds.put("orangetree", "gfx/invobjs/orange");
        seeds.put("peartree", "gfx/invobjs/pear");
        seeds.put("persimmontree", "gfx/invobjs/persimmon");
        seeds.put("plumtree", "gfx/invobjs/plum");
        seeds.put("quincetree", "gfx/invobjs/quince");
        seeds.put("rowan", "gfx/invobjs/rowanberry");
        seeds.put("sorbtree", "gfx/invobjs/sorbapple");
        seeds.put("stonepine", "gfx/invobjs/stonepinecone");
        seeds.put("strawberrytree", "gfx/invobjs/woodstrawberry");
        seeds.put("walnuttree", "gfx/invobjs/walnut");
        seeds.put("whitebeam", "gfx/invobjs/whitebeamfruit");
        seeds.put("charredtree", null);
        seeds.put("ghostpipe", "gfx/invobjs/ghostpipes");
        seeds.put("mastic", "gfx/invobjs/masticfruit");
        seeds.put("poppycaps", "gfx/invobjs/poppycapss");
        SEEDS_MAP = Collections.unmodifiableMap(seeds);

        Map<String, String> leaves = new HashMap<>();
        leaves.put("conkertree", "gfx/invobjs/leaf-conkertree");
        leaves.put("figtree", "gfx/invobjs/leaf-fig");
        leaves.put("laurel", "gfx/invobjs/leaf-laurel");
        leaves.put("maple", "gfx/invobjs/leaf-maple");
        leaves.put("mulberry", "gfx/invobjs/leaf-mulberrytree");
        leaves.put("teabush", "gfx/invobjs/tea-fresh");
        LEAVES_MAP = Collections.unmodifiableMap(leaves);

        Map<String, String> boughs = new HashMap<>();
        boughs.put("alder", "gfx/invobjs/bough-alder");
        boughs.put("elm", "gfx/invobjs/bough-elm");
        boughs.put("fir", "gfx/invobjs/bough-fir");
        boughs.put("grayalder", "gfx/invobjs/bough-grayalder");
        boughs.put("linden", "gfx/invobjs/bough-linden");
        boughs.put("spruce", "gfx/invobjs/bough-spruce");
        boughs.put("sweetgum", "gfx/invobjs/bough-sweetgum");
        boughs.put("yew", "gfx/invobjs/bough-yew");
        boughs.put("beech", "gfx/invobjs/bough-beech");
        BOUGHS_MAP = Collections.unmodifiableMap(boughs);

        Map<String, String> barks = new HashMap<>();
        barks.put("birch", "gfx/invobjs/bark-birch");
        barks.put("willow", "gfx/invobjs/bark-willow");
        barks.put("beech", "gfx/invobjs/toughbark");
        barks.put("cedar", "gfx/invobjs/toughbark");
        barks.put("elm", "gfx/invobjs/toughbark");
        barks.put("juniper", "gfx/invobjs/toughbark");
        barks.put("linden", "gfx/invobjs/toughbark");
        barks.put("mulberry", "gfx/invobjs/toughbark");
        barks.put("orangetree", "gfx/invobjs/toughbark");
        barks.put("sallow", "gfx/invobjs/toughbark");
        barks.put("wychelm", "gfx/invobjs/toughbark");
        BARKS_MAP = Collections.unmodifiableMap(barks);

        // Species confirmed (via VSpec's -yester icon entries) to grow a "Yesteryear's " variant
        // of their normal fruit during the off-season (Winter/Spring). Everything else (catkins,
        // nuts, pods, etc.) has no seasonal icon swap.
        YESTERYEAR_CAPABLE_SEEDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "appletree", "cherry", "figtree", "lemontree", "mulberry", "orangetree", "peartree",
            "persimmontree", "plumtree", "quincetree", "strawberrytree", "medlartree", "sorbtree",
            "crabappletree", "blackberrybush", "redcurrant", "sandthorn", "blackcurrant",
            "elderberrybush", "gooseberrybush", "raspberrybush"
        )));

        // The Yesteryear's icon is simply the normal seed icon's resource path with a "-yester"
        // suffix appended - except for these two, confirmed (via VSpec) to use a differently-named
        // resource instead of following that convention.
        Map<String, String> yesterOverrides = new HashMap<>();
        yesterOverrides.put("blackberrybush", "gfx/invobjs/seed-blackberry-yester");
        yesterOverrides.put("sandthorn", "gfx/invobjs/sandthorn-yester");
        YESTERYEAR_ICON_OVERRIDE = Collections.unmodifiableMap(yesterOverrides);
    }

    private HarvestState() {}

    public static boolean isTreeOrBushRes(String resname) {
        if (resname == null) return false;
        if (resname.startsWith("gfx/terobjs/trees"))
            return !resname.endsWith("log") && !resname.endsWith("oldtrunk");
        return resname.startsWith("gfx/terobjs/bushes");
    }

    public static boolean isTreeOrBush(Resource res) {
        if (res == null) return false;
        String n = res.name;
        if (n == null) return false;
        if (n.startsWith("gfx/terobjs/trees")) {
            return !n.endsWith("log") && !n.endsWith("oldtrunk");
        }
        return n.startsWith("gfx/terobjs/bushes");
    }

    public static boolean hasBough(String basename) {
        return BOUGHS_MAP.containsKey(basename);
    }

    /**
     * The bark item a tree/bush species yields, assuming the generic "Treebark" unless the
     * species is confirmed (per the wiki) to give something else - reuses BARKS_MAP's existing
     * per-species grouping rather than a second parallel map, since that's the same "does this
     * species get special-cased bark" distinction the harvest-icon (bark-birch/bark-willow vs.
     * shared toughbark vs. generic bark icon) already encodes.
     */
    public static String getBarkProductName(String gobResName) {
        if (gobResName == null) return null;
        String basename = gobResName.substring(gobResName.lastIndexOf('/') + 1);
        if ("birch".equals(basename)) return "Birchbark";
        if ("willow".equals(basename)) return "Willow Bark";
        if (BARKS_MAP.containsKey(basename)) return "Tough Bark";
        return "Treebark";
    }

    /**
     * True if this gob is a loaded, mature tree/bush (i.e. one that could show any harvest
     * indicator at all). Throws Loading if the sprite hasn't loaded yet, same as isSpriteKind().
     */
    public static boolean isMatureTreeOrBush(Gob gob, ResDrawable d) {
        Resource res = d.getres();
        if (!isTreeOrBush(res))
            return false;

        if (!isSpriteKind(gob, "Tree"))
            return false;

        Message data = d.sdt.clone();
        if (data == null || data.eom())
            return false;

        data.skip(1);
        int growth = data.eom() ? -1 : data.uint8();
        if (growth != -1) {
            if (res.name.contains("gfx/terobjs/trees") && !res.name.endsWith("log") && !res.name.endsWith("oldtrunk")) {
                growth = (int) (TREE_MULT * (growth - TREE_START));
            } else if (res.name.startsWith("gfx/terobjs/bushes")) {
                growth = (int) (BUSH_MULT * (growth - BUSH_START));
            }
        }

        return growth == -1 || growth >= 100;
    }

    /**
     * Whether this specific gob instance currently shows a harvestable seed/fruit layer, per the
     * live state data the 3D renderer itself uses — not a per-resource-type guess. Ignores the
     * treeHarvestSeeds display toggle (that's a rendering preference, irrelevant to this data
     * query). Throws Loading if the sprite hasn't loaded yet.
     */
    public static boolean hasHarvestableSeed(Gob gob) {
        if (gob == null) return false;
        Drawable dr = gob.getattr(Drawable.class);
        if (!(dr instanceof ResDrawable)) return false;
        ResDrawable d = (ResDrawable) dr;
        if (!isMatureTreeOrBush(gob, d)) return false;

        return hasSeedBit(Sprite.decnum(d.sdt.clone()));
    }

    // Named accessors for the live per-instance state bitmask's two known bits, so callers that
    // already have the decoded sdt in hand (TreeHarvestSpec/BushHarvestSpec, which decode it once
    // to check both) don't need to re-derive the bit meanings themselves.
    public static boolean hasSeedBit(int sdt) {
        return (sdt & 1) != 1;
    }

    public static boolean hasLeafBit(int sdt) {
        return (sdt & 2) != 2;
    }

    /** Resolves a tree/bush species' icon for one harvest category ("seed"/"leaf"/"bough"/"bark"). */
    public static BufferedImage getIcon(String basename, String type) {
        if (basename == null) return null;

        String resourceName = null;
        if ("seed".equals(type)) {
            resourceName = SEEDS_MAP.containsKey(basename) ? SEEDS_MAP.get(basename) : "gfx/invobjs/seed-" + basename;
            if (YESTERYEAR_CAPABLE_SEEDS.contains(basename) && isYesteryearSeason()) {
                resourceName = YESTERYEAR_ICON_OVERRIDE.containsKey(basename)
                    ? YESTERYEAR_ICON_OVERRIDE.get(basename) : resourceName + "-yester";
            }
        } else if ("leaf".equals(type)) {
            resourceName = LEAVES_MAP.get(basename);
        } else if ("bough".equals(type)) {
            resourceName = BOUGHS_MAP.get(basename);
        } else if ("bark".equals(type)) {
            if (BARKS_MAP.containsKey(basename)) {
                resourceName = BARKS_MAP.get(basename);
            } else {
                resourceName = "gfx/invobjs/bark";
            }
        }

        return loadIcon(resourceName);
    }

    // Loads, scales, and caches an icon by its exact resource path - the generic half of
    // getIcon() above, for callers with a resource path already in hand (e.g. LpExplorer
    // resolving a non-tree/bush product's icon via VSpec.getIconPath()) that don't have a tree
    // basename/category to go through getIcon() with.
    public static BufferedImage loadIcon(String resourceName) {
        if (resourceName == null) return null;

        Optional<BufferedImage> cached = ICON_CACHE.get(resourceName);
        if (cached != null) return cached.orElse(null);

        BufferedImage img;
        try {
            img = Resource.remote().loadwait(resourceName).flayer(Resource.imgc).img;
            Coord tsz = resourceName.startsWith("gfx/invobjs/bough-") ? UI.scale(26, 52) : UI.scale(26, 26);
            img = PUtils.convolvedown(img, tsz, CharWnd.iconfilter);
        } catch (Exception e) {
            img = null;
        }
        ICON_CACHE.put(resourceName, Optional.ofNullable(img));
        return img;
    }

    // Whether it's currently the off-season (Winter/Spring) that swaps some species' fruit icon
    // for their "Yesteryear's " variant. Defaults to false (normal icon) if the season isn't known
    // yet (e.g. still loading), same fail-safe direction as the rest of this class's Loading handling.
    // Public so LpExplorer can filter its discovery check to the same season-appropriate product.
    public static boolean isYesteryearSeason() {
        UI ui = NUtils.getUI();
        if (ui == null || ui.sess == null || ui.sess.glob == null || ui.sess.glob.ast == null)
            return false;
        return ui.sess.glob.ast.isYesteryearSeason();
    }

    private static boolean isSpriteKind(Gob gob, String... kind) {
        List<String> kinds = Arrays.asList(kind);
        Drawable d = gob.getattr(Drawable.class);
        if (d instanceof ResDrawable) {
            Sprite spr = ((ResDrawable) d).spr;
            if (spr == null) throw new Loading();
            Class<?> spc = spr.getClass();
            return kinds.contains(spc.getSimpleName()) || (spc.getSuperclass() != null && kinds.contains(spc.getSuperclass().getSimpleName()));
        }
        return false;
    }
}
