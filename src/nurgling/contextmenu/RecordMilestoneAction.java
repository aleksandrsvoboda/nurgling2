package nurgling.contextmenu;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;

/** Ctrl+right-click a milestone gob arms {@link nurgling.navigation.MilestoneTracker} to record the next real Travel teleport into {@link nurgling.tools.MilestoneRegistry}. */
public class RecordMilestoneAction implements GobContextAction {

    @Override
    public boolean appliesTo(Gob gob) {
        return gob != null && gob.ngob != null && gob.ngob.name != null
                && gob.ngob.name.toLowerCase().contains("milestone");
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.record_milestone");
    }

    @Override
    public Action create(Gob gob) {
        // Never called - this is a UI-only entry.
        return null;
    }

    @Override
    public boolean isUiAction() {
        return true;
    }

    @Override
    public void performUi(Gob gob) {
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && gui.map instanceof NMapView) {
            ((NMapView) gui.map).getMilestoneTracker().arm(gob);
        }
    }
}
