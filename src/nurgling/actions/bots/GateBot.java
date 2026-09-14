package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NHitBox;
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
    private static final long STATE_POLL_TIMEOUT_MS = 5000;
    // Where closing stands: half a tile clear of the gate's footprint, right in front of its middle - within reach of it.
    private static final double STAND_OFF = MCache.tilesz.x / 2;

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

        String mode = wantOpen ? "open" : "close";
        Gob gate = Finder.findGob(player.rc, new NAlias(GateDetector.GATE_NAMES), null, DETECT_RADIUS);
        if (gate == null) {
            System.out.println("[GateBot] " + mode + ": no gate within " + DETECT_RADIUS + " of " + player.rc);
            return Results.ERROR("GateBot: no gate found nearby.");
        }
        System.out.println(String.format("[GateBot] %s %s#%d: state=%d, player %.0f from its centre",
                mode, gate.ngob.name, gate.id, gate.ngob.getModelAttribute(), player.rc.dist(gate.rc)));

        if (GateDetector.isDoorOpen(gate) == wantOpen) {
            return Results.SUCCESS();
        }

        if (!wantOpen) {
            // An open gate is empty space to the pathfinder, so walking "to" it ends in the gateway - in the way of the very gate
            // being closed. Stand just in front of it instead, on the side the player is already on: the walk never needs the
            // gateway, and steps out of it if the player is standing in it.
            Coord2d front = frontOf(gate, player.rc);
            if (!new PathFinder(front).run(gui).IsSuccess()) {
                System.out.println("[GateBot] close: couldn't reach the spot in front of the gate " + front);
                return Results.ERROR("GateBot: couldn't get close enough to the gate.");
            }
        } else if (player.rc.dist(gate.rc) > INTERACT_RADIUS && !new PathFinder(gate).run(gui).IsSuccess()) {
            // PathFinder's own success means standing beside the gate's hitbox - a big gate is ~3 tiles long, so its centre
            // can be further than INTERACT_RADIUS from a perfectly good spot next to it.
            return Results.ERROR("GateBot: couldn't get close enough to the gate.");
        }

        NUtils.rclickGob(gate);
        boolean changed = waitForGateState(gate, wantOpen, STATE_POLL_TIMEOUT_MS);
        System.out.println(String.format("[GateBot] %s %s#%d: state after click=%d (%s)",
                mode, gate.ngob.name, gate.id, gate.ngob.getModelAttribute(), changed ? "done" : "timed out"));
        if (!changed)
            return Results.ERROR("GateBot: gate didn't " + mode + " in time.");

        return Results.SUCCESS();
    }

    /** A spot in front of the middle of the gate, STAND_OFF clear of its footprint on `from`'s side - the thin axis of its hitbox,
     *  turned by the gate's angle (walls are tile-aligned, so which way the angle turns doesn't change the axis). */
    private static Coord2d frontOf(Gob gate, Coord2d from) {
        NHitBox hb = gate.ngob.hitBox;
        boolean thinX = hb == null || (hb.end.x - hb.begin.x) <= (hb.end.y - hb.begin.y);
        double halfThin = (hb == null) ? MCache.tilesz.x / 2 : (thinX ? hb.end.x - hb.begin.x : hb.end.y - hb.begin.y) / 2;
        Coord2d axis = (thinX ? new Coord2d(1, 0) : new Coord2d(0, 1)).rot(gate.a);
        double side = Math.signum((from.x - gate.rc.x) * axis.x + (from.y - gate.rc.y) * axis.y);
        if (side == 0)
            side = 1;
        return gate.rc.add(axis.mul(side * (halfThin + STAND_OFF)));
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
