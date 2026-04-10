package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;

public class ShearWoolAreaAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        String name = gob.ngob.name;
        return name.startsWith("gfx/kritter/sheep/") || name.startsWith("gfx/kritter/goat/");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.shear_wool_area");
    }

    @Override
    public Action create(Gob gob) {
        return new nurgling.actions.bots.ShearWoolArea();
    }
}
