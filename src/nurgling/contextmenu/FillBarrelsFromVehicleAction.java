package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.SelectGob;
import nurgling.tasks.IsOverlay;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class FillBarrelsFromVehicleAction implements GobContextAction {
    private static final NAlias WELL = new NAlias("well");
    private static final NAlias BARREL = new NAlias("barrel");
    private static final NAlias WATER = new NAlias("water");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, WELL);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.fill_barrels_from_well");
    }

    @Override
    public Action create(Gob well) {
        return gui -> {
            gui.msg("Please click on vehicle to fill barrels from");
            SelectGob selgob = new SelectGob(Resource.loadsimg("baubles/outputVeh"));
            selgob.run(gui);
            Gob vehicle = selgob.getResult();
            if (vehicle == null) {
                return Results.ERROR("No vehicle selected");
            }

            if (vehicle.ngob.name == null) {
                return Results.ERROR("Not a supported vehicle type");
            }

            int filled;
            if (vehicle.ngob.name.contains("cart")) {
                filled = fillFromCart(gui, well, vehicle);
            } else if (vehicle.ngob.name.contains("snekkja") || vehicle.ngob.name.contains("wagon")) {
                filled = fillFromWindowVehicle(gui, well, vehicle);
            } else {
                return Results.ERROR("Not a supported vehicle type");
            }

            if (filled > 0) {
                gui.msg("Filled " + filled + " barrel" + (filled > 1 ? "s" : "") + " with water");
            } else {
                gui.msg("No empty barrels found in vehicle");
            }
            return Results.SUCCESS();
        };
    }

    private static int fillFromCart(NGameUI gui, Gob well, Gob vehicle) throws InterruptedException {
        int filled = 0;
        for (int slot = 0; slot < 6; slot++) {
            Results takeResult = new TakeFromVehicleSlot(vehicle, slot).run(gui);
            if (!takeResult.IsSuccess()) {
                continue;
            }

            Gob barrel = Finder.findLiftedbyPlayer();
            if (barrel == null) {
                continue;
            }

            if (!NParser.checkName(barrel.ngob.name, BARREL) || NUtils.barrelHasContent(barrel)) {
                new TransferToVehicle(barrel, vehicle).run(gui);
                continue;
            }

            filled += fillBarrelAtWell(gui, well, barrel);
            new TransferToVehicle(barrel, vehicle).run(gui);
        }
        return filled;
    }

    private static int fillFromWindowVehicle(NGameUI gui, Gob well, Gob vehicle) throws InterruptedException {
        int itemCount = TakeFromVehicleSlot.countCargoItems(gui, vehicle);
        int filled = 0;

        for (int slot = 0; slot < itemCount; slot++) {
            Results takeResult = new TakeFromVehicleSlot(vehicle, slot).run(gui);
            if (!takeResult.IsSuccess()) {
                continue;
            }

            Gob barrel = Finder.findLiftedbyPlayer();
            if (barrel == null) {
                continue;
            }

            if (!NParser.checkName(barrel.ngob.name, BARREL) || NUtils.barrelHasContent(barrel)) {
                new TransferToVehicle(barrel, vehicle).run(gui);
                continue;
            }

            filled += fillBarrelAtWell(gui, well, barrel);
            new TransferToVehicle(barrel, vehicle).run(gui);
        }
        return filled;
    }

    private static int fillBarrelAtWell(NGameUI gui, Gob well, Gob barrel) throws InterruptedException {
        new PathFinder(well).run(gui);
        NUtils.activateGob(well);
        IsOverlay overlayTask = new IsOverlay(barrel, WATER);
        NUtils.addTask(overlayTask);

        if (!overlayTask.getResult()) {
            gui.msg("Failed to fill barrel at well");
            return 0;
        }
        return 1;
    }
}
