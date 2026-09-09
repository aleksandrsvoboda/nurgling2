package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.NWItem;
import nurgling.areas.NArea;
import nurgling.tasks.*;
import nurgling.tools.Container;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

/**
 * Collects the whole heads/fruit a harvest leaves lying in the field, cuts them up on the
 * spot and delivers the pieces: seeds to the seed area, the cut by-product to its piles.
 */
public class LettucePumpkinAndWatermelonCollector implements Action {

    /**
     * The per-crop half of the job. The pickup itself is identical for all three crops;
     * what differs is the flower-menu verb that cuts one item up, the name of the piece
     * that falls out, and how full the inventory has to be before it is worth stopping to
     * cut. Heads of lettuce are small and numerous, so they are split at half full; a
     * pumpkin or a watermelon is cut only once nothing else fits.
     */
    public enum Product {
        LETTUCE("Lettuce Leaf", "Split", true),
        PUMPKIN("Pumpkin Flesh", "Slice", false),
        WATERMELON("Watermelon Slice", "Slice", false);

        public final String byProduct;
        public final String verb;
        public final boolean cutWhenHalfFull;

        Product(String byProduct, String verb, boolean cutWhenHalfFull) {
            this.byProduct = byProduct;
            this.verb = verb;
            this.cutWhenHalfFull = cutWhenHalfFull;
        }
    }

    NArea input;
    NArea seedOutput;
    NArea itemOutput;
    NArea troughArea;
    NAlias items;
    Product product;
    boolean isQualityGrid = false;

    public LettucePumpkinAndWatermelonCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, Product product, NArea troughArea) {
        this.input = input;
        this.seedOutput = seedOutput;
        this.itemOutput = itemOutput;
        this.items = items;
        this.product = product;
        this.troughArea = troughArea;
    }

    public LettucePumpkinAndWatermelonCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, Product product, NArea troughArea, boolean isQualityGrid) {
        this(input, seedOutput, itemOutput, items, product, troughArea);
        this.isQualityGrid = isQualityGrid;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Preserve any exceptions the caller passed (e.g. "flesh" to keep the by-product
        // out of the search) and add the standard container exclusions.
        ArrayList<String> exceptions = new ArrayList<>(items.exceptions);
        exceptions.add("stockpile");
        exceptions.add("barrel");
        // A growing crop and the item it yields share a name - "gfx/terobjs/plants/pumpkin"
        // vs "gfx/terobjs/items/pumpkin" - so an item alias like "Pumpkin" (substring match)
        // also hits the planted crop. Picking up from the earth can never apply to a plant,
        // so exclude the plant path outright: on a partially planted field takeFromEarth
        // would otherwise wait forever for a gob that never goes away.
        exceptions.add("plants/");
        NAlias collected_items = new NAlias(items.keys, exceptions);
        ArrayList<WItem> testItems;

        int totalItemsThatCanFit = 0;
        int currentQuantity = 0;

        while (!Finder.findGobs(input, collected_items).isEmpty()) {
            if (!(testItems = gui.getInventory().getItems(items)).isEmpty()) {
                totalItemsThatCanFit = Math.max(gui.getInventory().getNumberFreeCoord(testItems.get(0)) + 1, totalItemsThatCanFit);
                currentQuantity = gui.getInventory().getItems(items).size();

                int free = gui.getInventory().getNumberFreeCoord(testItems.get(0));
                boolean timeToCut = product.cutWhenHalfFull
                        ? free <= Math.floor(totalItemsThatCanFit / 2)
                        : free == 0;

                if (timeToCut) {
                    splitItems(gui);

                    if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
                        transferSeeds(gui);
                    }

                    if (!(testItems = gui.getInventory().getItems(new NAlias(product.byProduct))).isEmpty()) {
                        transferSecondaryItems(gui);
                    }

                    currentQuantity = 0;
                }
            }

            Gob item = Finder.findGob(collected_items);
            if (item == null)
                break;
            if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x) {
                PathFinder pf = new PathFinder(item);
                pf.run(gui);
            }
            NUtils.takeFromEarth(item);
            NUtils.getUI().core.addTask(new WaitMoreItems(NUtils.getGameUI().getInventory(), items, currentQuantity+1));
        }

        splitItems(gui);

        if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
            transferSeeds(gui);
        }

        if (!(testItems = gui.getInventory().getItems(new NAlias(product.byProduct))).isEmpty()) {
            transferSecondaryItems(gui);
        }

        return Results.SUCCESS();
    }

    /**
     * Moves the cut by-product ("Pumpkin Flesh" / "Lettuce Leaf" / "Watermelon Slice") to its
     * piles by exact name. The exact-name form keeps TransferToPiles on the batched
     * Stockpile.put() path - matched as an alias, "Pumpkin Flesh" collides with its own
     * stacking-category sibling "Pumpkin" and every load would be moved one item at a time.
     */
    private void transferSecondaryItems(NGameUI gui) throws InterruptedException {
        new TransferToPiles(itemOutput.getRCArea(), product.byProduct, 0).run(gui);
    }

    private void transferSeeds(NGameUI gui) throws InterruptedException {
        if (isQualityGrid) {
            // Quality mode: transfer seeds to containers
            ArrayList<Container> containers = new ArrayList<>();
            for (Gob sm : Finder.findGobs(seedOutput.getRCArea(), new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
                Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), null);
                cand.initattr(Container.Space.class);
                containers.add(cand);
            }

            if (containers.isEmpty())
                throw new RuntimeException("No container found in seed area!");

            Container container = containers.get(0);
            new TransferToContainer(container, new NAlias("Seed")).run(gui);
            new CloseTargetContainer(container).run(gui);
        } else {
            // Regular mode: transfer seeds to barrels, then trough, then piles
            ArrayList<Gob> barrels = Finder.findGobs(seedOutput, new NAlias("barrel"));

            if (!barrels.isEmpty()) {
                for (Gob barrel : barrels) {
                    TransferToBarrel tb = new TransferToBarrel(barrel, new NAlias("Seed"));
                    tb.run(gui);
                    if (!tb.isFull()) break;
                }

                if (troughArea != null && !gui.getInventory().getItems(new NAlias("Seed")).isEmpty()) {
                    Gob trough = Finder.findGob(troughArea, new NAlias("gfx/terobjs/trough"));
                    if (trough != null) {
                        new TransferToTrough(trough, new NAlias("Seed")).run(gui);
                    }
                }
            }

            if (!gui.getInventory().getItems(new NAlias("Seed")).isEmpty()) {
                new TransferToPiles(seedOutput.getRCArea(), new NAlias("Seed")).run(gui);
            }
        }
    }

    private void splitItems(NGameUI gui) throws InterruptedException {
        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
        ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(this.items);
        for (WItem item : items) {
            new SelectFlowerAction(product.verb, (NWItem) item).run(gui);
        }
    }
}
