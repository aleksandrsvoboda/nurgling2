package nurgling.actions.bots.boatfishing;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NBoatFishingSettings;
import nurgling.i18n.L10n;
import nurgling.tasks.GetCurs;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.NAlias;
import nurgling.tools.VSpec;

import java.util.ArrayList;
import java.util.List;

/** Boat Fisher (bot id "boatfisher"): fishes from a boat without ever leaving it. Keeps the rod rigged from the creels
 *  and the inventory, lets the game put the catch in the creels or the inventory, and stops when both are full.
 *  Written beside the land Fishing bot, which stays as it is. */
public class BoatFisher implements Action {
    /** Stops in a row without catching anything before the bot gives up on the spot. */
    private static final int MAX_STRIKES = 3;
    private static final long CAST_SETTLE_MS = 10_000;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.BoatFishing w = null;
        NBoatFishingSettings prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(NUtils.getGameUI().add((w = new nurgling.widgets.bots.BoatFishing()), UI.scale(200, 200))));
            prop = w.prop;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null)
            return Results.ERROR("No config");
        if (prop.tool == null || (prop.fishline == null && !prop.anyLine) || (prop.hook == null && !prop.anyHook) || (prop.bait == null && !prop.anyBait))
            return Results.ERROR(L10n.get("boatfishing.err_settings"));
        if (Boat.aboard(NUtils.player()) == null)
            return Results.ERROR(L10n.get("boatfishing.err_no_boat"));

        NContext context = new NContext(gui);
        String areaId = context.createArea(L10n.get("boatfishing.select_area"), Resource.loadsimg("baubles/fishingPlace"));
        NArea area = context.getCreatedArea(areaId);
        Pair<Coord2d, Coord2d> rc = area == null ? null : area.getRCArea();
        if (rc == null)
            return Results.ERROR(L10n.get("boatfishing.err_area"));

        try {
            return new Trip(gui, prop, rc).fish();
        } finally {
            if (gui.vhand != null)
                Tackle.emptyHand(null);
        }
    }

    /** One run's state; the bot menu reuses the Action instance. */
    private static final class Trip {
        final NGameUI gui;
        final NBoatFishingSettings prop;
        final Pair<Coord2d, Coord2d> area;
        final NAlias fish = VSpec.getAllFish();

        Coord2d aim;
        String restPose;
        boolean poseKnown;
        Window seenWindow;
        int strikes = 0;

        Trip(NGameUI gui, NBoatFishingSettings prop, Pair<Coord2d, Coord2d> area) {
            this.gui = gui;
            this.prop = prop;
            this.area = area;
        }

        Results fish() throws InterruptedException {
            Results r = new RigRod(prop).run(gui);
            if (!r.IsSuccess())
                return r;
            r = cast();
            if (!r.IsSuccess())
                return r;

            while (true) {
                BoatFishingTask t = new BoatFishingTask(prop, restPose, poseKnown, seenWindow, fish);
                NUtils.addTask(t);
                if (t.caught)
                    strikes = 0;
                System.out.println("[BoatFisher] event " + t.state
                        + (t.missing != null ? " missing=" + t.missing : "")
                        + (t.error != null ? " error=" + t.error : ""));
                switch (t.state) {
                    case LEFT_BOAT:
                        return Results.ERROR(L10n.get("boatfishing.err_left_boat"));
                    case NO_ROOM:
                        gui.msg(L10n.get("boatfishing.full"));
                        return Results.SUCCESS();
                    case ERROR:
                        if (t.error.contains("around"))
                            return Results.ERROR(t.error);
                        r = recastAfterStop();
                        break;
                    case BITE_WINDOW:
                        r = handleBite();
                        break;
                    case PART_MISSING:
                        r = new RigRod(prop).run(gui);
                        if (r.IsSuccess())
                            r = cast();
                        break;
                    case STOPPED:
                    case IDLE:
                        r = recastAfterStop();
                        break;
                    default:
                        r = Results.SUCCESS();
                }
                if (!r.IsSuccess())
                    return r;
            }
        }

        Results recastAfterStop() throws InterruptedException {
            if (++strikes > MAX_STRIKES)
                return Results.ERROR(L10n.get("boatfishing.err_no_catch"));
            Results r = new RigRod(prop).run(gui);
            return r.IsSuccess() ? cast() : r;
        }

        /** Aim for a target (or the best fish), or recast when none of the targets is on offer. */
        Results handleBite() throws InterruptedException {
            Window w = BiteWindow.find();
            if (w == null)
                return Results.SUCCESS();
            List<BiteWindow.Row> rows = BiteWindow.rows(w);
            List<String> names = new ArrayList<>();
            List<Double> chances = new ArrayList<>();
            for (BiteWindow.Row row : rows) {
                names.add(row.fish);
                chances.add(row.overall);
            }
            int pick = BiteWindow.choose(names, chances, prop.targets, prop.goForBest);
            System.out.println("[BoatFisher] bite window " + rows + " -> " + (pick >= 0 ? rows.get(pick) : "recast"));
            if (pick < 0 || rows.get(pick).aim == null) {
                seenWindow = w;
                return cast();
            }
            rows.get(pick).aim.click();
            seenWindow = w;
            Waits.until(() -> BiteWindow.find() != w, 5_000);
            strikes = 0;
            return Results.SUCCESS();
        }

        /** Gets the area in reach (rowing if needed and possible), picks up the Fish action and clicks the water. */
        Results cast() throws InterruptedException {
            Gob player = NUtils.player();
            if (player == null || Boat.aboard(player) == null)
                return Results.ERROR(L10n.get("boatfishing.err_left_boat"));
            aim = CastGeometry.aim(player.rc, area.a, area.b);
            if (!CastGeometry.inRange(player.rc, aim)) {
                if (!Boat.steering(player))
                    return Results.ERROR(L10n.get("boatfishing.err_out_of_range"));
                if (!Boat.rowCloser(gui, aim))
                    return Results.ERROR(L10n.get("boatfishing.err_row"));
                player = NUtils.player();
                aim = CastGeometry.aim(player.rc, area.a, area.b);
            }

            MenuGrid.Pagina fishAct = null;
            for (MenuGrid.Pagina pag : gui.menu.paginae) {
                if (pag.button() != null && pag.button().name().equals("Fish")) {
                    fishAct = pag;
                    break;
                }
            }
            if (fishAct == null)
                return Results.ERROR(L10n.get("boatfishing.err_no_action"));

            // The seated pose before the first cast; later casts can start while the fishing pose is still showing.
            if (restPose == null)
                restPose = player.pose();
            NUtils.getUI().dropLastError();
            fishAct.button().use(new MenuGrid.Interaction(1, 0));
            GetCurs curs = new GetCurs("fish");
            if (Waits.until(curs::check, 5_000).timedOut)
                return Results.ERROR(L10n.get("boatfishing.err_no_action")); // never click the water without the fish cursor: it would steer
            NUtils.lclick(aim);

            Waits.until(() -> {
                Gob p = NUtils.player();
                String pose = p == null ? null : p.pose();
                return (pose != null && !pose.equals(restPose)) || (BiteWindow.find() != null && BiteWindow.find() != seenWindow);
            }, CAST_SETTLE_MS);
            Gob p = NUtils.player();
            String pose = p == null ? null : p.pose();
            poseKnown = pose != null && !pose.equals(restPose);
            System.out.println("[BoatFisher] cast at " + aim + " from " + (p == null ? "?" : p.rc)
                    + ", rest pose " + restPose + ", fishing pose " + (poseKnown ? pose : "(no change)"));
            return Results.SUCCESS();
        }
    }
}
