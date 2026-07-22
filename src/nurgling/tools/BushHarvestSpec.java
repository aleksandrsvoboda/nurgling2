package nurgling.tools;

import haven.Gob;
import haven.ResDrawable;
import haven.Resource;
import haven.Sprite;
import nurgling.NConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bushes: same live per-instance seed/leaf bitmask as trees (HarvestState already treats bushes
 * as tree-like for maturity/state purposes), but no bough/bark and no granular sub-toggles - one
 * master switch shows both seed and leaf together.
 */
public class BushHarvestSpec implements HarvestSpec {
    @Override
    public boolean matches(String gobResName) {
        return HarvestState.isTreeOrBushRes(gobResName) && gobResName.startsWith("gfx/terobjs/bushes");
    }

    @Override
    public boolean horizontal() {
        return false;
    }

    @Override
    public NConfig.Key masterToggle() {
        return NConfig.Key.bushHarvestOverlay;
    }

    @Override
    public List<Part> parts(Gob gob, ResDrawable d) {
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return Collections.emptyList();

        Resource res = d.getres();
        if (!VSpec.object.containsKey(res.name))
            // A species we have no data for at all - see TreeHarvestSpec's identical check for
            // why this is judged at the species level, not per-category below.
            return Collections.singletonList(new Part("unknown", HarvestState.unknownIcon(), true));

        int sdt = Sprite.decnum(d.sdt.clone());

        boolean seed = HarvestState.hasSeedBit(sdt);
        boolean leaf = HarvestState.hasLeafBit(sdt);

        boolean lpassistentOn = LpExplorer.isEnabled();
        LpExplorer.UndiscoveredCategories undiscovered = lpassistentOn
            ? LpExplorer.undiscoveredCategories(res.name) : null;
        boolean seedUndiscovered = seed && undiscovered != null && undiscovered.seed;
        boolean leafUndiscovered = leaf && undiscovered != null && undiscovered.leaf;

        List<Part> parts = new ArrayList<>(2);
        HarvestSpec.addPart(parts, "leaf", leaf, HarvestState.getIcon(res, "leaf"), leafUndiscovered);
        HarvestSpec.addPart(parts, "seed", seed, HarvestState.getIcon(res, "seed"), seedUndiscovered);
        return parts;
    }
}
