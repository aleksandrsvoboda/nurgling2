package nurgling.actions.cart;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;

/**
 * Puts the object the character is holding into the cart (tied or parked).
 *
 * <p>Like {@code TransferToVehicle}, but the wait is bounded: a cart that won't take the object
 * (full, or a kind it doesn't carry) returns FAIL with the object still held instead of hanging.
 */
public class LoadIntoCart implements Action {
    private final long cartId;

    public LoadIntoCart(Gob cart) {
        this.cartId = cart.id;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob held = Finder.findLiftedbyPlayer();
        if (held == null)
            return Results.ERROR("Nothing held");
        Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
        if (cart == null)
            return Results.ERROR("Cart not in view");
        new PathFinder(cart).run(gui);
        cart = gui.ui.sess.glob.oc.getgob(cartId);
        if (cart == null)
            return Results.ERROR("Cart not in view");
        final long heldId = held.id;
        NUtils.rclickGob(cart);
        NUtils.addTask(new NTask() {
            int checks = 0;

            @Override
            public boolean check() {
                return ++checks >= 150 || gui.ui.sess.glob.oc.getgob(heldId) == null;
            }
        });
        return (gui.ui.sess.glob.oc.getgob(heldId) == null) ? Results.SUCCESS() : Results.FAIL();
    }
}
