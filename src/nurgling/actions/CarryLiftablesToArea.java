package nurgling.actions;

import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.cart.CartHauler;
import nurgling.areas.NArea;
import nurgling.i18n.L10n;
import nurgling.tools.Finder;

import java.util.ArrayList;

/**
 * Moves every liftable object a {@link Source} offers into a target area.
 *
 * <p>By hand (no cart, the default) this is the loop TransferLiftable and Chopper had inline, moved
 * here unchanged: nearest reachable object, lift, place in the target area, step 2 units off it, go
 * back to the source, repeat.
 *
 * <p>With a {@link CartHauler} it fills the cart six at a time and tows it over instead. Any trouble with
 * the cart (a destination it can't get to, a cart that won't take the object, losing it too often) empties
 * whatever is held or loaded into the target, puts the cart back, and finishes by hand.
 *
 * <p>{@code flush=false} keeps a partly filled cart parked at the source for the next call (Chopper calls
 * this after every tree); only a full cart is hauled. {@code flush=true} hauls whatever is loaded and
 * then returns the cart to where it was clicked.
 */
public class CarryLiftablesToArea implements Action {

    public interface Source {
        /** The objects still to move; re-read every pick. */
        ArrayList<Gob> candidates() throws InterruptedException;

        /** Gets back to the source after a delivery. */
        void goBack() throws InterruptedException;
    }

    private final Source source;
    private final NArea target;
    private final int spread;
    private final String unreachableMsg;
    private final CartHauler hauler;
    private final boolean flush;

    public CarryLiftablesToArea(Source source, NArea target, int spread, String unreachableMsg) {
        this(source, target, spread, unreachableMsg, null, true);
    }

    public CarryLiftablesToArea(Source source, NArea target, int spread, String unreachableMsg,
                                CartHauler hauler, boolean flush) {
        this.source = source;
        this.target = target;
        this.spread = spread;
        this.unreachableMsg = unreachableMsg;
        this.hauler = hauler;
        this.flush = flush;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (hauler != null && !hauler.gaveUp()) {
            Results r = runWithCart(gui);
            if (r != null)
                return r;
        }
        return runByHand(gui);
    }

    private Results runByHand(NGameUI gui) throws InterruptedException {
        ArrayList<Gob> items;
        while (!(items = source.candidates()).isEmpty())
        {
            ArrayList<Gob> availableItems = new ArrayList<>();
            for (Gob currGob : items)
            {
                if (PathFinder.isAvailable(currGob))
                    availableItems.add(currGob);
            }
            if (availableItems.isEmpty())
            {
                NUtils.getGameUI().msg(unreachableMsg);
                break;
            }

            availableItems.sort(NUtils.d_comp);
            Gob item = availableItems.get(0);

            // Lift the item
            new LiftObject(item).run(gui);

            // Move to output area and place the item. FindPlaceAndAction walks
            // onto the area first (it has the NArea) so the whole zone is loaded
            // before a drop cell is chosen.
            new FindPlaceAndAction(null, target, spread).run(gui);

            // Move away from the placed item
            Coord2d shift = item.rc.sub(NUtils.player().rc).norm().mul(2);
            new GoTo(NUtils.player().rc.sub(shift)).run(gui);
            source.goBack();
        }
        return Results.SUCCESS();
    }

    /** Null means "the cart is out of the picture now, carry the rest by hand". */
    private Results runWithCart(NGameUI gui) throws InterruptedException {
        while (true) {
            // Fill the cart.
            while (!hauler.isFull()) {
                ArrayList<Gob> items = source.candidates();
                if (items.isEmpty())
                    break;
                ArrayList<Gob> available = new ArrayList<>();
                for (Gob g : items) {
                    if (PathFinder.isAvailable(g))
                        available.add(g);
                }
                if (available.isEmpty()) {
                    gui.msg(unreachableMsg);
                    break;
                }
                available.sort(NUtils.d_comp);
                if (!hauler.load(available.get(0))) {
                    if (!hauler.gaveUp())
                        gui.msg(L10n.get("cart.tie_failed"));
                    dropCart(gui);
                    return null;
                }
            }

            int loaded = hauler.cargo();
            if (loaded == 0)
                break;
            boolean exhausted = !hauler.isFull();
            if (exhausted && !flush)
                break;

            // Deliver.
            CartHauler.Outcome o = hauler.towTo(target);
            if (o != CartHauler.Outcome.OK) {
                dropCart(gui);
                return null;
            }
            if (!hauler.unloadInto(target, spread)) {
                hauler.finish();
                return Results.ERROR("No free place in the output area");
            }
            if (exhausted)
                break;

            // Back for the next load.
            if (!hauler.ensureTied()) {
                gui.msg(L10n.get("cart.tie_failed"));
                return null;
            }
            source.goBack();
        }

        if (flush)
            hauler.finish();
        else
            hauler.park();
        return Results.SUCCESS();
    }

    /**
     * Gives up on the cart for this run without losing anything: whatever is held is placed in the
     * target by hand, the cart's cargo is emptied there if the cart can still get there, and the cart
     * goes back to where it was clicked.
     */
    private void dropCart(NGameUI gui) throws InterruptedException {
        // Placed before anything is done to the cart: clicking a cart while holding something loads it.
        if (Finder.findLiftedbyPlayer() != null)
            new FindPlaceAndAction(null, target, spread).run(gui);
        if (hauler.cargo() > 0 && !hauler.gaveUp()
                && hauler.towTo(target) == CartHauler.Outcome.OK)
            hauler.unloadInto(target, spread);
        hauler.finish();
    }
}
