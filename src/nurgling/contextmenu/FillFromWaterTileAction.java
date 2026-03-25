package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tasks.HandIsFree;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitItemContent;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;

import static haven.OCache.posres;

public class FillFromWaterTileAction implements TileContextAction {
    private static final NAlias WATER_TILE = new NAlias("gfx/tiles/deep", "gfx/tiles/owater");
    private static final NAlias DRINK_CONTAINERS = new NAlias("Waterskin", "Glass Jug", "Waterflask", "Kuksa");

    @Override
    public boolean appliesTo(Coord2d mapPos) {
        Coord tileCoord = new Coord2d(mapPos.x / 11, mapPos.y / 11).floor();
        return NParser.isIt(tileCoord, WATER_TILE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.fill_from_water_tile");
    }

    @Override
    public Action create(Coord2d mapPos) {
        return gui -> {
            fillBeltContainers(gui);
            fillEquipSlot(gui, NEquipory.Slots.LFOOT.idx);
            fillEquipSlot(gui, NEquipory.Slots.RFOOT.idx);
            fillEquipBucket(gui, NEquipory.Slots.HAND_LEFT.idx);
            fillEquipBucket(gui, NEquipory.Slots.HAND_RIGHT.idx);
            fillInventoryContainers(gui);

            return Results.SUCCESS();
        };
    }

    private static void activateOnWater(NGameUI gui) {
        gui.map.wdgmsg("itemact", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
    }

    private static void fillBeltContainers(NGameUI gui) throws InterruptedException {
        WItem wbelt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
        if (wbelt == null || !(wbelt.item.contents instanceof NInventory))
            return;

        NInventory beltInv = (NInventory) wbelt.item.contents;
        ArrayList<WItem> items = beltInv.getItems(DRINK_CONTAINERS);
        for (WItem item : items) {
            NGItem ngItem = (NGItem) item.item;
            if (ngItem.content().isEmpty()) {
                NUtils.takeItemToHand(item);
                activateOnWater(gui);
                NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
                NUtils.transferToBelt();
                NUtils.getUI().core.addTask(new HandIsFree(beltInv));
            }
        }
    }

    private static void fillEquipSlot(NGameUI gui, int slotIdx) throws InterruptedException {
        WItem item = NUtils.getEquipment().findItem(slotIdx);
        if (item == null || !(item.item instanceof NGItem))
            return;
        NGItem ngItem = (NGItem) item.item;
        if (!NParser.checkName(ngItem.name(), DRINK_CONTAINERS))
            return;
        if (!ngItem.content().isEmpty())
            return;

        NUtils.takeItemToHand(item);
        activateOnWater(gui);
        NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
        NUtils.getEquipment().wdgmsg("drop", -1);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return NUtils.getGameUI().vhand == null;
            }
        });
    }

    private static void fillEquipBucket(NGameUI gui, int slotIdx) throws InterruptedException {
        WItem item = NUtils.getEquipment().findItem(slotIdx);
        if (item == null || !(item.item instanceof NGItem))
            return;
        NGItem ngItem = (NGItem) item.item;
        if (!NParser.checkName(ngItem.name(), "Bucket"))
            return;
        if (!ngItem.content().isEmpty())
            return;

        NUtils.takeItemToHand(item);
        activateOnWater(gui);
        NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
        NUtils.getEquipment().wdgmsg("drop", -1);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return NUtils.getGameUI().vhand == null;
            }
        });
    }

    private static void fillInventoryContainers(NGameUI gui) throws InterruptedException {
        NInventory inv = gui.getInventory();
        if (inv == null) return;

        ArrayList<WItem> items = inv.getItems(DRINK_CONTAINERS);
        for (WItem item : items) {
            NGItem ngItem = (NGItem) item.item;
            if (!ngItem.content().isEmpty())
                continue;

            Coord originalPos = item.c.div(Inventory.sqsz);
            NUtils.takeItemToHand(item);
            activateOnWater(gui);
            NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
            inv.dropOn(originalPos);
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return NUtils.getGameUI().vhand == null;
                }
            });
        }
    }
}
