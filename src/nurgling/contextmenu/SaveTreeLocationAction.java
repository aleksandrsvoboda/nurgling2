package nurgling.contextmenu;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;

public class SaveTreeLocationAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob.ngob.name.startsWith("gfx/terobjs/trees/")
                && !gob.ngob.name.contains("log")
                && !gob.ngob.name.contains("trunk")
                && !gob.ngob.name.contains("stump");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.save_tree_location");
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            if (gui.treeLocationService != null) {
                gui.treeLocationService.saveTreeLocation(gob);
            }
            return Results.SUCCESS();
        };
    }
}
