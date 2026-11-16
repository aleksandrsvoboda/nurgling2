package nurgling.actions.bots.largefields;

import haven.Gob;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.TakeFromBarrel;
import nurgling.actions.TakeItemsFromPile;
import nurgling.actions.TransferToBarrel;
import nurgling.actions.TransferToPiles;
import nurgling.actions.TransferToTrough;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.CropRegistry;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The seed, put and trough areas of a large field. They are kept as areas, never as objects:
 * barrels, troughs and piles are looked up again after every walk there, because the objects
 * found on an earlier visit were dropped by the client when the bot walked out of range.
 *
 * Every trip walks to the area nearest to where the bot is (so players can put several storage
 * stations along a long field) and leaves the bot there; the caller walks back.
 */
final class FieldStores {
    private final LargeFieldCrop crop;
    private final boolean ignoreStraw;

    FieldStores(LargeFieldCrop crop, boolean ignoreStraw) {
        this.crop = crop;
        this.ignoreStraw = ignoreStraw;
    }

    /** Nearest reachable seed area of the crop, or null. */
    NArea seedArea() {
        ArrayList<NArea> areas = NContext.findAllSpec(Specialisation.SpecName.seed.toString(), crop.subtype);
        return areas.isEmpty() ? null : areas.get(0);
    }

    /** Where a vegetable of this crop is kept: its own put area for dual-product crops, else the seed area. */
    NArea vegArea(NGameUI gui) {
        if (crop.vegOut != null)
            return NContext.findOutGlobal(crop.vegOut, 1, gui);
        return seedArea();
    }

    /** Put area of a by-product, or null when there is none (or it's straw and straw is ignored). */
    NArea byProductArea(LargeFieldCrop.ByProduct bp, NGameUI gui) {
        if (bp.straw && ignoreStraw)
            return null;
        return NContext.findOutGlobal(bp.name, 1, gui);
    }

    /** By-products to pick up from the field: those with a put area. */
    List<LargeFieldCrop.ByProduct> collectedByProducts(NGameUI gui) {
        List<LargeFieldCrop.ByProduct> res = new ArrayList<>();
        for (LargeFieldCrop.ByProduct bp : crop.byProducts)
            if (bp.alias != null && byProductArea(bp, gui) != null)
                res.add(bp);
        return res;
    }

    /** Why the run can't start, or null: pumpkin and watermelon need a put area for their pieces. */
    String missingRequired(NGameUI gui) {
        if (crop.whole != null && crop.whole.pieceRequired) {
            for (LargeFieldCrop.ByProduct bp : crop.byProducts)
                if (byProductArea(bp, gui) == null)
                    return "PUT area for " + bp.name + " required, but not found";
        }
        return null;
    }

    /** Cuts every whole head or fruit carried into seeds and pieces (Split / Slice). */
    void cutWhole(NGameUI gui) throws InterruptedException {
        if (crop.whole == null)
            return;
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        java.util.ArrayList<WItem> items = gui.getInventory().getItems(crop.whole.alias);
        if (items.isEmpty())
            return;
        NUtils.getUI().core.addTask(new nurgling.tasks.NFlowerMenuIsClosed());
        for (WItem item : items)
            new nurgling.actions.SelectFlowerAction(crop.whole.verb, item).run(gui);
    }

    /** The champignon store: the nearest seed area, holding containers. */
    private nurgling.actions.AreaContainerStore containerStore() {
        NArea seed = seedArea();
        // home = the store itself: the caller walks back to the field on its own.
        return seed == null ? null : new nurgling.actions.AreaContainerStore(seed, seed);
    }

    List<NAlias> containerKinds() {
        return CropRegistry.getPlantingMaterials(crop.plant, CropRegistry.StorageBehavior.CONTAINER);
    }

    /** Exact names of inventory items of the given storage behaviour this crop yields. */
    private Set<String> productNames(CropRegistry.StorageBehavior behavior) {
        Set<String> res = new LinkedHashSet<>();
        for (CropRegistry.CropStage s : CropRegistry.getStages(crop.plant))
            if (s.storageBehavior == behavior)
                res.addAll(s.result.keys);
        return res;
    }

    private static List<String> namesInInventory(NGameUI gui, Set<String> wanted) throws InterruptedException {
        List<String> res = new ArrayList<>();
        for (WItem it : gui.getInventory().getItems()) {
            String n = ((NGItem) it.item).name();
            if (n != null && wanted.contains(n) && !res.contains(n))
                res.add(n);
        }
        return res;
    }

    /** Drops by-products that have nowhere to go, as the regular farmers leave them on the field. */
    void dropHomeless(NGameUI gui) throws InterruptedException {
        for (LargeFieldCrop.ByProduct bp : crop.byProducts) {
            if (byProductArea(bp, gui) != null)
                continue;
            for (WItem it : gui.getInventory().getItems())
                if (bp.name.equals(((NGItem) it.item).name()))
                    NUtils.drop(it);
        }
    }

    /**
     * Takes everything the harvest yielded to its storage: seeds to the seed barrels (overflow to
     * the troughs), vegetables and by-products to their piles. Leaves the bot at the last store.
     *
     * @return null on success, else why the harvest can't go anywhere (the bot must stop)
     */
    String unload(NGameUI gui) throws InterruptedException {
        cutWhole(gui);
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        dropHomeless(gui);

        if (crop.containerStore && nurgling.actions.AreaContainerStore.count(gui, containerKinds()) > 0) {
            nurgling.actions.AreaContainerStore store = containerStore();
            if (store == null)
                return "no reachable seed area (containers) for " + crop.subtype;
            store.deposit(gui, containerKinds(), 0);
        }

        List<String> seeds = namesInInventory(gui, productNames(CropRegistry.StorageBehavior.BARREL));
        if (!seeds.isEmpty()) {
            NArea seed = seedArea();
            if (seed == null || !NUtils.navigateToArea(seed, true))
                return "can't reach a seed area for " + crop.subtype;
            for (String name : seeds) {
                for (Gob barrel : Finder.findGobs(seed, new NAlias("barrel"))) {
                    TransferToBarrel tb = new TransferToBarrel(barrel, name);
                    tb.run(gui);
                    if (!tb.isFull())
                        break;
                }
            }
            List<String> left = namesInInventory(gui, productNames(CropRegistry.StorageBehavior.BARREL));
            if (!left.isEmpty()) {
                NArea trough = NContext.findSpecGlobal(Specialisation.SpecName.trough.toString());
                if (trough != null && NUtils.navigateToArea(trough, true)) {
                    List<Gob> troughs = TransferToTrough.findTroughs(trough);
                    // Full troughs are emptied into the swill area's cistern, as HarvestCrop does;
                    // only when that cistern is loaded from the troughs (they normally stand together).
                    Gob cistern = null;
                    NArea swill = NContext.findSpecGlobal(Specialisation.SpecName.swill.toString());
                    if (swill != null && swill.getRCArea() != null)
                        cistern = Finder.findGob(swill, new NAlias("gfx/terobjs/cistern"));
                    if (!troughs.isEmpty())
                        for (String name : left)
                            new TransferToTrough(troughs, new NAlias(name), cistern).run(gui);
                }
                if (!namesInInventory(gui, productNames(CropRegistry.StorageBehavior.BARREL)).isEmpty())
                    return "the seed barrels (and troughs) for " + crop.subtype + " are full";
            }
        }

        List<String> vegs = namesInInventory(gui, productNames(CropRegistry.StorageBehavior.STOCKPILE));
        if (!vegs.isEmpty()) {
            NArea veg = vegArea(gui);
            if (veg == null)
                return "no put area for " + String.join(", ", vegs);
            if (!NUtils.navigateToArea(veg, true))
                return "can't reach the put area for " + String.join(", ", vegs);
            for (String name : vegs)
                new TransferToPiles(veg.getRCArea(), name, 0).run(gui);
        }

        for (LargeFieldCrop.ByProduct bp : crop.byProducts) {
            NArea out = byProductArea(bp, gui);
            if (out == null || namesInInventory(gui, java.util.Collections.singleton(bp.name)).isEmpty())
                continue;
            if (!NUtils.navigateToArea(out, true))
                return "can't reach the put area for " + bp.name;
            new TransferToPiles(out.getRCArea(), bp.name, 0).run(gui);
        }
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        return null;
    }

    /**
     * Fills the inventory with planting material from the nearest store: seeds from the barrels,
     * or vegetables from the piles. Leaves the bot at the store.
     */
    void fetch(NGameUI gui, FieldWork.Source src, int wanted) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        if (crop.containerStore) {
            nurgling.actions.AreaContainerStore store = containerStore();
            if (store != null)
                store.take(gui, containerKinds(), wanted);
        } else if (src.stacked) {
            NArea seed = seedArea();
            if (seed == null || !NUtils.navigateToArea(seed, true))
                return;
            for (Gob barrel : Finder.findGobs(seed, new NAlias("barrel"))) {
                if (gui.getInventory().getFreeSpace() <= 0)
                    break;
                if (NUtils.barrelHasContent(barrel))
                    new TakeFromBarrel(barrel, src.item).run(gui);
            }
        } else {
            NArea veg = vegArea(gui);
            if (veg == null || !NUtils.navigateToArea(veg, true))
                return;
            for (Gob pile : Finder.findGobs(veg, new NAlias("stockpile"))) {
                if (gui.getInventory().getFreeSpace() <= 0)
                    break;
                new nurgling.actions.PathFinder(pile).run(gui);
                new OpenTargetContainer("Stockpile", pile).run(gui);
                if (gui.getStockpile() == null)
                    continue;
                int n = gui.getInventory().getFreeSpace();
                if (((NInventory) gui.maininv).bundle.a)
                    n *= StackSupporter.getFullStackSize(src.item.getDefault());
                new TakeItemsFromPile(pile, gui.getStockpile(), n).run(gui);
            }
        }
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
    }
}
