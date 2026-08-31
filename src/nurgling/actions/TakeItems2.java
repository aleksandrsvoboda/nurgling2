package nurgling.actions;

import haven.Coord;
import haven.Gob;
import haven.UI;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.res.ui.barterbox.Shopbox;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NInventory.QualityType;
import nurgling.NUtils;
import nurgling.areas.NContext;
import nurgling.tasks.WaitItems;
import nurgling.tasks.WindowIsClosed;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
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

    /* Optional, for takeAny: the containers this fetch is meant to fill. Capacity for irregular
     * (Tetris) destinations can't be known until the item's real footprint is seen, so with these
     * set takeAny re-targets itself the moment it sees one instead of relying on a worst-case
     * guess - which otherwise asks for one or two items and then has to come back for the rest. */
    public ArrayList<Container> fillTargets = null;
    private Coord observedShape = null;

    /* Whether another matching item could still be carried. A multi-cell item never stacks, so
     * its real footprint is the honest test; for a 1x1 item the plain free-cell count is, and
     * asking by footprint there would ignore the stacking that makes room beyond free cells. */
    private boolean noRoomLeft(NGameUI gui) throws InterruptedException
    {
        if(observedShape != null && !observedShape.equals(1, 1))
            return gui.getInventory().getNumberFreeCoord(observedShape) <= 0;
        return gui.getInventory().getFreeSpace() <= 0;
    }

    /* Footprint of the first matching item takeAny saw, or null if it saw none. */
    public Coord getObservedShape()
    {
        return observedShape;
    }

    private void observeShape(ArrayList<WItem> candidates)
    {
        if(observedShape != null)
            return;
        for(WItem witem: candidates)
        {
            if(witem.item.spr != null)
            {
                observedShape = witem.item.spr.sz().div(UI.scale(32)).swapXY();
                if(fillTargets != null)
                {
                    int room = 0;
                    for(Container target: fillTargets)
                        room += target.freeSpace(observedShape);
                    count = room;
                }
                return;
            }
        }
    }


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

    /* For takeAny(NAlias, NGameUI) - no single exact item name is known up front. */
    public TakeItems2(NContext context, int count, Specialisation.SpecName specName, QualityType qualityType)
    {
        this.cnt = context;
        this.count = count;
        this.specName = specName;
        this.qualityType = qualityType;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        // The takeAny constructor leaves item null - that instance must be driven through
        // takeAny(alias, gui), which matches on the alias instead of one exact name.
        if(item == null)
            return Results.FAIL();
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

    /* Like run(), but for callers that accept ANY of several item names rather than one exact
     * one (e.g. any of a dozen ore types). Doing this one name at a time via run() would repeat
     * a full pile+container scan of the area per name tried before the stocked one is found -
     * here every storage is visited once, and a container is asked for all names in a single
     * open/close pass (TakeItemsFromContainer already accepts a name set). itemsAlias is also
     * passed through as the exclude-aware match pattern, so a caller's exclusions (e.g. "hide"
     * but not "Fresh hide") are honoured here the same way they already are at deposit time. */
    public Results takeAny(NAlias itemsAlias, NGameUI gui) throws InterruptedException
    {
        ArrayList<NContext.ObjectStorage> inputs;
        if(specName == null) {
            inputs = cnt.getInStorages(itemsAlias.getKeys().get(0));
        } else {
            inputs = cnt.getSpecStorages(this.specName, this.specSubtype);
        }

        if(inputs == null || inputs.isEmpty())
            return Results.FAIL();

        HashSet<String> names = new HashSet<>(itemsAlias.getKeys());
        AtomicInteger left = new AtomicInteger(count);
        for(NContext.ObjectStorage input: inputs)
        {
            /* count is an absolute inventory target and may legitimately exceed one load - fifty
             * empty drying frames want far more hides than fit - so the tour has to end when the
             * inventory is full, not when the target is met. Otherwise every remaining pile and
             * chest in the area is still walked to and opened for nothing. */
            if(noRoomLeft(gui))
                return Results.SUCCESS();
            if(input instanceof NContext.Barter)
                takeFromBarter(left, gui, (NContext.Barter) input);
            else if (input instanceof NContext.Pile)
            {
                /* A pile's contents can't be measured until something has been taken, so the first
                 * pass runs on the pre-observation guess; observing then re-targets count and the
                 * next pass collects the rest. Without this the guess is all a pile ever yields,
                 * and the caller comes back for one item at a time. */
                while(true)
                {
                    int before = NUtils.getGameUI().getInventory().getItems(itemsAlias).size();
                    if(before >= count)
                        break;
                    left.set(count - before);
                    if(!takeFromPile(left, gui, (NContext.Pile) input).IsSuccess())
                        break;
                    observeShape(NUtils.getGameUI().getInventory().getItems(itemsAlias));
                    if(NUtils.getGameUI().getInventory().getItems(itemsAlias).size() == before)
                        break;
                    if(noRoomLeft(gui))
                        break;
                }
            }
            else if (input instanceof Container)
            {
                Container cont = (Container) input;
                /* Bare Finder.findGob can miss a container sitting inside a house whose gob
                 * hasn't streamed in yet - Container.pathTo falls back to ChunkNav via the
                 * container's own area before giving up, so a source stored indoors is still
                 * reached instead of silently skipped. */
                Gob contgob = Container.pathTo(gui, cont);
                if(contgob == null)
                    continue;
                if(!"Frame".equals(cont.cap) && contgob.ngob.isContainerEmpty())
                    continue;
                new OpenTargetContainer(cont).run(gui);
                NInventory cinv = gui.getInventory(cont.cap);
                if(cinv != null)
                    observeShape(cinv.getItems(itemsAlias));
                /* TakeItemsFromContainer gives up early - on its own count, or as soon as one name
                 * still holds more than it could take - so a single call leaves the other names
                 * untouched. Keep pulling while the window is open rather than closing and coming
                 * back, which is what made it reopen the same chest once per hide type. */
                while(true)
                {
                    int before = NUtils.getGameUI().getInventory().getItems(itemsAlias).size();
                    if(before >= count)
                        break;
                    TakeItemsFromContainer tifc = new TakeItemsFromContainer(cont, names, itemsAlias, qualityType);
                    tifc.minSize = count - before;
                    tifc.exactMatch = this.exactMatch;
                    tifc.run(gui);
                    if(NUtils.getGameUI().getInventory().getItems(itemsAlias).size() == before)
                        break;
                    if(noRoomLeft(gui))
                        break;
                }
                new CloseTargetContainer(cont).run(gui);
            }
            /* Keep visiting storages until the requested count is actually met - stopping at the
             * first one holding anything would leave a near-empty pile satisfying the whole
             * request and send the caller back for another full round trip per item. */
            int got = NUtils.getGameUI().getInventory().getItems(itemsAlias).size();
            if(got >= count)
                return Results.SUCCESS();
            left.set(count - got);
        }
        return Results.SUCCESS();
    }

    public Results takeFromBarter(AtomicInteger left, NGameUI gui, NContext.Barter barter) throws InterruptedException
    {
        /* Buying needs the exact offer name, so bail before touching the chest when called from
         * takeAny (item null) - otherwise the currency below is carried out and nothing matches
         * it, leaving the branches stranded in the inventory. */
        if(item == null)
            return Results.FAIL();
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
        /* The gob was captured when the area was scanned, but taking a stockpile's last item
         * destroys it. The stale reference still carries an id and a position, so PathFinder
         * plots a course to where the pile used to be and OpenTargetContainer then waits forever
         * for a "Stockpile" window that will never arrive - or, when a neighbour's hitbox still
         * blocks that cell, dies in fixStartEnd because the target id no longer resolves.
         * Re-resolve on every visit and report a pile that is gone as "nothing taken". */
        Gob gpile = (pile.pile == null) ? null : Finder.findGob(pile.pile.id);
        if(gpile == null || !PathFinder.isAvailable(gpile))
            return Results.FAIL();
        new PathFinder(gpile).run(gui);
        new OpenTargetContainer("Stockpile", gpile).run(gui);
        /* A stockpile hands over one item per "xfer2" and the server silently drops the ones
         * that no longer fit, so asking for more than the inventory can hold ends the transfer
         * short and leaves TakeItemsFromPile waiting on items that never arrive. Every other
         * caller budgets against free space before asking; do the same here, stack-aware, since
         * a stacking item packs getFullStackSize() into one cell.
         *
         * takeAny drives this with item null, and a stack size cannot be looked up without a
         * name - StackSupporter.isStackable would dereference it. Budget by plain free cells
         * then: under-asking only costs another pass, which takeAny already loops for, while
         * over-asking is the failure this whole guard exists to prevent. Once takeAny has seen
         * one of the items its measured footprint beats assuming 1x1. */
        Coord shape = (observedShape != null) ? observedShape : new Coord(1, 1);
        int room = (item == null)
                ? Math.min(left.get(), gui.getInventory().getNumberFreeCoord(shape))
                : StackSupporter.getOptimalItemCapacity(gui.getInventory(), item, shape, left.get());
        if(room > 0)
            new TakeItemsFromPile(gpile, gui.getStockpile(), room).run(gui);
        new CloseTargetWindow(NUtils.getGameUI().getWindow("Stockpile")).run(gui);
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
