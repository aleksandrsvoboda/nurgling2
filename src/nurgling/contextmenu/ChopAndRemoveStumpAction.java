package nurgling.contextmenu;

import haven.Coord2d;
import haven.Gob;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Equip;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.SelectFlowerAction;
import nurgling.tasks.NTask;
import nurgling.tasks.NoGob;
import nurgling.tasks.WaitPoseOrNoGob;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.Arrays;

public class ChopAndRemoveStumpAction implements GobContextAction {
    private static final NAlias TREE_ALIAS = new NAlias(
            new ArrayList<>(Arrays.asList("gfx/terobjs/trees/")),
            new ArrayList<>(Arrays.asList("log", "trunk", "oldtrunk", "stump"))
    );

    private static final NAlias AXE_ALIAS = new NAlias(
            "Woodsman's Axe", "Stone Axe", "Metal Axe",
            "Butcher's cleaver", "Tinker's Throwing Axe",
            "Battle Axe of the Twelfth Bay"
    );

    private static final NAlias SHOVEL_ALIAS = new NAlias(
            "Wooden Shovel", "Metal Shovel", "Tinker's Shovel"
    );

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, TREE_ALIAS);
    }

    @Override
    public String label() {
        return "Chop + Remove Stump";
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            Coord2d treePos = gob.rc;
            long treeId = gob.id;

            PathFinder pf = new PathFinder(gob);
            pf.setMode(PathFinder.Mode.Y_MAX);
            pf.isHardMode = true;
            pf.run(gui);

            if (!new Equip(AXE_ALIAS).run(gui).IsSuccess())
                return Results.ERROR("No axe found on belt");

            new SelectFlowerAction("Chop", gob).run(gui);
            NUtils.getUI().core.addTask(new WaitPoseOrNoGob(NUtils.player(), gob, "gfx/borka/treechop"));

            NUtils.addTask(new NoGob(treeId));

            NAlias stumpAlias = new NAlias("stump");
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    try {
                        return Finder.findGob(treePos, stumpAlias, null, 2.0) != null;
                    } catch (InterruptedException e) {
                        return true;
                    }
                }
            });

            Gob stump = Finder.findGob(treePos, stumpAlias, null, 2.0);
            if (stump == null)
                return Results.ERROR("Stump not found");

            if (!new Equip(SHOVEL_ALIAS).run(gui).IsSuccess())
                return Results.ERROR("No shovel found on belt");

            PathFinder pf2 = new PathFinder(stump);
            pf2.setMode(PathFinder.Mode.Y_MAX);
            pf2.isHardMode = true;
            pf2.run(gui);

            NUtils.destroy(stump);
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return Finder.findGob(stump.id) == null;
                }
            });

            return Results.SUCCESS();
        };
    }
}
