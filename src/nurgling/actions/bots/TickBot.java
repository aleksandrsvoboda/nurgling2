package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.IMeter;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.ActionWithFinal;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.widgets.Specialisation;

import static haven.OCache.posres;

public class TickBot extends ActionWithFinal {

    private Boolean originalParasiteEnabled;
    private String originalTickAction;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Save original parasite settings
        originalParasiteEnabled = (Boolean) NConfig.get(NConfig.Key.parasiteBotEnabled);
        originalTickAction = (String) NConfig.get(NConfig.Key.tickAction);

        // Enable parasite bot to put ticks into inventory
        NConfig.set(NConfig.Key.parasiteBotEnabled, true);
        NConfig.set(NConfig.Key.tickAction, "inventory");

        NContext context = new NContext(gui);

        // Find and navigate to thicket area
        NArea thicketArea = context.goToArea(Specialisation.SpecName.thicket);
        if (thicketArea == null) {
            return Results.ERROR("No Thicket area found");
        }

        // Navigate to center of thicket area so we're actually in the thicket
        navigateToCenter(gui, thicketArea);

        while (true) {
            // Wait until HP drops below 50% or inventory is full
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    IMeter.Meter hp = gui.getmeter("hp", 0);
                    if (hp != null && hp.a < 0.5) {
                        return true;
                    }

                    int freeSpace = gui.getInventory().calcFreeSpace();
                    return freeSpace >= 0 && freeSpace < 1;
                }
            });

            // Determine which condition triggered
            IMeter.Meter hp = gui.getmeter("hp", 0);
            boolean lowHp = hp != null && hp.a < 0.5;

            // Drop off ticks
            new FreeInventory2(context).run(gui);

            if (lowHp) {
                gui.msg("TickBot: HP below 50%, stopping");
                return Results.SUCCESS();
            }

            // Inventory was full - navigate back to thicket
            gui.msg("TickBot: Inventory cleared, returning to thicket");
            NUtils.navigateToArea(thicketArea);
            navigateToCenter(gui, thicketArea);
        }
    }

    @Override
    public void endAction() {
        // Restore original parasite settings
        NConfig.set(NConfig.Key.parasiteBotEnabled,
                originalParasiteEnabled != null ? originalParasiteEnabled : false);
        NConfig.set(NConfig.Key.tickAction,
                originalTickAction != null ? originalTickAction : "ground");
    }

    private void navigateToCenter(NGameUI gui, NArea area) throws InterruptedException {
        Coord2d center = area.getCenter2d();
        if (center == null) return;

        gui.map.wdgmsg("click", Coord.z, center.floor(posres), 1, 0);
        NUtils.addTask(new NTask() {
            int count = 0;

            @Override
            public boolean check() {
                count++;
                if (NUtils.player() == null) return false;
                Coord2d playerPos = NUtils.player().rc;
                double dist = Math.sqrt(
                        Math.pow(playerPos.x - center.x, 2) +
                        Math.pow(playerPos.y - center.y, 2)
                );
                if (dist <= 2) return true;
                if (count > 200 && !NUtils.player().pose().contains("walking")) {
                    return true;
                }
                return false;
            }
        });
    }
}
