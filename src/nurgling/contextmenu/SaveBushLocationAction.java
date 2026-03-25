package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.Results;

public class SaveBushLocationAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob.ngob.name.startsWith("gfx/terobjs/bushes/");
    }

    @Override
    public String label() {
        return "Save Bush Location";
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
