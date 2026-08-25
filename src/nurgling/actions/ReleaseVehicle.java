package nurgling.actions;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitVehicleReleased;
import nurgling.tools.VehicleMarker;

/**
 * Unties a towed vehicle, leaving it parked where it stands.
 *
 * <p>Counterpart to {@link TakeVehicle}.
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

        // Only a positive TOWED gets a click: on UNKNOWN we would be toggling blind.
        if (VehicleMarker.towState(vehicle) != VehicleMarker.Tow.TOWED)
            return Results.SUCCESS();

        WaitVehicleReleased released = new WaitVehicleReleased(vehicle);
        NUtils.rclickGob(vehicle);
        NUtils.addTask(released);

        return released.released() ? Results.SUCCESS() : Results.FAIL();
    }

}
