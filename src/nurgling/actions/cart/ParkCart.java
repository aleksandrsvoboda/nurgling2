package nurgling.actions.cart;

import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.GoTo;
import nurgling.actions.Results;
import nurgling.tasks.WaitVehicleTow;
import nurgling.tools.VehicleMarker;

/**
 * Unties a towed cart where it stands.
 *
 * <p>Deliberately does not pathfind to the cart first: a towed cart trails the character, so walking
 * at it means chasing a target that keeps retreating. It is within click range; only when the click
 * doesn't take does it close the distance with a plain step towards it.
 */
public class ParkCart implements Action {
    private final long cartId;

    public ParkCart(Gob cart) {
        this.cartId = cart.id;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
            if (cart == null)
                return Results.ERROR("Cart not in view");
            VehicleMarker.Tow state = VehicleMarker.towState(cart);
            if (state == VehicleMarker.Tow.PARKED)
                return Results.SUCCESS();
            if (state == VehicleMarker.Tow.UNKNOWN) {
                NUtils.addTask(new TieCart.MarkerKnown(gui, cartId));
                continue;
            }
            if (attempt > 0) {
                Gob player = NUtils.player();
                Coord2d toward = cart.rc.sub(player.rc);
                if (toward.abs() > 6)
                    new GoTo(player.rc.add(toward.norm(toward.abs() - 6))).run(gui);
            }
            WaitVehicleTow parked = new WaitVehicleTow(cart, false);
            NUtils.rclickGob(cart);
            NUtils.addTask(parked);
            if (parked.reached())
                return Results.SUCCESS();
        }
        return Results.FAIL();
    }
}
