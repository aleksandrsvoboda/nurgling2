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
        if (!VSpec.object.containsKey(res.name))
            // A species we have no data for at all (e.g. one the game added after this mod was
            // last updated) - flag it rather than silently showing nothing. Not every KNOWN
            // species tracks every category (most have no distinct leaf product, for instance),
            // so that's judged here at the species level, not per-category below.
            return Collections.singletonList(new Part("unknown", HarvestState.unknownIcon(), true));

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
        LpExplorer.UndiscoveredCategories undiscovered = lpassistentOn
            ? LpExplorer.undiscoveredCategories(res.name) : null;
        boolean seedUndiscovered = seed && undiscovered != null && undiscovered.seed;
        boolean leafUndiscovered = leaf && undiscovered != null && undiscovered.leaf;
        boolean boughUndiscovered = bough && undiscovered != null && undiscovered.bough;
        boolean barkUndiscovered = bark && lpassistentOn && LpExplorer.hasUndiscoveredBarkProduct(res.name);

        List<Part> parts = new ArrayList<>(4);
        HarvestSpec.addPart(parts, "leaf", leaf, HarvestState.getIcon(res, "leaf"), leafUndiscovered);
        HarvestSpec.addPart(parts, "seed", seed, HarvestState.getIcon(res, "seed"), seedUndiscovered);
        HarvestSpec.addPart(parts, "bough", bough, HarvestState.getIcon(res, "bough"), boughUndiscovered);
        HarvestSpec.addPart(parts, "bark", bark, HarvestState.getIcon(res, "bark"), barkUndiscovered);
        return parts;
    }
}
