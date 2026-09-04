package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.Pair;
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
    /** Distance from the aimed-at point that still counts as having arrived, in world units. */
    private static final double ARRIVAL_SLACK = 12.0;
    /** Length of a single haul leg. Local pathfinding plans within roughly +/-450 units. */
    private static final double HOP_LENGTH = 150.0;
    /** Movement below this counts as no progress. */
    private static final double MIN_PROGRESS = 5.0;
    private static final int MAX_STALLED_HOPS = 4;
    private static final int MAX_HOPS = 80;
    /** Beyond this, reachability of a point cannot be tested -- it is not in planning range. */
    private static final double PLANNING_RANGE = 300.0;

    /**
     * Whether to park the cart while loading at the input zone.
     *
     * <p>Parking avoids dragging the cart around behind the character as they shuttle objects,
     * which is the snag-prone situation. Leaving it tied trades that risk for two fewer
     * tie/untie round trips per cycle. Flip to try the other behaviour; the unload side is
     * unaffected and still parks.
     */
    private static final boolean PARK_WHILE_LOADING = false;

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
            if (PARK_WHILE_LOADING && !park(gui, cartId))
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
        for (int attempt = 0; attempt < HAUL_ATTEMPTS; attempt++) {
            Gob cart = Finder.findGob(cartId);
            if (cart == null) {
                gui.msg("CartCarrier: lost sight of the cart.");
                return false;
            }

            // Already standing in the zone with the cart to hand: nothing to haul. Covers both
            // "towed and we walked here" and "parked here already", the latter being the normal
            // state of the input zone at the start of a cycle.
            if (atArea(area, ARRIVAL_SLACK)
                    && (VehicleMarker.towState(cart) == VehicleMarker.Tow.TOWED || area.checkHit(cart.rc)))
                return true;

            if (!tie(gui, cartId))
                return false;

            // Global navigation first: it already knows about distance, chunk graphs and portals,
            // and reimplementing that is a bad trade. Its internal PathFinders are not cart-aware
            // since the isolation refactor, so the cart gets no extra clearance on these legs --
            // an untie is recoverable (we re-tie below), a bot that cannot travel is not.
            // Trust navigateToArea's verdict: it returns true once the zone is within local
            // pathfinding range, which is its definition of "close enough to work here".
            boolean walked = NUtils.navigateToArea(area, true) || atArea(area, ARRIVAL_SLACK);

            if (!walked) {
                // Only if global navigation cannot get there at all.
                gui.msg("CartCarrier: global navigation could not reach the zone, walking it manually.");
                walked = towCartTo(gui, cartId, area);
            }

            cart = Finder.findGob(cartId);
            if (cart != null && VehicleMarker.towState(cart) == VehicleMarker.Tow.PARKED) {
                gui.msg("CartCarrier: cart came off on the way, going back for it.");
                continue;
            }
            if (walked)
                return true;
            gui.msg("CartCarrier: could not get the cart to the zone.");
            return false;
        }
        return false;
    }

    /**
     * Tow the cart into a zone of any distance, in hops that each fit inside local pathfinding.
     *
     * <p>{@code PathFinder} can only plan inside the streamed area around the character — roughly
     * ±450 world units — so a single request to a zone 800 units away simply fails. The haul is
     * therefore walked as a series of short cart-aware legs. Each leg is an ordinary
     * {@link CartPathFinder} run, so obstacles are still routed around and the cart still gets its
     * clearance; only the distance is chopped up.
     *
     * <p>The aiming point is re-chosen every leg rather than fixed at the start. Reachability is
     * not a question that can be answered about somewhere 800 units away — nothing out there is in
     * planning range — so committing to one corner up front means discovering only on arrival that
     * it was inside a wall, with no way to pick another.
     */
    private boolean towCartTo(NGameUI gui, long cartId, NArea area) throws InterruptedException {
        int stalls = 0;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            Coord2d from = NUtils.player().rc;
            if (atArea(area, ARRIVAL_SLACK))
                return true;

            Coord2d target = approachTarget(area, from);
            if (target == null) {
                gui.msg("CartCarrier: cannot work out where in that zone to go.");
                return false;
            }
            double remaining = from.dist(target);
            if (remaining <= ARRIVAL_SLACK)
                return true;

            // Each stall halves the stride: a full-length leg can aim past something the local
            // window has no route around, where a shorter one on the same bearing plans fine.
            double reach = Math.min(remaining, HOP_LENGTH / (1 << stalls));
            Coord2d step = (remaining <= reach)
                    ? target
                    : from.add(target.sub(from).norm().mul(reach));

            new CartPathFinder(step, cartId).run(gui);
            double moved = NUtils.player().rc.dist(from);

            // Judged by movement rather than by the return value: a leg that lands short of its
            // waypoint still made progress, and one that reports success without moving has not.
            if (moved < MIN_PROGRESS) {
                if (++stalls >= MAX_STALLED_HOPS) {
                    gui.msg("CartCarrier: stuck " + String.format("%.0f", remaining)
                            + " units short of the zone.");
                    return false;
                }
                continue;
            }
            stalls = 0;
        }
        gui.msg("CartCarrier: gave up hauling after " + MAX_HOPS + " legs.");
        return false;
    }

    /**
     * Are we at the zone, allowing a tile of slack?
     *
     * <p>Not {@code checkHit} alone. Global navigation delivers you to the nearest <em>corner</em>,
     * which sits on the boundary, so an exact containment test on the resulting position routinely
     * says no when you are plainly standing there. Gating on it made a successful trip read as a
     * failure, and made the "already here" shortcut never fire.
     */
    private static boolean atArea(NArea area, double slack) {
        Gob player = NUtils.player();
        if (player == null)
            return false;
        if (area.checkHit(player.rc))
            return true;
        Pair<Coord2d, Coord2d> rc = area.getRCArea();
        if (rc == null)
            return false;
        double minX = Math.min(rc.a.x, rc.b.x), maxX = Math.max(rc.a.x, rc.b.x);
        double minY = Math.min(rc.a.y, rc.b.y), maxY = Math.max(rc.a.y, rc.b.y);
        double dx = Math.max(Math.max(minX - player.rc.x, player.rc.x - maxX), 0);
        double dy = Math.max(Math.max(minY - player.rc.y, player.rc.y - maxY), 0);
        return Math.hypot(dx, dy) <= slack;
    }

    /**
     * Where in the zone to aim for, from where we are standing now.
     *
     * <p>Far away, every candidate is equally untestable, so just head for the nearest one. Once
     * the zone is inside planning range the question becomes answerable, so prefer a point we can
     * actually plan a route to — which is what stops the haul dying 110 units short against a
     * corner that happens to sit inside a wall.
     */
    private Coord2d approachTarget(NArea area, Coord2d from) throws InterruptedException {
        Pair<Coord2d, Coord2d> rc = area.getRCArea();
        if (rc == null)
            return null;

        ArrayList<Coord2d> candidates = new ArrayList<>();
        candidates.add(new Coord2d((rc.a.x + rc.b.x) / 2, (rc.a.y + rc.b.y) / 2));
        candidates.add(new Coord2d(rc.a.x, rc.a.y));
        candidates.add(new Coord2d(rc.b.x, rc.a.y));
        candidates.add(new Coord2d(rc.a.x, rc.b.y));
        candidates.add(new Coord2d(rc.b.x, rc.b.y));
        candidates.sort((a, b) -> Double.compare(a.dist(from), b.dist(from)));

        if (candidates.get(0).dist(from) > PLANNING_RANGE)
            return candidates.get(0);

        for (Coord2d candidate : candidates) {
            if (PathFinder.isAvailable(candidate))
                return candidate;
        }
        return candidates.get(0);
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
        if (VehicleMarker.towState(cart) != VehicleMarker.Tow.TOWED)
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
