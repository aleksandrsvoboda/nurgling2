package nurgling.actions;

import haven.Gob;

import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.tasks.WaitPose;
import nurgling.tasks.WaitVehicleTied;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VehicleMarker;

public class TakeVehicle implements Action {

    /** The only vehicle whose marker bit layout has actually been measured. */
    private static final NAlias CART = new NAlias("vehicle/cart");
    private static final int CLICK_ATTEMPTS = 3;

    @Override
    public Results run ( NGameUI gui )
            throws InterruptedException {
        if(vehicle==null)
            vehicle = Finder.findGob ( name );
        if(vehicle == null)
            return Results.ERROR("TakeVehicle: no vehicle found");

        // Already under tow. Only trusted for carts -- see VehicleMarker.
        if(isCart(vehicle) && VehicleMarker.isTowed(vehicle))
            return Results.SUCCESS();

        if(!hasCarryPose())
            NUtils.addTask(new WaitPose(NUtils.player(), "idle"));

        if(hasCarryPose())
            return Results.SUCCESS();

        // Wait on the vehicle's marker changing rather than on the character's pose: a cart never
        // adopts borka/carry at all, so the old pose-only wait could only finish by timing out.
        // Retried: a click issued while the server still considers the character busy with the
        // previous action is simply ignored, which is easy to hit right after loading the vehicle.
        for(int attempt = 0; attempt < CLICK_ATTEMPTS; attempt++) {
            // Re-check before every click, and only click on a POSITIVE "parked". Right-click is a
            // toggle, so clicking on an unreadable marker is how a successful tie gets undone.
            VehicleMarker.Tow state = VehicleMarker.towState(vehicle);
            if(state == VehicleMarker.Tow.TOWED)
                return Results.SUCCESS();
            if(state == VehicleMarker.Tow.UNKNOWN) {
                // Marker not resolved yet -- wait for it rather than guessing.
                NUtils.addTask(new WaitVehicleTied(vehicle));
                continue;
            }

            WaitVehicleTied tied = new WaitVehicleTied(vehicle);
            NUtils.rclickGob(vehicle);
            NUtils.addTask(tied);
            if(tied.tied())
                return Results.SUCCESS();
        }

        return Results.FAIL();
    }


    private static boolean isCart(Gob vehicle) {
        return vehicle.ngob != null && NParser.checkName(vehicle.ngob.name, CART);
    }

    private static boolean hasCarryPose() {
        Gob player = NUtils.player();
        String pose = (player == null) ? null : player.pose();
        return pose != null && pose.contains("borka/carry");
    }

    public TakeVehicle(NAlias name ) {
        this.name = name;
    }

    public TakeVehicle(Gob vehicle ) {
        this.vehicle = vehicle;
    }

    NAlias name;
    Gob vehicle = null;
}
