package nurgling.tasks;

import haven.Gob;
import nurgling.NUtils;
import nurgling.tools.Finder;
import nurgling.tools.VehicleMarker;

/**
 * Waits for a right-clicked vehicle to actually come under tow.
 *
 * <p>The authoritative signal is the vehicle's model marker changing: a cart flips from
 * {@code parked} to {@code towed} in the low bits of its sdt the moment it ties on. Watching for a
 * <em>change</em> rather than a specific value keeps this correct for plows and wheelbarrows too,
 * whose bit layout has not been measured.
 *
 * <p>The character's {@code borka/carry} pose is kept as a second, independent signal. It is not
 * redundant: a cart never produces that pose at all (measured across a full towing session), so
 * the pose alone &mdash; which is what this replaced &mdash; could only ever finish by timing out.
 * For vehicles that do produce it, it stays a valid early exit.
 *
 * <p>Bounded, but it counts itself out and returns normally rather than letting NTask's timeout
 * fire: a timeout sets {@code criticalExit}, which makes NCore log "Incorrect final of task" for
 * what is an ordinary retryable miss. Callers check {@link #tied()} rather than assuming success.
 */
public class WaitVehicleTied extends NTask {
    private final long gobid;
    private final long before;
    private boolean markerChanged = false;
    private boolean carryPose = false;

    private static final int GIVE_UP_AFTER = 150;
    private int checks = 0;

    public WaitVehicleTied(Gob vehicle) {
        this.gobid = (vehicle == null) ? -1 : vehicle.id;
        this.before = VehicleMarker.markerOf(vehicle);
        this.infinite = true;
    }

    @Override
    public boolean check() {
        if (gobid < 0)
            return true;
        if (++checks >= GIVE_UP_AFTER)
            return true;

        Gob vehicle = Finder.findGob(gobid);
        if (vehicle != null) {
            long now = VehicleMarker.markerOf(vehicle);
            // known(), not >= 0: markers past 127 arrive sign-extended negative, so a loaded
            // cart (253 parked / 254 towed) would otherwise never register as having changed.
            if (VehicleMarker.known(now) && now != before) {
                markerChanged = true;
                return true;
            }
        }

        Gob player = NUtils.player();
        String pose = (player == null) ? null : player.pose();
        if (pose != null && pose.contains("borka/carry")) {
            carryPose = true;
            return true;
        }
        return false;
    }

    /** True when either signal fired; false means the wait ran out and the vehicle is not tied. */
    public boolean tied() {
        return markerChanged || carryPose;
    }

    /** Which signal settled it -- useful when confirming the marker layout for a new vehicle type. */
    public boolean byMarker() {
        return markerChanged;
    }

    /** The marker this started from, for diagnostics when a tie does not take. */
    public long before() {
        return before;
    }
}
