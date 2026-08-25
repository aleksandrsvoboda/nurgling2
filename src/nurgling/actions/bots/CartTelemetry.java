package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.pf.NPFMap;
import nurgling.tasks.NTask;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VehicleMarker;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * T0 telemetry tap for towed-vehicle pathfinding work.
 *
 * <p>Pure observation: it never moves the character. Start it, tie a cart, walk the move script
 * below, stop it with the bot-stop button; the trace lands as JSON next to the other nurgling
 * data files. It exists to settle, from data rather than inference, the facts the cart-aware
 * pathfinder design is blocked on:
 *
 * <ul>
 *   <li><b>Attachment model.</b> Which {@link Moving} subclass the vehicle carries while towed.
 *       {@link Following} is a rigid bone attachment whose {@code getc()} returns the target's own
 *       coordinate, so a vehicle that trails, turns and un-ties cannot be using it. The distinction
 *       decides whether {@code NPFMap.addGob}'s {@code Following} skip applies at all.</li>
 *   <li><b>Is the cart in my own obstacle map?</b> Answered directly, not by reasoning: the probe
 *       builds a real {@link NPFMap} around the player and looks for the vehicle's id in the cells.</li>
 *   <li><b>Tow distance and heading lag.</b> {@code d} and {@code aDelta} over a turn give the
 *       trailer geometry the planner would have to respect.</li>
 *   <li><b>Pose.</b> Whether the towing walk pose still matches the {@code borka/walking|running|wading}
 *       set that {@code MovingCompleted} and {@code IsMoving} key off. If it does not, every leg
 *       mis-detects arrival.</li>
 *   <li><b>Untie signature.</b> Transitions in the attachment signals, with timing — the raw material
 *       for the T1 oracle.</li>
 * </ul>
 *
 * <p><b>Move script</b> (perform in order; phases are recoverable from the trace itself, so the
 * exact timing does not matter): stand still tied · walk straight ~10 tiles · stop · turn 90° and
 * walk · reverse 180° · walk a tight circle · repeat walking vs running.
 */
public class CartTelemetry implements Action {

    /** Vehicles to lock onto. Deliberately broad: cart, wheelbarrow and plow all tow. */
    private static final NAlias VEHICLE = new NAlias("vehicle");

    /** Don't sample faster than this; the core tick is well above the rate we need. */
    private static final long SAMPLE_MIN_MS = 40;
    /** Ticks of cheap per-tick sampling between returns to the bot thread. */
    private static final int BURST_TICKS = 25;
    /** Run an NPFMap probe every N bursts. Rasterising the map is too heavy for the core thread. */
    private static final int PF_PROBE_EVERY_BURSTS = 4;
    private static final long FLUSH_EVERY_MS = 10000;
    private static final long LIVE_MSG_EVERY_MS = 2000;
    /** Only consider vehicles this close when first locking on. */
    private static final double LOCK_RADIUS = 60.0;
    /** How far around the cart to record obstacles when it unties -- about three tiles. */
    private static final double UNTIE_SCAN_RADIUS = 33.0;
    private static final int MAX_SAMPLES = 60000;

    /**
     * Guards the three record lists. They are appended from the core tick thread (sampling) and
     * drained from the bot thread (flushing), so an unsynchronised snapshot would throw
     * ConcurrentModificationException mid-run and take the whole trace with it.
     */
    private final Object lock = new Object();
    private final List<JSONObject> samples = new ArrayList<>();
    private final List<JSONObject> events = new ArrayList<>();
    private final List<JSONObject> pfProbes = new ArrayList<>();
    private final List<JSONObject> unties = new ArrayList<>();

    private long t0;
    private String outputFilePath;
    private long playerId = -1;

    // Written on the core thread, read on the bot thread.
    private volatile long cartId = -1;
    private volatile String cartName = null;
    private volatile JSONObject cartInfo = null;
    private volatile boolean truncated = false;

    private long lastSampleAt = 0;
    private long lastFlushAt = 0;
    private long lastLiveMsgAt = 0;

    // Previous values, for edge-triggered event records.
    private String prevMoving = "<init>";
    private String prevFollowTgt = "<init>";
    private Boolean prevCarryPose = null;
    private Boolean prevPresent = null;
    private String prevMarker = "<init>";
    private Boolean prevTowed = null;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        t0 = System.currentTimeMillis();
        lastFlushAt = t0;
        lastLiveMsgAt = t0;
        outputFilePath = generateOutputFilePath();
        playerId = NUtils.playerID();

        gui.msg("Cart telemetry running. Tie the cart and walk the script; stop the bot when done.");
        gui.msg("Trace -> " + outputFilePath);

        int burst = 0;
        try {
            while (true) {
                NUtils.addTask(new Burst());
                burst++;

                // Everything below runs on the bot thread, where blocking work is safe.
                if (burst % PF_PROBE_EVERY_BURSTS == 0) {
                    probePfMap();
                }

                long now = System.currentTimeMillis();
                if (now - lastLiveMsgAt >= LIVE_MSG_EVERY_MS) {
                    lastLiveMsgAt = now;
                    liveMsg(gui);
                }
                if (now - lastFlushAt >= FLUSH_EVERY_MS) {
                    lastFlushAt = now;
                    save();
                }
            }
        } finally {
            save();
            int ns, ne, np;
            synchronized (lock) {
                ns = samples.size();
                ne = events.size();
                np = pfProbes.size();
            }
            gui.msg("Cart telemetry stopped. " + ns + " samples, "
                    + ne + " events, " + np + " pf probes.");
            gui.msg("Trace -> " + outputFilePath);
        }
    }

    /**
     * Samples once per core tick and completes after {@link #BURST_TICKS}. Deliberately finishes by
     * returning true rather than by letting {@code baseCheck} time out, which would set
     * {@code criticalExit} and log a spurious error every burst.
     */
    private class Burst extends NTask {
        private int ticks = 0;

        Burst() {
            this.infinite = true;
        }

        @Override
        public boolean check() {
            sample();
            return ++ticks >= BURST_TICKS;
        }
    }

    // ------------------------------------------------------------------ sampling

    /** Runs on the core tick thread: field reads only, nothing that can block. */
    private void sample() {
        long now = System.currentTimeMillis();
        if (now - lastSampleAt < SAMPLE_MIN_MS)
            return;
        lastSampleAt = now;

        Gob player = NUtils.player();
        if (player == null)
            return;

        Gob cart = resolveCart(player);

        boolean present = cart != null;
        String moving = null;
        String followTgt = null;
        if (cart != null) {
            Moving mv = cart.getattr(Moving.class);
            if (mv != null)
                moving = mv.getClass().getSimpleName();
            Following fl = cart.getattr(Following.class);
            if (fl != null)
                followTgt = String.valueOf(fl.tgt);
        }
        String pose = player.pose();
        boolean carryPose = pose != null && pose.contains("borka/carry");
        long marker = VehicleMarker.markerOf(cart);

        noteChange(now, "cartPresent", prevPresent == null ? null : String.valueOf(prevPresent), String.valueOf(present));
        prevPresent = present;
        noteChange(now, "cartMoving", prevMoving, String.valueOf(moving));
        prevMoving = String.valueOf(moving);
        noteChange(now, "cartFollowTgt", prevFollowTgt, String.valueOf(followTgt));
        prevFollowTgt = String.valueOf(followTgt);
        noteChange(now, "playerCarryPose", prevCarryPose == null ? null : String.valueOf(prevCarryPose), String.valueOf(carryPose));
        prevCarryPose = carryPose;
        // The marker is the only signal that reports tow state at rest, so its edges are the
        // ones that matter most: a tie and an untie should each show up here.
        noteChange(now, "cartMarker", prevMarker, cart == null ? "null" : String.valueOf(marker));
        // The tow bit dropping is the untie, and it beats the server's message by ~50ms. Grab the
        // surroundings now: a trace that says only "it untied at t=41s" cannot tell us what it hit.
        boolean towedNow = VehicleMarker.known(marker) && (marker & VehicleMarker.MASK_TOWED) != 0;
        if (prevTowed != null && prevTowed && !towedNow && cart != null)
            captureUntie(now, player, cart);
        prevTowed = towedNow;
        prevMarker = (cart == null) ? "null" : String.valueOf(marker);

        synchronized (lock) {
            if (samples.size() >= MAX_SAMPLES) {
                truncated = true;
                return;
            }
        }

        JSONObject s = new JSONObject();
        s.put("t", now - t0);
        s.put("plX", round(player.rc.x));
        s.put("plY", round(player.rc.y));
        s.put("plA", deg(player.a));
        s.put("plPose", pose);
        s.put("plMoving", movingName(player));
        s.put("plV", velocity(player));
        // The exact set MovingCompleted / IsMoving test against. If this is false while the
        // character is visibly walking, those tasks mis-detect arrival on every leg.
        s.put("plWalkPoseMatch", NParser.checkName(pose, "borka/walking", "borka/running", "borka/wading"));
        s.put("plCarryPose", carryPose);

        s.put("cartPresent", present);
        if (cart != null) {
            s.put("cartX", round(cart.rc.x));
            s.put("cartY", round(cart.rc.y));
            s.put("cartA", deg(cart.a));
            s.put("cartPose", cart.pose());
            s.put("cartMoving", moving);
            s.put("cartV", velocity(cart));

            Following fl = cart.getattr(Following.class);
            // This is precisely NPFMap.addGob's skip predicate. If it is false while towing, the
            // cart is being rasterised as an obstacle directly behind the player on every plan.
            s.put("cartFollowing", fl != null);
            if (fl != null) {
                s.put("cartFollowTgt", fl.tgt);
                s.put("cartFollowIsMe", fl.tgt == playerId);
                s.put("cartFollowXfname", fl.xfname);
            }
            Homing hm = cart.getattr(Homing.class);
            if (hm != null) {
                s.put("cartHomingTgt", hm.tgt);
                s.put("cartHomingV", round(hm.v));
            }

            s.put("cartMarker", marker);
            s.put("cartMarkerU", VehicleMarker.unsigned(marker));
            s.put("cartTowedBit", VehicleMarker.known(marker) && (marker & VehicleMarker.MASK_TOWED) != 0);
            s.put("cartParkedBit", VehicleMarker.known(marker) && (marker & VehicleMarker.MASK_PARKED) != 0);
            s.put("cartCargo", VehicleMarker.cargoCount(cart, VehicleMarker.CART_CARGO_SLOTS));

            Coord2d delta = cart.rc.sub(player.rc);
            s.put("d", round(delta.abs()));
            // Where the cart sits relative to where the character is facing. ~180 deg means
            // directly behind; a steady non-zero drift means the trailer tracks off-line.
            s.put("bearingRel", normDeg(Math.toDegrees(Math.atan2(delta.y, delta.x)) - Math.toDegrees(player.a)));
            // Heading lag. Non-zero through a turn is the trailer time constant.
            s.put("aDelta", normDeg(Math.toDegrees(cart.a) - Math.toDegrees(player.a)));
        }

        // Only catches UI.error() messages -- UI.msg() info notices never reach lastError -- and
        // the read is destructive, so nothing else may be polling it during a telemetry run.
        String err = NUtils.getUI().getLastError();
        if (err != null) {
            s.put("err", err);
            JSONObject ev = new JSONObject();
            ev.put("t", now - t0);
            ev.put("kind", "error");
            ev.put("to", err);
            synchronized (lock) {
                events.add(ev);
            }
        }

        synchronized (lock) {
            samples.add(s);
        }
    }

    /**
     * Snapshot the scene at the moment the cart comes off: where both bodies were, and every
     * obstacle near the cart with its resolved hitbox. This is what turns an untie from an event
     * into a measurement -- it is the difference between knowing the cart failed and knowing which
     * object it failed against, and whether it was mid-turn at the time.
     *
     * <p>Runs on the core thread and walks the object cache, which is heavy -- but only on the
     * untie edge, which is rare. The oc monitor is taken before the record lock, never the reverse.
     */
    private void captureUntie(long now, Gob player, Gob cart) {
        JSONObject snap = new JSONObject();
        snap.put("t", now - t0);
        snap.put("plX", round(player.rc.x));
        snap.put("plY", round(player.rc.y));
        snap.put("plA", deg(player.a));
        snap.put("cartX", round(cart.rc.x));
        snap.put("cartY", round(cart.rc.y));
        snap.put("cartA", deg(cart.a));
        snap.put("d", round(cart.rc.dist(player.rc)));
        snap.put("aDelta", normDeg(Math.toDegrees(cart.a) - Math.toDegrees(player.a)));
        // Lets the analyser slice the preceding track out of the sample list.
        synchronized (lock) {
            snap.put("sampleIndex", samples.size());
        }

        JSONArray near = new JSONArray();
        OCache oc = NUtils.getGameUI().ui.sess.glob.oc;
        synchronized (oc) {
            for (Gob gob : oc) {
                if (gob.id == player.id || gob.id == cart.id)
                    continue;
                if (gob.ngob == null || gob.ngob.name == null || gob.ngob.hitBox == null)
                    continue;
                double dist = gob.rc.dist(cart.rc);
                if (dist > UNTIE_SCAN_RADIUS)
                    continue;
                JSONObject o = new JSONObject();
                o.put("name", gob.ngob.name);
                o.put("x", round(gob.rc.x));
                o.put("y", round(gob.rc.y));
                o.put("a", deg(gob.a));
                o.put("distToCart", round(dist));
                o.put("distToPlayer", round(gob.rc.dist(player.rc)));
                o.put("hbBeginX", round(gob.ngob.hitBox.begin.x));
                o.put("hbBeginY", round(gob.ngob.hitBox.begin.y));
                o.put("hbEndX", round(gob.ngob.hitBox.end.x));
                o.put("hbEndY", round(gob.ngob.hitBox.end.y));
                near.put(o);
            }
        }
        snap.put("nearCart", near);

        synchronized (lock) {
            unties.add(snap);
        }
    }

    private void noteChange(long now, String kind, String from, String to) {
        if (from != null && from.equals(to))
            return;
        if (from == null && to == null)
            return;
        JSONObject ev = new JSONObject();
        ev.put("t", now - t0);
        ev.put("kind", kind);
        if (from != null)
            ev.put("from", from);
        ev.put("to", to);
        synchronized (lock) {
            events.add(ev);
        }
    }

    /**
     * Locks onto the nearest vehicle once and keeps tracking it by id afterwards, so the trace
     * continues across an untie -- which is the transition we most want to see.
     */
    private Gob resolveCart(Gob player) {
        OCache oc = NUtils.getGameUI().ui.sess.glob.oc;
        if (cartId >= 0) {
            Gob known = oc.getgob(cartId);
            if (known != null)
                return known;
        }

        Gob best = null;
        double bestDist = LOCK_RADIUS;
        synchronized (oc) {
            for (Gob gob : oc) {
                if (gob.id == player.id || gob.ngob == null || gob.ngob.name == null)
                    continue;
                if (!NParser.checkName(gob.ngob.name, VEHICLE))
                    continue;
                double dist = gob.rc.dist(player.rc);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = gob;
                }
            }
        }
        if (best != null && best.id != cartId) {
            cartId = best.id;
            cartName = best.ngob.name;
            cartInfo = describeCart(best);
        }
        return best;
    }

    private JSONObject describeCart(Gob cart) {
        JSONObject o = new JSONObject();
        o.put("id", cart.id);
        o.put("name", cart.ngob.name);
        o.put("markerAtLock", VehicleMarker.markerOf(cart));
        if (cart.ngob.hitBox != null) {
            JSONObject hb = new JSONObject();
            hb.put("beginX", round(cart.ngob.hitBox.begin.x));
            hb.put("beginY", round(cart.ngob.hitBox.begin.y));
            hb.put("endX", round(cart.ngob.hitBox.end.x));
            hb.put("endY", round(cart.ngob.hitBox.end.y));
            o.put("hitBox", hb);
        }
        return o;
    }

    // ------------------------------------------------------------------ pf probe

    /**
     * Builds a real {@link NPFMap} centred on the player and reports whether the towed vehicle's id
     * shows up in it. This is the direct answer to "does the pathfinder treat my own cart as an
     * obstacle", with no inference from which attribute the cart happens to carry.
     *
     * <p>Runs on the bot thread: {@code build()} walks the whole object cache and rasterises every
     * hitbox, which does not belong on the core tick.
     */
    private void probePfMap() {
        Gob player = NUtils.player();
        if (player == null)
            return;

        NPFMap map = new NPFMap(player.rc, player.rc, 1);
        try {
            // build() reads tile types across the whole probe window, so a grid that has not
            // streamed in yet throws LoadingMap. Skipping one probe is fine; letting it escape
            // would end an unattended telemetry run.
            map.build();
        } catch (Loading l) {
            return;
        }

        JSONArray cells = new JSONArray();
        int blocked = 0;
        for (int i = 0; i < map.getSize(); i++) {
            for (int j = 0; j < map.getSize(); j++) {
                NPFMap.Cell cell = map.getCells()[i][j];
                if (cell.val != 0)
                    blocked++;
                if (cartId >= 0 && cell.content.contains(cartId)) {
                    JSONArray c = new JSONArray();
                    c.put(i);
                    c.put(j);
                    cells.put(c);
                }
            }
        }

        JSONObject probe = new JSONObject();
        probe.put("t", System.currentTimeMillis() - t0);
        probe.put("gridSize", map.getSize());
        probe.put("blockedCells", blocked);
        probe.put("cartInPfMap", cells.length() > 0);
        probe.put("cartCells", cells);

        Gob cart = (cartId >= 0) ? NUtils.getGameUI().ui.sess.glob.oc.getgob(cartId) : null;
        if (cart != null) {
            probe.put("cartFollowing", cart.getattr(Following.class) != null);
            // getCA() is what PathFinder consumes; null here means the cart contributes no cells.
            probe.put("cartHasCA", cart.ngob.getCA() != null);
        }
        synchronized (lock) {
            pfProbes.add(probe);
        }
    }

    // ------------------------------------------------------------------ output

    private void liveMsg(NGameUI gui) {
        Gob player = NUtils.player();
        if (player == null)
            return;
        Gob cart = (cartId >= 0) ? NUtils.getGameUI().ui.sess.glob.oc.getgob(cartId) : null;
        if (cart == null) {
            gui.tickmsg("cart: none in range");
            return;
        }
        Coord2d delta = cart.rc.sub(player.rc);
        Moving mv = cart.getattr(Moving.class);
        Boolean inPf;
        synchronized (lock) {
            inPf = pfProbes.isEmpty() ? null : pfProbes.get(pfProbes.size() - 1).optBoolean("cartInPfMap");
        }
        gui.tickmsg(String.format(
                "cart mark=%d towed=%s d=%.2f bearing=%.0f da=%.0f mv=%s follow=%s inPf=%s pose=%s",
                VehicleMarker.markerOf(cart),
                VehicleMarker.isTowed(cart) ? "Y" : "N",
                delta.abs(),
                normDeg(Math.toDegrees(Math.atan2(delta.y, delta.x)) - Math.toDegrees(player.a)),
                normDeg(Math.toDegrees(cart.a) - Math.toDegrees(player.a)),
                mv == null ? "-" : mv.getClass().getSimpleName(),
                cart.getattr(Following.class) != null ? "Y" : "N",
                inPf == null ? "?" : (inPf ? "Y" : "N"),
                player.pose()));
    }

    private void save() {
        // Snapshot under the lock, serialise outside it: sampling continues on the core thread
        // throughout, and toString(2) on a large trace is far too slow to hold a lock across.
        List<JSONObject> sampleCopy;
        List<JSONObject> eventCopy;
        List<JSONObject> probeCopy;
        List<JSONObject> untieCopy;
        synchronized (lock) {
            sampleCopy = new ArrayList<>(samples);
            eventCopy = new ArrayList<>(events);
            probeCopy = new ArrayList<>(pfProbes);
            untieCopy = new ArrayList<>(unties);
        }

        JSONObject main = new JSONObject();

        JSONObject info = new JSONObject();
        info.put("startTime", Instant.ofEpochMilli(t0).toString());
        info.put("endTime", Instant.now().toString());
        info.put("playerId", playerId);
        info.put("sampleCount", sampleCopy.size());
        info.put("truncated", truncated);
        info.put("cartName", cartName);
        main.put("runInfo", info);
        if (cartInfo != null)
            main.put("cart", cartInfo);

        main.put("events", new JSONArray(eventCopy));
        main.put("pfProbes", new JSONArray(probeCopy));
        main.put("unties", new JSONArray(untieCopy));
        main.put("samples", new JSONArray(sampleCopy));

        try (FileWriter writer = new FileWriter(outputFilePath, StandardCharsets.UTF_8)) {
            writer.write(main.toString(2));
        } catch (IOException e) {
            System.out.println("CartTelemetry: failed to save trace: " + e.getMessage());
        }
    }

    private String generateOutputFilePath() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        Path basePath;
        try {
            basePath = NUtils.getDataFilePath();
        } catch (RuntimeException e) {
            basePath = Paths.get(System.getProperty("user.home"));
        }
        return basePath.resolve("carttest_" + timestamp + ".json").toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String movingName(Gob gob) {
        Moving mv = gob.getattr(Moving.class);
        return mv == null ? null : mv.getClass().getSimpleName();
    }

    private static double velocity(Gob gob) {
        Moving mv = gob.getattr(Moving.class);
        if (mv == null)
            return 0;
        try {
            return round(mv.getv());
        } catch (Loading l) {
            return -1;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double deg(double radians) {
        return normDeg(Math.toDegrees(radians));
    }

    /** Normalise to (-180, 180] so heading differences read as small signed numbers. */
    private static double normDeg(double d) {
        double r = d % 360.0;
        if (r > 180.0)
            r -= 360.0;
        if (r <= -180.0)
            r += 360.0;
        return Math.round(r * 100.0) / 100.0;
    }
}
