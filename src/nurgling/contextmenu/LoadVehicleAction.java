package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.SelectArea;
import nurgling.conf.NCarrierProp;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;

public class LoadVehicleAction implements GobContextAction {
    private static final NAlias VEHICLE = new NAlias("vehicle");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, VEHICLE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.load_vehicle");
    }

    @Override
    public Action create(Gob vehicle) {
        return gui -> {
            nurgling.widgets.bots.Carrier w = null;
            NCarrierProp prop = null;
            try {
                NUtils.getUI().core.addTask(new WaitCheckable(gui.add((w = new nurgling.widgets.bots.Carrier()), UI.scale(200, 200))));
                prop = w.prop;
            } catch (InterruptedException e) {
                throw e;
            } finally {
                if (w != null)
                    w.destroy();
            }
            if (prop == null) {
                return Results.ERROR("No config");
            }

            SelectArea insa;
            gui.msg("Please, select input area");
            (insa = new SelectArea(Resource.loadsimg("baubles/inputArea"))).run(gui);

            IsVehicleFull ivf = new IsVehicleFull(vehicle);
            ivf.run(gui);
            int maxCount = ivf.getCount();

            int count = 0;
            ArrayList<Gob> gobs;
            while (!(gobs = Finder.findGobs(insa.getRCArea(), new NAlias(prop.object))).isEmpty() && count < maxCount) {
                ArrayList<Gob> available = new ArrayList<>();
                for (Gob currGob : gobs) {
                    if (PathFinder.isAvailable(currGob))
                        available.add(currGob);
                }
                if (available.isEmpty())
                    return Results.ERROR("Can't reach any object");

                available.sort(NUtils.d_comp);
                Gob gob = available.get(0);
                new LiftObject(gob).run(gui);
                new TransferToVehicle(gob, vehicle).run(gui);
                count++;
            }

            return Results.SUCCESS();
        };
    }
}
