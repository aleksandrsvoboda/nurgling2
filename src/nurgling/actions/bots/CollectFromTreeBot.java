package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Resource;
import haven.res.lib.tree.TreeScale;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.awt.Color;
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
    /**
     * Null keeps the original two-prompt flow (pick a tree area, then a pile area). Set to a
     * specialisation and the bot resolves its trees from that zone and empties the inventory
     * through FreeInventory2 instead, so the run needs no user interaction at all.
     */
    protected final Specialisation.SpecName zoneSpec;

    public CollectFromTreeBot(String flowerAction, String defaultPose, Coord itemSize,
                              NAlias itemAlias, NAlias treePattern, boolean filterByModelAttr,
                              String treesIcon, String pilesIcon,
                              String treesPrompt, String pilesPrompt) {
        this(flowerAction, defaultPose, itemSize, itemAlias, treePattern, filterByModelAttr,
                treesIcon, pilesIcon, treesPrompt, pilesPrompt, null);
    }

    public CollectFromTreeBot(String flowerAction, String defaultPose, Coord itemSize,
                              NAlias itemAlias, NAlias treePattern, boolean filterByModelAttr,
                              String treesIcon, String pilesIcon,
                              String treesPrompt, String pilesPrompt,
                              Specialisation.SpecName zoneSpec) {
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
        this.zoneSpec = zoneSpec;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        NArea treeArea;
        NArea pileArea = null;
        if (zoneSpec != null) {
            treeArea = context.findArea(zoneSpec);
            if (treeArea == null) {
                gui.msg("No area found! Please create an area with the '" + zoneLabel() + "' specialisation.", Color.RED);
                return Results.FAIL();
            }
            // ensurePresence=true rather than context.goToArea(): that skips the walk whenever the
            // area is merely reachable by local PF, and Finder.findGobs below only sees gobs that
            // are actually streamed in - the trap FillWaterskins hit, where a perfectly valid zone
            // reported nothing inside it.
            if (!NUtils.navigateToArea(treeArea, true)) {
                gui.msg("Failed to reach the '" + zoneLabel() + "' area", Color.RED);
                return Results.FAIL();
            }
        } else {
            String treeAreaId = context.createArea(treesPrompt, Resource.loadsimg(treesIcon));
            treeArea = context.goToAreaById(treeAreaId);

            String pileAreaId = context.createArea(pilesPrompt, Resource.loadsimg(pilesIcon));
            pileArea = context.goToAreaById(pileAreaId);
        }

        ArrayList<Gob> trees = Finder.findGobs(treeArea, treePattern);
        if (filterByModelAttr) {
            trees.removeIf(tree -> {
                long attr = tree.ngob.getModelAttribute();
                return attr != -1 && (attr & 2) != 0;
            });
        }
        if (zoneSpec != null) {
            // A tree carries a TreeScale attribute only while it is still growing - the server
            // drops it once the tree reaches full size, so "has no TreeScale" is the test for
            // fully grown, the same one Chopper's skip-growing-trees option uses.
            trees.removeIf(tree -> tree.getattr(TreeScale.class) != null);
        }
        trees.sort(NUtils.d_comp);

        for (Gob tree : trees) {
            Gob target = tree;
            if (zoneSpec != null) {
                if (NUtils.getGameUI().getInventory().getNumberFreeCoord(itemSize) == 0) {
                    new FreeInventory2(context).run(gui);
                    if (NUtils.getGameUI().getInventory().getNumberFreeCoord(itemSize) == 0) {
                        gui.msg("Inventory is full and nothing could be stored - set up an output area for the collected items.", Color.RED);
                        return Results.FAIL();
                    }
                    NUtils.navigateToArea(treeArea, true);
                }
                // This list was resolved before any dumping trip. A tree whose grid unloaded while
                // the player was away still stands in the world, but its Gob object is gone, so it
                // has to be looked up again by id rather than reused from the snapshot.
                target = Finder.findGob(tree.id);
                if (target == null) {
                    continue;
                }
            }
            String pose = resolvePose(target);
            if (zoneSpec != null) {
                new CollectFromGob(target, flowerAction, pose, itemSize, itemAlias, context, treeArea).run(gui);
            } else {
                new CollectFromGob(target, flowerAction, pose, itemSize, itemAlias, pileArea.getRCArea()).run(gui);
            }
        }
        if (zoneSpec != null) {
            new FreeInventory2(context).run(gui);
        } else {
            new TransferToPiles(pileArea.getRCArea(), itemAlias).run(gui);
        }
        return Results.SUCCESS();
    }

    private String zoneLabel() {
        Specialisation.SpecialisationItem item = Specialisation.findSpecialisation(zoneSpec.toString());
        return item != null ? item.prettyName : zoneSpec.toString();
    }

    protected String resolvePose(Gob tree) {
        if (defaultPose != null) {
            return defaultPose;
        }
        // Dynamic pose for bushes vs trees (used by CollectLeaf)
        return tree.ngob.name.contains("tree") ? "gfx/borka/treepickan" : "gfx/borka/bushpickan";
    }
}
