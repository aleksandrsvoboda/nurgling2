package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.bots.LightFireplace;

public class LightFireplaceAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob.ngob.name.contains("gfx/terobjs/pow");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.light");
    }

    @Override
    public Action create(Gob gob) {
        return new LightFireplace(gob);
    }
}
