package nurgling.actions;

import haven.Coord;
import haven.Gob;
import haven.Inventory;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.tasks.HandIsFree;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitItemAmount;
import nurgling.tasks.WaitItemContent;
import nurgling.tools.DrinkContainers;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.NEquipory;

import java.util.ArrayList;

import static haven.OCache.posres;

/**
 * Fills every water container the character carries from a source already within reach.
 *
 * Covers the belt, both feet, both hands and the main inventory, and every container
 * type - Waterskin, Glass Jug, Waterflask, Kuksa and Bucket. Partially filled water
 * containers are topped off; containers holding anything else are left alone.
 *
 * This action does not navigate. The caller is responsible for standing next to the
 * source - see FillWaterskins for the zone-resolving variant, or the context menu
 * actions for the click-driven ones.
 */
public class FillWaterContainers implements Action
{
    private static final NAlias BARREL = new NAlias("barrel");

    /** Where the water comes from, and how to check it is still there. */
    public interface Source
    {
        /** Use the item currently in hand on the source. */
        void activate() throws InterruptedException;

        /** Re-checked before every container, since a barrel can run dry mid-run. */
        boolean available() throws InterruptedException;

        String describe();
    }

    /** A barrel, cistern or well. Barrels are verified to actually hold water. */
    public static Source fromGob(Gob gob)
    {
        return new Source()
        {
            @Override
            public void activate()
            {
                NUtils.activateItem(gob);
            }

            @Override
            public boolean available() throws InterruptedException
            {
                if (NParser.isIt(gob, BARREL))
                    return NUtils.barrelHasContent(gob) && NParser.checkName(NUtils.getContentsOfBarrel(gob), "water");
                return true;
            }

            @Override
            public String describe()
            {
                return "barrel/cistern/well";
            }
        };
    }

    /** The water tile the player is standing next to. */
    public static Source fromWaterTile()
    {
        return new Source()
        {
            @Override
            public void activate()
            {
                NUtils.getGameUI().map.wdgmsg("itemact", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
            }

            @Override
            public boolean available()
            {
                return true;
            }

            @Override
            public String describe()
            {
                return "water tile";
            }
        };
    }

    private final Source source;
    private int filled = 0;

    public FillWaterContainers(Source source)
    {
        this.source = source;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        if (!source.available())
            return Results.ERROR("No water in " + source.describe());

        filled = 0;
        fillBelt();
        fillEquipSlot(NEquipory.Slots.LFOOT.idx);
        fillEquipSlot(NEquipory.Slots.RFOOT.idx);
        fillEquipSlot(NEquipory.Slots.HAND_LEFT.idx);
        fillEquipSlot(NEquipory.Slots.HAND_RIGHT.idx);
        fillInventory(gui);

        gui.msg(filled > 0 ? ("Filled " + filled + " water container(s)") : "No containers needed water");
        return Results.SUCCESS();
    }

    /** Puts the held container back where it came from. */
    private interface PutBack
    {
        void run() throws InterruptedException;
    }

    private void fillBelt() throws InterruptedException
    {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return;
        WItem wbelt = equip.findItem(NEquipory.Slots.BELT.idx);
        if (wbelt == null || !(wbelt.item.contents instanceof NInventory))
            return;

        NInventory beltInv = (NInventory) wbelt.item.contents;
        for (WItem item : beltInv.getItems(DrinkContainers.ALL)) {
            fillOne(item, () -> {
                NUtils.transferToBelt();
                NUtils.getUI().core.addTask(new HandIsFree(beltInv));
            });
        }
    }

    /**
     * Fills a single equipment slot. Handles feet and hands alike - a waterskin worn
     * on a foot, a bucket held in a hand and a jug held in a hand all end up here.
     */
    private void fillEquipSlot(int slotIdx) throws InterruptedException
    {
        NEquipory equip = NUtils.getEquipment();
        if (equip == null)
            return;
        WItem item = equip.findItem(slotIdx);
        if (item == null || !(item.item instanceof NGItem))
            return;
        if (!DrinkContainers.isContainer(((NGItem) item.item).name()))
            return;

        fillOne(item, this::putBackToEquip);
    }

    private void fillInventory(NGameUI gui) throws InterruptedException
    {
        NInventory inv = gui.getInventory();
        if (inv == null)
            return;

        ArrayList<WItem> items = inv.getItems(DrinkContainers.ALL);
        for (WItem item : items) {
            Coord originalPos = item.c.div(Inventory.sqsz);
            fillOne(item, () -> inv.dropOn(originalPos));
        }
    }

    /**
     * Takes one container to hand, uses it on the source, waits for the fill and puts
     * it back. Skips containers that cannot take water and stops early once the source
     * has run out.
     */
    private void fillOne(WItem item, PutBack putBack) throws InterruptedException
    {
        if (item == null || !(item.item instanceof NGItem) || item.item.spr == null)
            return;

        NGItem ngItem = (NGItem) item.item;
        if (!DrinkContainers.isFillable(ngItem))
            return;
        if (!source.available())
            return;

        boolean topOff = !ngItem.content().isEmpty();
        float before = DrinkContainers.amount(ngItem);
        float capacity = DrinkContainers.capacity(ngItem.name());

        NUtils.takeItemToHand(item);
        WItem hand = NUtils.getGameUI().vhand;
        if (hand == null)
            return;

        source.activate();
        if (topOff)
            NUtils.addTask(new WaitItemAmount(hand, before, capacity));
        else
            NUtils.getUI().core.addTask(new WaitItemContent(hand));

        putBack.run();
        filled++;
    }

    private void putBackToEquip() throws InterruptedException
    {
        NUtils.getEquipment().wdgmsg("drop", -1);
        NUtils.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                return NUtils.getGameUI().vhand == null;
            }
        });
    }
}
