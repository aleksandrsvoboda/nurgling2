package nurgling.tasks;

import haven.Gob;
import nurgling.tools.Finder;
import nurgling.tools.VehicleMarker;

/**
 * Waits for a right-clicked vehicle to come off tow.
 *
 * <p>Mirror of {@link WaitVehicleTied}: the marker changing is the generic signal, which keeps this
 * correct for vehicle types whose bit layout has not been measured, while the tow bit clearing is
 * the precise one for carts.
 */
public class WaitVehicleReleased extends NTask {
    private final long gobid;
    private boolean released = false;

    private static final int GIVE_UP_AFTER = 150;
    private int checks = 0;

    public WaitVehicleReleased(Gob vehicle) {
        this.gobid = (vehicle == null) ? -1 : vehicle.id;
        // Counts itself out rather than tripping NTask's timeout, which would log
        // "Incorrect final of task" for an ordinary retryable miss.
        this.infinite = true;
    }

    @Override
    public boolean check() {
        if (gobid < 0)
            return true;
        if (++checks >= GIVE_UP_AFTER)
            return true;
        Gob vehicle = Finder.findGob(gobid);
        if (vehicle == null)
            return true;
        long now = VehicleMarker.markerOf(vehicle);
        if (!VehicleMarker.known(now))
            return false;
        // Tow bit clear, specifically. "Changed" would also fire on cargo coming off.
        if ((now & VehicleMarker.MASK_TOWED) == 0) {
            released = true;
            return true;
        }
        return false;
    }

    public boolean released() {
        return released;
    }
}
