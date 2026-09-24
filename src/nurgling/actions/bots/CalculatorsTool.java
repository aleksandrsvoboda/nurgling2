package nurgling.actions.bots;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.widgets.calculators.NCalculatorsWindow;

/**
 * Opens the Calculators window, or brings it to the front when it is already open.
 */
public class CalculatorsTool implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        for (NCalculatorsWindow open : gui.children(NCalculatorsWindow.class)) {
            open.show();
            open.raise();
            return Results.SUCCESS();
        }
        NCalculatorsWindow wnd = new NCalculatorsWindow();
        gui.add(wnd, new Coord(gui.sz.x / 2 - wnd.sz.x / 2, gui.sz.y / 2 - wnd.sz.y / 2));
        return Results.SUCCESS();
    }
}
