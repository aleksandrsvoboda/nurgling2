package nurgling.actions.bots;

import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.GateDetector;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.Map;

/** Opens or closes the nearest gate per the step's configured mode; a no-op if it's already in that state. */
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                return System.currentTimeMillis() > deadline || (gate.ngob != null && GateDetector.isDoorOpen(gate) == wantOpen);
            }
        });
        return gate.ngob != null && GateDetector.isDoorOpen(gate) == wantOpen;
    }
}
