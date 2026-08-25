package nurgling.tasks;

import haven.Gob;
import nurgling.NUtils;
import nurgling.tools.Finder;
import nurgling.tools.VehicleMarker;

/**
 * Waits for a right-clicked vehicle to actually come under tow.
 *
 * <p>The authoritative signal is the vehicle's model marker: bit 1 is set the moment it ties on.
 * This waits for that bit specifically rather than for the marker to change at all — a right-click
 * toggles the tow and, on a loaded cart, can pull cargo off instead, so "something changed" is
 * equally consistent with success and with having just undone it.
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
    private boolean byMarker = false;
    private boolean carryPose = false;

    private static final int GIVE_UP_AFTER = 150;
    private int checks = 0;

    public WaitVehicleTied(Gob vehicle) {
        this.gobid = (vehicle == null) ? -1 : vehicle.id;
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
            // The tow bit must be SET. Merely "the marker changed" is not good enough: right-click
            // is a toggle, and on a loaded cart it can also pull cargo off, so a change is equally
            // consistent with having just untied the thing. Treating that as success made
            // TakeVehicle report a tie it had actually just undone, and the bot walked off alone.
            // known() first: markers past 127 arrive sign-extended negative (253 parked, 254 towed).
            if (VehicleMarker.known(now) && (now & VehicleMarker.MASK_TOWED) != 0) {
                byMarker = true;
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
        return byMarker || carryPose;
    }


}
