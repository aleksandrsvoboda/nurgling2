package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class FindAndEatItems implements Action
{
    final NContext cnt;
    final ArrayList<String> items;
    final double level;

    public FindAndEatItems(NContext context, ArrayList<String> items, int level)
    {
        this.cnt = context;
        this.items = items;
        this.level = level;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        NAlias foodAlias = new NAlias(items);

        eatFood(gui, gui.getInventory().getItems(foodAlias));
        if (energyReached()) {
            return Results.SUCCESS();
        }

        NArea area = cnt.findArea(Specialisation.SpecName.eat);
        if (area == null) {
            return Results.FAIL();
        }

        for (String item : items) {
            cnt.addInItem(item, null);
        }

        NArea navigated = cnt.goToArea(Specialisation.SpecName.eat);
        if (navigated == null) {
            return Results.FAIL();
        }

        ArrayList<Gob> containerGobs = Finder.findGobs(navigated, new NAlias(new ArrayList<>(NContext.contcaps.keySet())));
        if (containerGobs.isEmpty()) {
            return Results.FAIL();
        }

        for (Gob gob : containerGobs) {
            if (energyReached()) {
                return Results.SUCCESS();
            }

            String cap = NContext.contcaps.get(gob.ngob.name);
            Container container = new Container(gob, cap, navigated);

            new PathFinder(gob).run(gui);
            new OpenTargetContainer(container).run(gui);

            NInventory containerInv = gui.getInventory(cap);
            if (containerInv != null) {
                eatFood(gui, containerInv.getItems(foodAlias));
            }

            new CloseTargetContainer(container).run(gui);
        }

        return energyReached() ? Results.SUCCESS() : Results.FAIL();
    }

    private boolean energyReached() {
        return NUtils.getEnergy() * 10000 >= level;
    }

    private void eatFood(NGameUI gui, ArrayList<WItem> foodItems) throws InterruptedException {
        for (WItem item : foodItems) {
            if (energyReached()) {
                return;
            }
            new SelectFlowerAction("Eat", (NWItem) item).run(gui);
        }
    }
}
