package nurgling.tasks;

import haven.Composite;
import haven.Drawable;
import haven.Gob;
import nurgling.NUtils;
import nurgling.tools.Finder;
import nurgling.tools.NParser;
import nurgling.tools.VehicleMarker;

/**
 * Arrival check for a leg walked while towing a vehicle, which also ends the leg the moment the
 * vehicle comes off.
 *
 * <p>The vehicle's model marker is the authority: bit 1 is set while it is under tow and clears the
 * instant the server gives up on it. Nothing else is consulted, because nothing else needs to be —
 * a cart only unties when it snags, so the marker cannot report a problem that is not real.
 *
 * <p>Earlier versions tried to <em>predict</em> the snag from separation and vehicle speed and stop
 * before the rope broke. That is attractive — the deflection is visible 300-800 ms ahead — but the
 * thresholds are delicate: healthy towing separation reaches 35.98 units and a stationary vehicle
 * is normal out to 27.75, so a plausible-looking limit aborts every leg two steps in. A false
 * positive costs a whole leg; late detection costs one walk back, and the caller re-ties anyway.
 * Prediction can come back later if unties prove frequent enough to be worth the risk, and there is
 * now telemetry to tune it against.
 */
public class MovingCompletedTowed extends NTask {

    private final long vehicleId;
    private boolean untied = false;
    private int count = 0;

    public MovingCompletedTowed(long vehicleId) {
        this.vehicleId = vehicleId;
    }

    @Override
    public boolean check() {
        Gob player = NUtils.player();
        if (player == null)
            return true;
        if (++count >= 1000)
            return true;

        Gob vehicle = (vehicleId >= 0) ? Finder.findGob(vehicleId) : null;
        // known() first: isTowed() is also false for a marker we cannot read yet, and treating
        // "not loaded" as "came off" would reintroduce exactly the false positive this replaced.
        long marker = VehicleMarker.markerOf(vehicle);
        if (vehicle != null && VehicleMarker.known(marker)
                && (marker & VehicleMarker.MASK_TOWED) == 0) {
            untied = true;
            // Stop the pathfinder excluding it: parked, it is an obstacle again.
            if (NUtils.getUI() != null && NUtils.getUI().core != null)
                NUtils.getUI().core.towedVehicle.release();
            return true;
        }

        // Same arrival test MovingCompleted uses: the walk pose is unaffected by towing.
        Drawable drawable = player.getattr(Drawable.class);
        if (drawable instanceof Composite) {
            String pose = ((Composite) drawable).current_pose;
            return pose != null && !NParser.checkName(pose, "borka/walking", "borka/running", "borka/wading");
        }
        return false;
    }

    /** True when the leg ended because the vehicle came off rather than because we arrived. */
    public boolean untied() {
        return untied;
    }
}
