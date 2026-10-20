package nurgling.diagnostics;

import haven.Coord2d;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.navigation.AreaNavigationHelper;
import nurgling.navigation.ChunkNavManager;
import nurgling.tasks.NTask;

import java.util.*;

/**
 * Benchmark walker for bot mode without a scenario. Tours the saved areas within
 * {@link #MAX_HOPS} chunks of the login spot with the bots' own navigation (local
 * pathfinding near, chunk navigation far), in an order fixed by a seed so that runs on the
 * same character walk the same route and can be compared. Without usable areas it falls back
 * to pathfinding between random nearby points. A movement/frame trace is saved every
 * {@link #TRACE_EVERY} ms throughout.
 */
public class WalkAround implements Action {
    private static final int MAX_HOPS = 2;
    private static final long SEED = 20261019;
    private static final double RADIUS = 30 * MCache.tilesz.x;
    private static final long TRACE_EVERY = 11000, LEG_TIMEOUT = 20000;
    private final double seconds;
    private final int[] route;

    /** @param route area ids to tour in order, or null to pick nearby areas by seed */
    public WalkAround(double seconds, int[] route) {
        this.seconds = seconds;
        this.route = route;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        if (NUtils.player() == null)
            return Results.FAIL();
        NUtils.setSpeed(2);
        if (route != null) {
            List<NArea> areas = new ArrayList<>();
            for (int id : route) {
                NArea area = gui.map.glob.map.areas.get(id);
                if (area != null)
                    areas.add(area);
            }
            /* Reach the start of the route first, untimed and untraced, so every run
             * measures the same walk wherever the character logged in. */
            if (areas.isEmpty() || !NUtils.navigateToArea(areas.get(0), true))
                return Results.FAIL();
            long end = System.currentTimeMillis() + (long) (seconds * 1000);
            Thread tracer = startTracer(gui, end);
            try {
                follow(areas, end);
            } finally {
                tracer.interrupt();
            }
            return Results.SUCCESS();
        }
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        Thread tracer = startTracer(gui, end);
        try {
            List<NArea> areas = nearbyAreas(gui);
            System.out.println("[WalkAround] touring " + areas.size() + " areas within " + MAX_HOPS + " chunks");
            if (areas.size() >= 2)
                tour(areas, end);
            else
                wander(gui, end);
        } finally {
            tracer.interrupt();
        }
        return Results.SUCCESS();
    }

    private static void follow(List<NArea> areas, long end) throws InterruptedException {
        int legs = 0, failed = 0;
        for (int i = 1; System.currentTimeMillis() < end; i++) {
            if (NUtils.navigateToArea(areas.get(i % areas.size()), true))
                legs++;
            else
                failed++;
        }
        System.out.println("[WalkAround] done: " + legs + " route legs, " + failed + " failed");
    }

    private static void tour(List<NArea> areas, long end) throws InterruptedException {
        Random rnd = new Random(SEED);
        NArea last = null;
        int legs = 0, failed = 0;
        while (System.currentTimeMillis() < end) {
            NArea next;
            do {
                next = areas.get(rnd.nextInt(areas.size()));
            } while (next == last);
            last = next;
            System.out.println("[WalkAround] area " + next.id);
            if (NUtils.navigateToArea(next, true))
                legs++;
            else
                failed++;
        }
        System.out.println("[WalkAround] done: " + legs + " areas reached, " + failed + " failed");
    }

    private static void wander(NGameUI gui, long end) throws InterruptedException {
        Coord2d home = NUtils.player().rc;
        Random rnd = new Random(SEED);
        int legs = 0, failed = 0;
        while (System.currentTimeMillis() < end) {
            double a = rnd.nextDouble() * 2 * Math.PI, r = RADIUS * (0.5 + rnd.nextDouble() * 0.5);
            Coord2d target = home.add(Math.cos(a) * r, Math.sin(a) * r);
            if (!PathFinder.isAvailable(target)) {
                failed++;
                NUtils.addTask(new Ticks(1));
                continue;
            }
            PathFinder pf = new PathFinder(target);
            long deadline = System.currentTimeMillis() + LEG_TIMEOUT;
            pf.abort = () -> System.currentTimeMillis() > deadline;
            if (pf.run(gui).IsSuccess())
                legs++;
            else
                failed++;
        }
        System.out.println("[WalkAround] done: " + legs + " legs walked, " + failed + " unreachable");
    }

    /* Saved areas whose chunks are within MAX_HOPS of the player, in id order so the seeded
     * tour is the same every run. Without chunk navigation, areas local pathfinding reaches. */
    private static List<NArea> nearbyAreas(NGameUI gui) throws InterruptedException {
        ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        Map<Long, Integer> hops = null;
        if ((nav != null) && nav.isEnabled() && nav.isInitialized()) {
            /* The player's chunk is recorded shortly after login. */
            for (int i = 0; (i < 50) && ((hops = nav.chunkHopsFromPlayer()) == null); i++)
                NUtils.addTask(new Ticks(10));
        }
        List<NArea> ret = new ArrayList<>();
        List<Integer> ids = new ArrayList<>(gui.map.glob.map.areas.keySet());
        Collections.sort(ids);
        for (Integer id : ids) {
            NArea area = gui.map.glob.map.areas.get(id);
            if ((area == null) || (area.space == null))
                continue;
            boolean near;
            if (hops != null) {
                near = false;
                for (Long grid : area.space.space.keySet()) {
                    Integer h = hops.get(grid);
                    if ((h != null) && (h <= MAX_HOPS)) {
                        near = true;
                        break;
                    }
                }
            } else {
                near = AreaNavigationHelper.isAreaReachableByLocalPF(area);
            }
            if (near)
                ret.add(area);
        }
        return ret;
    }

    private static Thread startTracer(NGameUI gui, long end) {
        Thread t = new Thread(() -> {
            try {
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(TRACE_EVERY);
                    MovementTrace.trigger(gui.ui);
                }
            } catch (InterruptedException e) {
                /* Walk finished. */
            }
        }, "WalkAround tracer");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static class Ticks extends NTask {
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
