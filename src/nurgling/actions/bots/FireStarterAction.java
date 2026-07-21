package nurgling.actions.bots;

import haven.Gob;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NContext;

import java.util.ArrayList;

/**
 * Fire Starter Bot - Ignites objects and refuels them if needed.
 *
 * <p>The user clicks a target object; the bot determines what it is from the gob path, adds fuel if
 * that workstation needs it (only Fire Place / pow and Cauldron are auto-fueled), and then lights it
 * through the unified {@link LightObject} lighter (embers → torches → torchposts → candelabrum →
 * branches). All workstation flame/fuel flags come from {@link LightObject#getConfig} so there is a
 * single source of truth.
 */
public class FireStarterAction implements Action {

    // Fire Place "in use" states (hearthfire / special) — do not touch.
    private static final int POW_IN_USE_MASK = 48;
    // Fuel-present bit on the model for pow / cauldron.
    private static final int FUEL_BIT = 1;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Ask user to click on target object
        SelectGob selgob;
        NUtils.getGameUI().msg("Click on the object to ignite");
        (selgob = new SelectGob(Resource.loadsimg("baubles/ignite"))).run(gui);

        Gob target = selgob.getResult();
        if (target == null) {
            return Results.ERROR("No object selected");
        }

        String gobName = target.ngob.name;
        if (gobName == null) {
            return Results.ERROR("Cannot determine object type");
        }

        LightObject.LightConfig config = LightObject.getConfig(gobName);
        if (config == null) {
            return Results.ERROR("Unknown object type: " + gobName);
        }

        gui.msg("Fire Starter: Processing " + config.displayName);

        // Already burning?
        if ((target.ngob.getModelAttribute() & config.fireFlag) != 0) {
            gui.msg(config.displayName + " is already burning");
            return Results.SUCCESS();
        }

        boolean isPow = gobName.contains("gfx/terobjs/pow");
        boolean isCauldron = gobName.contains("gfx/terobjs/cauldron");

        // Fire Place special states (hearthfire, etc.) — leave alone.
        if (isPow && (target.ngob.getModelAttribute() & POW_IN_USE_MASK) != 0) {
            gui.msg("Fire Place is already in use");
            return Results.SUCCESS();
        }

        // Only Fire Place and Cauldron are auto-fueled by this bot.
        if ((isPow || isCauldron) && (target.ngob.getModelAttribute() & FUEL_BIT) == 0) {
            NContext context = new NContext(gui);
            ArrayList<Gob> gobs = new ArrayList<>();
            gobs.add(target);
            gui.msg("Adding fuel to " + config.displayName + "...");
            Results fuelResult = new FillFuelPowOrCauldron(context, gobs, 1).run(gui);
            if (!fuelResult.IsSuccess()) {
                return Results.ERROR("NO FUEL");
            }
            gui.msg("Fuel added successfully");
        }

        // Light it through the unified lighter (list constructor enables the full bot behavior,
        // including candelabrum-area fetch).
        ArrayList<Gob> toLight = new ArrayList<>();
        toLight.add(target);
        gui.msg("Lighting " + config.displayName + "...");
        Results lightResult = new LightObject(toLight).run(gui);
        if (!lightResult.IsSuccess()) {
            return Results.ERROR("Failed to light fire");
        }

        gui.msg("Fire Starter: Completed successfully!");
        return Results.SUCCESS();
    }
}
