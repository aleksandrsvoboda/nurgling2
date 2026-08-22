package nurgling.actions;

import haven.Coord;
import haven.Gob;
import haven.WItem;
import haven.Window;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.WaitFreeHand;
import nurgling.tasks.WindowIsClosed;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class FuelToContainers implements Action
{

    ArrayList<Container> conts;
    Coord targetCoord = new Coord(1, 1);

    public FuelToContainers(ArrayList<Container> conts) {
        this.conts = conts;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        for (Container cont : conts) {
            Container.FuelLvl fuelLvl = cont.getattr(Container.FuelLvl.class);
            String ftype = (String) fuelLvl.getRes().get(Container.FuelLvl.FUELTYPE);
            while (fuelLvl.neededFuel() > 0) {
                if (gui.getInventory().getItems(ftype).isEmpty()) {
                    Results res = refill(gui, ftype);
                    if (!res.IsSuccess())
                        return res;
                    /* Nothing came back from the piles. Feeding the container is pointless
                     * and the loop above would never end, so give up here instead. */
                    if (gui.getInventory().getItems(ftype).isEmpty())
                        return Results.ERROR("Can't get any " + ftype + " for fuel");
                }

                PathFinder pf = new PathFinder(Finder.findGob(cont.gobHash));
                pf.isHardMode = true;
                pf.run(gui);
                new OpenTargetContainer(cont).run(gui);
                fuelLvl = cont.getattr(Container.FuelLvl.class);
                Window window = NUtils.getGameUI().getWindow(cont.cap);

                int needed = Math.max(0, fuelLvl.neededFuel());
                int fueled = 0;
                while (fueled < needed) {
                    /* Re-read the items every pass. Feeding one item in can collapse the
                     * stack it came from, and that destroys and re-creates the widgets of
                     * everything still in it, so a list captured up front goes stale after
                     * the first insert and the rest are sent to dead widgets. */
                    ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(ftype);
                    if (items.isEmpty())
                        break;
                    NUtils.takeItemToHand(items.get(0));
                    NUtils.activateItem(Finder.findGob(cont.gobHash));
                    NUtils.getUI().core.addTask(new WaitFreeHand());
                    fueled++;
                }
                if (fueled > 0 && window != null)
                {
                    NUtils.getUI().core.addTask(new WindowIsClosed(window));
                }
                /* Reopening refreshes the fuel level this loop is driven by. */
                new OpenTargetContainer(cont).run(gui);
                new CloseTargetContainer(cont).run(gui);
                if (needed > 0 && fueled == 0)
                    return Results.ERROR("Can't put " + ftype + " into " + cont.cap);
            }
        }
        return Results.SUCCESS();
    }

    /**
     * Loads the player inventory with fuel of the given type, enough for everything in this
     * batch that still burns it.
     */
    private Results refill(NGameUI gui, String ftype) throws InterruptedException {
        int target_size = 0;
        for (Container tcont : conts) {
            Container.FuelLvl tfuelLvl = tcont.getattr(Container.FuelLvl.class);
            if (!ftype.equals(tfuelLvl.getRes().get(Container.FuelLvl.FUELTYPE)))
                continue;
            target_size += Math.max(0, tfuelLvl.neededFuel());
        }

        while (target_size > 0 && gui.getInventory().getNumberFreeCoord(targetCoord) != 0) {
            NArea fuel = NContext.findSpec(Specialisation.SpecName.fuel.toString(), ftype);
            if (fuel == null)
                return Results.ERROR("No specialisation \"FUEL\" set.");
            ArrayList<Gob> piles = Finder.findGobs(fuel, new NAlias("stockpile"));
            if (piles.isEmpty()) {
                if (gui.getInventory().getItems(ftype).isEmpty())
                    return Results.ERROR("no items");
                else
                    break;
            }
            piles.sort(NUtils.d_comp);

            /* Free cells alone under-report what fits once the client bundles items into
             * stacks, so ask for what the inventory can really hold. */
            int room = StackSupporter.getOptimalItemCapacity(gui.getInventory(), ftype, targetCoord, target_size);
            if (room <= 0)
                break;

            Gob pile = piles.get(0);
            new PathFinder(pile).run(gui);
            new OpenTargetContainer("Stockpile", pile).run(gui);
            TakeItemsFromPile tifp = new TakeItemsFromPile(pile, gui.getStockpile(), room);
            tifp.run(gui);
            new CloseTargetWindow(NUtils.getGameUI().getWindow("Stockpile")).run(gui);
            /* The pile handed over nothing. Walking back to it can only repeat that, so
             * stop rather than spin on it. */
            if (tifp.getResult() <= 0)
                break;
            target_size -= tifp.getResult();
        }
        return Results.SUCCESS();
    }
}
