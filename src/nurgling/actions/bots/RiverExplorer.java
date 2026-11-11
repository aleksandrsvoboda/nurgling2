package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.bots.riverexplorer.Boat;
import nurgling.actions.bots.riverexplorer.Hull;
import nurgling.conf.NRiverExplorerProp;
import nurgling.i18n.L10n;
import nurgling.tasks.WaitCheckable;

/** River Explorer (bot id "riverexplorer"): reveals the map by boat, going up rivers. It picks targets the way Map Explorer does
 *  (the known grid that reveals the most unknown ones for the least travel) but by water - fresh water first, upstream preferred,
 *  then the coast (sea with shallows in it) to find the next river mouth; open sea is crossed, never explored, and very deep
 *  ocean (odeeper) is never entered (RiverFrontier over WaterAtlas). At sea the course keeps to the deep side of the
 *  shallows' edge. Bots running at once spread out (Claims, and the head setting). Each leg into the next grid is planned down the
 *  middle of the river on the live map (Pilot) and sailed without stopping, the way Ocean Explorer steers; reeds in the way
 *  are chopped from the boat. Ocean Explorer (WorldExplorer) is left as it is: only its vessel helpers are used. The sailing
 *  itself is riverexplorer.Boat, which other bots sail with too. */
public class RiverExplorer implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.RiverExplorerWnd w = null;
        NRiverExplorerProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.RiverExplorerWnd()), UI.scale(200, 200))));
            prop = w.prop;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null)
            return Results.ERROR("No config");

        Gob player = NUtils.player();
        Gob vessel = (player == null) ? null : Boat.vessel(player);
        if (vessel == null)
            return Results.ERROR(L10n.get("river.err_no_boat"));
        if (gui.mmap == null || gui.mmap.file == null)
            return Results.ERROR(L10n.get("river.err_no_map"));
        boolean ship = Boat.isShip(vessel);
        if (ship)
            gui.msg(L10n.get("river.msg_ship"));

        Boat boat = new Boat(gui, prop, Hull.of(vessel, ship));
        try {
            return boat.explore();
        } finally {
            boat.close();
        }
    }
}
