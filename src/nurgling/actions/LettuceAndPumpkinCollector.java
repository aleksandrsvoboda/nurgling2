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
import java.util.Arrays;

public class LettuceAndPumpkinCollector implements Action {
    private static final int PICKUP_VISIBILITY_MAX_CHECKS = 200;

    NArea input;
    NArea seedOutput;
    NArea itemOutput;
    NArea troughArea;
    NAlias items;
    String secondaryItemAlias;
    boolean isQualityGrid = false;

    public LettuceAndPumpkinCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, NArea troughArea) {
        this.input = input;
        this.seedOutput = seedOutput;
        this.itemOutput = itemOutput;
        this.items = items;
        this.troughArea = troughArea;
        this.secondaryItemAlias = items.keys.contains("Head of Lettuce") ? "Lettuce Leaf" : "Pumpkin Flesh";
    }

    public LettuceAndPumpkinCollector(NArea input, NArea seedOutput, NArea itemOutput, NAlias items, NArea troughArea, boolean isQualityGrid) {
        this(input, seedOutput, itemOutput, items, troughArea);
        this.isQualityGrid = isQualityGrid;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Preserve any exceptions the caller passed (e.g. "plants" to keep growing
        // crops out of the ground-item search) and add the standard container exclusions.
        ArrayList<String> exceptions = new ArrayList<>(items.exceptions);
        exceptions.add("stockpile");
        exceptions.add("barrel");
        NAlias collected_items = new NAlias(items.keys, exceptions);
        ArrayList<WItem> testItems;

        int totalItemsThatCanFit = 0;
        int currentQuantity = 0;

        while (!Finder.findGobs(input, collected_items).isEmpty()) {
            if (!(testItems = gui.getInventory().getItems(items)).isEmpty()) {
                totalItemsThatCanFit = Math.max(gui.getInventory().getNumberFreeCoord(testItems.get(0)) + 1, totalItemsThatCanFit);
                currentQuantity = gui.getInventory().getItems(items).size();

                if ((this.items.keys.contains("Head of Lettuce") && gui.getInventory().getNumberFreeCoord(testItems.get(0)) <= Math.floor(totalItemsThatCanFit/2))
                        || (this.items.keys.contains("Pumpkin") && gui.getInventory().getNumberFreeCoord(testItems.get(0)) == 0)) {
                    if (!splitItems(gui))
                        return Results.FAIL();

                    if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
                        if (!transferSeeds(gui))
                            return Results.FAIL();
                    }

                    if (!(testItems = gui.getInventory().getItems(new NAlias(this.secondaryItemAlias))).isEmpty()) {
                        if (!transferSecondaryItems(gui))
                            return Results.FAIL();
                    }

                    currentQuantity = 0;
                }
            }

            Gob item = Finder.findGob(input, collected_items);
            if (item == null)
                break;
            if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x) {
                PathFinder pf = new PathFinder(item);
                pf.run(gui);
            }
            NUtils.takeFromEarth(item);
            NUtils.getUI().core.addTask(new WaitMoreItems(
                    NUtils.getGameUI().getInventory(),
                    items,
                    currentQuantity + 1,
                    PICKUP_VISIBILITY_MAX_CHECKS
            ));
        }

        if (!splitItems(gui))
            return Results.FAIL();

        if (!(testItems = gui.getInventory().getItems(new NAlias("Seed"))).isEmpty()) {
            if (!transferSeeds(gui))
                return Results.FAIL();
        }

        if (!(testItems = gui.getInventory().getItems(new NAlias(this.secondaryItemAlias))).isEmpty()) {
            if (!transferSecondaryItems(gui))
                return Results.FAIL();
        }

        return Results.SUCCESS();
    }

    private boolean transferSeeds(NGameUI gui) throws InterruptedException {
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
            boolean transferred = new TransferToContainer(container, new NAlias("Seed")).run(gui).IsSuccess();
            boolean inventoryEmpty = gui.getInventory().getItems(new NAlias("Seed")).isEmpty();
            boolean closed = new CloseTargetContainer(container).run(gui).IsSuccess();
            return transferred && inventoryEmpty && closed;
        } else {
            // Regular mode: transfer seeds to barrels, then trough, then piles
            ArrayList<Gob> barrels = Finder.findGobs(seedOutput, new NAlias("barrel"));

            if (!barrels.isEmpty()) {
                for (Gob barrel : barrels) {
                    TransferToBarrel tb = new TransferToBarrel(barrel, new NAlias("Seed"));
                    if (!tb.run(gui).IsSuccess())
                        return false;
                    if (!tb.isFull()) break;
                }

                if (troughArea != null && !gui.getInventory().getItems(new NAlias("Seed")).isEmpty()) {
                    Gob trough = Finder.findGob(troughArea, new NAlias("gfx/terobjs/trough"));
                    if (trough != null) {
                        if (!new TransferToTrough(trough, new NAlias("Seed")).run(gui).IsSuccess())
                            return false;
                    }
                }
            }

            if (!gui.getInventory().getItems(new NAlias("Seed")).isEmpty()) {
                if (!new TransferToPiles(seedOutput.getRCArea(), new NAlias("Seed")).run(gui).IsSuccess())
                    return false;
                return gui.getInventory().getItems(new NAlias("Seed")).isEmpty();
            }
            return true;
        }
    }

    private boolean transferSecondaryItems(NGameUI gui) throws InterruptedException {
        NAlias secondaryItems = new NAlias(this.secondaryItemAlias);
        if (!new TransferToPiles(itemOutput.getRCArea(), secondaryItems).run(gui).IsSuccess())
            return false;
        return gui.getInventory().getItems(secondaryItems).isEmpty();
    }

    private boolean splitItems(NGameUI gui) throws InterruptedException {
        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
        ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(this.items);
        for (WItem item : items) {
            if(this.items.keys.contains("Head of Lettuce")) {
                if (!new SelectFlowerAction("Split", (NWItem) item).run(gui).IsSuccess())
                    return false;
            } else if(this.items.keys.contains("Pumpkin")) {
                if (!new SelectFlowerAction("Slice", (NWItem) item).run(gui).IsSuccess())
                    return false;
            }

        }
        return true;
    }
}
