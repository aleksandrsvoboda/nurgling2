package nurgling.actions;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.MenuGrid;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitForGridChangeOrTimeout;
import nurgling.tasks.WaitPlayerNotNull;
import nurgling.tasks.WaitProgress;

public class TravelToHearthFire implements Action {

    // Comfortably above the hearth-fire channel duration, so this is only ever hit for the
    // "teleported but stayed in the same grid" case (hearth close enough that travel didn't
    // cross a grid boundary) - see WaitForGridChangeOrTimeout.
    private static final long TRAVEL_TIMEOUT_MS = 20_000;

    // Same two-phase "hourglass" wait CoracleBot/WorkBellows already use for a single click that
    // starts a server-timed action - see WaitProgress's own javadoc. Bounds below match
    // CoracleBot's: generous on the start side (the server may still be settling the character
    // before the channel can begin), generous on the finish side (the actual channel duration).
    private static final long PROGRESS_START_TIMEOUT_MS = 10_000;
    private static final long PROGRESS_FINISH_TIMEOUT_MS = 30_000;

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        // Snapshot the grid we're leaving from before triggering travel, so completion can be
        // detected by an actual grid change rather than assuming the destination area's render
        // state (which can already be true beforehand, e.g. traveling to a nearby, already-
        // loaded surface hearth) or a fixed pose transition alone.
        long beforeGridId = -1;
        Gob player = NUtils.player();
        if (player != null && player.rc != null) {
            Coord2d rc = player.rc;
            Coord tc = rc.div(MCache.tilesz).floor();
            Coord gc = tc.div(gui.ui.sess.glob.map.cmaps);
            if (gui.ui.sess.glob.map.grids.get(gc) != null) {
                beforeGridId = gui.ui.sess.glob.map.getgridt(tc).id;
            }
        }

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

        // This is what actually confirms the server started the teleport channel, rather than
        // guessing from client-side pose - a pose transition can be spoofed/skipped (e.g. the
        // character was already idle, or mid-movement from something else like ChunkNav, right
        // as the click landed), which previously let this return SUCCESS() with no real teleport
        // having happened at all (reported live: a safety-guard "travel hearth" outcome fired
        // while ChunkNav was mid-navigation, and the bot moved straight on to its next scheduled
        // run without ever actually going home). gui.prog only exists because the server put it
        // there in response to this exact click - see WaitProgress's own javadoc, and
        // CoracleBot's identical use of this pattern for the same "one click starts a timed
        // action" shape.
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
