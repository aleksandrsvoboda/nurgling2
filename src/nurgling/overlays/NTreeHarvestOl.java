package nurgling.overlays;

import haven.*;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import nurgling.NConfig;
import nurgling.styles.TooltipStyle;
import nurgling.tools.HarvestState;
import nurgling.tools.LpExplorer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlay for trees/bushes showing what can be harvested (leaf/seed/fruit/bough),
 * inspired by HerSRC's GobReadyForHarvestInfo.
 *
 * Renders small combined icons (leaf + fruit/seed + bough + bark) only when the object is mature/ready.
 */
public class NTreeHarvestOl extends NObjectTexLabel {
    // Light tint (icon stays recognizable) marking a still-undiscovered LP product's icon,
    // shared with NLPassistant's own standalone fallback marker.
    public static final Color LP_UNDISCOVERED_TINT = new Color(0, 255, 0, 110);

    private static final Map<String, TexI> LABEL_CACHE = new ConcurrentHashMap<>();

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
        if (gob.getattr(Drawable.class) == null)
            return true;
        if (!refresh())
            return true;  // label is null → remove overlay
        return false;
    }

    public static void clearLabelCache() {
        LABEL_CACHE.clear();
    }

    public static boolean isTreeOrBushRes(String resname) {
        return HarvestState.isTreeOrBushRes(resname);
    }

    /**
     * True if this gob currently shows its own harvest-icon display (i.e. is a tree/bush AND
     * "Show harvest icons on trees" is enabled) - so LP-assistant markers should let this class's
     * own icon-tinting handle it instead of showing a second, separate marker.
     */
    public static boolean coversGob(String resName) {
        return isTreeOrBushRes(resName) && Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestOverlay));
    }

    public static TexI computeLabel(Gob gob) {
        if (gob == null) return null;
        Drawable dr = gob.getattr(Drawable.class);
        ResDrawable d = (dr instanceof ResDrawable) ? (ResDrawable) dr : null;
        if (d == null) return null;
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return null;

        Resource res = d.getres();
        int sdt = Sprite.decnum(d.sdt.clone());
        String base = res.basename();
        boolean isTree = res.name.startsWith("gfx/terobjs/trees");

        boolean showSeeds = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestSeeds));
        boolean showLeaves = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestLeaves));
        boolean showBoughs = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBoughs));
        boolean showBark = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBark));

        boolean seed = showSeeds && (sdt & 1) != 1;
        boolean leaf = showLeaves && (sdt & 2) != 2;
        boolean bough = showBoughs && isTree && HarvestState.hasBough(base);
        boolean bark = showBark && isTree;

        // If a shown icon's OWN category still has an LP-undiscovered product, tint just that
        // icon instead of a separate marker - reverts to normal automatically once discovered,
        // since this whole method re-evaluates every tick. Checked per-category (not "is anything
        // for this species undiscovered") since a species can track more than one product (e.g.
        // figtree -> "Fig Leaf" + "Fig"), and a blanket check would tint the wrong icon, or leave
        // the right one untinted, whenever only one of them is still unknown.
        boolean lpassistentOn = LpExplorer.isEnabled();
        boolean seedUndiscovered = seed && lpassistentOn && LpExplorer.hasUndiscoveredSeedProduct(res.name);
        boolean leafUndiscovered = leaf && lpassistentOn && LpExplorer.hasUndiscoveredLeafProduct(res.name);
        boolean boughUndiscovered = bough && lpassistentOn && LpExplorer.hasUndiscoveredBoughProduct(res.name);
        boolean barkUndiscovered = bark && lpassistentOn && LpExplorer.hasUndiscoveredBarkProduct(res.name);

        StringBuilder key = new StringBuilder();
        key.append(res.name.startsWith("gfx/terobjs/bushes") ? "bush_" : "tree_");
        key.append(base).append("_");
        if (seed) key.append("withSeed_");
        if (seedUndiscovered) key.append("undiscoveredSeed_");
        if (leaf) key.append("withLeaf_");
        if (leafUndiscovered) key.append("undiscoveredLeaf_");
        if (bough) key.append("withBough_");
        if (boughUndiscovered) key.append("undiscoveredBough_");
        if (bark) key.append("withBark_");
        if (barkUndiscovered) key.append("undiscoveredBark_");

        TexI cached = LABEL_CACHE.get(key.toString());
        if (cached != null)
            return cached;

        BufferedImage[] parts = new BufferedImage[]{
                resolveIcon(base, "leaf", leaf, leafUndiscovered),
                resolveIcon(base, "seed", seed, seedUndiscovered),
                resolveIcon(base, "bough", bough, boughUndiscovered),
                resolveIcon(base, "bark", bark, barkUndiscovered),
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

    // Resolves one harvest-category icon, tinted if its product is still LP-undiscovered.
    private static BufferedImage resolveIcon(String base, String type, boolean shown, boolean undiscovered) {
        if (!shown) return null;
        BufferedImage img = HarvestState.getIcon(base, type);
        return (undiscovered && img != null) ? tint(img, LP_UNDISCOVERED_TINT) : img;
    }

    // Public so NLPassistant and LpExplorer can frame their own icon(s) in the same style.
    public static BufferedImage catimgshCentered(int margin, BufferedImage... imgs) {
        int w = 0, h = -margin;
        int n = 0;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            n++;
            if (img.getWidth() > w) w = img.getWidth();
            h += img.getHeight() + margin;
        }
        if (n == 0) return null;
        int pad = UI.scale(3);
        BufferedImage ret = TexI.mkbuf(new Coord(w + pad * 2, h + pad * 2));
        Graphics g = ret.getGraphics();
        g.setColor(TooltipStyle.COLOR_OVERLAY_BG);
        g.fillRect(0, 0, w + pad * 2, h + pad * 2);
        int y = pad;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            int x = pad + (w - img.getWidth()) / 2;
            g.drawImage(img, x, y, null);
            y += img.getHeight() + margin;
        }
        g.dispose();
        return ret;
    }

    // Tint that preserves the source image's own alpha/silhouette, matching the math
    // haven.ColorMask applies for 3D-rendered sprites (result = base*(1-a) + tint*a). Public so
    // NLPassistant and LpExplorer can tint their own icons the same way.
    public static BufferedImage tint(BufferedImage src, Color color) {
        BufferedImage out = TexI.mkbuf(Utils.imgsz(src));
        Graphics2D g = (Graphics2D) out.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.setComposite(AlphaComposite.SrcAtop);
        g.setColor(color);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.dispose();
        return out;
    }
}
