package nurgling.actions.bots;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.widgets.bots.QuickBarrageBotWnd;

/**
 * Toggles the Quick Barrage window. The open window is found among this session's own
 * widgets rather than kept in a static, so two sessions each get their own.
 */
public class QuickBarrageBot implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        QuickBarrageBotWnd open = gui.getchild(QuickBarrageBotWnd.class);
        if (open != null) {
            open.stopBot();
            open.reqdestroy();
            return Results.SUCCESS();
        }

        QuickBarrageBotWnd wnd = new QuickBarrageBotWnd(gui);
        Coord center = new Coord(gui.sz.x / 2 - wnd.sz.x / 2, gui.sz.y / 2 - wnd.sz.y / 2 - 200);
        gui.add(wnd, center);
        wnd.startBot();

        return Results.SUCCESS();
    }
}
