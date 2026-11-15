package nurgling.actions.bots;

import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import haven.Pair;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.navigation.ChunkNavManager;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Area-to-area navigation test.
 *
 * From where the character stands it picks reachable areas (recorded and with a real chunk-navigation route,
 * spread from near to far up to maxCostTiles) and unavailable ones (no portal link to them, walled off, or never
 * visited). It tours the reachable ones in three orders (forward, reverse, shuffled), with the unavailable ones
 * mixed in. Unavailable legs must fail, quickly and without wandering off.
 *
 * Per leg it logs: time until the character starts moving, total time, result, distance from the area's edge at the
 * end, and how far the character moved. CSV goes to the client data dir under nav_test/.
 */
public class AreaNavigationTest implements Action {
    private final int reachableCount;
    private final int unavailableCount;
    private final double maxCostTiles;
    private final long seed;

    public AreaNavigationTest() {
        this(Collections.emptyMap());
    }

    public AreaNavigationTest(Map<String, Object> settings) {
        reachableCount = intOf(settings, "reachable", 5);
        unavailableCount = intOf(settings, "unavailable", 3);
        maxCostTiles = intOf(settings, "maxCostTiles", 600);
        seed = intOf(settings, "seed", 20261114);
    }

    private static int intOf(Map<String, Object> s, String k, int def) {
        Object v = s == null ? null : s.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static final class Leg {
        String order;
        NArea target;
        boolean expectReach;
        String kind;
        boolean ok;
        long ms, moveMs;
        double endTiles = Double.NaN, movedTiles;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (!(gui.map instanceof NMapView) || NUtils.player() == null) return Results.ERROR("No map or player");
        ChunkNavManager nav = ((NMapView) gui.map).getChunkNavManager();
        if (nav == null || !nav.isInitialized()) return Results.ERROR("Chunk navigation is not initialized");

        Random rnd = new Random(seed);
        List<NArea> reachable = new ArrayList<>();
        List<NArea> unavailable = new ArrayList<>();
        List<String> unavailableKinds = new ArrayList<>();
        pickAreas(gui, nav, reachable, unavailable, unavailableKinds, rnd);
        if (reachable.size() < 2) return Results.ERROR("Found fewer than 2 reachable areas within " + (int) maxCostTiles + " tiles");
        gui.msg("Nav test: " + reachable.size() + " reachable, " + unavailable.size() + " unavailable areas");
        System.out.println("[NavTest] reachable: " + describe(reachable));
        System.out.println("[NavTest] unavailable: " + describe(unavailable) + " " + unavailableKinds);

        List<List<NArea>> orders = new ArrayList<>();
        List<String> orderNames = new ArrayList<>();
        orders.add(new ArrayList<>(reachable)); orderNames.add("forward");
        List<NArea> rev = new ArrayList<>(reachable);
        Collections.reverse(rev);
        orders.add(rev); orderNames.add("reverse");
        List<NArea> shuf = new ArrayList<>(reachable);
        Collections.shuffle(shuf, rnd);
        orders.add(shuf); orderNames.add("shuffled");

        Path file = outFile();
        List<Leg> legs = new ArrayList<>();
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            csv.println("order,targetId,targetName,expect,kind,ok,ms,moveMs,endTiles,movedTiles");
            for (int o = 0; o < orders.size(); o++) {
                List<NArea> route = withUnavailable(orders.get(o), unavailable);
                // Every order starts from its first area, reached untimed.
                if (!NUtils.navigateToArea(route.get(0), true)) {
                    System.out.println("[NavTest] could not reach the start of order " + orderNames.get(o) + ", skipping it");
                    continue;
                }
                for (int i = 1; i < route.size(); i++) {
                    NArea target = route.get(i);
                    Leg leg = new Leg();
                    leg.order = orderNames.get(o);
                    leg.target = target;
                    int ui = unavailable.indexOf(target);
                    leg.expectReach = ui < 0;
                    leg.kind = ui < 0 ? "reachable" : unavailableKinds.get(ui);
                    runLeg(leg);
                    legs.add(leg);
                    csv.printf(Locale.ROOT, "%s,%d,%s,%s,%s,%b,%d,%d,%.1f,%.1f%n", leg.order, target.id,
                            target.name.replace(',', ' '), leg.expectReach ? "reach" : "fail", leg.kind, leg.ok, leg.ms, leg.moveMs,
                            leg.endTiles, leg.movedTiles);
                    csv.flush();
                    System.out.printf(Locale.ROOT, "[NavTest] %s -> %s (%s): %s in %d ms, moved after %d ms, end %.1f tiles from area, moved %.0f tiles%n",
                            leg.order, target.name, leg.kind, leg.ok ? "OK" : "FAIL", leg.ms, leg.moveMs, leg.endTiles, leg.movedTiles);
                    // A reachable leg that failed leaves us somewhere else: carry on from wherever we are.
                }
            }
            String summary = summary(legs);
            csv.println();
            for (String line : summary.split("\n")) csv.println("# " + line);
            System.out.println("[NavTest] summary\n" + summary);
            for (String line : summary.split("\n")) gui.msg(line);
        } catch (IOException e) {
            return Results.ERROR("Could not write " + file + ": " + e.getMessage());
        }
        gui.msg("Nav test log: " + file);
        return Results.SUCCESS();
    }

    private void runLeg(Leg leg) throws InterruptedException {
        Gob player = NUtils.player();
        Coord2d from = player.rc;
        MoveWatch watch = new MoveWatch(player);
        watch.start();
        long t0 = System.currentTimeMillis();
        try {
            leg.ok = NUtils.navigateToArea(leg.target, true);
        } finally {
            watch.stopWatch();
        }
        leg.ms = System.currentTimeMillis() - t0;
        leg.moveMs = watch.movedAt > 0 ? watch.movedAt - t0 : -1;
        Gob now = NUtils.player();
        if (now != null) {
            leg.movedTiles = now.rc.dist(from) / MCache.tilesz.x;
            Pair<Coord2d, Coord2d> rc = leg.target.getRCArea();
            if (rc != null) leg.endTiles = outsideDistance(rc, now.rc) / MCache.tilesz.x;
        }
    }

    /** Distance from p to the rectangle, 0 inside. */
    private static double outsideDistance(Pair<Coord2d, Coord2d> rc, Coord2d p) {
        double x0 = Math.min(rc.a.x, rc.b.x), x1 = Math.max(rc.a.x, rc.b.x);
        double y0 = Math.min(rc.a.y, rc.b.y), y1 = Math.max(rc.a.y, rc.b.y);
        double dx = Math.max(0, Math.max(x0 - p.x, p.x - x1));
        double dy = Math.max(0, Math.max(y0 - p.y, p.y - y1));
        return Math.hypot(dx, dy);
    }

    /** Notes when the character first moves more than a few units, sampling the gob position every 20 ms. */
    private static final class MoveWatch {
        private final Gob player;
        private final Coord2d start;
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NavTest-MoveWatch");
            t.setDaemon(true);
            return t;
        });
        volatile long movedAt = 0;

        MoveWatch(Gob player) {
            this.player = player;
            this.start = player.rc;
        }

        void start() {
            exec.scheduleAtFixedRate(() -> {
                if (movedAt == 0 && player.rc.dist(start) > 2) movedAt = System.currentTimeMillis();
            }, 0, 20, TimeUnit.MILLISECONDS);
        }

        void stopWatch() {
            exec.shutdownNow();
        }
    }

    private void pickAreas(NGameUI gui, ChunkNavManager nav, List<NArea> reachable, List<NArea> unavailable,
                           List<String> unavailableKinds, Random rnd) {
        Gob player = NUtils.player();
        List<NArea> all = new ArrayList<>(gui.map.glob.map.areas.values());
        all.sort(Comparator.comparingInt(a -> a.id));
        List<NArea> noRoute = new ArrayList<>(), walled = new ArrayList<>(), unrecorded = new ArrayList<>();
        List<NArea> routable = new ArrayList<>();
        for (NArea a : all) {
            if (a.space == null || a.space.space == null || a.space.space.isEmpty()) continue;
            boolean recorded = false;
            for (Long gid : a.space.space.keySet()) recorded |= nav.getGraph().getChunk(gid) != null;
            if (!recorded) { unrecorded.add(a); continue; }
            if (!nav.isAreaReachableByChunks(a)) { noRoute.add(a); continue; }
            routable.add(a);
        }
        double[] cost = nav.costsToAreas(routable);
        List<NArea> candidates = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        for (int i = 0; i < routable.size(); i++) {
            NArea a = routable.get(i);
            if (cost[i] == Double.MAX_VALUE) { walled.add(a); continue; }
            if (a.checkHit(player.rc) || a.isDisabled()) continue;
            if (cost[i] > maxCostTiles || cost[i] < 15) continue;
            candidates.add(a);
            costs.add(cost[i]);
        }
        // Spread the reachable picks from near to far.
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(costs::get));
        int k = Math.min(reachableCount, order.length);
        for (int i = 0; i < k; i++) {
            int idx = order[(int) Math.round(i * (order.length - 1) / (double) Math.max(1, k - 1))];
            NArea a = candidates.get(idx);
            if (!reachable.contains(a)) reachable.add(a);
        }
        // Unavailable: one of each kind first, then whatever is left.
        List<List<NArea>> kinds = Arrays.asList(noRoute, walled, unrecorded);
        String[] kindNames = {"no-chunk-route", "walled-off", "never-visited"};
        for (List<NArea> l : kinds) Collections.shuffle(l, rnd);
        for (int round = 0; unavailable.size() < unavailableCount && round < 50; round++) {
            boolean any = false;
            for (int i = 0; i < kinds.size() && unavailable.size() < unavailableCount; i++) {
                if (round < kinds.get(i).size()) {
                    unavailable.add(kinds.get(i).get(round));
                    unavailableKinds.add(kindNames[i]);
                    any = true;
                }
            }
            if (!any) break;
        }
    }

    /** The route with the unavailable areas spread through it (never first: the start must be reachable). */
    private static List<NArea> withUnavailable(List<NArea> route, List<NArea> unavailable) {
        List<NArea> res = new ArrayList<>(route);
        for (int i = 0; i < unavailable.size(); i++) {
            int at = 1 + (int) Math.round((i + 1) * (res.size() - 1) / (double) (unavailable.size() + 1));
            res.add(Math.min(at, res.size()), unavailable.get(i));
        }
        return res;
    }

    private static String summary(List<Leg> legs) {
        List<Long> ms = new ArrayList<>(), move = new ArrayList<>(), failMs = new ArrayList<>();
        List<Double> end = new ArrayList<>();
        int reach = 0, reachOk = 0, fail = 0, failOk = 0;
        double maxFailMove = 0;
        for (Leg l : legs) {
            if (l.expectReach) {
                reach++;
                if (l.ok) {
                    reachOk++;
                    ms.add(l.ms);
                    if (l.moveMs >= 0) move.add(l.moveMs);
                    if (!Double.isNaN(l.endTiles)) end.add(l.endTiles);
                }
            } else {
                fail++;
                if (!l.ok) failOk++;
                failMs.add(l.ms);
                maxFailMove = Math.max(maxFailMove, l.movedTiles);
            }
        }
        return String.format(Locale.ROOT,
                "reached %d/%d, median %s ms (start moving after %s ms), end %s tiles from edge | must-fail legs failed %d/%d, median %s ms, max moved %.0f tiles",
                reachOk, reach, med(ms), med(move), medD(end), failOk, fail, med(failMs), maxFailMove);
    }

    private static String med(List<Long> l) {
        if (l.isEmpty()) return "-";
        List<Long> c = new ArrayList<>(l);
        Collections.sort(c);
        return String.valueOf(c.get(c.size() / 2));
    }

    private static String medD(List<Double> l) {
        if (l.isEmpty()) return "-";
        List<Double> c = new ArrayList<>(l);
        Collections.sort(c);
        return String.format(Locale.ROOT, "%.1f", c.get(c.size() / 2));
    }

    private static String describe(List<NArea> l) {
        StringBuilder sb = new StringBuilder();
        for (NArea a : l) sb.append(a.id).append(":").append(a.name).append(" ");
        return sb.toString().trim();
    }

    private static Path outFile() {
        Path dir = NUtils.getDataFilePath("nav_test");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // fall through, the writer reports it
        }
        return dir.resolve("nav_test_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".csv");
    }
}
