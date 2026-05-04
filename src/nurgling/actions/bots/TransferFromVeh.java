package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Finder;

public class TransferFromVeh implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        String outsaId = context.createArea("Please, select output area", Resource.loadsimg("baubles/outputArea"));
        NArea outsaArea = context.goToAreaById(outsaId);

        SelectGob selgob;
        NUtils.getGameUI().msg("Please select output cart or vehicle");
        (selgob = new SelectGob(Resource.loadsimg("baubles/outputVeh"))).run(gui);
        Gob target = selgob.result;
        if(target==null)
        {
            return Results.ERROR("Vehicle not found");
        }

        while (new TakeFromVehicle(selgob.result).run(gui).IsSuccess()) {
            Gob gob = Finder.findLiftedbyPlayer();
            new FindPlaceAndAction(gob, outsaArea.getRCArea()).run(gui);
            Coord2d shift = gob.rc.sub(NUtils.player().rc).norm().mul(2);
            new GoTo(NUtils.player().rc.sub(shift)).run(gui);
        }

        return Results.SUCCESS();
    }
}
