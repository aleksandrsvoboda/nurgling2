package nurgling.contextmenu;

import haven.Gob;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Equip;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.Arrays;

public class RemoveStumpAction implements GobContextAction {
    private static final NAlias TREE_PATH_ALIAS = new NAlias("gfx/terobjs/trees/");
    private static final NAlias STUMP_ALIAS = new NAlias("stump");

    private static final NAlias SHOVEL_ALIAS = new NAlias(
            "Wooden Shovel", "Metal Shovel", "Tinker's Shovel"
    );

    @Override
    public boolean appliesTo(Gob gob) {
        String name = gob.ngob.name;
        return NParser.checkName(name, TREE_PATH_ALIAS) && NParser.checkName(name, STUMP_ALIAS);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.remove_stump");
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            if (!new Equip(SHOVEL_ALIAS).run(gui).IsSuccess())
                NUtils.getGameUI().msg("No shovel found on belt");

            PathFinder pf = new PathFinder(gob);
            pf.setMode(PathFinder.Mode.Y_MAX);
            pf.isHardMode = true;
            pf.run(gui);

            NUtils.destroy(gob);
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return Finder.findGob(gob.id) == null;
                }
            });

            return Results.SUCCESS();
        };
    }
}
