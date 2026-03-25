package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.SelectArea;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class UnloadVehicleAction implements GobContextAction {
    private static final NAlias VEHICLE = new NAlias("vehicle");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, VEHICLE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.unload_vehicle");
    }

    @Override
    public Action create(Gob vehicle) {
        return gui -> {
            SelectArea outsa;
            gui.msg("Please, select output area");
            (outsa = new SelectArea(Resource.loadsimg("baubles/outputArea"))).run(gui);

            while (new TakeFromVehicle(vehicle).run(gui).IsSuccess()) {
                Gob gob = Finder.findLiftedbyPlayer();
                new FindPlaceAndAction(gob, outsa.getRCArea()).run(gui);
                Coord2d shift = gob.rc.sub(NUtils.player().rc).norm().mul(2);
                new GoTo(NUtils.player().rc.sub(shift)).run(gui);
            }

            return Results.SUCCESS();
        };
    }
}
