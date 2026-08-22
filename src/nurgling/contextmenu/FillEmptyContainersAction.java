package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.FillWaterContainers;
import nurgling.actions.PathFinder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

public class FillEmptyContainersAction implements GobContextAction {
    private static final NAlias WATER_SOURCE = new NAlias("barrel", "cistern", "well");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, WATER_SOURCE);
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.fill_empty_containers");
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            new PathFinder(gob).run(gui);
            return new FillWaterContainers(FillWaterContainers.fromGob(gob)).run(gui);
        };
    }
}
