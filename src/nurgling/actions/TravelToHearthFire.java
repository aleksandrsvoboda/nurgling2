package nurgling.actions;

import haven.MenuGrid;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitForGridChangeOrTimeout;
import nurgling.tasks.WaitPlayerNotNull;
import nurgling.tasks.WaitProgress;

public class TravelToHearthFire implements Action {

    // Above the channel duration - only hit if travel stays within the same grid (see WaitForGridChangeOrTimeout).
    private static final long TRAVEL_TIMEOUT_MS = 20_000;

    // Two-phase "hourglass" wait for a single click starting a server-timed action - see WaitProgress.
    private static final long PROGRESS_START_TIMEOUT_MS = 10_000;
    private static final long PROGRESS_FINISH_TIMEOUT_MS = 30_000;

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        // Snapshot the grid we're leaving from, so completion is detected by an actual grid change.
        long beforeGridId = WaitForGridChangeOrTimeout.currentGridId(gui);

        boolean foundButton = false;
        for (MenuGrid.Pagina pag : NUtils.getGameUI().menu.paginae)
        {
            if(pag.button()!=null && pag.button().name().equals("Travel to your Hearth Fire"))
            {
                pag.button().use(new MenuGrid.Interaction(1, 0));
                foundButton = true;
                break;
            }
        }
        if (!foundButton) {
            return Results.ERROR("Travel to Hearth Fire: menu option not found (no hearth bound?)");
        }

        // Confirms the server actually started the channel, rather than trusting a spoofable client-side pose transition.
        WaitProgress started = new WaitProgress(WaitProgress.Phase.START, PROGRESS_START_TIMEOUT_MS);
        NUtils.addTask(started);
        if (started.isTimedOut()) {
            return Results.ERROR("Travel to Hearth Fire: channel never started (click may have missed)");
        }
        NUtils.addTask(new WaitProgress(WaitProgress.Phase.FINISH, PROGRESS_FINISH_TIMEOUT_MS));

        NUtils.getUI().core.addTask(new WaitPlayerNotNull());
        NUtils.getUI().core.addTask(new WaitForGridChangeOrTimeout(gui, beforeGridId, TRAVEL_TIMEOUT_MS));

        return Results.SUCCESS();
    }
}
