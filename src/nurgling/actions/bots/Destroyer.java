package nurgling.actions.bots;

import haven.Gob;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;

import java.util.ArrayList;

public class Destroyer implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        String outsaId = context.createArea("Please, select input area", Resource.loadsimg("baubles/outputArea"));
        NArea outsaArea = context.goToAreaById(outsaId);
        ArrayList<Gob> gobs = Finder.findGobs(outsaArea, null);
        while (!gobs.isEmpty()) {
            gobs.sort(NUtils.d_comp);
            for (Gob gob : gobs) {
                if(PathFinder.isAvailable(gob)) {
                    PathFinder pf = new PathFinder(gob);
                    pf.isHardMode = true;
                    pf.run(gui);
                    while (Finder.findGob(gob.id) != null) {
                        new RestoreResources().run(gui);
                        NUtils.destroy(gob);
                        NUtils.addTask(new NTask() {
                            @Override
                            public boolean check() {
                                return NUtils.getEnergy() < 0.25 || NUtils.getStamina() < 0.25 || Finder.findGob(gob.id) == null;
                            }
                        });
                    }
                }
            }
            gobs = Finder.findGobs(outsaArea, null);
        }
        return Results.SUCCESS();
    }
}
