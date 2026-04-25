package nurgling.actions.bots;

import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.tools.NAlias;

public class CreateSoilPiles implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        SelectArea outsa;
        NUtils.getGameUI().msg("Please, select input area");
        (outsa = new SelectArea(Resource.loadsimg("baubles/outputArea"))).run(gui);
        new CreateFreePiles(outsa.getRCArea(),new NAlias("Soil"),new NAlias("gfx/terobjs/stockpile-soil")).run(gui);
        return Results.SUCCESS();
    }
}
