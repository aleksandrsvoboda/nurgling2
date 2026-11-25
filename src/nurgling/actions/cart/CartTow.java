package nurgling.actions.cart;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.pf.NPFMap;
import nurgling.pf.Utils;
import nurgling.tools.VehicleMarker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * "This session's character is towing this cart."
 *
 * <p>Two ways to get one:
 * <ul>
 *   <li><b>Hauler</b>: {@link CartHauler} sets {@link NGameUI#cartTow} right after a tie and clears it right
 *       before a park.</li>
 *   <li><b>Automatic</b> ({@link #detect}): with {@code allowCarts} on, any walk that starts with a towed cart
 *       trailing the character treats it as theirs. A towed cart only ever trails within
 *       {@link #OWN_TOW_RANGE}, so anything further is someone else's.</li>
 * </ul>
 *
 * <p>Either way the pf stack grows obstacles by {@link #dilationCells()} so the line keeps the cart's
 * clearance ({@link #dilate}, with an undilated fallback when that leaves no path), and watches for the
 * measured pre-untie stall ({@link #snagWatcher}) to stop before the rope breaks. Only a hauler tow
 * also ends a walk the moment the cart comes off ({@link #lost}), because only the hauler goes back for
 * it; an automatic tow leaves the cart where it came off. With no tow every walk is exactly what it was
 * before carts existed, and with {@code allowCarts} off there is never an automatic one.
 */
public final class CartTow {

    /** Bare-character half-width the pf stack implicitly assumes (NHitBox min clamp). */
    static final double PLAYER_RADIUS = 3.0;
    /** Cart obstacle is a ±5.892 square that rotates about its centre: circumscribed radius. */
    static final double CART_RADIUS = 8.33;
    /** Cart LinMove must persist this long before it counts as a snag (measured warning: 0.5-0.8 s). */
    private static final long SNAG_CONFIRM_MS = 120;
    /** Cart covering less than this share of the character's distance while on LinMove counts as stalled. */
    private static final double STALL_RATIO = 0.75;
    /** Radius around a remembered snag spot that later plans keep out of. */
    private static final double SNAG_BLOCK_R = MCache.tilehsz.x;
    /** Snag spot is taken this far in front of the cart, towards the character. */
    private static final double SNAG_AHEAD = 6;

    public final long cartId;
    /** Set by the pf stack when the tow bit cleared during a walk. */
    public volatile boolean lost = false;
    /** Found by {@link #detect} rather than set by a hauler: never ends a walk, nobody goes back for it. */
    public final boolean automatic;
    /** Where the cart got stuck on this tow; later plans keep clear of them. */
    final List<Coord2d> snagSpots = new ArrayList<>();
    /** Last world position the cart was seen at, for finding it again after it came off. */
    volatile Coord2d lastSeen;

    /** A towed cart's centre stays within this of the character's; anything further isn't theirs. */
    static final double OWN_TOW_RANGE = 30;

    CartTow(long cartId, Coord2d at) {
        this(cartId, at, false);
    }

    private CartTow(long cartId, Coord2d at, boolean automatic) {
        this.cartId = cartId;
        this.lastSeen = at;
        this.automatic = automatic;
    }

    /**
     * An automatic tow for a walk starting now, or null. Null whenever {@code allowCarts} is off (nothing
     * is scanned then) or no towed cart is trailing the character.
     */
    public static CartTow detect(NGameUI gui) {
        if (gui == null || !SelectCart.allowed())
            return null;
        Gob cart = ownTowedCart(gui);
        if (cart == null)
            return null;
        return new CartTow(cart.id, cart.rc, true);
    }

    /**
     * Whether a route planned now, on this thread's session, is for a character towing a cart: a hauler tow, or
     * (carts allowed) a towed cart trailing the character. With carts off nothing is scanned.
     */
    public static boolean planningWithCart() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null)
            return false;
        if (gui.cartTow != null)
            return true;
        return SelectCart.allowed() && gui.map != null && ownTowedCart(gui) != null;
    }

    /** The nearest towed cart within {@link #OWN_TOW_RANGE} of the character, or null. */
    public static Gob ownTowedCart(NGameUI gui) {
        Gob player = gui.map.player();
        if (player == null)
            return null;
        Gob best = null;
        double bestDist = OWN_TOW_RANGE;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (!VehicleMarker.isCart(g) || !VehicleMarker.isTowed(g))
                    continue;
                double d = g.rc.dist(player.rc);
                if (d < bestDist) {
                    bestDist = d;
                    best = g;
                }
            }
        }
        return best;
    }

    /** The session's tow, or null when not towing. */
    public static CartTow of(NGameUI gui) {
        return (gui == null) ? null : gui.cartTow;
    }

    public static CartTow current() {
        return of(NUtils.getGameUI());
    }

    Gob cart(NGameUI gui) {
        Gob g = gui.ui.sess.glob.oc.getgob(cartId);
        if (g != null)
            lastSeen = g.rc;
        return g;
    }

    /**
     * Hauler tows only: true once the tow bit has cleared. Positive "parked" only: a gob out of the cache
     * or an unread marker is no evidence. Always false for an automatic tow.
     */
    public boolean cameOff(NGameUI gui) {
        if (automatic)
            return false;
        Gob cart = cart(gui);
        return cart != null && VehicleMarker.towState(cart) == VehicleMarker.Tow.PARKED;
    }

    /** How far a cart trails the character while being pulled (measured 15-27). */
    private static final double TRAIL = 24;
    /** Longest overshoot tried past a corner, and the step between tries. */
    private static final double MAX_OVERSHOOT = 32, OVERSHOOT_STEP = 4;
    /** The cart's half-width beyond what the pf map's obstacle inflation already covers. */
    private static final double CART_SIDE = 3;

    /** Corner changes for a towed walk, by index of the corner in the planned path. */
    public static final class Overshoots {
        /** After reaching corner i, first carry on to this point. */
        public final Map<Integer, Coord2d> past = new HashMap<>();
        /** Walk to this point instead of corner i: the turn after an overshoot runs parallel to the old one. */
        public final Map<Integer, Coord2d> shifted = new HashMap<>();
    }

    /**
     * Where to carry on past a corner before turning.
     *
     * <p>A towed cart doesn't follow the character's footsteps: it trails about {@link #TRAIL} behind and
     * heads in a straight line at wherever the character is. Turned sharply at the mouth of a gap, it cuts the
     * corner and catches. So each corner is checked by following a simulated cart along the path: if its line
     * to the character crosses an obstacle, the character first carries straight on past the corner, the
     * shortest distance that keeps the line clear, and then turns along a line parallel to the planned one
     * (the next corner moves over by the same amount) rather than cutting back to it. When the next corner is
     * the destination, or the parallel line isn't walkable, the turn heads for the planned corner instead.
     * The overshoot is limited to free ground. A corner no overshoot clears gets the longest one that fits,
     * up to {@link #TRAIL}, and the walk goes ahead anyway.
     */
    public Overshoots planOvershoots(NGameUI gui, NPFMap map, Coord2d start, List<Coord2d> planned) {
        Overshoots res = new Overshoots();
        Gob cartGob = gui.ui.sess.glob.oc.getgob(cartId);
        if (cartGob == null || planned.size() < 2)
            return res;
        List<Coord2d> corners = new ArrayList<>(planned);
        Coord2d cart = cartGob.rc;
        Coord2d from = start;
        for (int i = 0; i < corners.size(); i++) {
            Coord2d c = corners.get(i);
            Coord2d next = (i + 1 < corners.size()) ? corners.get(i + 1) : null;
            if (next == null || c.dist(from) < 0.01 || next.dist(c) < 0.01) {
                cart = follow(map, cart, from, c, null);
                from = c;
                continue;
            }
            Coord2d after = (i + 2 < corners.size()) ? corners.get(i + 2) : null;
            Coord2d in = c.sub(from).norm();
            Coord2d best = null, bestTurn = null, fit = null, fitTurn = null;
            for (double l = 0; l <= MAX_OVERSHOOT; l += OVERSHOOT_STEP) {
                Coord2d o = c.add(in.mul(l));
                if (l > 0 && !walkable(map, c, o))
                    break;
                // Turn parallel to the planned leg, unless that leg ends the walk or its parallel isn't walkable.
                Coord2d turn = next;
                if (l > 0 && after != null) {
                    Coord2d moved = next.add(o.sub(c));
                    if (walkable(map, o, moved) && walkable(map, moved, after))
                        turn = moved;
                }
                if (l > 0 && turn == next && !walkable(map, o, next))
                    break;
                boolean[] clear = {true};
                Coord2d sim = follow(map, cart, from, c, clear);
                if (l > 0)
                    sim = follow(map, sim, c, o, clear);
                sim = follow(map, sim, (l > 0) ? o : c, turn, clear);
                if (turn != next && after != null)
                    follow(map, sim, turn, after, clear);
                if (l <= TRAIL) {
                    fit = o;
                    fitTurn = turn;
                }
                if (clear[0]) {
                    best = o;
                    bestTurn = turn;
                    break;
                }
            }
            Coord2d chosen = (best != null) ? best : fit;
            Coord2d chosenTurn = (best != null) ? bestTurn : fitTurn;
            cart = follow(map, cart, from, c, null);
            if (chosen != null && chosen.dist(c) > 0.5) {
                res.past.put(i, chosen);
                if (chosenTurn != null && chosenTurn != next) {
                    res.shifted.put(i + 1, chosenTurn);
                    corners.set(i + 1, chosenTurn);
                }
                System.out.println(String.format("[Cart] corner at (%.0f,%.0f): carrying on %.0f before turning%s%s", c.x, c.y,
                        chosen.dist(c), (chosenTurn != null && chosenTurn != next) ? ", turning parallel" : "",
                        best == null ? " (still too tight for the cart, going ahead)" : ""));
                cart = follow(map, cart, c, chosen, null);
                from = chosen;
            } else {
                if (best == null && fit != null) {
                    System.out.println(String.format("[Cart] corner at (%.0f,%.0f) too tight for the cart and no room to carry on past it, going ahead",
                            c.x, c.y));
                }
                from = c;
            }
        }
        return res;
    }

    /**
     * Moves the simulated cart while the character walks a to b: it stays where it is until the character
     * is more than {@link #TRAIL} away, then follows straight at them. Clears {@code clear[0]} when the line
     * from the cart to the character crosses an obstacle on the way.
     */
    private Coord2d follow(NPFMap map, Coord2d cart, Coord2d a, Coord2d b, boolean[] clear) {
        double len = a.dist(b);
        int steps = Math.max(1, (int) Math.ceil(len));
        for (int s = 1; s <= steps; s++) {
            Coord2d p = a.add(b.sub(a).mul((double) s / steps));
            double d = cart.dist(p);
            if (d > TRAIL)
                cart = cart.add(p.sub(cart).norm(d - TRAIL));
            if (clear != null && clear[0] && !lineClear(map, cart, p))
                clear[0] = false;
        }
        return cart;
    }

    /** The cart-to-character line, and the two lines either side of it at {@link #CART_SIDE}, miss every obstacle. */
    private boolean lineClear(NPFMap map, Coord2d cart, Coord2d player) {
        Coord2d dir = player.sub(cart);
        double len = dir.abs();
        if (len < 0.01)
            return true;
        Coord2d side = new Coord2d(-dir.y / len, dir.x / len).mul(CART_SIDE);
        for (int k = -1; k <= 1; k++) {
            Coord2d off = side.mul(k);
            // The side lines start past the cart's own body: a cart standing in a tight gap is not yet a snag.
            for (double t = (k == 0) ? 0 : Math.min(len, 6); t <= len; t += 1) {
                if (obstacle(map, cart.add(off).add(dir.mul(t / len))))
                    return false;
            }
        }
        return true;
    }

    /** The character can walk the straight line a to b (no real obstacle on it). */
    private boolean walkable(NPFMap map, Coord2d a, Coord2d b) {
        double len = a.dist(b);
        for (double t = 0; t <= len; t += 1) {
            if (obstacle(map, a.add(b.sub(a).mul(len < 0.01 ? 0 : t / len))))
                return false;
        }
        return true;
    }

    /**
     * A real obstacle on the pf map at w (not grown clearance, not our own cart). Off the map is unknown, not an
     * obstacle: the cart trails behind the start, often just outside the map the walk was planned on.
     */
    private boolean obstacle(NPFMap map, Coord2d w) {
        Coord c = Utils.toPfGrid(w).sub(map.getBegin());
        if (c.x < 0 || c.y < 0 || c.x >= map.getSize() || c.y >= map.getSize())
            return false;
        NPFMap.Cell cell = map.getCells()[c.x][c.y];
        return (cell.val == 1 || cell.val == 2) && !isOwnCart(cell);
    }

    /** How long a portal hop waits for a towed cart to land and catch up. */
    private static final long PORTAL_SETTLE_MS = 4000;
    /** A cart that comes off on landing is re-tied only this close; anything further isn't the one we brought. */
    private static final double PORTAL_REATTACH_RANGE = 80;

    /**
     * Before a portal hop: the cart an automatic tow is bringing along, or -1. Hauler tows are left to the
     * hauler, which already goes back for a cart that came off.
     */
    public static long cartBeforePortal(NGameUI gui) {
        if (gui == null || gui.cartTow != null || !SelectCart.allowed())
            return -1;
        Gob cart = ownTowedCart(gui);
        return (cart == null) ? -1 : cart.id;
    }

    /**
     * After a portal hop with a towed cart: the cart lands a few dozen units off and homes in, and measured,
     * it can come off right there while the character is still standing at the exit - before any walk could
     * watch for it. Waits for it to land; if it came off within reach and nothing is held, ties it again.
     */
    public static void reattachAfterPortal(NGameUI gui, long cartId) throws InterruptedException {
        if (cartId < 0)
            return;
        long deadline = System.currentTimeMillis() + PORTAL_SETTLE_MS;
        NUtils.addTask(new nurgling.tasks.NTask() {
            @Override
            public boolean check() {
                if (System.currentTimeMillis() > deadline)
                    return true;
                Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
                Gob player = gui.map.player();
                if (cart == null || player == null)
                    return false;
                VehicleMarker.Tow state = VehicleMarker.towState(cart);
                if (state == VehicleMarker.Tow.PARKED)
                    return true;
                return state == VehicleMarker.Tow.TOWED && cart.getattr(Moving.class) == null
                        && cart.rc.dist(player.rc) < OWN_TOW_RANGE;
            }
        });
        Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
        Gob player = gui.map.player();
        if (cart == null || player == null || VehicleMarker.towState(cart) != VehicleMarker.Tow.PARKED)
            return;
        if (cart.rc.dist(player.rc) > PORTAL_REATTACH_RANGE || nurgling.tools.Finder.findLiftedbyPlayer() != null)
            return;
        System.out.println(String.format("[Cart] cart %d came off landing after a portal, %.0f away - tying it again",
                cartId, cart.rc.dist(player.rc)));
        new TieCart(cart).run(gui);
    }

    public static int dilationCells() {
        return (int) Math.ceil((CART_RADIUS - PLAYER_RADIUS) / MCache.tilehsz.x);
    }

    /** How many clearance levels a walk can step down through before it plans as a bare character. */
    public static final int CLEARANCE_LEVELS = 3;

    /**
     * Grows every blocked cell by {@link #dilationCells()} into free cells, as far as {@code level} allows:
     * <ol start="0">
     *   <li>everywhere but the start and its 8 neighbours;</li>
     *   <li>also not within {@link #ROOMY_EXEMPT} cells of the start or the goal - the cramped spots a walk
     *       begins or ends in (stepping off a ladder, a cart parked against a wall) without giving up the
     *       clearance along the way, which is where the cart catches on things;</li>
     *   <li>no growth at all.</li>
     * </ol>
     * Approach cells (not free) and our own cart are never grown: the cart is part of the moving agent,
     * growing it would wall the character in. Remembered snag spots are blocked at every level. Grown
     * cells get value 3, which reads as blocked to the graph search and to the corridor check alike.
     */
    public void dilate(NPFMap map, Coord start, Coord goal, int level) {
        int size = map.getSize();
        NPFMap.Cell[][] cells = map.getCells();
        int startExempt = (level == 0) ? 1 : ROOMY_EXEMPT;
        int goalExempt = (level == 0) ? -1 : ROOMY_EXEMPT;

        if (level < 2) {
            int r = dilationCells();
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
                            block(cells, size, start, startExempt, goal, goalExempt, i + di, j + dj);
                        }
                    }
                }
            }
        }

        synchronized (snagSpots) {
            if (!snagSpots.isEmpty()) {
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        Coord2d w = Utils.pfGridToWorld(cells[i][j].pos);
                        for (Coord2d spot : snagSpots) {
                            if (w.dist(spot) < SNAG_BLOCK_R) {
                                block(cells, size, start, 1, null, -1, i, j);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Cells around the start and goal left ungrown at level 1: about a cart's length. */
    private static final int ROOMY_EXEMPT = 3;

    private static void block(NPFMap.Cell[][] cells, int size, Coord start, int startExempt, Coord goal, int goalExempt,
                              int i, int j) {
        if (i < 0 || j < 0 || i >= size || j >= size)
            return;
        if (cells[i][j].val != 0)
            return;
        if (start != null && Math.abs(i - start.x) <= startExempt && Math.abs(j - start.y) <= startExempt)
            return;
        if (goal != null && goalExempt >= 0 && Math.abs(i - goal.x) <= goalExempt && Math.abs(j - goal.y) <= goalExempt)
            return;
        cells[i][j].val = 3;
    }

    private boolean isOwnCart(NPFMap.Cell cell) {
        return cell.val == 1 && cell.content.size() == 1 && cell.content.contains(cartId);
    }

    /**
     * Polled by GoTo on the task thread. True once the cart has dropped its {@code Homing} for a
     * {@code LinMove} for {@link #SNAG_CONFIRM_MS}: measured, that is what a cart does when it fouls
     * something, roughly half a second before the tie breaks. Records the snag spot when it fires.
     */
    public SnagWatch snagWatcher(NGameUI gui, boolean watchSnags) {
        return new SnagWatch(gui, watchSnags);
    }

    /** A walk step whose character hasn't moved this long while towing lost its click; see {@link SnagWatch#stuck()}. */
    private static final long STUCK_MS = 3000;

    public final class SnagWatch implements BooleanSupplier {
        private final NGameUI gui;
        private long since = -1;
        private Coord2d cartAtSince, playerAtSince;
        private volatile boolean fired = false;
        private volatile boolean stuck = false;
        private final boolean watchSnags;
        private Coord2d playerLastPos = null;
        private long playerLastMove = System.currentTimeMillis();

        SnagWatch(NGameUI gui, boolean watchSnags) {
            this.gui = gui;
            this.watchSnags = watchSnags;
        }

        /** The cart is about to come off: stop and re-plan around the snag. */
        public boolean fired() {
            return fired;
        }

        /**
         * The character never moved for {@link #STUCK_MS}: the walk click was swallowed (measured right after
         * re-tying a cart), and GoTo's wait for movement would otherwise wait forever. Re-plan to click again.
         */
        public boolean stuck() {
            return stuck;
        }

        @Override
        public boolean getAsBoolean() {
            if (fired || stuck)
                return true;
            Gob player = gui.map.player();
            if (player != null) {
                long t = System.currentTimeMillis();
                if (playerLastPos == null || player.rc.dist(playerLastPos) > 0.5) {
                    playerLastPos = player.rc;
                    playerLastMove = t;
                } else if (t - playerLastMove > STUCK_MS) {
                    System.out.println("[Cart] character hasn't moved for " + STUCK_MS + " ms on a walk step - clicking again");
                    stuck = true;
                    return true;
                }
            }
            if (!watchSnags)
                return false;
            Gob cart = gui.ui.sess.glob.oc.getgob(cartId);
            if (cart == null || player == null) {
                since = -1;
                return false;
            }
            lastSeen = cart.rc;
            Moving m = cart.getattr(Moving.class);
            if (!(m instanceof LinMove)) {
                since = -1;
                return false;
            }
            long now = System.currentTimeMillis();
            if (since < 0) {
                since = now;
                cartAtSince = cart.rc;
                playerAtSince = player.rc;
                return false;
            }
            if (now - since < SNAG_CONFIRM_MS)
                return false;
            // A real stall: the cart falls behind - measured, it drops to ~60% of the character's speed, then stops.
            double cartMoved = cart.rc.dist(cartAtSince), playerMoved = player.rc.dist(playerAtSince);
            if (cartMoved >= STALL_RATIO * playerMoved) {
                since = now;
                cartAtSince = cart.rc;
                playerAtSince = player.rc;
                return false;
            }
            Coord2d toPlayer = player.rc.sub(cart.rc);
            Coord2d spot = (toPlayer.abs() > 0.01) ? cart.rc.add(toPlayer.norm(SNAG_AHEAD)) : cart.rc;
            synchronized (snagSpots) {
                snagSpots.add(spot);
            }
            System.out.println(String.format("[Cart] snag: cart at (%.0f,%.0f) moved %.1f while the character moved %.1f, %.0f away - stopping",
                    cart.rc.x, cart.rc.y, cartMoved, playerMoved, toPlayer.abs()));
            fired = true;
            return true;
        }
    }
}
