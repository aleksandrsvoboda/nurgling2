package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.List;

public class ShearWoolArea implements Action {

    private static final NAlias SHEARABLE_ANIMALS = new NAlias(
            new ArrayList<>(List.of("gfx/kritter/sheep", "gfx/kritter/goat")),
            new ArrayList<>(List.of("wild"))
    );

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        String areaId = context.createArea("Please select area for shearing", Resource.loadsimg("baubles/inputArea"));

        boolean needRestart = true;
        while (needRestart) {
            needRestart = false;
            ArrayList<Gob> gobs = context.getGobs(areaId, SHEARABLE_ANIMALS);
            gobs.sort(NUtils.d_comp);

            for (Gob target : gobs) {
                if (gui.getInventory().getNumberFreeCoord(Coord.of(1, 1)) < 3) {
                    new FreeInventory2(context).run(gui);
                    needRestart = true;
                    break;
                }

                ShearWool.shear(gui, target);
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }
}
