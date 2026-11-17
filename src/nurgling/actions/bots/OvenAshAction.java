package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.WaitForBurnout;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

/**
 * Burns branches into ash in ovens, fuelled with branches as well.
 * <p>
 * Copied from {@link BlockAshAction}; check fixes in one against the other. Differences:
 * ovens instead of kilns, the {@code branchforash} input zone, flame bit 4, no Tetris
 * (a branch is 1x1 and does not stack in an oven, so free cells are the capacity), and the
 * ovens are fuelled <i>before</i> they are filled. Input and fuel are the same item and may
 * be the same pile, and filling first could load the last batch with every branch and leave
 * none to burn it with. An oven fuelled but left empty keeps its fuel for the next run.
 */
public class OvenAshAction implements Action {
    private static final int FUEL = 8;
    private static final int FLAME_FLAG = 4;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea.Specialisation rBranchForAsh = new NArea.Specialisation(Specialisation.SpecName.branchforash.toString());
        NArea.Specialisation rovens = new NArea.Specialisation(Specialisation.SpecName.ovens.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(rovens);
        req.add(rBranchForAsh);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        if(!new Validator(req, opt)
                .fuel(Specialisation.SpecName.fuelOven, "Branch")
                .run(gui).IsSuccess())
            return Results.FAIL();

        NArea input = NContext.findSpec(Specialisation.SpecName.branchforash.toString());
        Pair<Coord2d,Coord2d> rca = (input == null) ? null : input.getRCArea();
        if(rca == null)
            return Results.ERROR("Input area not found");

        NAlias branch = new NAlias("Branch");
        ArrayList<Container> containers = new ArrayList<>();
        NArea ovensarea = NContext.findSpec(rovens.name);
        for (Gob oven : Finder.findGobs(ovensarea, new NAlias("gfx/terobjs/oven"))) {
            Container cand = new Container(oven, "Oven", ovensarea);
            cand.initattr(Container.Space.class);
            cand.initattr(Container.FuelLvl.class);
            cand.getattr(Container.FuelLvl.class).setMaxlvl(FUEL);
            cand.getattr(Container.FuelLvl.class).setFueltype("Branch");
            cand.getattr(Container.FuelLvl.class).setFuelZone(Specialisation.SpecName.fuelOven);
            containers.add(cand);
        }
        if(containers.isEmpty())
            return Results.ERROR("No ovens found");

        ArrayList<String> flighted = new ArrayList<>();
        for (Container cont : containers) {
            flighted.add(cont.gobHash);
        }

        Results res = null;
        while (res == null || res.IsSuccess()) {
            NUtils.getUI().core.addTask(new WaitForBurnout(flighted, FLAME_FLAG));
            new FreeContainers(containers).run(gui);

            /* Nothing left to burn: stop before fuelling ovens that would stay empty. */
            if(gui.getInventory().getItems(branch).isEmpty()
                    && Finder.findGobs(rca, new NAlias("stockpile")).isEmpty())
                break;

            Results fuelRes = new FuelToContainers(containers).run(gui);
            if (!fuelRes.IsSuccess())
                return fuelRes;

            res = new FillContainersFromPiles(containers, rca, branch).run(gui);

            flighted.clear();
            for (Container container : containers) {
                Container.Space space = container.getattr(Container.Space.class);
                if (!space.isEmpty())
                    flighted.add(container.gobHash);
            }
            if (!new LightGob(flighted, FLAME_FLAG).run(gui).IsSuccess())
                return Results.ERROR("I can't start a fire");
        }
        return Results.SUCCESS();
    }
}
