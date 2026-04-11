package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;
import nurgling.actions.bots.LightObject;

public class LightAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return LightObject.getConfig(gob.ngob.name) != null;
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.light");
    }

    @Override
    public Action create(Gob gob) {
        return new LightObject(gob);
    }
}
