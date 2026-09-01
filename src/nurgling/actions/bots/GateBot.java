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
 * Toggles the nearest gate: opens it if closed, closes it if open. Meant to be attached as a
 * Forager waypoint step (see WaypointStepsWindow) at a point right before/after a gated stretch
 * of a route - the route's own normal PathFinder walk to the next waypoint handles actually
 * walking through it, so this bot only ever needs to do the interact, not track "pending close"
 * state across a whole run the way ChunkNavExecutor's always-on gate handling does.
 */
public class GateBot implements Action {

    private static final double DETECT_RADIUS = MCache.tilesz.x * 3;
    private static final double INTERACT_RADIUS = MCache.tilesz.x * 1.2;
    private static final long STATE_POLL_TIMEOUT_MS = 3000;

    private final String gateType;

    public GateBot() {
        this.gateType = "any";
    }

    public GateBot(Map<String, Object> settings) {
        Object v = settings != null ? settings.get("gateType") : null;
        this.gateType = (v instanceof String) ? (String) v : "any";
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null)
            return Results.ERROR("Player not found.");

        NAlias alias = "any".equals(gateType) || !isKnownGateType(gateType)
                ? new NAlias(GateDetector.GATE_NAMES)
                : new NAlias(gateType);

        Gob gate = Finder.findGob(player.rc, alias, null, DETECT_RADIUS);
        if (gate == null)
            return Results.ERROR("GateBot: no gate found nearby.");

        if (player.rc.dist(gate.rc) > INTERACT_RADIUS) {
            new PathFinder(gate).run(gui);
            player = NUtils.player();
            if (player == null || player.rc.dist(gate.rc) > INTERACT_RADIUS)
                return Results.ERROR("GateBot: couldn't get close enough to the gate.");
        }

        boolean wasOpen = GateDetector.isDoorOpen(gate);
        NUtils.rclickGob(gate);
        boolean changed = waitForGateState(gate, !wasOpen, STATE_POLL_TIMEOUT_MS);
        if (!changed)
            return Results.ERROR("GateBot: gate didn't " + (wasOpen ? "close" : "open") + " in time.");

        return Results.SUCCESS();
    }

    private boolean isKnownGateType(String type) {
        for (String name : GateDetector.GATE_NAMES) {
            if (name.equals(type))
                return true;
        }
        return false;
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
