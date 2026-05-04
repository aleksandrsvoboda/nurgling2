package nurgling.actions.bots;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

public class CollectDreams implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        NArea dreamcatcherArea = context.goToArea(Specialisation.SpecName.dreamcatcher);

        for (Gob dreamCatcher : Finder.findGobs(dreamcatcherArea,
                new NAlias("gfx/terobjs/dreca"))) {
            new PathFinder(dreamCatcher).run(gui);
            Results harvestResult;
            int harvestAttempt = 0;
            do {
                harvestAttempt++;
                harvestResult = new SelectFlowerAction("Harvest", dreamCatcher).run(gui);
                if(harvestAttempt == 2) {
                    break;
                }
            } while (harvestResult.isSuccess);

            if(NUtils.getGameUI().getInventory().getFreeSpace()<3) {
                new FreeInventory2(context).run(gui);
                NUtils.navigateToArea(dreamcatcherArea);
            }
        }

        new FreeInventory2(context).run(gui);

        return Results.SUCCESS();
    }
}
