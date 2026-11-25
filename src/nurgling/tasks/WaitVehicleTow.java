package nurgling.tasks;

import haven.Gob;
import nurgling.NUtils;
import nurgling.tools.VehicleMarker;

/**
 * Waits for a right-clicked cart to reach a tow state: towed ({@code wantTowed}) or parked.
 *
 * <p>Waits for the tow bit itself, not for "the marker changed": on a loaded cart the marker also
 * changes when cargo comes off. Bounded by counting its own checks and returning normally, so a
 * missed click is a retryable miss rather than an NTask timeout. Callers read {@link #reached()}.
 */
public class WaitVehicleTow extends NTask {
    private static final int GIVE_UP_AFTER = 150;

    private final long gobid;
    private final boolean wantTowed;
    private int checks = 0;
    private boolean reached = false;

    public WaitVehicleTow(Gob vehicle, boolean wantTowed) {
        this.gobid = (vehicle == null) ? -1 : vehicle.id;
        this.wantTowed = wantTowed;
        this.infinite = true;
    }

    @Override
    public boolean check() {
        if (gobid < 0 || ++checks >= GIVE_UP_AFTER)
            return true;
        Gob vehicle = NUtils.getGameUI().ui.sess.glob.oc.getgob(gobid);
        if (vehicle == null)
            return true;
        VehicleMarker.Tow state = VehicleMarker.towState(vehicle);
        if (state == VehicleMarker.Tow.UNKNOWN)
            return false;
        if ((state == VehicleMarker.Tow.TOWED) == wantTowed) {
            reached = true;
            return true;
        }
        return false;
    }

    public boolean reached() {
        return reached;
    }
}
