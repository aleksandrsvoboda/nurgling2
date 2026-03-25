package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.actions.bots.SelectGob;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class EmptyBarrelsIntoCisternAction implements GobContextAction {
    private static final NAlias CISTERN = new NAlias("cistern");
    private static final NAlias BARREL = new NAlias("barrel");
    private static final NAlias WATER = new NAlias("water");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, CISTERN);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.empty_barrels_into_cistern");
    }

    @Override
    public Action create(Gob cistern) {
        return gui -> {
            gui.msg("Please click on vehicle to empty barrels from");
            SelectGob selgob = new SelectGob(Resource.loadsimg("baubles/outputVeh"));
            selgob.run(gui);
            Gob vehicle = selgob.getResult();
            if (vehicle == null) {
                return Results.ERROR("No vehicle selected");
            }

            if (vehicle.ngob.name == null) {
                return Results.ERROR("Not a supported vehicle type");
            }

            int emptied;
            if (vehicle.ngob.name.contains("cart")) {
                emptied = emptyFromCart(gui, cistern, vehicle);
            } else if (vehicle.ngob.name.contains("snekkja") || vehicle.ngob.name.contains("wagon")) {
                emptied = emptyFromWindowVehicle(gui, cistern, vehicle);
            } else {
                return Results.ERROR("Not a supported vehicle type");
            }

            if (emptied > 0) {
                gui.msg("Emptied " + emptied + " barrel" + (emptied > 1 ? "s" : "") + " into cistern");
            } else {
                gui.msg("No water barrels found in vehicle");
            }
            return Results.SUCCESS();
        };
    }

    private static int emptyFromCart(NGameUI gui, Gob cistern, Gob vehicle) throws InterruptedException {
        int emptied = 0;
        for (int slot = 0; slot < 6; slot++) {
            Results takeResult = new TakeFromVehicleSlot(vehicle, slot).run(gui);
            if (!takeResult.IsSuccess()) {
                continue;
            }

            Gob barrel = Finder.findLiftedbyPlayer();
            if (barrel == null) {
                continue;
            }

            if (!NParser.checkName(barrel.ngob.name, BARREL) || !NUtils.isOverlay(barrel, WATER)) {
                new TransferToVehicle(barrel, vehicle).run(gui);
                continue;
            }

            emptied += emptyBarrelIntoCistern(gui, cistern, barrel);
            new TransferToVehicle(barrel, vehicle).run(gui);
        }
        return emptied;
    }

    private static int emptyFromWindowVehicle(NGameUI gui, Gob cistern, Gob vehicle) throws InterruptedException {
        int itemCount = TakeFromVehicleSlot.countCargoItems(gui, vehicle);
        int emptied = 0;

        for (int slot = 0; slot < itemCount; slot++) {
            Results takeResult = new TakeFromVehicleSlot(vehicle, slot).run(gui);
            if (!takeResult.IsSuccess()) {
                continue;
            }

            Gob barrel = Finder.findLiftedbyPlayer();
            if (barrel == null) {
                continue;
            }

            if (!NParser.checkName(barrel.ngob.name, BARREL) || !NUtils.isOverlay(barrel, WATER)) {
                new TransferToVehicle(barrel, vehicle).run(gui);
                continue;
            }

            emptied += emptyBarrelIntoCistern(gui, cistern, barrel);
            new TransferToVehicle(barrel, vehicle).run(gui);
        }
        return emptied;
    }

    private static int emptyBarrelIntoCistern(NGameUI gui, Gob cistern, Gob barrel) throws InterruptedException {
        new PathFinder(cistern).run(gui);
        NUtils.activateGob(cistern);
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return !NUtils.isOverlay(barrel, WATER);
            }
        });

        if (NUtils.isOverlay(barrel, WATER)) {
            gui.msg("Failed to empty barrel into cistern");
            return 0;
        }
        return 1;
    }
}
