package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Inventory;
import haven.Loading;
import haven.WItem;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NConfig;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitItems;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.*;

/**
 * Ducks - Duck Manager Bot
 * Duck counterpart of {@link KFC}, operating on its own areas: duck coops live in the
 * "Duck Coops" area and hatching coops in the "Duck Hatchery" area, so a chicken setup and
 * a duck setup never touch each other.
 *
 * Replaces low-quality drakes/duck hens in the coops with better ones raised in the hatchery,
 * moves ducklings from the coops to the hatchery, butchers the surplus birds, and discards
 * eggs below the breeding threshold.
 *
 * Ducks are kept in ordinary chicken coops, so items are matched by resource name rather than
 * display name — a coop inventory cannot be told apart by window caption.
 */
public class Ducks implements Action {

    private static final String COOP_WINDOW = "Chicken Coop";
    private static final NAlias COOP_GOB = new NAlias("gfx/terobjs/chickencoop");

    /** Maximum ducklings a single hatchery coop holds. */
    private static final int MAX_DUCKLINGS_PER_COOP = 24;

    private enum DuckType {
        DRAKE,
        HEN,
        DUCKLING,
        EGG,
        DEAD_DRAKE,
        DEAD_HEN,
        PLUCKED_DRAKE,
        PLUCKED_HEN,
        CLEANED
    }

    private static final EnumMap<DuckType, String> DUCK_RESOURCES = new EnumMap<>(DuckType.class);

    static {
        DUCK_RESOURCES.put(DuckType.DRAKE, "gfx/invobjs/duckdrake");
        DUCK_RESOURCES.put(DuckType.HEN, "gfx/invobjs/duckhen");
        DUCK_RESOURCES.put(DuckType.DUCKLING, "gfx/invobjs/duckling");
        DUCK_RESOURCES.put(DuckType.EGG, "gfx/invobjs/egg-duck");
        DUCK_RESOURCES.put(DuckType.DEAD_DRAKE, "gfx/invobjs/duckdrake-dead");
        DUCK_RESOURCES.put(DuckType.DEAD_HEN, "gfx/invobjs/duckhen-dead");
        DUCK_RESOURCES.put(DuckType.PLUCKED_DRAKE, "gfx/invobjs/duckdrake-plucked");
        DUCK_RESOURCES.put(DuckType.PLUCKED_HEN, "gfx/invobjs/duckhen-plucked");
        DUCK_RESOURCES.put(DuckType.CLEANED, "gfx/invobjs/duck-cleaned");
    }

    /** State of one breeding coop in the duck coop area. */
    private static class CoopInfo {
        final String gobHash;
        double drakeQuality;
        final ArrayList<Float> henQualities = new ArrayList<>();

        CoopInfo(String gobHash, double drakeQuality) {
            this.gobHash = gobHash;
            this.drakeQuality = drakeQuality;
        }

        boolean isBreeding() {
            return drakeQuality != -1 && !henQualities.isEmpty();
        }
    }

    /** A single bird found in a hatchery coop, and which coop it sits in. */
    private static class HatchlingInfo {
        final String gobHash;
        final double quality;

        HatchlingInfo(String gobHash, double quality) {
            this.gobHash = gobHash;
            this.quality = quality;
        }
    }

    private final Comparator<HatchlingInfo> hatchlingComparator = (o1, o2) -> Double.compare(o1.quality, o2.quality);

    private final Comparator<CoopInfo> coopComparator = (o1, o2) -> {
        int res = Double.compare(o1.drakeQuality, o2.drakeQuality);
        if (res == 0) {
            if (!o1.henQualities.isEmpty() && !o2.henQualities.isEmpty()) {
                double avgQuality1 = o1.henQualities.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                double avgQuality2 = o2.henQualities.stream().mapToDouble(Float::doubleValue).average().orElse(0);
                res = Double.compare(avgQuality1, avgQuality2);
            }
        }
        return res;
    };

    NContext context;

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        context = new NContext(gui);

        NArea.Specialisation duckSpec = new NArea.Specialisation(Specialisation.SpecName.duck.toString());
        NArea.Specialisation hatcherySpec = new NArea.Specialisation(Specialisation.SpecName.duckHatchery.toString());
        NArea.Specialisation swillSpec = new NArea.Specialisation(Specialisation.SpecName.swill.toString());
        NArea.Specialisation waterSpec = new NArea.Specialisation(Specialisation.SpecName.water.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(duckSpec);
        req.add(hatcherySpec);

        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(swillSpec);
        opt.add(waterSpec);

        if (!new Validator(req, opt).run(gui).IsSuccess()) {
            return Results.FAIL();
        }

        // Resolve areas (local first, then global) without navigating — bot navigates explicitly below
        NArea duckArea = context.findArea(Specialisation.SpecName.duck);
        NArea hatcheryArea = context.findArea(Specialisation.SpecName.duckHatchery);
        NArea swillArea = context.findArea(Specialisation.SpecName.swill);
        NArea waterArea = context.findArea(Specialisation.SpecName.water);

        if (duckArea == null) {
            return Results.ERROR("Duck coop area not found!");
        }
        if (hatcheryArea == null) {
            return Results.ERROR("Duck hatchery area not found!");
        }

        NUtils.navigateToArea(duckArea);
        ArrayList<String> coopHashes = collectCoopHashes(duckArea);

        NUtils.navigateToArea(hatcheryArea);
        ArrayList<String> hatcheryHashes = collectCoopHashes(hatcheryArea);

        if (coopHashes.isEmpty()) {
            return Results.ERROR("No coops in duck coop area!");
        }

        // Top up swill and water in both areas
        if (swillArea != null || waterArea != null) {
            ArrayList<Container> coops = getContainersFromHashes(coopHashes, duckArea);
            ArrayList<Container> hatchery = getContainersFromHashes(hatcheryHashes, hatcheryArea);

            if (swillArea != null) {
                new FillFluid(coops, swillArea.getRCArea(), new NAlias("swill"), 2).run(gui);
                new FillFluid(hatchery, swillArea.getRCArea(), new NAlias("swill"), 2).run(gui);
            }
            if (waterArea != null) {
                new FillFluid(coops, waterArea.getRCArea(), new NAlias("water"), 1).run(gui);
                new FillFluid(hatchery, waterArea.getRCArea(), new NAlias("water"), 1).run(gui);
            }
        }

        // Read breeding coop contents, best first
        ArrayList<CoopInfo> coopInfos = readCoopInfos(gui, coopHashes, true);

        // Read hatchery contents
        ArrayList<HatchlingInfo> qdrakes = new ArrayList<>();
        ArrayList<HatchlingInfo> qhens = new ArrayList<>();

        NUtils.navigateToArea(hatcheryArea);
        for (String hash : hatcheryHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, gob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            for (WItem drake : getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.DRAKE)) {
                qdrakes.add(new HatchlingInfo(hash, itemQuality(drake)));
            }
            for (WItem hen : getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.HEN)) {
                qhens.add(new HatchlingInfo(hash, itemQuality(hen)));
            }

            new CloseTargetContainer(COOP_WINDOW).run(gui);
        }

        Results drakeResult = processDrakes(gui, coopInfos, qdrakes);
        if (!drakeResult.IsSuccess()) {
            return drakeResult;
        }

        Results henResult = processHens(gui, coopInfos, qhens);
        if (!henResult.IsSuccess()) {
            return henResult;
        }

        transferDucklings(gui, coopHashes, hatcheryHashes);

        // Coop contents changed while replacing birds, so re-read before picking the egg threshold
        coopInfos = readCoopInfos(gui, coopHashes, false);

        Optional<CoopInfo> bestBreedingCoop = coopInfos.stream()
                .filter(CoopInfo::isBreeding)
                .max(coopComparator);
        if (bestBreedingCoop.isEmpty()) {
            return Results.ERROR("No duck breeding coops found");
        }

        // Eggs worse than the weakest hen of the best coop cannot improve the flock
        double eggThreshold = bestBreedingCoop.get().henQualities.stream()
                .mapToDouble(Float::doubleValue)
                .min()
                .orElse(-1);

        collectAndDisposeLowQualityEggs(gui, coopHashes, eggThreshold);

        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    private ArrayList<String> collectCoopHashes(NArea area) throws InterruptedException {
        ArrayList<String> hashes = new ArrayList<>();
        for (Gob coop : Finder.findGobs(area, COOP_GOB)) {
            if (coop.ngob != null && coop.ngob.hash != null) {
                hashes.add(coop.ngob.hash);
            }
        }
        return hashes;
    }

    private ArrayList<Container> getContainersFromHashes(ArrayList<String> hashes, NArea area) {
        ArrayList<Container> containers = new ArrayList<>();
        for (String hash : hashes) {
            Gob gob = Finder.findGob(hash);
            if (gob != null) {
                Container cand = new Container(gob, COOP_WINDOW, area);
                cand.initattr(Container.Space.class);
                containers.add(cand);
            }
        }
        return containers;
    }

    /**
     * Walk every coop in the duck coop area and record its drake and hen qualities.
     *
     * @param failOnClosedCoop fail the whole bot when a coop refuses to open, instead of skipping it
     */
    private ArrayList<CoopInfo> readCoopInfos(NGameUI gui, ArrayList<String> coopHashes, boolean failOnClosedCoop) throws InterruptedException {
        ArrayList<CoopInfo> coopInfos = new ArrayList<>();

        context.goToArea(Specialisation.SpecName.duck);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, gob).run(gui).IsSuccess())) {
                if (failOnClosedCoop) {
                    return coopInfos;
                }
                continue;
            }

            WItem drake = getDuckItem(gui.getInventory(COOP_WINDOW), DuckType.DRAKE);
            CoopInfo coopInfo = new CoopInfo(hash, drake != null ? itemQuality(drake) : -1);

            for (WItem hen : getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.HEN)) {
                coopInfo.henQualities.add(itemQuality(hen));
            }
            coopInfo.henQualities.sort(Float::compareTo);

            coopInfos.add(coopInfo);

            new CloseTargetContainer(COOP_WINDOW).run(gui);
        }

        coopInfos.sort(coopComparator.reversed());
        return coopInfos;
    }

    private void transferDucklings(NGameUI gui, ArrayList<String> coopHashes, ArrayList<String> hatcheryHashes) throws InterruptedException {
        context.goToArea(Specialisation.SpecName.duck);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, gob).run(gui).IsSuccess())) {
                continue;
            }

            for (WItem duckling : getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.DUCKLING)) {
                duckling.item.wdgmsg("transfer", Coord.z);
            }

            new CloseTargetContainer(COOP_WINDOW).run(gui);

            // If inventory is getting full, drop what we have into the hatchery (don't kill yet)
            if (shouldDropOffItems(gui)) {
                transferDucklingsToHatchery(gui, hatcheryHashes);
                context.goToArea(Specialisation.SpecName.duck);
            }
        }

        transferDucklingsToHatchery(gui, hatcheryHashes);

        // Only once the whole hatchery is full is the remainder surplus
        killExcessDucklings(gui);
    }

    private void transferDucklingsToHatchery(NGameUI gui, ArrayList<String> hatcheryHashes) throws InterruptedException {
        ArrayList<WItem> ducklings = getDuckItems(gui.getInventory(), DuckType.DUCKLING);
        if (ducklings.isEmpty()) return;

        context.goToArea(Specialisation.SpecName.duckHatchery);
        for (String hash : hatcheryHashes) {
            ducklings = getDuckItems(gui.getInventory(), DuckType.DUCKLING);
            if (ducklings.isEmpty()) break;

            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, gob).run(gui).IsSuccess())) {
                continue;
            }

            int canAdd = MAX_DUCKLINGS_PER_COOP - getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.DUCKLING).size();
            if (canAdd <= 0) {
                new CloseTargetContainer(COOP_WINDOW).run(gui);
                continue;
            }

            int transferred = 0;
            for (WItem duckling : ducklings) {
                if (transferred >= canAdd) break;
                if (gui.getInventory(COOP_WINDOW).getNumberFreeCoord(new Coord(2, 2)) > 0) {
                    duckling.item.wdgmsg("transfer", Coord.z);
                    transferred++;
                } else {
                    break;
                }
            }

            new CloseTargetContainer(COOP_WINDOW).run(gui);
        }
    }

    /**
     * Kill ducklings that could not fit in the hatchery.
     * Wring neck -> wait for "A Bloody Mess" -> drop on ground
     */
    private void killExcessDucklings(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> ducklings = getDuckItems(gui.getInventory(), DuckType.DUCKLING);

        while (!ducklings.isEmpty()) {
            WItem duckling = ducklings.get(0);

            new SelectFlowerAction("Wring neck", duckling).run(gui);
            NUtils.addTask(new WaitItems((NInventory) gui.maininv, new NAlias("A Bloody Mess"), 1));

            WItem bloodyMess = gui.getInventory().getItem(new NAlias("A Bloody Mess"));
            if (bloodyMess != null) {
                NUtils.drop(bloodyMess);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        try {
                            return gui.getInventory().getItems(new NAlias("A Bloody Mess")).isEmpty();
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                });
            }

            ducklings = getDuckItems(gui.getInventory(), DuckType.DUCKLING);
        }
    }

    /**
     * Collect duck eggs with quality BELOW the threshold and dispose of them via FreeInventory2.
     * Good eggs stay in the coops for hatching.
     */
    private void collectAndDisposeLowQualityEggs(NGameUI gui, ArrayList<String> coopHashes, double qualityThreshold) throws InterruptedException {
        context.goToArea(Specialisation.SpecName.duck);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, gob).run(gui).IsSuccess())) {
                continue;
            }

            for (WItem egg : getDuckItems(gui.getInventory(COOP_WINDOW), DuckType.EGG)) {
                if (itemQuality(egg) < qualityThreshold) {
                    egg.item.wdgmsg("transfer", Coord.z);
                }
            }

            new CloseTargetContainer(COOP_WINDOW).run(gui);

            if (shouldDropOffItems(gui)) {
                new FreeInventory2(context).run(gui);
                context.goToArea(Specialisation.SpecName.duck);
            }
        }
    }

    /**
     * Promote the best hatchery drakes into breeding coops, cascading each displaced drake
     * down the coop list and butchering whichever drake falls out the bottom.
     */
    private Results processDrakes(NGameUI gui, ArrayList<CoopInfo> coopInfos, ArrayList<HatchlingInfo> qdrakes) throws InterruptedException {
        qdrakes.sort(hatchlingComparator.reversed());

        for (HatchlingInfo drakeInfo : qdrakes) {
            context.goToArea(Specialisation.SpecName.duckHatchery);

            Gob drakeGob = Finder.findGob(drakeInfo.gobHash);
            if (drakeGob == null) continue;

            new PathFinder(drakeGob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, drakeGob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            WItem drake = getDuckItem(gui.getInventory(COOP_WINDOW), DuckType.DRAKE, drakeInfo.quality);
            if (drake == null) {
                new CloseTargetContainer(COOP_WINDOW).run(gui);
                continue;
            }
            double drakeQuality = itemQuality(drake);

            Coord pos = drake.c.div(Inventory.sqsz);
            drake.item.wdgmsg("transfer", Coord.z);
            Coord takenFrom = pos;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return gui.getInventory(COOP_WINDOW).isSlotFree(takenFrom);
                }
            });
            new CloseTargetContainer(COOP_WINDOW).run(gui);

            for (CoopInfo coopInfo : coopInfos) {
                if (coopInfo.drakeQuality < drakeQuality && coopInfo.drakeQuality != -1) {
                    drake = getDuckItem(gui.getInventory(), DuckType.DRAKE);
                    if (drake == null) break;

                    context.goToArea(Specialisation.SpecName.duck);

                    Gob coopGob = Finder.findGob(coopInfo.gobHash);
                    if (coopGob == null) continue;

                    new PathFinder(coopGob).run(gui);
                    if (!(new OpenTargetContainer(COOP_WINDOW, coopGob).run(gui).IsSuccess())) {
                        return Results.FAIL();
                    }

                    WItem oldDrake = getDuckItem(gui.getInventory(COOP_WINDOW), DuckType.DRAKE, coopInfo.drakeQuality);
                    if (oldDrake == null) {
                        new CloseTargetContainer(COOP_WINDOW).run(gui);
                        continue;
                    }

                    pos = oldDrake.c.div(Inventory.sqsz);
                    oldDrake.item.wdgmsg("transfer", Coord.z);
                    Coord vacated = pos;
                    NUtils.addTask(new NTask() {
                        @Override
                        public boolean check() {
                            return gui.getInventory(COOP_WINDOW).isSlotFree(vacated);
                        }
                    });

                    NUtils.takeItemToHand(drake);
                    gui.getInventory(COOP_WINDOW).dropOn(pos);

                    coopInfo.drakeQuality = drakeQuality;
                    drakeQuality = itemQuality(oldDrake);
                    new CloseTargetContainer(COOP_WINDOW).run(gui);
                }
            }

            drake = getDuckItem(gui.getInventory(), DuckType.DRAKE);
            if (drake != null) {
                butcherDuck(gui, drake, DuckType.DRAKE);
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    /**
     * Promote the best hatchery hens into breeding coops, cascading each displaced hen
     * down the coop list and butchering whichever hen falls out the bottom.
     */
    private Results processHens(NGameUI gui, ArrayList<CoopInfo> coopInfos, ArrayList<HatchlingInfo> qhens) throws InterruptedException {
        qhens.sort(hatchlingComparator.reversed());

        for (HatchlingInfo henInfo : qhens) {
            context.goToArea(Specialisation.SpecName.duckHatchery);

            Gob henGob = Finder.findGob(henInfo.gobHash);
            if (henGob == null) continue;

            new PathFinder(henGob).run(gui);
            if (!(new OpenTargetContainer(COOP_WINDOW, henGob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            WItem hen = getDuckItem(gui.getInventory(COOP_WINDOW), DuckType.HEN, henInfo.quality);
            if (hen == null) {
                new CloseTargetContainer(COOP_WINDOW).run(gui);
                continue;
            }
            float henQuality = itemQuality(hen);

            Coord pos = hen.c.div(Inventory.sqsz);
            hen.item.wdgmsg("transfer", Coord.z);
            Coord takenFrom = pos;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return gui.getInventory(COOP_WINDOW).isSlotFree(takenFrom);
                }
            });
            new CloseTargetContainer(COOP_WINDOW).run(gui);

            for (CoopInfo coopInfo : coopInfos) {
                for (int i = 0; i < coopInfo.henQualities.size(); i++) {
                    if (coopInfo.henQualities.get(i) < henQuality) {
                        hen = getDuckItem(gui.getInventory(), DuckType.HEN);
                        if (hen == null) break;

                        context.goToArea(Specialisation.SpecName.duck);

                        Gob coopGob = Finder.findGob(coopInfo.gobHash);
                        if (coopGob == null) continue;

                        new PathFinder(coopGob).run(gui);
                        if (!(new OpenTargetContainer(COOP_WINDOW, coopGob).run(gui).IsSuccess())) {
                            return Results.FAIL();
                        }

                        WItem oldHen = getDuckItem(gui.getInventory(COOP_WINDOW), DuckType.HEN, coopInfo.henQualities.get(i));
                        if (oldHen == null) {
                            new CloseTargetContainer(COOP_WINDOW).run(gui);
                            continue;
                        }

                        pos = oldHen.c.div(Inventory.sqsz);
                        oldHen.item.wdgmsg("transfer", Coord.z);
                        Coord vacated = pos;
                        NUtils.addTask(new NTask() {
                            @Override
                            public boolean check() {
                                return gui.getInventory(COOP_WINDOW).isSlotFree(vacated);
                            }
                        });

                        NUtils.takeItemToHand(hen);
                        gui.getInventory(COOP_WINDOW).dropOn(pos);

                        coopInfo.henQualities.set(i, henQuality);
                        henQuality = itemQuality(oldHen);
                        new CloseTargetContainer(COOP_WINDOW).run(gui);
                        break;
                    }
                }
            }

            hen = getDuckItem(gui.getInventory(), DuckType.HEN);
            if (hen != null) {
                butcherDuck(gui, hen, DuckType.HEN);
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    /**
     * Butcher a duck - wring neck, pluck, clean, butcher.
     */
    private void butcherDuck(NGameUI gui, WItem duck, DuckType liveType) throws InterruptedException {
        if (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) < 2) {
            new FreeInventory2(context).run(gui);
        }

        DuckType deadType = (liveType == DuckType.DRAKE) ? DuckType.DEAD_DRAKE : DuckType.DEAD_HEN;
        DuckType pluckedType = (liveType == DuckType.DRAKE) ? DuckType.PLUCKED_DRAKE : DuckType.PLUCKED_HEN;

        int deadCount = getDuckItems(gui.getInventory(), deadType).size();
        new SelectFlowerAction("Wring neck", duck).run(gui);
        waitForDuckItems(gui, deadType, deadCount + 1);

        WItem deadDuck = getDuckItem(gui.getInventory(), deadType);
        if (deadDuck == null) return;

        Boolean skipPluckDrakes = (Boolean) NConfig.get(NConfig.Key.skipPluckingDrakesInDucks);
        if (skipPluckDrakes != null && skipPluckDrakes && liveType == DuckType.DRAKE) {
            // Leave as Dead Drake for recipes that need the whole bird
            if (shouldDropOffItems(gui)) {
                new FreeInventory2(context).run(gui);
            }
            return;
        }

        int pluckedCount = getDuckItems(gui.getInventory(), pluckedType).size();
        new SelectFlowerAction("Pluck", deadDuck).run(gui);
        waitForDuckItems(gui, pluckedType, pluckedCount + 1);

        WItem plucked = getDuckItem(gui.getInventory(), pluckedType);
        if (plucked == null) return;

        int cleanedCount = getDuckItems(gui.getInventory(), DuckType.CLEANED).size();
        new SelectFlowerAction("Clean", plucked).run(gui);
        waitForDuckItems(gui, DuckType.CLEANED, cleanedCount + 1);

        WItem cleaned = getDuckItem(gui.getInventory(), DuckType.CLEANED);
        if (cleaned == null) return;

        Boolean skipButcher = (Boolean) NConfig.get(NConfig.Key.skipButcherInDucks);
        if (skipButcher == null || !skipButcher) {
            int beforeButcher = getDuckItems(gui.getInventory(), DuckType.CLEANED).size();
            new SelectFlowerAction("Butcher", cleaned).run(gui);
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    try {
                        return getDuckItems(gui.getInventory(), DuckType.CLEANED).size() < beforeButcher;
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
            });
        }

        if (shouldDropOffItems(gui)) {
            new FreeInventory2(context).run(gui);
        }
    }

    private void waitForDuckItems(NGameUI gui, DuckType type, int minCount) throws InterruptedException {
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                try {
                    return getDuckItems(gui.getInventory(), type).size() >= minCount;
                } catch (InterruptedException e) {
                    return false;
                }
            }
        });
    }

    private ArrayList<WItem> getDuckItems(NInventory inventory, DuckType type) throws InterruptedException {
        ArrayList<WItem> ducks = new ArrayList<>();
        if (inventory == null) return ducks;
        String resource = DUCK_RESOURCES.get(type);
        for (WItem item : inventory.getItems()) {
            if (resource.equals(itemResource(item))) {
                ducks.add(item);
            }
        }
        return ducks;
    }

    private WItem getDuckItem(NInventory inventory, DuckType type) throws InterruptedException {
        ArrayList<WItem> ducks = getDuckItems(inventory, type);
        return ducks.isEmpty() ? null : ducks.get(0);
    }

    private WItem getDuckItem(NInventory inventory, DuckType type, double quality) throws InterruptedException {
        for (WItem item : getDuckItems(inventory, type)) {
            if (Double.compare(itemQuality(item), quality) == 0) {
                return item;
            }
        }
        return null;
    }

    private String itemResource(WItem item) {
        NGItem ngItem = (NGItem) item.item;
        if (ngItem.res == null) return null;
        try {
            return ngItem.res.get().name;
        } catch (Loading ignored) {
            return null;
        }
    }

    private float itemQuality(WItem item) {
        Float quality = ((NGItem) item.item).quality;
        return quality == null ? -1 : quality;
    }

    /**
     * Checks if inventory drop-off is needed based on available space.
     * A duck is 2x2 plus room for its products.
     */
    private boolean shouldDropOffItems(NGameUI gui) throws InterruptedException {
        return gui.getInventory().getNumberFreeCoord(new Coord(2, 2)) <= 2;
    }
}
