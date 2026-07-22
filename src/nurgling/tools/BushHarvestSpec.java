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
        int sdt = Sprite.decnum(d.sdt.clone());
        String base = res.basename();

        boolean seed = HarvestState.hasSeedBit(sdt);
        boolean leaf = HarvestState.hasLeafBit(sdt);

        boolean lpassistentOn = LpExplorer.isEnabled();
        boolean seedUndiscovered = seed && lpassistentOn && LpExplorer.hasUndiscoveredSeedProduct(res.name);
        boolean leafUndiscovered = leaf && lpassistentOn && LpExplorer.hasUndiscoveredLeafProduct(res.name);

        List<Part> parts = new ArrayList<>(2);
        HarvestSpec.addPart(parts, "leaf", leaf, HarvestState.getIcon(base, "leaf"), leafUndiscovered);
        HarvestSpec.addPart(parts, "seed", seed, HarvestState.getIcon(base, "seed"), seedUndiscovered);
        return parts;
    }
}
