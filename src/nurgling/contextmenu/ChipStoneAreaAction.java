package nurgling.contextmenu;

import haven.Gob;
import nurgling.actions.Action;

public class ChipStoneAreaAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob.ngob.name.startsWith("gfx/terobjs/bumlings");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.chip_stone_area");
    }

    @Override
    public Action create(Gob gob) {
        return new nurgling.actions.bots.Chipper();
    }
}
