package nurgling.actions.bots;

import haven.Resource;
import nurgling.NGameUI;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;

public class CreateSoilPiles implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        String outsaId = context.createArea("Please, select input area", Resource.loadsimg("baubles/outputArea"));
        NArea outsaArea = context.goToAreaById(outsaId);
        new CreateFreePiles(outsaArea.getRCArea(),new NAlias("Soil"),new NAlias("gfx/terobjs/stockpile-soil")).run(gui);
        return Results.SUCCESS();
    }
}
