package nurgling.actions;

import haven.Gob;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.barterbox.Shopbox;
import nurgling.NGameUI;
import nurgling.NInventory.QualityType;
import nurgling.NUtils;
import nurgling.areas.NContext;
import nurgling.tasks.WaitItems;
import nurgling.tasks.WindowIsClosed;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

public class TakeItems2 implements Action
{
    final NContext cnt;
    String item;
    int count;
    Specialisation.SpecName specName;
    String specSubtype;
    QualityType qualityType;
    public boolean exactMatch = false;


    public TakeItems2(NContext context, String item, int count)
    {
        this.cnt = context;
        this.item = item;
        this.count = count;
        this.qualityType = null;
    }

    public TakeItems2(NContext context, String item, int count, Specialisation.SpecName specName)
    {
        this.cnt = context;
        this.item = item;
        this.count = count;
        this.specName = specName;
        this.qualityType = null;
    }

    public TakeItems2(NContext context, String item, int count, QualityType qualityType)
    {
        this.cnt = context;
        this.item = item;
        this.count = count;
        this.qualityType = qualityType;
    }

    public TakeItems2(NContext context, String item, int count, Specialisation.SpecName specName, QualityType qualityType)
    {
        this.cnt = context;
        this.item = item;
        this.count = count;
        this.specName = specName;
        this.qualityType = qualityType;
    }

    public TakeItems2(NContext context, String item, int count, Specialisation.SpecName specName, String specSubtype)
    {
        this.cnt = context;
        this.item = item;
        this.count = count;
        this.specName = specName;
        this.specSubtype = specSubtype;
        this.qualityType = QualityType.High;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        AtomicInteger left = new AtomicInteger(count);
        ArrayList<NContext.ObjectStorage> inputs;
        if(specName == null) {
            inputs = cnt.getInStorages(item);
        } else {
            inputs = cnt.getSpecStorages(this.specName, this.specSubtype);
        }

        if(inputs == null || inputs.isEmpty())
            return Results.FAIL();
        for(NContext.ObjectStorage input: inputs)
        {
            if(input instanceof NContext.Barter)
                takeFromBarter(left,gui, (NContext.Barter)input);
            else if (input instanceof NContext.Pile)
            {
                takeFromPile(left, gui,(NContext.Pile) input);
            }
            else if (input instanceof Container)
            {
                takeFromContainer(left, gui, (Container) input);
            }
            if(NUtils.getGameUI().getInventory().getItems(new NAlias(item)).size() >= count) {
                return Results.SUCCESS();
            }
            else
            {
                left.set(count - NUtils.getGameUI().getInventory().getItems(new NAlias(item)).size());
            }
        }
        return Results.SUCCESS();
    }

    public Results takeFromBarter(AtomicInteger left, NGameUI gui, NContext.Barter barter) throws InterruptedException
    {
        Gob gchest = Finder.findGob(barter.chest);
        Gob gbarter = Finder.findGob(barter.barter);
        if(gbarter==null || gchest==null)
            return Results.FAIL();

        // A single visit can only carry as many "Branch" (the barter currency) as fit in the
        // free inventory slots, so we may not be able to buy everything we need in one pass.
        // Repeat the whole take-currency -> buy cycle until we have enough or we can no longer
        // make progress (chest out of currency, no free inventory space, or stand out of stock).
        while (left.get() > 0)
        {
            // 1. Open the chest and look at how much currency is available.
            new PathFinder(gchest).run(gui);
            new OpenTargetContainer("Chest", gchest).run(gui);
            if(gui.getInventory("Chest") == null)
                break;
            ArrayList<WItem> chestBranches = gui.getInventory("Chest").getItems("Branch");
            if(chestBranches.isEmpty())
                break; // no currency left to buy with

            // 2. How many can we carry this pass: limited by need, chest stock and free slots.
            int freeSlots = gui.getInventory().getNumberFreeCoord(chestBranches.get(0));
            int to_take = Math.min(Math.min(left.get(), chestBranches.size()), freeSlots);
            if(to_take <= 0)
                break; // no room to carry currency -> cannot make progress

            // 3. Move the currency into the inventory and read how many actually arrived
            // (SimpleTransferToContainer silently clamps to free space).
            int branchesBefore = gui.getInventory().getItems("Branch").size();
            new SimpleTransferToContainer(gui.getInventory(), gui.getInventory("Chest").getItems("Branch"), to_take).run(gui);
            int payable = gui.getInventory().getItems("Branch").size() - branchesBefore;
            Window chestWnd = gui.getWindow("Chest");
            if(chestWnd != null)
            {
                chestWnd.wdgmsg("close");
                gui.ui.core.addTask(new WindowIsClosed(chestWnd));
            }
            if(payable <= 0)
                break; // nothing actually transferred -> avoid spinning forever

            // 4. Walk to the stand and buy exactly as many as we can pay for.
            new PathFinder(gbarter).run(gui);
            new OpenTargetContainer("Barter Stand", gbarter).run(gui);

            Window barter_wnd = gui.getWindow("Barter Stand");
            if(barter_wnd==null)
            {
                return Results.ERROR("No Barter window");
            }

            int bought = 0;
            for(Widget ch = barter_wnd.child; ch != null; ch = ch.next)
            {
                if (ch instanceof Shopbox)
                {
                    Shopbox sb = (Shopbox) ch;
                    Shopbox.ShopItem offer = sb.getOffer();
                    if (offer != null)
                    {
                        if (offer.name.equals(item))
                        {
                            // Cap by what the stand still has in stock (leftNum == 0 means unlimited).
                            int to_buy = (sb.leftNum != 0) ? Math.min(payable, sb.leftNum) : payable;
                            int itemBefore = gui.getInventory().getItems(new NAlias(item)).size();
                            for (int i = 0; i < to_buy; i++)
                            {
                                sb.wdgmsg("buy", new Object[0]);
                            }

                            NUtils.getUI().core.addTask(new WaitItems(NUtils.getGameUI().getInventory(), new NAlias(item), itemBefore + to_buy));
                            bought = gui.getInventory().getItems(new NAlias(item)).size() - itemBefore;
                            break;
                        }
                    }
                }
            }

            if(bought <= 0)
                break; // matching offer missing or stand could not deliver -> stop

            left.set(left.get() - bought);
        }
        return Results.SUCCESS();
    }

    public Results takeFromPile(AtomicInteger left, NGameUI gui, NContext.Pile pile) throws InterruptedException
    {
        if(PathFinder.isAvailable(pile.pile))
        {
            new PathFinder(pile.pile).run(gui);
            new OpenTargetContainer("Stockpile", pile.pile).run(gui);
            TakeItemsFromPile tifp;
            (tifp = new TakeItemsFromPile(pile.pile, gui.getStockpile(), left.get())).run(gui);
            new CloseTargetWindow(NUtils.getGameUI().getWindow("Stockpile")).run(gui);
        }
        return Results.SUCCESS();
    }

    public Results takeFromContainer(AtomicInteger left, NGameUI gui, Container cont) throws InterruptedException
    {
        Gob contgob = Finder.findGob(cont.gobHash);
        if(contgob == null)
            return Results.FAIL();
        // Skip empty containers using visual flag (except dframes)
        if(!"Frame".equals(cont.cap) && contgob.ngob.isContainerEmpty())
            return Results.SUCCESS();
        new PathFinder(contgob).run(gui);
        new OpenTargetContainer(cont).run(gui);
        TakeItemsFromContainer tifc = new TakeItemsFromContainer(cont,new HashSet<>(Arrays.asList(item)), null, qualityType);
        tifc.minSize = left.get();
        tifc.exactMatch = this.exactMatch;
        tifc.run(gui);
        new CloseTargetContainer(cont).run(gui);
        return Results.SUCCESS();
    }
}
