package nurgling.tools;

import nurgling.NConfig;

import java.util.Arrays;
import java.util.List;

/** The four gob types the always-visible harvest overlay (NObjHarvestOl) currently covers. */
public class HarvestSpecs {
    public static final HarvestSpec TREE = new TreeHarvestSpec();
    public static final HarvestSpec BUSH = new BushHarvestSpec();
    public static final HarvestSpec LOG = new ProductListHarvestSpec(
        name -> name.startsWith("gfx/terobjs/trees") && name.endsWith("log"),
        NConfig.Key.logHarvestOverlay, true);
    public static final HarvestSpec STONE = new ProductListHarvestSpec(
        name -> name.startsWith("gfx/terobjs/bumlings"),
        NConfig.Key.stoneHarvestOverlay, false);

    private static final List<HarvestSpec> ALL = Arrays.asList(TREE, BUSH, LOG, STONE);

    private HarvestSpecs() {}

    /** The spec that applies to this gob resource, or null if none of the four cover it. */
    public static HarvestSpec forResource(String gobResName) {
        for (HarvestSpec spec : ALL) {
            if (spec.matches(gobResName))
                return spec;
        }
        return null;
    }

    /**
     * True if this gob's always-visible harvest overlay is currently showing (its type's master
     * toggle is on) - so the LP-discovery fallback marker should let it handle display instead of
     * showing a second, separate marker.
     */
    public static boolean isCovered(String gobResName) {
        HarvestSpec spec = forResource(gobResName);
        return spec != null && Boolean.TRUE.equals(NConfig.get(spec.masterToggle()));
    }
}
