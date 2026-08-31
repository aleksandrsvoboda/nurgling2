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
import nurgling.tasks.WaitPlayerPose;

public class TravelToHearthFire implements Action {

    // Comfortably above the hearth-fire channel duration, so this is only ever hit for the
    // "teleported but stayed in the same grid" case (hearth close enough that travel didn't
    // cross a grid boundary) - see WaitForGridChangeOrTimeout.
    private static final long TRAVEL_TIMEOUT_MS = 20_000;

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

        for (MenuGrid.Pagina pag : NUtils.getGameUI().menu.paginae)
        {
            if(pag.button()!=null && pag.button().name().equals("Travel to your Hearth Fire"))
            {
                pag.button().use(new MenuGrid.Interaction(1, 0));
                break;
            }
        }

        NUtils.getUI().core.addTask(new WaitPlayerPose("gfx/borka/point"));
        NUtils.getUI().core.addTask(new WaitPlayerPose("gfx/borka/idle"));

        NUtils.getUI().core.addTask(new WaitPlayerNotNull());
        NUtils.getUI().core.addTask(new WaitForGridChangeOrTimeout(gui, beforeGridId, TRAVEL_TIMEOUT_MS));

        return Results.SUCCESS();
    }
}
