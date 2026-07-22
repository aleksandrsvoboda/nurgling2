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
        String gobResName = d.getres().name;
        List<String> products = VSpec.object.get(gobResName);
        if (products == null || products.isEmpty())
            return Collections.emptyList();

        boolean lpassistentOn = LpExplorer.isEnabled();
        List<Part> parts = new ArrayList<>(products.size());
        for (String product : products) {
            BufferedImage img = LpExplorer.resolveProductIcon(gob, product);
            if (img != null)
                parts.add(new Part(product, img, lpassistentOn && LpExplorer.isProductUndiscovered(gobResName, product)));
        }
        return parts;
    }
}
