package nurgling.tools;

import haven.*;
import nurgling.NUtils;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
    private static final Map<String, String> BARK_ICON_TO_PRODUCT;

    private static final Map<String, Optional<BufferedImage>> ICON_CACHE = new ConcurrentHashMap<>();

    // Off-season fruit is reported under this literal prefix (e.g. "Yesteryear's Red Apple");
    // shared with LpExplorer so both sides of the season-aware behavior (icon selection here,
    // discovery-check filtering there) agree on the exact same string.
    public static final String YESTERYEAR_PREFIX = "Yesteryear's ";

    private static final Map<String, String> YESTERYEAR_ICON_OVERRIDE;

    private static final Pattern BUMLING_VARIANT_SUFFIX = Pattern.compile("\\d+$");

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
        boughs.put("poplar", "gfx/invobjs/bough-poplar");
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

        // The Yesteryear's icon is simply the normal seed icon's resource path with a "-yester"
        // suffix appended - except for these two, confirmed (via VSpec) to use a differently-named
        // resource instead of following that convention.
        Map<String, String> yesterOverrides = new HashMap<>();
        yesterOverrides.put("blackberrybush", "gfx/invobjs/seed-blackberry-yester");
        yesterOverrides.put("sandthorn", "gfx/invobjs/sandthorn-yester");
        YESTERYEAR_ICON_OVERRIDE = Collections.unmodifiableMap(yesterOverrides);

        // The bark item name a species yields, keyed by the same per-species icon path BARKS_MAP
        // already assigns it (birch/willow get a uniquely-named item, everything else in
        // BARKS_MAP shares "Tough Bark") - one source of truth instead of a second hand-guessed
        // name per species that can independently go stale (confirmed happened once already:
        // birch was guessed as "Birchbark", really "Birch Bark").
        Map<String, String> barkNames = new HashMap<>();
        barkNames.put("gfx/invobjs/bark-birch", "Birch Bark");
        barkNames.put("gfx/invobjs/bark-willow", "Willow Bark");
        barkNames.put("gfx/invobjs/toughbark", "Tough Bark");
        BARK_ICON_TO_PRODUCT = Collections.unmodifiableMap(barkNames);
    }

    private HarvestState() {}

    /**
     * Mineable "bumling" resources are suffixed with a trailing digit for their visual variant
     * (e.g. "granite3"), but VSpec.object/categories track them by the bare species name - strips
     * that suffix so lookups against those maps work regardless of which variant a gob is
     * currently showing. A no-op for every other resource.
     */
    public static String normalizeBumlingRes(String resName) {
        if (resName != null && resName.contains("bumlings"))
            return BUMLING_VARIANT_SUFFIX.matcher(resName).replaceAll("");
        return resName;
    }

    private static boolean isTreeRes(String resname) {
        return resname != null && resname.startsWith("gfx/terobjs/trees")
            && !resname.endsWith("log") && !resname.endsWith("oldtrunk");
    }

    private static boolean isBushRes(String resname) {
        return resname != null && resname.startsWith("gfx/terobjs/bushes");
    }

    public static boolean isTreeOrBushRes(String resname) {
        return isTreeRes(resname) || isBushRes(resname);
    }

    public static boolean isTreeOrBush(Resource res) {
        return res != null && isTreeOrBushRes(res.name);
    }

    public static boolean hasBough(String basename) {
        return BOUGHS_MAP.containsKey(basename);
    }

    /**
     * The bark item a tree/bush species yields, assuming the generic "Treebark" unless the
     * species is confirmed to give something else - joins BARKS_MAP's per-species icon path
     * through BARK_ICON_TO_PRODUCT rather than guessing the name directly, so the two "does this
     * species get special-cased bark" facts can't drift out of sync with each other.
     */
    public static String getBarkProductName(String gobResName) {
        if (gobResName == null) return null;
        String basename = gobResName.substring(gobResName.lastIndexOf('/') + 1);
        return BARK_ICON_TO_PRODUCT.getOrDefault(BARKS_MAP.get(basename), "Treebark");
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
            if (isTreeRes(res.name)) {
                growth = (int) (TREE_MULT * (growth - TREE_START));
            } else if (isBushRes(res.name)) {
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
    public static BufferedImage getIcon(Resource res, String type) {
        if (res == null) return null;
        String basename = res.basename();

        String resourceName = null;
        if ("seed".equals(type)) {
            resourceName = SEEDS_MAP.containsKey(basename) ? SEEDS_MAP.get(basename) : "gfx/invobjs/seed-" + basename;
            if (isYesteryearSeason() && isYesteryearCapable(res.name)) {
                resourceName = YESTERYEAR_ICON_OVERRIDE.containsKey(basename)
                    ? YESTERYEAR_ICON_OVERRIDE.get(basename) : resourceName + "-yester";
            }
        } else if ("leaf".equals(type)) {
            resourceName = LEAVES_MAP.get(basename);
        } else if ("bough".equals(type)) {
            resourceName = BOUGHS_MAP.get(basename);
        } else if ("bark".equals(type)) {
            resourceName = BARKS_MAP.containsKey(basename) ? BARKS_MAP.get(basename) : "gfx/invobjs/bark";
        }

        return loadIcon(resourceName);
    }

    /**
     * Whether this tree/bush resource has a confirmed "Yesteryear's " seasonal counterpart for its
     * seed/fruit product - derived directly from VSpec.object (a species with a seasonal pair
     * lists both the normal and "Yesteryear's "-prefixed product as literal sibling entries)
     * rather than a separately hand-maintained species list that could drift out of sync with it.
     */
    public static boolean isYesteryearCapable(String gobResName) {
        List<String> products = VSpec.object.get(gobResName);
        if (products == null) return false;
        for (String product : products) {
            if (product.startsWith(YESTERYEAR_PREFIX)) return true;
        }
        return false;
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

    private static BufferedImage unknownIcon;

    /**
     * A generic "?" icon shown in place of a category we determined should be visible (a live
     * bitmask bit set, or a tracked resource with no VSpec entry) but couldn't resolve an actual
     * icon for - almost always a species new to the game that hasn't been added to VSpec/
     * HarvestState's maps yet. Makes that gap visible in-game instead of silently showing
     * nothing, so it gets noticed and reported rather than going unnoticed indefinitely.
     */
    public static BufferedImage unknownIcon() {
        if (unknownIcon == null) {
            Coord sz = UI.scale(26, 26);
            BufferedImage img = TexI.mkbuf(sz);
            Graphics2D g = (Graphics2D) img.getGraphics();
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, sz.x, sz.y);
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, sz.y * 0.75f));
            FontMetrics fm = g.getFontMetrics();
            String s = "?";
            int x = (sz.x - fm.stringWidth(s)) / 2;
            int y = (sz.y - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(s, x, y);
            g.dispose();
            unknownIcon = img;
        }
        return unknownIcon;
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
