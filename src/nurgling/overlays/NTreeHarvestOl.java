package nurgling.overlays;

import haven.*;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import nurgling.NConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlay for trees/bushes showing what can be harvested (leaf/seed/fruit/bough),
 * inspired by HerSRC's GobReadyForHarvestInfo.
 *
 * Renders small combined icons (leaf + fruit/seed + bough + bark) only when the object is mature/ready.
 */
public class NTreeHarvestOl extends NObjectTexLabel {
    private static final int TREE_START = 10;
    private static final int BUSH_START = 30;
    private static final double TREE_MULT = 100.0 / (100.0 - TREE_START);
    private static final double BUSH_MULT = 100.0 / (100.0 - BUSH_START);

    private static final Map<String, String> SEEDS_MAP;
    private static final Map<String, String> LEAVES_MAP;
    private static final Map<String, String> BOUGHS_MAP;
    private static final Map<String, String> BARKS_MAP;

    private static final Map<String, TexI> LABEL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<BufferedImage>> ICON_CACHE = new ConcurrentHashMap<>();

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
        BARKS_MAP = Collections.unmodifiableMap(barks);
    }

    private final Gob gob;

    public NTreeHarvestOl(Gob target) {
        super(target);
        this.gob = target;
        this.pos = new Coord3f(0, 0, 4);
        refresh();
    }

    public boolean refresh() {
        TexI tex = computeLabel(gob);
        this.label = tex;
        this.img = tex;
        return tex != null;
    }

    @Override
    public boolean tick(double dt) {
        return gob.getattr(Drawable.class) == null;
    }

    public static boolean isTreeOrBushRes(String resname) {
        if (resname == null) return false;
        return resname.startsWith("gfx/terobjs/trees") || resname.startsWith("gfx/terobjs/bushes");
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

    public static TexI computeLabel(Gob gob) {
        if (gob == null) return null;
        Drawable dr = gob.getattr(Drawable.class);
        ResDrawable d = (dr instanceof ResDrawable) ? (ResDrawable) dr : null;
        if (d == null) return null;
        Resource res = d.getres();
        if (!isTreeOrBush(res))
            return null;

        if (!isSpriteKind(gob, "Tree"))
            return null;

        Message data = d.sdt.clone();
        if (data == null || data.eom())
            return null;

        data.skip(1);
        int growth = data.eom() ? -1 : data.uint8();
        if (growth != -1) {
            if (res.name.contains("gfx/terobjs/trees") && !res.name.endsWith("log") && !res.name.endsWith("oldtrunk")) {
                growth = (int) (TREE_MULT * (growth - TREE_START));
            } else if (res.name.startsWith("gfx/terobjs/bushes")) {
                growth = (int) (BUSH_MULT * (growth - BUSH_START));
            }
        }

        if (!(growth == -1 || growth >= 100))
            return null;

        int sdt = Sprite.decnum(d.sdt.clone());
        String base = res.basename();
        boolean isTree = res.name.startsWith("gfx/terobjs/trees");

        boolean showSeeds = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestSeeds));
        boolean showLeaves = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestLeaves));
        boolean showBoughs = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBoughs));
        boolean showBark = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBark));

        boolean seed = showSeeds && (sdt & 1) != 1;
        boolean leaf = showLeaves && (sdt & 2) != 2;
        boolean bough = showBoughs && isTree && BOUGHS_MAP.containsKey(base);
        boolean bark = showBark && isTree;

        StringBuilder key = new StringBuilder();
        key.append(res.name.startsWith("gfx/terobjs/bushes") ? "bush_" : "tree_");
        key.append(base).append("_");
        if (seed) key.append("withSeed_");
        if (leaf) key.append("withLeaf_");
        if (bough) key.append("withBough_");
        if (bark) key.append("withBark_");

        TexI cached = LABEL_CACHE.get(key.toString());
        if (cached != null)
            return cached;

        BufferedImage[] parts = new BufferedImage[]{
                leaf ? getIcon(base, "leaf") : null,
                seed ? getIcon(base, "seed") : null,
                bough ? getIcon(base, "bough") : null,
                bark ? getIcon(base, "bark") : null,
        };

        boolean hasPart = false;
        for (BufferedImage part : parts) {
            if (part != null) {
                hasPart = true;
                break;
            }
        }
        if (!hasPart)
            return null;

        BufferedImage combined = catimgshCentered(1, parts);
        TexI tex = new TexI(combined);
        LABEL_CACHE.put(key.toString(), tex);
        return tex;
    }

    private static BufferedImage catimgshCentered(int margin, BufferedImage... imgs) {
        int w = 0, h = -margin;
        int n = 0;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            n++;
            if (img.getWidth() > w) w = img.getWidth();
            h += img.getHeight() + margin;
        }
        if (n == 0) return null;
        BufferedImage ret = TexI.mkbuf(new Coord(w, h));
        Graphics g = ret.getGraphics();
        int y = 0;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            int x = (w - img.getWidth()) / 2;
            g.drawImage(img, x, y, null);
            y += img.getHeight() + margin;
        }
        g.dispose();
        return ret;
    }

    private static BufferedImage getIcon(String basename, String type) {
        if (basename == null) return null;

        String resourceName = null;
        if ("seed".equals(type)) {
            if (SEEDS_MAP.containsKey(basename)) {
                resourceName = SEEDS_MAP.get(basename);
            } else {
                resourceName = "gfx/invobjs/seed-" + basename;
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
