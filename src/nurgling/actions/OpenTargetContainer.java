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
        // Riding: the horse's long body can fail to get the rider in reach (small piles packed in rows), and then no
        // window ever comes. Give the open a time limit, walk up once more, and report failure instead of waiting forever.
        if (nurgling.actions.mount.HorseRide.of(gui) != null && ("Stockpile".equals(name) || isPlainInventory(name)))
            return openMounted(gui);
        return openOnFoot(gui);
    }

    private static final long MOUNTED_OPEN_MS = 3000;

    private static boolean isPlainInventory(String name)
    {
        switch (name)
        {
            case "Barter Stand": case "Barrel": case "Cauldron":
            case "Study Desk": case "Fine Study Desk": case "Grand Study Desk":
                return false;
            default:
                return true;
        }
    }

    private Results openMounted(NGameUI gui) throws InterruptedException
    {
        // On horseback the click walks the horse in, unless it is already pressed against the object (then nothing
        // opens, by hand either): click, and if no window comes, back off a few units and click once more.
        for (int attempt = 0; attempt <= 1; attempt++)
        {
            if (attempt > 0 && !backOff(gui))
                break;
            if (tryOpen(gui))
            {
                Gob me = gui.map.player();
                System.out.println(String.format("[Horse] opened %s %d from %.1f%s", name, gob.id,
                        me != null ? me.rc.dist(gob.rc) : -1.0, attempt > 0 ? " (after backing off)" : ""));
                if (cont != null)
                    cont.update();
                return Results.SUCCESS();
            }
            Gob me = gui.map.player();
            System.out.println(String.format("[Horse] couldn't open %s %d (%s) from %.1f%s", name, gob.id,
                    gob.ngob != null ? gob.ngob.name : "?", me != null ? me.rc.dist(gob.rc) : -1.0,
                    attempt > 0 ? " (after backing off)" : ""));
        }
        return Results.FAIL();
    }

    /** Steps straight away from the object by HorseRide.APPROACH_GAP plus a little; false when there is no room. */
    private boolean backOff(NGameUI gui) throws InterruptedException
    {
        Gob me = gui.map.player();
        if (me == null)
            return false;
        Coord2d away = me.rc.sub(gob.rc);
        if (away.abs() < 0.01)
            return false;
        Coord2d to = me.rc.add(away.norm(nurgling.actions.mount.HorseRide.APPROACH_GAP + 2));
        if (!PathFinder.isAvailable(to))
            return false;
        new GoTo(to, new nurgling.actions.mount.StepStuckWatch(gui)).run(gui);
        return true;
    }

    /** One click and a time-limited wait for the window; true when it opened. */
    private boolean tryOpen(NGameUI gui) throws InterruptedException
    {
        Window already = gui.getWindow(name);
        if (already != null && !isOwnedBy(gui, already, gob))
        {
            already.wdgmsg("close");
            gui.ui.core.addTask(new WindowIsClosed(already));
            already = null;
        }
        if (already == null)
        {
            gui.ui.core.setLastAction(gob);
            gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
        }
        final NTask inner = "Stockpile".equals(name) ? new FindNISBox(name, gob) : new FindNInventory(name);
        final long deadline = System.currentTimeMillis() + MOUNTED_OPEN_MS;
        final boolean[] timedOut = {false};
        gui.ui.core.addTask(new NTask()
        {
            @Override
            public boolean check()
            {
                if (inner.check())
                    return true;
                if (System.currentTimeMillis() > deadline)
                {
                    timedOut[0] = true;
                    return true;
                }
                return false;
            }
        });
        return !timedOut[0];
    }

    private Results openOnFoot(NGameUI gui) throws InterruptedException
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
                /* Pass the gob: a stockpile is destroyed by having its last item taken, so the
                 * window we are waiting for may never arrive, and the wait is infinite. */
                gui.ui.core.addTask(new FindNISBox(name, gob));
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
                // A non-owned desk pops an "Open"/"Take possession" flower menu instead of
                // opening directly; only a fresh click (not a reused window) can trigger one.
                if (already == null) {
                    NFlowerMenu deskMenu = NUtils.findFlowerMenu();
                    if (deskMenu != null) {
                        deskMenu.chooseOpt("Open");
                        gui.ui.core.addTask(new NFlowerMenuIsClosed());
                    }
                }
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
