package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;

public class CutDownAreaAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        String name = gob.ngob.name;
        return (name.startsWith("gfx/terobjs/trees/")
                && !name.contains("log")
                && !name.contains("trunk")
                && !name.contains("stump"))
                || name.startsWith("gfx/terobjs/bushes/");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.cut_down_area");
    }

    @Override
    public Action create(Gob gob) {
        return new nurgling.actions.bots.Chopper();
    }
}
