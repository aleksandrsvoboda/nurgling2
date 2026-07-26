package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.navigation.AreaNavigationHelper;
import nurgling.navigation.ChunkNavManager;
import nurgling.navigation.ChunkPath;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static nurgling.NUtils.getGameUI;

public class HoneyAndWaxCollector implements Action {

    private static final NAlias BEEHIVE = new NAlias("gfx/terobjs/beehive");
    private static final NAlias HONEY_OVERLAY = new NAlias("Honey");
    private static final NAlias BARREL_ALIAS = new NAlias("barrel");
    private static final NAlias CISTERN_ALIAS = new NAlias("cistern");

    private static final Coord ITEM_SIZE = Coord.of(1, 1);
    private static final int MIN_FREE_SLOTS = 5;

    /**
     * Frames to wait for a honey pull to resolve. A successful pull empties the hive and changes its
     * model attribute, so the wait exits early; this bound is only reached when the attribute never
     * moves, which is the barrel-full case.
     */
    private static final int RESOLVE_TICKS = 300;
    private static final int POUR_TICKS = 300;

    /**
     * Pulling honey is a partial transfer, so a single hive can legitimately need more than one barrel.
     * Beyond this many consecutive cistern trips at the same hive something else is wrong - give up on it
     * rather than shuttling forever.
     */
    private static final int MAX_FULL_RETRIES = 3;
    private static final int MAX_UNLOAD_RETRIES = 2;
    private static final int MAX_WAX_REPEATS = 5;

    /** Rescans per area, to pick up hives that only streamed in as the bot walked the sweep. */
    private static final int MAX_SCANS = 8;

    /** Gap along the bucketing axis above which two hives belong to different serpentine rows. */
    private static final double ROW_GAP = MCache.tilesz.x * 1.5;

    /** Hives that failed pathfinding this run. Shared by both passes so neither retries them. */
    private final Set<Long> unreachable = new HashSet<>();

    /** Per-area hive ids seen holding wax during the honey pass, used to skip wax-empty areas. */
    private final HashMap<Integer, Set<Long>> waxRecon = new HashMap<>();
    private boolean honeyReconDone = false;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        // Phase 0: UI config
        nurgling.widgets.bots.BeehiveManagerWnd w = null;
        boolean collectHoney;
        boolean collectWax;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable(
                    getGameUI().add((w = new nurgling.widgets.bots.BeehiveManagerWnd()), UI.scale(200, 200))));
            collectHoney = w.collectHoney;
            collectWax = w.collectWax;
        } catch (InterruptedException e) {
            throw e;
        } finally {
            if (w != null)
                w.destroy();
        }

        if (!collectHoney && !collectWax) {
            return Results.ERROR("Nothing selected");
        }

        NContext context = new NContext(gui);

        // Phase 1: Find all bee skep areas
        ArrayList<NArea> beeSkepAreas = NContext.findAllSpec(Specialisation.SpecName.beeSkep.toString());
        if (beeSkepAreas.isEmpty()) {
            getGameUI().error("No Bee Skep areas found");
            return Results.ERROR("No Bee Skep areas found");
        }

        // Phase 2: Honey collection
        if (collectHoney) {
            Results honeyResult = collectAllHoney(gui, context, beeSkepAreas);
            if (!honeyResult.IsSuccess())
                return honeyResult;
        }

        // Phase 3: Wax collection
        if (collectWax) {
            Results waxResult = collectAllWax(gui, context, beeSkepAreas);
            if (!waxResult.IsSuccess())
                return waxResult;
        }

        return Results.SUCCESS();
    }

    // ================================================================= honey

    private Results collectAllHoney(NGameUI gui, NContext context, ArrayList<NArea> beeSkepAreas) throws InterruptedException {
        NArea cisternArea = context.goToArea(Specialisation.SpecName.cistern, "Honey");
        if (cisternArea == null) {
            getGameUI().error("No Cistern area with Honey specialization found");
            return Results.ERROR("No Cistern area with Honey specialization");
        }

        Gob barrel = Finder.findGob(cisternArea, BARREL_ALIAS);
        if (barrel == null) {
            getGameUI().error("No barrel found in Honey cistern area");
            return Results.ERROR("No barrel in cistern area");
        }
        Gob cistern = Finder.findGob(cisternArea, CISTERN_ALIAS);
        if (cistern == null) {
            getGameUI().error("No cistern found in Honey cistern area");
            return Results.ERROR("No cistern in cistern area");
        }

        Coord2d barrelOriginalPos = barrel.rc;

        new LiftObject(barrel).run(gui);

        // If barrel already has honey, empty it first
        if (NUtils.isOverlay(barrel, HONEY_OVERLAY)) {
            Results r = emptyBarrelAtCistern(gui, barrel, cistern);
            if (!r.IsSuccess())
                return r;
        }

        // Cistern-anchored tour: pick the cheapest remaining area from wherever we currently stand,
        // rather than trusting the radial sort findAllSpec did from the bot's start position.
        ArrayList<NArea> remaining = new ArrayList<>(beeSkepAreas);
        while (!remaining.isEmpty()) {
            NArea beeArea = takeNearestArea(gui, remaining);
            NUtils.navigateToArea(beeArea, true);

            Results r = sweepAreaForHoney(gui, beeArea, cisternArea, barrel, cistern);
            if (!r.IsSuccess())
                return r;

            recordWax(beeArea);
        }

        NUtils.navigateToArea(cisternArea, true);
        if (NUtils.isOverlay(barrel, HONEY_OVERLAY)) {
            Results r = emptyBarrelAtCistern(gui, barrel, cistern);
            if (!r.IsSuccess())
                return r;
        }

        new PlaceObject(barrel, barrelOriginalPos, 0).run(gui);

        honeyReconDone = true;
        getGameUI().msg("Honey collection done!");
        return Results.SUCCESS();
    }

    /**
     * Sweeps one area, rescanning until no previously unseen hive turns up. A bee yard can be larger
     * than the gob streaming radius, so the first enumeration is not necessarily complete - walking the
     * sweep loads the rest.
     */
    private Results sweepAreaForHoney(NGameUI gui, NArea beeArea, NArea cisternArea, Gob barrel, Gob cistern) throws InterruptedException {
        Set<Long> handled = new HashSet<>();
        for (int scan = 0; scan < MAX_SCANS; scan++) {
            ArrayList<Gob> plan = planSweep(beeArea, handled);
            if (plan.isEmpty())
                break;
            Results r = runHoneyPlan(gui, plan, handled, beeArea, cisternArea, barrel, cistern);
            if (!r.IsSuccess())
                return r;
            recordWax(beeArea);
        }
        return Results.SUCCESS();
    }

    private Results runHoneyPlan(NGameUI gui, ArrayList<Gob> plan, Set<Long> handled, NArea beeArea,
                                 NArea cisternArea, Gob barrel, Gob cistern) throws InterruptedException {
        int i = 0;
        int fullRetries = 0;
        int unloadRetries = 0;

        while (i < plan.size()) {
            long id = plan.get(i).id;
            Gob hive = Finder.findGob(id);

            if (hive == null) {
                // Streamed out - skip it in this pass but leave it unhandled so a rescan can retry it
                // once more of the area has loaded.
                i++;
                fullRetries = 0;
                unloadRetries = 0;
                continue;
            }

            noteWax(beeArea, hive);

            if (unreachable.contains(id) || !hasHoney(hive)) {
                handled.add(id);
                i++;
                fullRetries = 0;
                unloadRetries = 0;
                continue;
            }

            PathFinder pf = new PathFinder(hive);
            pf.isHardMode = true;
            if (!pf.run(gui).IsSuccess()) {
                unreachable.add(id);
                handled.add(id);
                i++;
                fullRetries = 0;
                unloadRetries = 0;
                continue;
            }

            switch (pullHoney(hive)) {
                case EMPTIED:
                    handled.add(id);
                    i++;
                    fullRetries = 0;
                    unloadRetries = 0;
                    break;

                case BARREL_FULL:
                    // The hive still holds honey, so it must be revisited - leave i where it is and
                    // resume on exactly this hive after the cistern trip.
                    if (++fullRetries > MAX_FULL_RETRIES) {
                        getGameUI().error("Beehive still holds honey after " + MAX_FULL_RETRIES + " barrel trips, skipping it");
                        unreachable.add(id);
                        handled.add(id);
                        i++;
                        fullRetries = 0;
                        break;
                    }
                    NUtils.navigateToArea(cisternArea, true);
                    Results emptied = emptyBarrelAtCistern(gui, barrel, cistern);
                    if (!emptied.IsSuccess())
                        return emptied;
                    NUtils.navigateToArea(beeArea, true);
                    break;

                case UNLOADED:
                    // The hive streamed out mid-pull. That is a retry, not a full barrel - and it stays
                    // unhandled so a later rescan can still pick it up.
                    if (++unloadRetries > MAX_UNLOAD_RETRIES) {
                        i++;
                        unloadRetries = 0;
                    }
                    break;
            }
        }
        return Results.SUCCESS();
    }

    private enum PullOutcome {
        /** The hive gave up all its honey. */
        EMPTIED,
        /** The hive still holds honey after the pull, so the barrel has no room left. */
        BARREL_FULL,
        /** The hive left the object cache before the pull resolved. */
        UNLOADED
    }

    /**
     * Pulling honey is a partial transfer: it moves whatever fits. The only way to learn the barrel is
     * full is to attempt a pull and see that the hive still has honey afterwards - so this waits for the
     * pull to resolve and then reads hive state, rather than treating "attribute changed" as success.
     */
    private PullOutcome pullHoney(Gob hive) throws InterruptedException {
        long id = hive.id;
        long attrBefore = hive.ngob.getModelAttribute();

        NUtils.activateGob(hive);
        NUtils.getUI().core.addTask(new WaitPullResolved(id, attrBefore));

        Gob after = Finder.findGob(id);
        if (after == null)
            return PullOutcome.UNLOADED;
        return hasHoney(after) ? PullOutcome.BARREL_FULL : PullOutcome.EMPTIED;
    }

    private Results emptyBarrelAtCistern(NGameUI gui, Gob barrel, Gob cistern) throws InterruptedException {
        new PathFinder(cistern).run(gui);
        NUtils.activateGob(cistern);

        WaitBarrelEmpty wait = new WaitBarrelEmpty(barrel);
        NUtils.getUI().core.addTask(wait);
        if (!wait.emptied) {
            getGameUI().error("Could not empty the barrel at the cistern");
            return Results.ERROR("Barrel not emptied at cistern");
        }
        return Results.SUCCESS();
    }

    /**
     * Records which hives in this area hold wax, so the wax pass can skip areas with none. Called after
     * every scan rather than once at the end, because a single enumeration only sees the hives that
     * happen to be loaded right now.
     */
    private void recordWax(NArea area) throws InterruptedException {
        Set<Long> withWax = waxRecon.computeIfAbsent(area.id, k -> new HashSet<>());
        for (Gob hive : Finder.findGobs(area, BEEHIVE)) {
            if (hasWax(hive))
                withWax.add(hive.id);
        }
    }

    /** Notes a single hive's wax state while we happen to be standing at it during the honey pass. */
    private void noteWax(NArea area, Gob hive) {
        if (hasWax(hive))
            waxRecon.computeIfAbsent(area.id, k -> new HashSet<>()).add(hive.id);
    }

    // =================================================================== wax

    private Results collectAllWax(NGameUI gui, NContext context, ArrayList<NArea> beeSkepAreas) throws InterruptedException {
        ArrayList<NArea> remaining = new ArrayList<>();
        for (NArea area : beeSkepAreas) {
            if (honeyReconDone) {
                Set<Long> wax = waxRecon.get(area.id);
                // The honey pass already walked this area and saw no wax anywhere in it - do not travel
                // out there a second time just to confirm.
                if (wax != null && wax.isEmpty())
                    continue;
            }
            remaining.add(area);
        }

        if (remaining.isEmpty()) {
            getGameUI().msg("No wax seen during the honey pass, skipping wax collection");
            return Results.SUCCESS();
        }

        while (!remaining.isEmpty()) {
            NArea beeArea = takeNearestArea(gui, remaining);
            NUtils.navigateToArea(beeArea, true);

            Results r = sweepAreaForWax(gui, context, beeArea);
            if (!r.IsSuccess())
                return r;
        }

        new FreeInventory2(context).run(gui);
        getGameUI().msg("Wax collection done!");
        return Results.SUCCESS();
    }

    private Results sweepAreaForWax(NGameUI gui, NContext context, NArea beeArea) throws InterruptedException {
        Set<Long> handled = new HashSet<>();
        for (int scan = 0; scan < MAX_SCANS; scan++) {
            ArrayList<Gob> plan = planSweep(beeArea, handled);
            if (plan.isEmpty())
                break;
            Results r = runWaxPlan(gui, context, plan, handled, beeArea);
            if (!r.IsSuccess())
                return r;
        }
        return Results.SUCCESS();
    }

    private Results runWaxPlan(NGameUI gui, NContext context, ArrayList<Gob> plan, Set<Long> handled,
                               NArea beeArea) throws InterruptedException {
        int i = 0;
        int repeats = 0;

        while (i < plan.size()) {
            long id = plan.get(i).id;
            Gob hive = Finder.findGob(id);

            if (hive == null) {
                // Left unhandled on purpose so a rescan can retry it once more of the area has loaded.
                i++;
                repeats = 0;
                continue;
            }

            if (unreachable.contains(id) || !hasWax(hive)) {
                handled.add(id);
                i++;
                repeats = 0;
                continue;
            }

            if (gui.getInventory().getNumberFreeCoord(ITEM_SIZE) < MIN_FREE_SLOTS) {
                new FreeInventory2(context).run(gui);
                if (gui.getInventory().getNumberFreeCoord(ITEM_SIZE) < MIN_FREE_SLOTS) {
                    getGameUI().error("Inventory is full and nothing could be deposited");
                    return Results.ERROR("Inventory full, nothing deposited");
                }
                NUtils.navigateToArea(beeArea, true);
                // i is unchanged - resume on the hive we were about to harvest.
                continue;
            }

            PathFinder pf = new PathFinder(hive);
            pf.isHardMode = true;
            if (!pf.run(gui).IsSuccess()) {
                unreachable.add(id);
                handled.add(id);
                i++;
                repeats = 0;
                continue;
            }

            new SelectFlowerAction("Harvest wax", hive).run(gui);
            NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/bushpickan"));
            NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/idle"));

            // A hive can hold more than one harvest; stay on it while it still shows wax.
            if (++repeats >= MAX_WAX_REPEATS) {
                handled.add(id);
                i++;
                repeats = 0;
            }
        }
        return Results.SUCCESS();
    }

    // ============================================================== ordering

    /**
     * Removes and returns the cheapest area to travel to from the player's current position. Uses real
     * ChunkNav path cost when every candidate has one, falling back to straight-line distance so the two
     * cost units are never mixed in a single comparison.
     */
    private NArea takeNearestArea(NGameUI gui, ArrayList<NArea> remaining) throws InterruptedException {
        if (remaining.size() == 1)
            return remaining.remove(0);

        ChunkNavManager chunkNav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;

        double[] costs = new double[remaining.size()];
        boolean allChunkCosts = chunkNav != null && chunkNav.isInitialized();
        if (allChunkCosts) {
            for (int i = 0; i < remaining.size(); i++) {
                double cost = chunkCost(remaining.get(i), chunkNav);
                if (cost == Double.MAX_VALUE) {
                    allChunkCosts = false;
                    break;
                }
                costs[i] = cost;
            }
        }
        if (!allChunkCosts) {
            for (int i = 0; i < remaining.size(); i++)
                costs[i] = straightLineCost(remaining.get(i));
        }

        int best = 0;
        for (int i = 1; i < remaining.size(); i++) {
            if (costs[i] < costs[best])
                best = i;
        }
        return remaining.remove(best);
    }

    private double chunkCost(NArea area, ChunkNavManager chunkNav) throws InterruptedException {
        if (!chunkNav.isAreaReachableByChunks(area))
            return Double.MAX_VALUE;
        ChunkPath path = AreaNavigationHelper.findShortestPathToAreaCorners(area, chunkNav);
        return path != null ? path.totalCost : Double.MAX_VALUE;
    }

    private double straightLineCost(NArea area) {
        Pair<Coord2d, Coord2d> rc = area.getRCArea();
        Gob player = NUtils.player();
        if (rc == null || player == null)
            return Double.MAX_VALUE;
        return player.rc.dist(rc.a.add(rc.b).div(2));
    }

    private ArrayList<Gob> planSweep(NArea area, Set<Long> handled) throws InterruptedException {
        ArrayList<Gob> hives = Finder.findGobs(area, BEEHIVE);
        hives.removeIf(hive -> handled.contains(hive.id) || unreachable.contains(hive.id));

        Gob player = NUtils.player();
        return serpentine(hives, player != null ? player.rc : null);
    }

    /**
     * Orders hives as a boustrophedon sweep: rows run along the yard's longer axis and alternate
     * direction, so every hive is visited once with no backtracking. Entry orientation is chosen so the
     * sweep starts at the corner nearest where we are standing.
     */
    private ArrayList<Gob> serpentine(ArrayList<Gob> hives, Coord2d entry) {
        if (hives.size() < 2 || entry == null)
            return hives;

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Gob hive : hives) {
            minX = Math.min(minX, hive.rc.x);
            maxX = Math.max(maxX, hive.rc.x);
            minY = Math.min(minY, hive.rc.y);
            maxY = Math.max(maxY, hive.rc.y);
        }
        // Rows run along the longer axis, stacked across the shorter one - fewer turns that way.
        final boolean rowsAlongX = (maxX - minX) >= (maxY - minY);

        Comparator<Gob> byBucket = Comparator.comparingDouble(hive -> rowsAlongX ? hive.rc.y : hive.rc.x);
        Comparator<Gob> bySweep = Comparator.comparingDouble(hive -> rowsAlongX ? hive.rc.x : hive.rc.y);

        ArrayList<Gob> sorted = new ArrayList<>(hives);
        sorted.sort(byBucket);

        ArrayList<ArrayList<Gob>> rows = new ArrayList<>();
        ArrayList<Gob> row = new ArrayList<>();
        double prev = 0;
        for (Gob hive : sorted) {
            double v = rowsAlongX ? hive.rc.y : hive.rc.x;
            if (!row.isEmpty() && v - prev > ROW_GAP) {
                rows.add(row);
                row = new ArrayList<>();
            }
            row.add(hive);
            prev = v;
        }
        if (!row.isEmpty())
            rows.add(row);

        // Start from whichever end of the stack we are closest to.
        double entryBucket = rowsAlongX ? entry.y : entry.x;
        double firstBucket = rowsAlongX ? rows.get(0).get(0).rc.y : rows.get(0).get(0).rc.x;
        ArrayList<Gob> lastRow = rows.get(rows.size() - 1);
        double lastBucket = rowsAlongX ? lastRow.get(0).rc.y : lastRow.get(0).rc.x;
        if (Math.abs(entryBucket - lastBucket) < Math.abs(entryBucket - firstBucket))
            Collections.reverse(rows);

        // ...and from whichever end of the first row we are closest to.
        ArrayList<Gob> head = new ArrayList<>(rows.get(0));
        head.sort(bySweep);
        double entrySweep = rowsAlongX ? entry.x : entry.y;
        double lo = rowsAlongX ? head.get(0).rc.x : head.get(0).rc.y;
        double hi = rowsAlongX ? head.get(head.size() - 1).rc.x : head.get(head.size() - 1).rc.y;
        boolean forward = Math.abs(entrySweep - lo) <= Math.abs(entrySweep - hi);

        ArrayList<Gob> plan = new ArrayList<>();
        for (ArrayList<Gob> r : rows) {
            r.sort(bySweep);
            if (!forward)
                Collections.reverse(r);
            plan.addAll(r);
            forward = !forward;
        }
        return plan;
    }

    // ================================================================ state

    private boolean hasHoney(Gob hive) {
        long attr = hive.ngob.getModelAttribute();
        return attr == 35 || attr == 39;
    }

    private boolean hasWax(Gob hive) {
        long attr = hive.ngob.getModelAttribute();
        return attr == 39 || attr == 6;
    }

    // ================================================================ tasks

    /**
     * Waits for a honey pull to resolve. Exits as soon as the hive's model attribute moves or the hive
     * leaves the object cache; the tick bound is only reached when neither happens, which is the
     * barrel-full case. The caller decides the outcome by reading hive state afterwards.
     */
    private static class WaitPullResolved extends NTask {
        private final long gobId;
        private final long initialAttr;
        private int ticks = 0;

        WaitPullResolved(long gobId, long initialAttr) {
            this.gobId = gobId;
            this.initialAttr = initialAttr;
            this.infinite = true;
        }

        @Override
        public boolean check() {
            ticks++;
            Gob gob = Finder.findGob(gobId);
            if (gob == null)
                return true;
            if (gob.ngob.getModelAttribute() != initialAttr)
                return true;
            return ticks >= RESOLVE_TICKS;
        }
    }

    private static class WaitBarrelEmpty extends NTask {
        private final Gob barrel;
        public boolean emptied = false;
        private int ticks = 0;

        WaitBarrelEmpty(Gob barrel) {
            this.barrel = barrel;
            this.infinite = true;
        }

        @Override
        public boolean check() {
            ticks++;
            if (!NUtils.isOverlay(barrel, HONEY_OVERLAY)) {
                emptied = true;
                return true;
            }
            return ticks >= POUR_TICKS;
        }
    }
}
