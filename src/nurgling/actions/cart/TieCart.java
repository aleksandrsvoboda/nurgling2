package nurgling.actions.cart;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitVehicleTow;
import nurgling.tools.VehicleMarker;

/**
 * Walks up to a parked cart and ties it on.
 *
 * <p>Right-click toggles the tie, so it clicks only on a positive PARKED and waits for the tow bit
 * itself. Retried: a click sent while the server still has the character busy is simply ignored.
 * Cart-only; {@code TakeVehicle} (the plow) is untouched.
 */
public class TieCart implements Action {
    private static final int CLICK_ATTEMPTS = 3;
    private final long cartId;

    public TieCart(Gob cart) {
        this.cartId = cart.id;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
        if (cart == null)
            return Results.ERROR("Cart not in view");
        if (VehicleMarker.isTowed(cart))
            return Results.SUCCESS();

        new PathFinder(cart).run(gui);

        for (int attempt = 0; attempt < CLICK_ATTEMPTS; attempt++) {
            cart = gui.ui.sess.glob.oc.getgob(cartId);
            if (cart == null)
                return Results.ERROR("Cart not in view");
            VehicleMarker.Tow state = VehicleMarker.towState(cart);
            if (state == VehicleMarker.Tow.TOWED)
                return Results.SUCCESS();
            if (state == VehicleMarker.Tow.UNKNOWN) {
                NUtils.addTask(new MarkerKnown(gui, cartId));
                continue;
            }
            WaitVehicleTow tied = new WaitVehicleTow(cart, true);
            NUtils.rclickGob(cart);
            NUtils.addTask(tied);
            if (tied.reached())
                return Results.SUCCESS();
        }
        return Results.FAIL();
    }

    /** Waits (bounded) for a gob's marker to become readable. */
    static final class MarkerKnown extends NTask {
        private final NGameUI gui;
        private final long id;
        private int checks = 0;

        MarkerKnown(NGameUI gui, long id) {
            this.gui = gui;
            this.id = id;
        }

        @Override
        public boolean check() {
            if (++checks >= 100)
                return true;
            Gob g = gui.ui.sess.glob.oc.getgob(id);
            return g == null || VehicleMarker.towState(g) != VehicleMarker.Tow.UNKNOWN;
        }
    }
}
