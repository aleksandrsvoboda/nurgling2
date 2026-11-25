package nurgling.actions.cart;

import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NGlobalCoord;
import nurgling.i18n.L10n;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.tools.Finder;
import nurgling.tools.VehicleMarker;

/**
 * One bot run's use of one cart: tie it, tow it, load and unload it, and put it back where it was clicked.
 *
 * <p>The tow state the pf stack reads ({@link NGameUI#cartTow}) is set here right after a tie and cleared
 * right before a park, so walks with the cart parked are ordinary walks. Callers must call
 * {@link #detach()} in a {@code finally} so a stopped bot never leaves the session in tow mode.
 */
public class CartHauler {
    /** How many times a run goes back for a cart that came off before giving up on it. */
    private static final int MAX_RECOVERIES = 5;

    public enum Outcome { OK, FAILED }

    private final NGameUI gui;
    private final long cartId;
    /** Where the user clicked the cart: it goes back there at the end. */
    private final NGlobalCoord home;
    private Coord2d lastSeen;
    private int recoveries = 0;
    /** Set once the cart has been put back; later calls carry by hand. */
    private boolean retired = false;
    /** False until the first tie: a cart that was never moved is simply left alone at the end. */
    private boolean everTied = false;

    public CartHauler(NGameUI gui, Gob cart) {
        this.gui = gui;
        this.cartId = cart.id;
        this.home = new NGlobalCoord(cart.rc);
        this.lastSeen = cart.rc;
    }

    /** The cart if it is in the object cache. */
    public Gob cart() {
        Gob g = gui.ui.sess.glob.oc.getgob(cartId);
        if (g != null)
            lastSeen = g.rc;
        return g;
    }

    /** Occupied slots, 0 when unknown or out of view. */
    public int cargo() {
        return Math.max(0, VehicleMarker.cargoCount(cart()));
    }

    public boolean isFull() {
        return cargo() >= VehicleMarker.CART_SLOTS;
    }

    public boolean gaveUp() {
        return retired || recoveries > MAX_RECOVERIES;
    }

    /** Clears the tow state without touching the cart. Safe to call from a finally. */
    public void detach() {
        CartTow tow = gui.cartTow;
        if (tow != null && tow.cartId == cartId)
            gui.cartTow = null;
    }

    /** Ties the cart unless already towing it. */
    public boolean ensureTied() throws InterruptedException {
        CartTow tow = gui.cartTow;
        if (tow != null && tow.cartId == cartId && !tow.cameOff(gui))
            return true;
        return tie();
    }

    public boolean tie() throws InterruptedException {
        detach();
        Gob c = findCart();
        if (c == null)
            return false;
        if (!new TieCart(c).run(gui).IsSuccess())
            return false;
        c = cart();
        gui.cartTow = new CartTow(cartId, (c != null) ? c.rc : lastSeen);
        everTied = true;
        return true;
    }

    public boolean park() throws InterruptedException {
        CartTow tow = gui.cartTow;
        if (tow != null && tow.lastSeen != null)
            lastSeen = tow.lastSeen;
        detach();
        Gob c = cart();
        if (c == null)
            return false;
        return new ParkCart(c).run(gui).IsSuccess();
    }

    /** Tows the cart into an area, going back for it whenever it comes off. */
    public Outcome towTo(NArea area) throws InterruptedException {
        while (!gaveUp()) {
            if (!ensureTied())
                return Outcome.FAILED;
            CartTow tow = gui.cartTow;
            tow.lost = false;
            boolean ok = NUtils.navigateToArea(area, true);
            if (tow.lost || tow.cameOff(gui)) {
                if (!recover())
                    return Outcome.FAILED;
                continue;
            }
            return ok ? Outcome.OK : Outcome.FAILED;
        }
        return Outcome.FAILED;
    }

    /**
     * Brings the cart next to an object that is about to be loaded and unties it there: loading happens with
     * the cart parked, so the character never drags it around the objects (that is how it gets wedged).
     * Goes back for the cart if it comes off on the way.
     */
    public boolean bringTo(Gob target) throws InterruptedException {
        while (!gaveUp()) {
            if (!ensureTied())
                return false;
            CartTow tow = gui.cartTow;
            tow.lost = false;
            new PathFinder(target).run(gui);
            if (tow.lost || tow.cameOff(gui)) {
                if (!recover())
                    return false;
                continue;
            }
            return park();
        }
        return false;
    }

    /** How far the parked cart is from a point; MAX_VALUE when it is out of view. */
    public double distanceTo(Coord2d p) {
        Gob c = cart();
        return (c == null) ? Double.MAX_VALUE : c.rc.dist(p);
    }

    /** Whether the cart is tied to the character right now. */
    public boolean isTied() {
        CartTow tow = gui.cartTow;
        return tow != null && tow.cartId == cartId;
    }

    /**
     * Lifts an object and puts it into the cart, which is parked first if it is still tied. False when
     * nothing ended up in the cart; the character may then still be holding the object, which the caller
     * carries by hand.
     */
    public boolean load(Gob item) throws InterruptedException {
        if (isTied() && !park())
            return false;
        new LiftObject(item).run(gui);
        if (Finder.findLiftedbyPlayer() == null)
            return false;
        Gob c = cart();
        if (c == null)
            return false;
        return new LoadIntoCart(c).run(gui).IsSuccess();
    }

    /**
     * Parks the cart and empties it into the area, placing each object on a free cell of the area's
     * rectangle (the area is already loaded around us, so no navigation). Leaves the cart parked.
     * False when the area has no more room or the cart couldn't be emptied.
     */
    public boolean unloadInto(NArea area, int spread) throws InterruptedException {
        park();
        while (cargo() > 0) {
            Gob c = cart();
            if (c == null)
                return false;
            new TakeFromVehicle(c).run(gui);
            Gob held = Finder.findLiftedbyPlayer();
            if (held == null)
                return false;
            if (area.getRCArea() == null || !new FindPlaceAndAction(held, area.getRCArea(), spread).run(gui).IsSuccess()) {
                new LoadIntoCart(c).run(gui);
                return false;
            }
            Coord2d shift = held.rc.sub(NUtils.player().rc).norm().mul(2);
            new GoTo(NUtils.player().rc.sub(shift)).run(gui);
        }
        return true;
    }

    /** Tows the cart back to where it was clicked and parks it there. */
    public void finish() throws InterruptedException {
        if (!everTied) {
            retired = true;
            return;
        }
        if (cargo() > 0)
            gui.msg(L10n.get("cart.left_cargo"));
        while (!gaveUp()) {
            if (!ensureTied())
                break;
            CartTow tow = gui.cartTow;
            tow.lost = false;
            towHome();
            if (tow.lost || tow.cameOff(gui)) {
                if (!recover())
                    break;
                continue;
            }
            break;
        }
        park();
        detach();
        retired = true;
    }

    private Outcome towHome() throws InterruptedException {
        Coord2d here = home.getCurrentCoord();
        if (here != null && PathFinder.isAvailable(here))
            return new PathFinder(here).run(gui).IsSuccess() ? Outcome.OK : Outcome.FAILED;
        if (!(gui.map instanceof NMapView))
            return Outcome.FAILED;
        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        if (nav == null || !nav.isInitialized() || home.getGridId() == -1 || home.getLocalTile() == null)
            return Outcome.FAILED;
        ChunkPath path = nav.planToGridCoord(home.getGridId(), home.getLocalTile());
        if (path == null)
            return Outcome.FAILED;
        nav.navigateWithPath(path, null, gui);
        here = home.getCurrentCoord();
        if (here != null && PathFinder.isAvailable(here))
            return new PathFinder(here).run(gui).IsSuccess() ? Outcome.OK : Outcome.FAILED;
        return Outcome.FAILED;
    }

    /**
     * The cart came off: walk back to it, put anything held into it, and tie it again. Counts towards
     * {@link #gaveUp()}.
     */
    private boolean recover() throws InterruptedException {
        CartTow tow = gui.cartTow;
        if (tow != null && tow.lastSeen != null)
            lastSeen = tow.lastSeen;
        detach();
        recoveries++;
        if (gaveUp()) {
            gui.msg(L10n.get("cart.gave_up"));
            return false;
        }
        gui.msg(L10n.get("cart.came_off"));
        Gob c = findCart();
        if (c == null)
            return false;
        if (Finder.findLiftedbyPlayer() != null && !new LoadIntoCart(c).run(gui).IsSuccess())
            return false;
        return tie();
    }

    /** The cart, walking back to where it was last seen if it has dropped out of view. */
    private Gob findCart() throws InterruptedException {
        Gob c = cart();
        if (c == null && lastSeen != null && PathFinder.isAvailable(lastSeen)) {
            new PathFinder(lastSeen).run(gui);
            c = cart();
        }
        return c;
    }
}
