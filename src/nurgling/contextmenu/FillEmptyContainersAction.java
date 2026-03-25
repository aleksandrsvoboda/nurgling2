package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.HandIsFree;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitItemContent;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;

public class FillEmptyContainersAction implements GobContextAction {
    private static final NAlias WATER_SOURCE = new NAlias("barrel", "cistern", "well");
    private static final NAlias DRINK_CONTAINERS = new NAlias("Waterskin", "Glass Jug", "Waterflask", "Kuksa");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, WATER_SOURCE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.fill_empty_containers");
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            new PathFinder(gob).run(gui);

            if (NParser.isIt(gob, new NAlias("barrel"))) {
                if (!NUtils.barrelHasContent(gob) || !NParser.checkName(NUtils.getContentsOfBarrel(gob), "water")) {
                    return Results.ERROR("Barrel does not contain water");
                }
            }

            fillBeltContainers(gob);
            fillEquipSlot(gob, NEquipory.Slots.LFOOT.idx);
            fillEquipSlot(gob, NEquipory.Slots.RFOOT.idx);
            fillEquipBucket(gob, NEquipory.Slots.HAND_LEFT.idx);
            fillEquipBucket(gob, NEquipory.Slots.HAND_RIGHT.idx);
            fillInventoryContainers(gui, gob);

            return Results.SUCCESS();
        };
    }

    private static boolean barrelStillHasWater(Gob gob) throws InterruptedException {
        if (NParser.isIt(gob, new NAlias("barrel"))) {
            return NUtils.barrelHasContent(gob) && NParser.checkName(NUtils.getContentsOfBarrel(gob), "water");
        }
        return true;
    }

    private static void fillBeltContainers(Gob target) throws InterruptedException {
        WItem wbelt = NUtils.getEquipment().findItem(NEquipory.Slots.BELT.idx);
        if (wbelt == null || !(wbelt.item.contents instanceof NInventory))
            return;

        NInventory beltInv = (NInventory) wbelt.item.contents;
        ArrayList<WItem> items = beltInv.getItems(DRINK_CONTAINERS);
        for (WItem item : items) {
            if (!barrelStillHasWater(target)) return;
            NGItem ngItem = (NGItem) item.item;
            if (ngItem.content().isEmpty()) {
                NUtils.takeItemToHand(item);
                NUtils.activateItem(target);
                NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
                NUtils.transferToBelt();
                NUtils.getUI().core.addTask(new HandIsFree(beltInv));
            }
        }
    }

    private static void fillEquipSlot(Gob target, int slotIdx) throws InterruptedException {
        if (!barrelStillHasWater(target)) return;
        WItem item = NUtils.getEquipment().findItem(slotIdx);
        if (item == null || !(item.item instanceof NGItem))
            return;
        NGItem ngItem = (NGItem) item.item;
        if (!NParser.checkName(ngItem.name(), DRINK_CONTAINERS))
            return;
        if (!ngItem.content().isEmpty())
            return;

        NUtils.takeItemToHand(item);
        NUtils.activateItem(target);
        NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
        NUtils.getEquipment().wdgmsg("drop", -1);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return NUtils.getGameUI().vhand == null;
            }
        });
    }

    private static void fillEquipBucket(Gob target, int slotIdx) throws InterruptedException {
        if (!barrelStillHasWater(target)) return;
        WItem item = NUtils.getEquipment().findItem(slotIdx);
        if (item == null || !(item.item instanceof NGItem))
            return;
        NGItem ngItem = (NGItem) item.item;
        if (!NParser.checkName(ngItem.name(), "Bucket"))
            return;
        if (!ngItem.content().isEmpty())
            return;

        NUtils.takeItemToHand(item);
        NUtils.activateItem(target);
        NUtils.getUI().core.addTask(new WaitItemContent(NUtils.getGameUI().vhand));
        NUtils.getEquipment().wdgmsg("drop", -1);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return NUtils.getGameUI().vhand == null;
            }
        });
    }

    private static void fillInventoryContainers(NGameUI gui, Gob target) throws InterruptedException {
        NInventory inv = gui.getInventory();
        if (inv == null) return;

        ArrayList<WItem> items = inv.getItems(DRINK_CONTAINERS);
        for (WItem item : items) {
            if (!barrelStillHasWater(target)) return;
            NGItem ngItem = (NGItem) item.item;
            if (!ngItem.content().isEmpty())
                continue;

            Coord originalPos = item.c.div(haven.Inventory.sqsz);
            NUtils.takeItemToHand(item);
            NUtils.activateItem(target);
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
