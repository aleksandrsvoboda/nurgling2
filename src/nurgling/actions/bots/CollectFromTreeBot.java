package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

/**
 * Parameterized tree/bush collection bot.
 * Collects items (bark, boughs, leaves) from trees in a user-selected area.
 * Delegates per-tree collection to CollectFromGob and transfers results to piles.
 */
public class CollectFromTreeBot implements Action {

    protected final String flowerAction;
    protected final String defaultPose;
    protected final Coord itemSize;
    protected final NAlias itemAlias;
    protected final NAlias treePattern;
    protected final boolean filterByModelAttr;
    protected final String treesIcon;
    protected final String pilesIcon;
    protected final String treesPrompt;
    protected final String pilesPrompt;

    public CollectFromTreeBot(String flowerAction, String defaultPose, Coord itemSize,
                              NAlias itemAlias, NAlias treePattern, boolean filterByModelAttr,
                              String treesIcon, String pilesIcon,
                              String treesPrompt, String pilesPrompt) {
        this.flowerAction = flowerAction;
        this.defaultPose = defaultPose;
        this.itemSize = itemSize;
        this.itemAlias = itemAlias;
        this.treePattern = treePattern;
        this.filterByModelAttr = filterByModelAttr;
        this.treesIcon = treesIcon;
        this.pilesIcon = pilesIcon;
        this.treesPrompt = treesPrompt;
        this.pilesPrompt = pilesPrompt;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        String treeAreaId = context.createArea(treesPrompt, Resource.loadsimg(treesIcon));
        NArea treeArea = context.goToAreaById(treeAreaId);

        String pileAreaId = context.createArea(pilesPrompt, Resource.loadsimg(pilesIcon));
        NArea pileArea = context.goToAreaById(pileAreaId);

        ArrayList<Gob> trees = Finder.findGobs(treeArea, treePattern);
        if (filterByModelAttr) {
            trees.removeIf(tree -> {
                long attr = tree.ngob.getModelAttribute();
                return attr != -1 && (attr & 2) != 0;
            });
        }
        trees.sort(NUtils.d_comp);

        for (Gob tree : trees) {
            String pose = resolvePose(tree);
            new CollectFromGob(tree, flowerAction, pose, itemSize, itemAlias, pileArea.getRCArea()).run(gui);
        }
        new TransferToPiles(pileArea.getRCArea(), itemAlias).run(gui);
        return Results.SUCCESS();
    }

    protected String resolvePose(Gob tree) {
        if (defaultPose != null) {
            return defaultPose;
        }
        // Dynamic pose for bushes vs trees (used by CollectLeaf)
        return tree.ngob.name.contains("tree") ? "gfx/borka/treepickan" : "gfx/borka/bushpickan";
    }
}
