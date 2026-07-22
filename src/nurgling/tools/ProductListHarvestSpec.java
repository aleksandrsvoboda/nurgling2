package nurgling.tools;

import haven.Gob;
import haven.ResDrawable;
import nurgling.NConfig;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gob types with no live per-instance harvest state at all (unlike trees/bushes): felled logs
 * (Board/Block) and mineable "bumlings" (a single ore/stone name). Every product VSpec.object
 * lists for the resource is always "present" as long as the gob itself still exists - same
 * existence-implies-harvestable assumption the LP-discovery marker already uses for these types -
 * so this just lists them all, tinting whichever are still undiscovered.
 */
public class ProductListHarvestSpec implements HarvestSpec {
    private final Predicate<String> matcher;
    private final NConfig.Key masterToggle;
    private final boolean horizontal;

    public ProductListHarvestSpec(Predicate<String> matcher, NConfig.Key masterToggle, boolean horizontal) {
        this.matcher = matcher;
        this.masterToggle = masterToggle;
        this.horizontal = horizontal;
    }

    @Override
    public boolean matches(String gobResName) {
        return gobResName != null && matcher.test(gobResName);
    }

    @Override
    public boolean horizontal() {
        return horizontal;
    }

    @Override
    public NConfig.Key masterToggle() {
        return masterToggle;
    }

    @Override
    public List<Part> parts(Gob gob, ResDrawable d) {
        // Bumlings' actual resource carries a variant-digit suffix VSpec.object doesn't key by
        // (see normalizeBumlingRes) - a no-op for logs, which have no such suffix.
        String gobResName = HarvestState.normalizeBumlingRes(d.getres().name);
        List<String> products = VSpec.object.get(gobResName);
        if (products == null)
            // A resource this spec matches (a log or a stone) but with no VSpec.object entry at
            // all - almost always a species new to the game we haven't added data for yet. Flag
            // it with the generic "?" rather than silently showing nothing.
            return Collections.singletonList(new Part("unknown", HarvestState.unknownIcon(), true));
        if (products.isEmpty())
            return Collections.emptyList();

        boolean lpassistentOn = LpExplorer.isEnabled();
        // Once every product for this resource has been found, every isProductUndiscovered() call
        // below would just return false anyway - skip them (icons still need to be resolved and
        // shown either way, just untinted).
        boolean fullyDiscovered = lpassistentOn && LpExplorer.isFullyDiscovered(gobResName);
        List<Part> parts = new ArrayList<>(products.size());
        for (String product : products) {
            BufferedImage img = LpExplorer.resolveProductIcon(gob, product);
            if (img != null)
                parts.add(new Part(product, img, lpassistentOn && !fullyDiscovered && LpExplorer.isProductUndiscovered(gobResName, product)));
        }
        return parts;
    }
}
