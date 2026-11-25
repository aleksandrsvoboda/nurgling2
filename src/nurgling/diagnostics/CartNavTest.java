package nurgling.diagnostics;

import haven.Coord2d;
import haven.Gob;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.cart.ParkCart;
import nurgling.actions.cart.TieCart;
import nurgling.areas.NArea;
import nurgling.navigation.ChunkNavData;
import nurgling.navigation.ChunkNavManager;
import nurgling.tools.VehicleMarker;

import java.util.*;

/**
 * Watchable in-game test of automatic cart awareness in plain navigation (no hauler).
 *
 * <p>Ties the nearest cart, then walks between areas with {@code NUtils.navigateToArea(area, true)}, the
 * same call every bot uses, so every walk with the cart trailing runs as an automatic tow. Two modes:
 * <ul>
 *   <li><b>route</b>: back and forth between two named areas for {@code laps} laps;</li>
 *   <li><b>random</b>: random saved areas for {@code minutes}, from those whose chunks are all outdoors
 *       (surface or mine, never a building interior or cellar) and within {@code maxHops} chunks.</li>
 * </ul>
 * One {@code [CartTest]} line per leg. A cart that came off is logged with where it happened and tied
 * again if it is in view, so a long run keeps going; out of view ends the run.
 *
 * <p>A watchdog ends the client when the character hasn't moved for {@link #HANG_MS}, after logging the
 * bot thread's stack, so a hang costs a minute rather than a whole run.
 *
 * <p>Bot mode config, {@code "cartTest"}: {@code {"from": A, "to": B, "laps": 2}} or
 * {@code {"random": true, "minutes": 20, "maxHops": 4, "seed": 1}}. Turns {@code allowCarts} on for the
 * run and puts it back as it was.
 */
public class CartNavTest implements Action {
    private static final long HANG_MS = 60_000;
    private static final double RETIE_RANGE = 200;

    private final String from, to;
    private final int laps;
    private final boolean random;
    private final int minutes, maxHops;
    private final long seed;

    private int legs = 0, reached = 0, losses = 0;
    private volatile String currentLeg = "setup";
    private volatile long watchedCart = -1;

    public CartNavTest(String from, String to, int laps) {
        this(from, to, laps, false, 0, 0, 0);
    }

    public CartNavTest(String from, String to, int laps, boolean random, int minutes, int maxHops, long seed) {
        this.from = from;
        this.to = to;
        this.laps = Math.max(1, laps);
        this.random = random;
        this.minutes = Math.max(1, minutes);
        this.maxHops = Math.max(1, maxHops);
        this.seed = seed;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Thread watchdog = startWatchdog(gui, Thread.currentThread(), -1);
        Object before = NConfig.get(NConfig.Key.allowCarts);
        NConfig.set(NConfig.Key.allowCarts, true);
        try {
            List<NArea> pool = random ? outdoorAreas(gui) : namedAreas();
            if (pool.size() < 2) {
                log("need at least 2 areas, have " + pool.size());
                return Results.ERROR("areas");
            }
            Gob cart = nearestCart(gui);
            if (cart == null) {
                log("no cart in view");
                return Results.ERROR("no cart");
            }
            log(String.format("cart %d at %.0f from the character, marker=%d, cargo=%d", cart.id,
                    cart.rc.dist(NUtils.player().rc), VehicleMarker.markerOf(cart) & 0xFF, VehicleMarker.cargoCount(cart)));
            watchedCart = cart.id;
            currentLeg = "tying the cart";
            if (!new TieCart(cart).run(gui).IsSuccess()) {
                log("could not tie the cart");
                return Results.ERROR("tie failed");
            }

            Random rnd = new Random(seed);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            NArea last = null;
            int index = 0;
            while (random ? System.currentTimeMillis() < end : index < laps * 2) {
                NArea target;
                if (random) {
                    do {
                        target = pool.get(rnd.nextInt(pool.size()));
                    } while (target == last);
                } else {
                    target = pool.get(index % 2);
                }
                index++;
                last = target;
                if (!leg(gui, cart.id, target))
                    break;
            }
            Gob c = gui.ui.sess.glob.oc.getgob(cart.id);
            if (c != null && VehicleMarker.isTowed(c))
                new ParkCart(c).run(gui);
            log(String.format("done: %d/%d legs reached, cart came off %d times", reached, legs, losses));
            return Results.SUCCESS();
        } finally {
            watchdog.interrupt();
            NConfig.set(NConfig.Key.allowCarts, Boolean.TRUE.equals(before));
        }
    }

    /** One leg; false ends the run (the cart is gone). */
    private boolean leg(NGameUI gui, long cartId, NArea target) throws InterruptedException {
        legs++;
        currentLeg = String.format("leg %d -> '%s' (%d)", legs, target.name, target.id);
        log(currentLeg);
        long t0 = System.currentTimeMillis();
        boolean ok = NUtils.navigateToArea(target, true);
        if (ok)
            reached++;
        Gob c = gui.ui.sess.glob.oc.getgob(cartId);
        Gob p = NUtils.player();
        log(String.format("leg %d %s in %.1fs, cart %s, trailing %s", legs, ok ? "reached" : "FAILED",
                (System.currentTimeMillis() - t0) / 1000.0,
                c == null ? "OUT OF VIEW" : VehicleMarker.towState(c).toString(),
                (c == null || p == null) ? "-" : String.format("%.0f", c.rc.dist(p.rc))));
        if (c != null && VehicleMarker.isTowed(c))
            return true;

        losses++;
        if (c == null || p == null || c.rc.dist(p.rc) > RETIE_RANGE) {
            log("cart lost on " + currentLeg + (c == null ? " (out of view)" : String.format(" (%.0f away at %s)",
                    c.rc.dist(p.rc), fmt(c.rc))) + " - ending the run");
            return false;
        }
        log("cart came off on " + currentLeg + " at " + fmt(c.rc) + " - going back to tie it");
        currentLeg = "re-tying the cart after " + currentLeg;
        return new TieCart(c).run(gui).IsSuccess();
    }

    private List<NArea> namedAreas() {
        NArea a = NUtils.findArea(from), b = NUtils.findArea(to);
        log("areas: '" + from + "'=" + (a != null ? a.id : "MISSING") + ", '" + to + "'=" + (b != null ? b.id : "MISSING"));
        List<NArea> ret = new ArrayList<>();
        if (a != null) ret.add(a);
        if (b != null) ret.add(b);
        return ret;
    }

    /** Saved areas within maxHops chunks whose every chunk is recorded as outdoors (surface or mine). */
    private List<NArea> outdoorAreas(NGameUI gui) throws InterruptedException {
        ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        if (nav == null || !nav.isInitialized()) {
            log("chunk navigation not ready");
            return Collections.emptyList();
        }
        Map<Long, Integer> hops = null;
        for (int i = 0; i < 50 && (hops = nav.chunkHopsFromPlayer()) == null; i++)
            NUtils.addTask(new Ticks(10));
        if (hops == null) {
            log("player chunk not recorded");
            return Collections.emptyList();
        }
        List<Integer> ids = new ArrayList<>(gui.map.glob.map.areas.keySet());
        Collections.sort(ids);
        List<NArea> ret = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (Integer id : ids) {
            NArea area = gui.map.glob.map.areas.get(id);
            if (area == null || area.space == null || area.space.space.isEmpty())
                continue;
            boolean near = false, outdoors = true;
            for (Long grid : area.space.space.keySet()) {
                Integer h = hops.get(grid);
                if (h != null && h <= maxHops)
                    near = true;
                ChunkNavData chunk = nav.getGraph().getChunk(grid);
                if (chunk == null || !"outside".equals(chunk.layer))
                    outdoors = false;
            }
            if (near && outdoors) {
                ret.add(area);
                names.append(" '").append(area.name).append("'");
            }
        }
        log("random pool: " + ret.size() + " outdoor areas within " + maxHops + " chunks:" + names);
        return ret;
    }

    /**
     * Ends the client when the character hasn't moved for {@link #HANG_MS}, after logging what the bot
     * thread is stuck in. Also logs where the character is every 10 s.
     */
    private Thread startWatchdog(NGameUI gui, Thread bot, long cartIdHint) {
        Thread t = new Thread(() -> {
            String lastTow = null;
            Coord2d lastPos = null;
            long lastMove = System.currentTimeMillis(), lastPrint = 0;
            while (!Thread.currentThread().isInterrupted()) {
                Gob p = gui.map.player();
                long now = System.currentTimeMillis();
                if (p != null && (lastPos == null || p.rc.dist(lastPos) > 1)) {
                    lastPos = p.rc;
                    lastMove = now;
                }
                long cid = watchedCart;
                Gob cart = (cid < 0) ? null : gui.ui.sess.glob.oc.getgob(cid);
                String tow = (cart == null) ? "OUT_OF_VIEW" : VehicleMarker.towState(cart).toString();
                if (cid >= 0 && lastTow != null && !tow.equals(lastTow)) {
                    log("cart " + lastTow + " -> " + tow + (cart != null ? " at " + fmt(cart.rc) : "")
                            + (p != null ? ", character at " + fmt(p.rc) : "") + " on " + currentLeg);
                    if (cart != null && "PARKED".equals(tow))
                        dumpAround(gui, cart);
                }
                lastTow = tow;
                if (now - lastPrint >= 10_000 && p != null) {
                    log("at " + fmt(p.rc) + " on " + currentLeg);
                    lastPrint = now;
                }
                if (now - lastMove > HANG_MS) {
                    log("HUNG: the character hasn't moved for " + HANG_MS / 1000 + " s on " + currentLeg
                            + (lastPos != null ? " at " + fmt(lastPos) : "") + ". Bot thread:");
                    for (StackTraceElement e : bot.getStackTrace())
                        System.out.println("[CartTest]     at " + e);
                    log(String.format("done: %d/%d legs reached, cart came off %d times, HUNG", reached, legs, losses));
                    System.exit(3);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "CartNavTest-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Every object within 20 units of the cart: what it came off against. */
    private static void dumpAround(NGameUI gui, Gob cart) {
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (g == cart || g.ngob == null || g.ngob.name == null)
                    continue;
                double d = g.rc.dist(cart.rc);
                if (d > 20)
                    continue;
                String hb = (g.ngob.hitBox == null) ? "no hitbox"
                        : String.format("hitbox (%.1f,%.1f)-(%.1f,%.1f)", g.ngob.hitBox.begin.x, g.ngob.hitBox.begin.y,
                        g.ngob.hitBox.end.x, g.ngob.hitBox.end.y);
                log(String.format("    near cart: %s at %s d=%.1f angle=%.2f %s marker=%d", g.ngob.name, fmt(g.rc), d, g.a, hb,
                        g.ngob.getModelAttribute()));
            }
        }
    }

    private static Gob nearestCart(NGameUI gui) {
        Gob player = NUtils.player();
        if (player == null)
            return null;
        Gob best = null;
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob g : gui.ui.sess.glob.oc) {
                if (VehicleMarker.isCart(g) && (best == null || g.rc.dist(player.rc) < best.rc.dist(player.rc)))
                    best = g;
            }
        }
        return best;
    }

    private static String fmt(Coord2d c) {
        return String.format("(%.0f,%.0f)", c.x, c.y);
    }

    private static void log(String s) {
        System.out.println("[CartTest] " + s);
    }

    private static class Ticks extends nurgling.tasks.NTask {
        private int left;

        Ticks(int n) {
            left = n;
        }

        @Override
        public boolean check() {
            return --left <= 0;
        }
    }
}
