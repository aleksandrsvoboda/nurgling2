package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.tasks.WaitMoreItems;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.ArrayList;

public class CollectItemsToPile implements Action{
    private static final int PICKUP_VISIBILITY_MAX_CHECKS = 200;

    Pair<Coord2d,Coord2d> out;
    Pair<Coord2d,Coord2d> in;

    NAlias items;
    public CollectItemsToPile(Pair<Coord2d, Coord2d> input, Pair<Coord2d, Coord2d> output, NAlias items)
    {
        this.out = output;
        this.in = input;
        this.items = items;
    }

    CollectItemsToPile(NArea input, NArea output, NAlias items)
    {
        this(input.getRCArea(), output.getRCArea(),items);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        // Preserve any exceptions the caller passed (e.g. "seed" to keep seeds out of a
        // vegetable pile) and add the standard container exclusions on top.
        ArrayList<String> exceptions = new ArrayList<>(items.exceptions);
        exceptions.add("stockpile");
        exceptions.add("barrel");
        NAlias collected_items = new NAlias(items.keys, exceptions);

        boolean deposited = GroundItemCollectionContract.collectAndDeposit(new GroundItemCollectionContract.Port<Gob>() {
            @Override
            public Gob nextGroundItem() throws InterruptedException {
                return Finder.findGob(in, collected_items);
            }

            @Override
            public boolean inventoryMustBeDeposited() throws InterruptedException {
                ArrayList<WItem> inventoryItems = gui.getInventory().getItems(items);
                return !inventoryItems.isEmpty()
                        && gui.getInventory().getNumberFreeCoord(inventoryItems.get(0)) == 0;
            }

            @Override
            public boolean depositInventory() throws InterruptedException {
                return new TransferToPiles(out, items).run(gui).IsSuccess();
            }

            @Override
            public void approach(Gob item) throws InterruptedException {
                if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x) {
                    new PathFinder(item).run(gui);
                }
            }

            @Override
            public int visibleInventoryCount() throws InterruptedException {
                return gui.getInventory().getItems(items).size();
            }

            @Override
            public void pickUp(Gob item) throws InterruptedException {
                NUtils.takeFromEarth(item);
            }

            @Override
            public void awaitInventoryCountAtLeast(int minimumCount) throws InterruptedException {
                NUtils.getUI().core.addTask(
                        new WaitMoreItems(
                                gui.getInventory(),
                                items,
                                minimumCount,
                                PICKUP_VISIBILITY_MAX_CHECKS
                        )
                );
            }
        });

        return deposited ? Results.SUCCESS() : Results.FAIL();
    }
}
