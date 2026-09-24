package nurgling.actions;

import haven.Gob;
import haven.WItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.HandIsFree;

import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;

public class PrepareWorkStation implements Action
{
    public PrepareWorkStation(NContext context, String name)
    {
        this.name = name;
        this.context = context;
    }

    String name;
    NContext context;
    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        NArea area = context.goToArea(context.workstation);
        if(area == null)
            return Results.ERROR("NO WORKSTATION");
        Gob ws = null;
        if(name.startsWith("gfx/terobjs/pow"))
        {
            ArrayList<Gob> gobs = Finder.findGobs(area, new NAlias("gfx/terobjs/pow"));
            gobs.sort(NUtils.d_comp);
            for(Gob gob: gobs)
            {
                if ((gob.ngob.getModelAttribute() & 48) == 0) {
                    ws = gob;
                    break;
                }
            }
        }
        else {
            ws = Finder.findGob(area, new NAlias(name));
        }
        if(ws == null)
            return Results.ERROR("NO WORKSTATION");
        else
        {
            context.workstation.selected = ws.id;
            if(name.contains("crucible"))
            {
                if(fillCrucible(ws,gui))
                    new LightGob(new ArrayList<>(Arrays.asList(ws.ngob.hash)),4).run(gui);
            }
            else if(name.startsWith("gfx/terobjs/pow"))
            {
                ArrayList<Gob> pows = new ArrayList<>(Arrays.asList(ws));
                if(!new FillFuelPowOrCauldron(context, pows, 1, Specialisation.SpecName.fuelFireplace).run(gui).IsSuccess())
                    return Results.FAIL();
                ArrayList<String> flighted = new ArrayList<>();
                for (Gob cont : pows) {
                    flighted.add(cont.ngob.hash);
                }
                if (!new LightGob(flighted, 4).run(gui).IsSuccess())
                    return Results.ERROR("I can't start a fire");
            }
            else if(name.startsWith("gfx/terobjs/cauldron"))
            {
                if(!new PrepareCauldron(ws, context).run(gui).IsSuccess())
                    return Results.ERROR("Failed to prepare cauldron");
            }
        }
        return Results.SUCCESS();
    }

    /**
     * A crucible's fuel is a two-bit field, not a single flag: bit 1 is branches, bit 2 is coal.
     * See LightObject.getConfig's crucible entry and NUtils.isWorkStationReady, which already
     * treat either as valid fuel (bit 4 is the separate flame bit). But most crucible recipes
     * actually need coal specifically - branch only works for a few (e.g. smelting bars into
     * nuggets) - so coal is always preferred: it counts as "already fueled" on its own, and is
     * topped off whenever available even if the crucible already holds branch fuel instead of
     * settling for it. Branch is only ever accepted, existing or freshly fetched, once coal is
     * confirmed unavailable anywhere (in hand, in the fuel zone, or the zone/station unreachable).
     */
    private static final String[] CRUCIBLE_FUELS = {"Coal", "Branch"};

    boolean fillCrucible(Gob crucible, NGameUI gui) throws InterruptedException
    {
        if((crucible.ngob.getModelAttribute()&2)!=0)
            return true;
        boolean hasBranchFuel = (crucible.ngob.getModelAttribute()&1)!=0;

        int count = 1;
        if(NUtils.getGameUI().getInventory().getFreeSpace()==0)
            return hasBranchFuel;

        if(!hasAny(CRUCIBLE_FUELS)) {
            int target_size = count;
            while (target_size != 0 && NUtils.getGameUI().getInventory().getFreeSpace() != 0) {
                // material=null matches a "Fuel: Crucible" zone tagged with either fuel.
                NArea fuelarea = context.goToFuelArea(Specialisation.SpecName.fuelCrucible, null);
                if (fuelarea == null)
                    return hasBranchFuel;
                ArrayList<Gob> piles = Finder.findGobs(fuelarea, new NAlias("stockpile"));
                if (piles.isEmpty()) {
                    if (gui.getInventory().getItems().isEmpty())
                        return hasBranchFuel;
                    else
                        break;
                }
                piles.sort(NUtils.d_comp);

                Gob pile = piles.get(0);
                new PathFinder(pile).run(gui);
                new OpenTargetContainer("Stockpile", pile).run(gui);
                TakeItemsFromPile tifp;
                (tifp = new TakeItemsFromPile(pile, gui.getStockpile(), Math.min(target_size, gui.getInventory().getFreeSpace()))).run(gui);
                new CloseTargetWindow(NUtils.getGameUI().getWindow("Stockpile")).run(gui);
                target_size = target_size - tifp.getResult();
            }
        }
        /* The fuel may have come from a zone nowhere near the crucible. */
        context.goToArea(context.workstation);
        Gob station = Finder.findGob(crucible.id);
        if (station == null)
            return hasBranchFuel;
        new PathFinder(station).run(gui);
        WItem fuelItem = firstOf(CRUCIBLE_FUELS);
        if (fuelItem == null) {
            return hasBranchFuel;
        }
        NUtils.takeItemToHand(fuelItem);
        NUtils.activateItem(station);
        NUtils.getUI().core.addTask(new HandIsFree(NUtils.getGameUI().getInventory()));
        return true;
    }

    private boolean hasAny(String[] itemNames) throws InterruptedException {
        for (String name : itemNames) {
            if (!NUtils.getGameUI().getInventory().getItems(name).isEmpty())
                return true;
        }
        return false;
    }

    private WItem firstOf(String[] itemNames) throws InterruptedException {
        for (String name : itemNames) {
            ArrayList<WItem> items = NUtils.getGameUI().getInventory().getItems(name);
            if (!items.isEmpty())
                return items.get(0);
        }
        return null;
    }
}
