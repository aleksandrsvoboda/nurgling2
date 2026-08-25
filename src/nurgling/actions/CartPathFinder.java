package nurgling.actions;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.pf.NPFMap;
import nurgling.tools.Finder;
import nurgling.tools.VehicleMarker;

/**
 * A {@link PathFinder} for a character towing a cart.
 *
 * <p>Used only where a caller has explicitly decided a route is a cart haul — nothing anywhere
 * detects a cart and silently changes how pathfinding works. Ordinary {@code PathFinder} is
 * untouched by any of this; the whole difference lives in the three hooks overridden below.
 *
 * <p>Two things differ from walking unencumbered:
 *
 * <ul>
 *   <li><b>The moving footprint is bigger.</b> The pf grid treats the walker as a point and every
 *       obstacle is pre-inflated by {@link #PLAYER_RADIUS}, so the extra clearance a cart needs has
 *       to be added to the map or the search will thread a gap the cart cannot take.</li>
 *   <li><b>The cart can come off.</b> If it snags, the tie breaks and walking on just leaves it
 *       further behind, so a leg ends the moment the tow is lost and the caller re-ties.</li>
 * </ul>
 *
 * <p>The cart itself stays in the obstacle map, deliberately. The server still collides the
 * character with their own cart, so a planner that cannot see it draws straight lines through it
 * and the character grinds to a halt — measured, and reverted once already.
 */
public class CartPathFinder extends PathFinder {

    /** The half-width the pf stack has always implicitly assumed for a bare character. */
    public static final double PLAYER_RADIUS = 3.0;

    /**
     * Radius to plan with while towing a cart.
     *
     * <p>The cart's obstacle is a square of ±5.892 world units, and it rotates about its own centre
     * as it re-aims at the character, so the safe figure is the circumscribed radius
     * 5.892·√2. It does <em>not</em> swing around the character — a towed cart holds position until
     * pulled — so this is the whole of the extra clearance, with no turn allowance on top.
     */
    public static final double CART_RADIUS = 8.33;

    /** Consecutive replans that leave the character where it was before abandoning the route. */
    private static final int MAX_STUCK_REPLANS = 4;

    private final long cartId;

    private Coord2d lastStuckAt = null;
    private int stuckReplans = 0;

    public CartPathFinder(Coord2d end, long cartId) {
        super(end);
        this.cartId = cartId;
    }

    public CartPathFinder(Gob target, long cartId) {
        super(target);
        this.cartId = cartId;
    }

    /**
     * Widen obstacles by however much the towed footprint exceeds the baseline.
     *
     * <p>Runs after the approach cells are chosen so the goal is not swallowed. Three things are
     * left alone: cells already marked as approach points (7); the start and its immediate
     * neighbours, since the character demonstrably got there with the cart in tow so that ground
     * is passable by construction; and the cart itself, which is part of the moving agent rather
     * than something it has to clear — dilating it would wrap the character in a five-cell block.
     */
    @Override
    protected void onMapReady(NPFMap map, Coord start) {
        int r = (int) Math.ceil((CART_RADIUS - PLAYER_RADIUS) / MCache.tilehsz.x);
        if (r <= 0)
            return;

        int size = map.getSize();
        NPFMap.Cell[][] cells = map.getCells();

        boolean[][] seed = new boolean[size][size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                seed[i][j] = (cells[i][j].val == 1 || cells[i][j].val == 2) && !isOwnCart(cells[i][j]);

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (!seed[i][j])
                    continue;
                for (int di = -r; di <= r; di++) {
                    for (int dj = -r; dj <= r; dj++) {
                        int ii = i + di, jj = j + dj;
                        if (ii < 0 || jj < 0 || ii >= size || jj >= size)
                            continue;
                        if (cells[ii][jj].val != 0)
                            continue;
                        if (start != null && Math.abs(ii - start.x) <= 1 && Math.abs(jj - start.y) <= 1)
                            continue;
                        // 3 is unused elsewhere and, like any non-zero value, reads as blocked to
                        // both the grid search and the corridor check in Graph.
                        cells[ii][jj].val = 3;
                    }
                }
            }
        }
    }

    /** True when a cell is blocked by our own cart and nothing else. */
    private boolean isOwnCart(NPFMap.Cell cell) {
        return cell.val == 1 && cell.content.size() == 1 && cell.content.contains(cartId);
    }

    /**
     * Walk one leg, then confirm the cart is still attached.
     *
     * <p>Checked between legs rather than during one: a leg is a single waypoint hop, and the cart
     * only unties when it snags, after which the character is free anyway. That keeps {@link GoTo}
     * — the hottest action in the codebase — completely untouched.
     */
    @Override
    protected Results walkTo(NGameUI gui, Coord2d target) throws InterruptedException {
        Results result = new GoTo(target).run(gui);
        if (!stillTowing())
            return Results.FAIL();
        return result;
    }

    @Override
    protected boolean onLegFailed(NGameUI gui, Coord2d at) {
        if (!stillTowing())
            return false;
        if (lastStuckAt != null && lastStuckAt.dist(at) < pfmdelta) {
            if (++stuckReplans >= MAX_STUCK_REPLANS)
                return false;
        } else {
            stuckReplans = 0;
        }
        lastStuckAt = at;
        return true;
    }

    /**
     * Whether the haul is still viable.
     *
     * <p>Only a positive "parked" counts as having lost the cart. A gob that is momentarily not in
     * the object cache, or whose marker has not resolved yet, is not evidence of anything — and
     * treating it as evidence aborts the leg, which sends the caller round its retry loop and back
     * to clicking the cart.
     */
    private boolean stillTowing() {
        Gob cart = Finder.findGob(cartId);
        if (cart == null)
            return true;
        return VehicleMarker.towState(cart) != VehicleMarker.Tow.PARKED;
    }
}
