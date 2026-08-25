package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Resource;
import haven.UI;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NCarrierProp;
import nurgling.tasks.WaitCheckable;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VehicleMarker;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

/**
 * Hauls liftable objects from a chosen zone to the CarrierOut zone using a cart.
 *
 * <p>Same interface as {@link TransferLiftable}: name the object, pick the input zone, and the
 * global {@code carrierout} zone is the destination. The difference is that objects ride a cart
 * six at a time instead of one per round trip on the character's shoulder.
 *
 * <p>Each cycle: tie the cart, walk it to the input zone, park it, fill it, tie it again, walk to
 * the output zone, park it, empty it into the zone, and go back. The cart is deliberately parked
 * while loading and unloading rather than left tied — a tied cart trails the character around the
 * zone as they shuttle objects, which is both slower and the main way carts get snagged and untied.
 *
 * <p>Ends when a cycle finds nothing left to load.
 */
public class CartCarrier implements Action {

    private static final NAlias CART = new NAlias("vehicle/cart");
    private static final int SLOTS = VehicleMarker.CART_CARGO_SLOTS;
    /** How many times to go back for a cart that untied en route before giving up. */
    private static final int HAUL_ATTEMPTS = 3;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NCarrierProp prop;
        nurgling.widgets.bots.Carrier w = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(
                    gui.add((w = new nurgling.widgets.bots.Carrier()), UI.scale(200, 200))));
            prop = w.prop;
        } finally {
            if (w != null)
                w.destroy();
        }
        if (prop == null || prop.object == null || prop.object.isEmpty())
            return Results.ERROR("No object configured");

        NContext context = new NContext(gui);

        NArea outArea = context.findArea(Specialisation.SpecName.carrierout);
        if (outArea == null)
            return Results.ERROR("No CarrierOut zone found! Please create a global zone with 'carrierout' specialization.");

        String inId = context.createArea("Please, select input area", Resource.loadsimg("baubles/inputArea"));
        NArea inArea = context.goToAreaById(inId);
        if (inArea == null)
            return Results.ERROR("No input area selected");

        SelectGob selgob;
        gui.msg("Please select the cart");
        (selgob = new SelectGob(Resource.loadsimg("baubles/inputVeh"))).run(gui);
        if (selgob.result == null)
            return Results.ERROR("No cart selected");
        if (selgob.result.ngob == null || !NParser.checkName(selgob.result.ngob.name, CART))
            return Results.ERROR("Selected object is not a cart");
        final long cartId = selgob.result.id;

        NAlias target = new NAlias(prop.object);
        int cycles = 0;

        while (true) {
            // Fetch the cart to the input zone and park it there.
            if (!haul(gui, cartId, inArea))
                return Results.ERROR("Could not get the cart to the input zone");
            if (!park(gui, cartId))
                return Results.ERROR("Could not untie the cart at the input zone");

            int loaded = load(gui, inArea, target, cartId);
            if (loaded == 0) {
                gui.msg("CartCarrier: nothing left to move after " + cycles + " load(s).");
                break;
            }
            cycles++;
            gui.msg("CartCarrier: loaded " + loaded + ", delivering.");

            if (!haul(gui, cartId, outArea))
                return Results.ERROR("Could not get the loaded cart to the output zone");
            if (!park(gui, cartId))
                return Results.ERROR("Could not untie the cart at the output zone");

            unload(gui, outArea, cartId);
        }

        return Results.SUCCESS();
    }

    /** Fills the cart from the input zone. Returns how many objects went on. */
    private int load(NGameUI gui, NArea inArea, NAlias target, long cartId) throws InterruptedException {
        int loaded = 0;
        while (true) {
            Gob cart = Finder.findGob(cartId);
            if (cart == null) {
                gui.msg("CartCarrier: lost sight of the cart while loading.");
                break;
            }
            if (VehicleMarker.cargoCount(cart, SLOTS) >= SLOTS)
                break;

            ArrayList<Gob> reachable = new ArrayList<>();
            for (Gob candidate : Finder.findGobs(inArea, target)) {
                if (candidate.id != cartId && PathFinder.isAvailable(candidate))
                    reachable.add(candidate);
            }
            if (reachable.isEmpty())
                break;

            reachable.sort(NUtils.d_comp);
            Gob item = reachable.get(0);

            if (!new LiftObject(item).run(gui).IsSuccess())
                break;
            if (!new TransferToVehicle(item, cart).run(gui).IsSuccess())
                break;
            loaded++;
        }
        return loaded;
    }

    /** Empties the cart into the output zone. */
    private void unload(NGameUI gui, NArea outArea, long cartId) throws InterruptedException {
        while (true) {
            Gob cart = Finder.findGob(cartId);
            if (cart == null) {
                gui.msg("CartCarrier: lost sight of the cart while unloading.");
                break;
            }
            if (VehicleMarker.cargoCount(cart, SLOTS) == 0)
                break;
            if (!new TakeFromVehicle(cart).run(gui).IsSuccess())
                break;

            Gob lifted = Finder.findLiftedbyPlayer();
            new FindPlaceAndAction(lifted, outArea).run(gui);
            if (lifted != null) {
                // Step off the object just set down, so the next placement search is not
                // choosing a cell the character is standing in.
                Coord2d shift = lifted.rc.sub(NUtils.player().rc).norm().mul(2);
                new GoTo(NUtils.player().rc.sub(shift)).run(gui);
            }
        }
    }

    /**
     * Tow the cart to a zone, re-tying it if it comes off along the way.
     *
     * <p>A snag unties the cart and the character keeps walking, so without this the bot would
     * arrive alone and then start dragging objects back to wherever the cart was abandoned. The
     * deflection guard in {@link GoTo} should usually stop before the tie breaks, but it cannot
     * catch every case, and losing the load silently is the failure worth spending a check on.
     */
    private boolean haul(NGameUI gui, long cartId, NArea area) throws InterruptedException {
        // Nothing to fetch if the cart is already standing in the destination zone.
        Gob standing = Finder.findGob(cartId);
        if (standing != null && !VehicleMarker.isTowed(standing) && area.checkHit(standing.rc)
                && area.checkHit(NUtils.player().rc))
            return true;

        for (int attempt = 0; attempt < HAUL_ATTEMPTS; attempt++) {
            if (!tie(gui, cartId))
                return false;
            NUtils.navigateToArea(area, true);

            Gob cart = Finder.findGob(cartId);
            if (cart != null && VehicleMarker.isTowed(cart))
                return true;
            gui.msg("CartCarrier: cart came off on the way, going back for it.");
        }
        return false;
    }

    private boolean tie(NGameUI gui, long cartId) throws InterruptedException {
        Gob cart = Finder.findGob(cartId);
        if (cart == null)
            return false;
        if (VehicleMarker.isTowed(cart))
            return true;
        new PathFinder(cart).run(gui);
        return new TakeVehicle(cart).run(gui).IsSuccess();
    }

    /**
     * Untie the cart where it stands.
     *
     * <p>Deliberately does not pathfind to it first. A towed cart is excluded from the obstacle
     * map and moves with the character, so asking PathFinder to walk to it means chasing a target
     * that keeps retreating, into a footprint the map says is empty -- which is exactly the
     * "walking into the middle of the cart" hang. It trails a couple of tiles behind, well within
     * click range, so a plain right-click is both sufficient and correct.
     */
    private boolean park(NGameUI gui, long cartId) throws InterruptedException {
        Gob cart = Finder.findGob(cartId);
        if (cart == null)
            return false;
        if (!VehicleMarker.isTowed(cart))
            return true;
        if (new ReleaseVehicle(cart).run(gui).IsSuccess())
            return true;

        // Only if the click did not take: close the distance without targeting the cart itself.
        Coord2d toward = cart.rc.sub(NUtils.player().rc);
        if (toward.abs() > 6)
            new GoTo(NUtils.player().rc.add(toward.norm().mul(toward.abs() - 6))).run(gui);
        return new ReleaseVehicle(cart).run(gui).IsSuccess();
    }
}
