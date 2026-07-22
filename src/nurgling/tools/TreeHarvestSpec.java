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
 * Living trees: seed/leaf/bough/bark, each independently toggleable, read from the tree's live
 * per-instance state bitmask (see HarvestState) rather than guessed from species/season. Bough
 * and bark aren't part of that bitmask - bough is a fixed per-species trait (HarvestState.hasBough),
 * bark is assumed always available on a mature tree (its own item disappears once fully harvested,
 * same as the tree itself would stop being "mature" - not modeled further here).
 */
public class TreeHarvestSpec implements HarvestSpec {
    @Override
    public boolean matches(String gobResName) {
        return HarvestState.isTreeOrBushRes(gobResName) && gobResName.startsWith("gfx/terobjs/trees");
    }

    @Override
    public boolean horizontal() {
        return false;
    }

    @Override
    public NConfig.Key masterToggle() {
        return NConfig.Key.treeHarvestOverlay;
    }

    @Override
    public List<Part> parts(Gob gob, ResDrawable d) {
        if (!HarvestState.isMatureTreeOrBush(gob, d))
            return Collections.emptyList();

        Resource res = d.getres();
        int sdt = Sprite.decnum(d.sdt.clone());
        String base = res.basename();

        boolean showSeeds = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestSeeds));
        boolean showLeaves = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestLeaves));
        boolean showBoughs = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBoughs));
        boolean showBark = Boolean.TRUE.equals(NConfig.get(NConfig.Key.treeHarvestBark));

        boolean seed = showSeeds && HarvestState.hasSeedBit(sdt);
        boolean leaf = showLeaves && HarvestState.hasLeafBit(sdt);
        boolean bough = showBoughs && HarvestState.hasBough(base);
        boolean bark = showBark;

        boolean lpassistentOn = LpExplorer.isEnabled();
        boolean seedUndiscovered = seed && lpassistentOn && LpExplorer.hasUndiscoveredSeedProduct(res.name);
        boolean leafUndiscovered = leaf && lpassistentOn && LpExplorer.hasUndiscoveredLeafProduct(res.name);
        boolean boughUndiscovered = bough && lpassistentOn && LpExplorer.hasUndiscoveredBoughProduct(res.name);
        boolean barkUndiscovered = bark && lpassistentOn && LpExplorer.hasUndiscoveredBarkProduct(res.name);

        List<Part> parts = new ArrayList<>(4);
        HarvestSpec.addPart(parts, "leaf", leaf, HarvestState.getIcon(base, "leaf"), leafUndiscovered);
        HarvestSpec.addPart(parts, "seed", seed, HarvestState.getIcon(base, "seed"), seedUndiscovered);
        HarvestSpec.addPart(parts, "bough", bough, HarvestState.getIcon(base, "bough"), boughUndiscovered);
        HarvestSpec.addPart(parts, "bark", bark, HarvestState.getIcon(base, "bark"), barkUndiscovered);
        return parts;
    }
}
