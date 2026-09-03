package nurgling.widgets;

import haven.*;
import nurgling.routes.ForagerWaypoint;

/**
 * Popout for editing the scheduler-style {@link nurgling.scenarios.BotStep}s attached to one
 * {@link ForagerWaypoint} (Ctrl+right-click a waypoint on the Routes map to open this). Modeled
 * directly on {@link ActionConfigWindow}'s "popout scoped to one entity" shape, but edits apply
 * immediately to the waypoint's own {@code steps} list (matching how every other edit on the
 * Routes map - add/move/delete a waypoint - already applies immediately and relies on the owning
 * panel's normal save flow to persist it), rather than buffering into a submit/cancel pair.
 */
public class WaypointStepsWindow extends Window {

    // Same "nothing"/"logout"/"travel hearth" vocabulary already used for onAnimalAction/
    // afterFinishAction/onFullInventoryAction (widgets/bots/Forager.java) - not shared as a
    // constant across packages since it's a trivial 3-entry literal, not shared logic.
    private static final String[] FAIL_ACTIONS = {"nothing", "logout", "travel hearth"};

    private final ForagerWaypoint waypoint;
    private final Runnable onChanged;

    private final StepListWidget stepList;
    private final StepSettingsPanel stepSettingsPanel;

    // Suppresses fireChanged() while the fail-action dropbox is being set to its initial value
    // below (Dropbox has no "select without firing" method distinct from change() itself) - opening
    // this popout shouldn't by itself mark the route dirty.
    private boolean initializing = true;

    public WaypointStepsWindow(ForagerWaypoint waypoint, Runnable onChanged) {
        super(new Coord(UI.scale(520), UI.scale(430)), "Waypoint Steps");
        this.waypoint = waypoint;
        this.onChanged = onChanged;

        Coord listSz = new Coord(UI.scale(300), UI.scale(300));
        Coord settingsSz = new Coord(UI.scale(180), UI.scale(300));

        Widget prev = add(new Label("Steps run in order when Forager arrives here,"), Coord.z);
        prev = add(new Label("before it continues the route."), prev.pos("bl").add(UI.scale(0, 2)));

        stepSettingsPanel = new StepSettingsPanel(settingsSz, null);

        stepList = add(new StepListWidget(
                listSz,
                () -> waypoint.steps,
                stepSettingsPanel::setStep,
                this::fireChanged,
                b -> b.allowedAsForagerStep
        ), prev.pos("bl").add(UI.scale(0, 10)));

        add(stepSettingsPanel, new Coord(stepList.c.x + listSz.x + UI.scale(10), stepList.c.y));

        prev = add(new Button(UI.scale(120), "Add Step", stepList::showAddStepDialog),
                stepList.pos("bl").add(UI.scale(0, 8)));

        prev = add(new Label("If steps fail:"), prev.pos("bl").add(UI.scale(0, 12)));

        Dropbox<String> failDropbox = add(new Dropbox<String>(UI.scale(150), FAIL_ACTIONS.length, UI.scale(16)) {
            @Override
            protected String listitem(int i) {
                return FAIL_ACTIONS[i];
            }

            @Override
            protected int listitems() {
                return FAIL_ACTIONS.length;
            }

            @Override
            protected void drawitem(GOut g, String item, int i) {
                g.text(item, Coord.z);
            }

            @Override
            public void change(String item) {
                super.change(item);
                waypoint.onStepsFailAction = item;
                if (!initializing) {
                    fireChanged();
                }
            }
        }, prev.pos("bl").add(UI.scale(0, 5)));
        prev = failDropbox;

        String current = waypoint.onStepsFailAction != null ? waypoint.onStepsFailAction : "nothing";
        for (String action : FAIL_ACTIONS) {
            if (action.equals(current)) {
                failDropbox.change(action);
                break;
            }
        }
        initializing = false;

        add(new Button(UI.scale(80), "Close", this::close), prev.pos("bl").add(UI.scale(0, 10)));

        stepList.refresh();

        pack();
    }

    private void fireChanged() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void close() {
        stepList.closeAddStepDialog();
        hide();
        destroy();
    }

    @Override
    public void wdgmsg(String msg, Object... args) {
        if (msg.equals("close")) {
            close();
        } else {
            super.wdgmsg(msg, args);
        }
    }
}
