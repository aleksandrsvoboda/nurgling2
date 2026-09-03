package nurgling.actions.bots;

import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.GateDetector;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.Map;

/**
 * Opens or closes the nearest gate, per the step's configured mode - never blindly toggles.
 * Meant to be attached as two separate Forager waypoint steps (see WaypointStepsWindow), one set
 * to "Open" right before a gated stretch of a route and one set to "Close" right after - the
 * route's own normal PathFinder walk to the next waypoint handles actually walking through it,
 * so this bot only ever needs to do the interact, not track "pending close" state across a whole
 * run the way ChunkNavExecutor's always-on gate handling does.
 * <p>
 * If the gate is already in the mode's target state - most notably, already open because another
 * player opened it - this is a no-op success rather than an interact. The old toggle-only
 * behavior would slam a gate shut that someone else had just opened and was using (reported
 * live), since it had no way to tell "closed, needs opening" apart from "already open, leave it".
 */
public class GateBot implements Action {

    private static final double DETECT_RADIUS = MCache.tilesz.x * 3;
    private static final double INTERACT_RADIUS = MCache.tilesz.x * 1.2;
    private static final long STATE_POLL_TIMEOUT_MS = 3000;

    private final boolean wantOpen;

    public GateBot() {
        this.wantOpen = true;
    }

    public GateBot(Map<String, Object> settings) {
        Object v = settings != null ? settings.get("mode") : null;
        this.wantOpen = !"close".equals(v);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return Results.ERROR("Player not found.");

        Gob gate = Finder.findGob(player.rc, new NAlias(GateDetector.GATE_NAMES), null, DETECT_RADIUS);
        if (gate == null)
            return Results.ERROR("GateBot: no gate found nearby.");

        if (GateDetector.isDoorOpen(gate) == wantOpen) {
            return Results.SUCCESS();
        }

        if (player.rc.dist(gate.rc) > INTERACT_RADIUS) {
            new PathFinder(gate).run(gui);
            player = NUtils.player();
            if (player == null || player.rc.dist(gate.rc) > INTERACT_RADIUS)
                return Results.ERROR("GateBot: couldn't get close enough to the gate.");
        }

        NUtils.rclickGob(gate);
        boolean changed = waitForGateState(gate, wantOpen, STATE_POLL_TIMEOUT_MS);
        if (!changed)
            return Results.ERROR("GateBot: gate didn't " + (wantOpen ? "open" : "close") + " in time.");

        return Results.SUCCESS();
    }

    private boolean waitForGateState(Gob gate, boolean wantOpen, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (gate.ngob != null && GateDetector.isDoorOpen(gate) == wantOpen)
                return true;
            Thread.sleep(100);
        }
        return gate.ngob != null && GateDetector.isDoorOpen(gate) == wantOpen;
    }
}
