package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Resource;
import nurgling.NFlowerMenu;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NContext;
import nurgling.tasks.NFlowerMenuIsClosed;
import nurgling.tasks.WaitCollectState;
import nurgling.tasks.WaitPose;
import nurgling.tools.NAlias;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;

public class ShearWoolArea implements Action {

    private static final NAlias SHEARABLE_ANIMALS = new NAlias(
            new ArrayList<>(List.of("gfx/kritter/sheep", "gfx/kritter/goat")),
            new ArrayList<>(List.of("wild"))
    );

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);

        String areaId = context.createArea("Please select area for shearing", Resource.loadsimg("baubles/inputArea"));

        String action = "Shear wool";
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

                gui.map.wdgmsg("click", Coord.z, target.rc.floor(posres), 3, 0, 1, (int) target.id, target.rc.floor(posres),
                        0, -1);
                NFlowerMenu fm = NUtils.findFlowerMenu();
                if (fm != null) {
                    if (fm.hasOpt(action)) {
                        new DynamicPf(target).run(gui);
                        if (fm.chooseOpt(action)) {
                            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                            NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/carving"));
                            WaitCollectState wcs = new WaitCollectState(target, Coord.of(1, 1));
                            NUtils.getUI().core.addTask(wcs);
                        } else {
                            NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                        }
                    } else {
                        fm.wdgmsg("cl", -1);
                        NUtils.getUI().core.addTask(new NFlowerMenuIsClosed());
                    }
                }
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }
}
