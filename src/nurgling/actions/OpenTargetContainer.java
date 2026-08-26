package nurgling.actions;

import haven.*;
import static haven.OCache.posres;
import nurgling.*;
import nurgling.tasks.*;
import nurgling.tools.Container;
import nurgling.tools.Finder;

public class OpenTargetContainer implements Action
{
    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        Window already = NUtils.getGameUI().getWindow(name);
        if(already != null && !isOwnedBy(gui, already, gob))
        {
            /* A container window is keyed only by its caption, so an open window from a
             * previously visited container of the same kind suppresses the click below and
             * the bot silently keeps working with that container instead of this one. Two
             * cupboards standing a couple of tiles apart stay in range of each other, so
             * every second one in a row was never opened at all. */
            already.wdgmsg("close");
            gui.ui.core.addTask(new WindowIsClosed(already));
            already = null;
        }
        if(already == null)
        {
            /* Inventory's factory binds the new window to core.getLastActions().gob, which
             * only real UI clicks populate - a bot's wdgmsg goes straight to the server and
             * leaves every bot-opened container unbound. Set it here so the window that is
             * about to arrive knows which gob it belongs to. */
            gui.ui.core.setLastAction(gob);
            gui.map.wdgmsg ( "click", Coord.z, gob.rc.floor ( posres ), 3, 0, 0, ( int ) gob.id,
                    gob.rc.floor ( posres ), 0, -1 );
        }
        switch (name)
        {
            case "Stockpile":
                gui.ui.core.addTask(new FindNISBox(name));
                break;
            case "Barter Stand":
                gui.ui.core.addTask(new FindBarterStand());
                break;
            case "Barrel":
                gui.ui.core.addTask(new FindBarrel());
                break;
            case "Cauldron":
                if((gob.ngob.getModelAttribute() & 2) != 0)//"lit"
                    new SelectFlowerAction("Open", gob, true).run(gui);
                gui.ui.core.addTask(new FindNInventory(name));
                break;
            case "Study Desk":
            case "Fine Study Desk":
            case "Grand Study Desk":
                /* A study desk owned by the current character opens directly on a plain
                 * click, same as any other container. One owned by someone else instead
                 * pops a flower menu ("Open" / "Take possession"), since the server now
                 * lets any character place items into any desk without taking ownership.
                 * Selecting "Open" (petalIgnored) handles both cases without ever taking
                 * possession, and is a no-op when no menu appeared at all. */
                new SelectFlowerAction("Open", gob, true).run(gui);
                gui.ui.core.addTask(new FindNInventory(name));
                break;
            default:
                gui.ui.core.addTask(new FindNInventory(name));
        }
        if(cont!=null)
        {
            cont.update();
        }
        return Results.SUCCESS();
    }

    /**
     * Whether an already open window is the one belonging to gob, and so may be reused
     * instead of being closed and reopened.
     *
     * Only NInventory-backed containers carry the binding. Windows without one (stockpiles
     * and other ISBoxes, barter stands) are left alone and keep the previous behaviour.
     *
     * Barrels are the exception: their window holds only a RelCont, so there is nothing to match
     * against, and TakeFromBarrel leaves its window open. An area with several barrels then had
     * every barrel after the first suppress its own click and silently reuse the previous barrel's
     * window. Always reopen those.
     */
    private static boolean isOwnedBy(NGameUI gui, Window wnd, Gob gob)
    {
        NInventory inv = null;
        for(Widget w = wnd.lchild; w != null; w = w.prev)
        {
            if(w instanceof NInventory)
            {
                inv = (NInventory) w;
                break;
            }
        }
        if(inv == null)
            return !"Barrel".equals(wnd.cap);
        return inv.parentGob != null && gob != null && inv.parentGob.id == gob.id;
    }

    public OpenTargetContainer(String name, Gob gob)
    {
        this.name = name;
        this.gob = gob;
    }

    public OpenTargetContainer(Container container)
    {
        this.name = container.cap;
        if(container.gobHash!=null && !container.gobHash.isEmpty())
        {
            this.gob = Finder.findGob(container.gobHash);
        }
        else
        {
            this.gob = Finder.findGob(container.gobid);
        }
        this.cont = container;
    }

    String name;
    Gob gob;
    Container cont = null;
}
