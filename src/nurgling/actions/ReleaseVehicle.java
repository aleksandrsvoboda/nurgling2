package nurgling.actions;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitVehicleReleased;
import nurgling.tools.VehicleMarker;

/**
 * Unties a towed vehicle, leaving it parked where it stands.
 *
 * <p>Counterpart to {@link TakeVehicle}. Also clears this session's towed-vehicle record, so the
 * pathfinder goes back to treating the vehicle as the obstacle it now is.
 */
public class ReleaseVehicle implements Action {
    private final Gob vehicle;

    public ReleaseVehicle(Gob vehicle) {
        this.vehicle = vehicle;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (vehicle == null)
            return Results.ERROR("ReleaseVehicle: no vehicle");

        if (!VehicleMarker.isTowed(vehicle)) {
            forget();
            return Results.SUCCESS();
        }

        WaitVehicleReleased released = new WaitVehicleReleased(vehicle);
        NUtils.rclickGob(vehicle);
        NUtils.addTask(released);

        if (!released.released())
            return Results.FAIL();
        forget();
        return Results.SUCCESS();
    }

    private static void forget() {
        if (NUtils.getUI() != null && NUtils.getUI().core != null)
            NUtils.getUI().core.towedVehicle.release();
    }
}
