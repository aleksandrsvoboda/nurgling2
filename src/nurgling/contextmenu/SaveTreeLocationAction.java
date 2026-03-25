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
                && !gob.ngob.name.contains("trunk");
    }

    @Override
    public String label() {
        return "Save Tree Location";
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
