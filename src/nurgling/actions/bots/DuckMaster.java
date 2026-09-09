package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.Inventory;
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
 * DuckMaster - Duck Manager Bot
 * Manages duck coops and duckling incubators: replaces low-quality drakes/hens with better ones,
 * transfers ducklings to incubators, and processes low-quality ducks.
 * <p>
 * Ducks live in the very same "Chicken Coop" building as chickens, so duck zones are told apart
 * from chicken zones purely by area specialisation ({@code duck} / {@code duckIncubator}).
 */
public class DuckMaster implements Action {

    // Building that houses ducks - the game reuses the chicken coop for them
    private static final String COOP_CAP = "Chicken Coop";
    private static final NAlias COOP_GOB = new NAlias("gfx/terobjs/chickencoop");

    /* Dead and plucked birds keep the sex in their name ("Dead Duck Drake", "Plucked Duck Hen"),
     * so every live-bird lookup has to exclude both, or a carcass in the inventory gets picked up
     * as if it were still walking. */
    private static final NAlias DRAKE = new NAlias(List.of("Duck Drake"), List.of("Dead", "Plucked"));
    private static final NAlias HEN = new NAlias(List.of("Duck Hen"), List.of("Dead", "Plucked"));
    private static final NAlias DUCKLING = new NAlias("Duckling");
    private static final NAlias DUCK_EGG = new NAlias("Duck Egg");

    private static final String DEAD_DRAKE = "Dead Duck Drake";
    private static final String DEAD_HEN = "Dead Duck Hen";
    private static final String PLUCKED_DRAKE = "Plucked Duck Drake";
    private static final String PLUCKED_HEN = "Plucked Duck Hen";
    private static final String CLEANED_DUCK = "Cleaned Duck";

    // Coop info class
    private static class CoopInfo {
        String gobHash;
        double drakeQuality;
        ArrayList<Float> henQualities = new ArrayList<>();

        public CoopInfo(String gobHash, double drakeQuality) {
            this.gobHash = gobHash;
            this.drakeQuality = drakeQuality;
        }
    }

    // Incubator info class
    private static class IncubatorInfo {
        String gobHash;
        double duckQuality;

        public IncubatorInfo(String gobHash, double duckQuality) {
            this.gobHash = gobHash;
            this.duckQuality = duckQuality;
        }
    }

    // Maximum ducklings per incubator
    private static final int MAX_DUCKLINGS_PER_INCUBATOR = 24;

    // Comparator for sorting incubators by quality
    Comparator<IncubatorInfo> incubatorComparator = (o1, o2) -> Double.compare(o1.duckQuality, o2.duckQuality);

    // Comparator for sorting coops
    Comparator<CoopInfo> coopComparator = (o1, o2) -> {
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

        // Validate required areas
        NArea.Specialisation duckSpec = new NArea.Specialisation(Specialisation.SpecName.duck.toString());
        NArea.Specialisation incubatorSpec = new NArea.Specialisation(Specialisation.SpecName.duckIncubator.toString());
        NArea.Specialisation swillSpec = new NArea.Specialisation(Specialisation.SpecName.swill.toString());
        NArea.Specialisation waterSpec = new NArea.Specialisation(Specialisation.SpecName.water.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(duckSpec);
        req.add(incubatorSpec);

        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(swillSpec);
        opt.add(waterSpec);

        if (!new Validator(req, opt).run(gui).IsSuccess()) {
            return Results.FAIL();
        }

        // Resolve areas (local first, then global) without navigating — bot navigates explicitly below
        NArea duckArea = context.findArea(Specialisation.SpecName.duck);
        NArea incubatorArea = context.findArea(Specialisation.SpecName.duckIncubator);
        NArea swillArea = context.findArea(Specialisation.SpecName.swill);
        NArea waterArea = context.findArea(Specialisation.SpecName.water);

        if (duckArea == null) {
            return Results.ERROR("Duck area not found!");
        }
        if (incubatorArea == null) {
            return Results.ERROR("Duckling incubator area not found!");
        }

        // Navigate to duck area and collect coop hashes
        NUtils.navigateToArea(duckArea);
        ArrayList<String> coopHashes = new ArrayList<>();
        for (Gob cc : Finder.findGobs(duckArea, COOP_GOB)) {
            if (cc.ngob != null && cc.ngob.hash != null) {
                coopHashes.add(cc.ngob.hash);
            }
        }

        // Navigate to incubator area and collect incubator hashes
        NUtils.navigateToArea(incubatorArea);
        ArrayList<String> incubatorHashes = new ArrayList<>();
        for (Gob cc : Finder.findGobs(incubatorArea, COOP_GOB)) {
            if (cc.ngob != null && cc.ngob.hash != null) {
                incubatorHashes.add(cc.ngob.hash);
            }
        }

        // Fill duck coops and incubators with fluids
        if (swillArea != null || waterArea != null) {
            ArrayList<Container> containers = getContainersFromHashes(coopHashes, duckArea);
            ArrayList<Container> ccontainers = getContainersFromHashes(incubatorHashes, incubatorArea);

            if (swillArea != null) {
                new FillFluid(containers, swillArea.getRCArea(), new NAlias("swill"), 2).run(gui);
                new FillFluid(ccontainers, swillArea.getRCArea(), new NAlias("swill"), 2).run(gui);
            }
            if (waterArea != null) {
                new FillFluid(containers, waterArea.getRCArea(), new NAlias("water"), 1).run(gui);
                new FillFluid(ccontainers, waterArea.getRCArea(), new NAlias("water"), 1).run(gui);
            }
        }

        // Read coop contents and sort them
        ArrayList<CoopInfo> coopInfos = new ArrayList<>();
        ArrayList<IncubatorInfo> qdrakes = new ArrayList<>();
        ArrayList<IncubatorInfo> qhens = new ArrayList<>();

        // Navigate to duck area and read coop contents
        NUtils.navigateToArea(duckArea);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, gob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            double drakeQuality;
            if (gui.getInventory(COOP_CAP).getItem(DRAKE) != null) {
                NGItem drake = (NGItem) gui.getInventory(COOP_CAP).getItem(DRAKE).item;
                drakeQuality = drake.quality;
            } else {
                drakeQuality = -1;
            }

            CoopInfo coopInfo = new CoopInfo(hash, drakeQuality);

            ArrayList<WItem> hens = gui.getInventory(COOP_CAP).getItems(HEN);
            for (WItem hen : hens) {
                coopInfo.henQualities.add(((NGItem) hen.item).quality);
            }
            coopInfo.henQualities.sort(Float::compareTo);

            coopInfos.add(coopInfo);

            new CloseTargetContainer(COOP_CAP).run(gui);
        }

        // Sort coops by drake quality and average hen quality
        coopInfos.sort(coopComparator.reversed());

        // Navigate to incubator area and read contents
        NUtils.navigateToArea(incubatorArea);
        for (String hash : incubatorHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, gob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            ArrayList<WItem> drakes = gui.getInventory(COOP_CAP).getItems(DRAKE);
            for (WItem drake : drakes) {
                qdrakes.add(new IncubatorInfo(hash, ((NGItem) drake.item).quality));
            }

            ArrayList<WItem> hens = gui.getInventory(COOP_CAP).getItems(HEN);
            for (WItem hen : hens) {
                qhens.add(new IncubatorInfo(hash, ((NGItem) hen.item).quality));
            }

            new CloseTargetContainer(COOP_CAP).run(gui);
        }

        Results drakeResult = processDrakes(gui, coopInfos, qdrakes);
        if (!drakeResult.IsSuccess()) {
            return drakeResult;
        }

        Results henResult = processHens(gui, coopInfos, qhens);
        if (!henResult.IsSuccess()) {
            return henResult;
        }

        // Transfer ducklings from duck coops to incubators
        transferDucklings(gui, coopHashes, incubatorHashes);

        // Determine threshold quality for eggs from best coop
        if (coopInfos.isEmpty()) {
            return Results.ERROR("No duck coops found!");
        }

        context.goToArea(Specialisation.SpecName.duck);
        Gob bestCoopGob = Finder.findGob(coopInfos.get(0).gobHash);
        if (bestCoopGob == null) {
            return Results.ERROR("Best coop not found!");
        }

        new PathFinder(bestCoopGob).run(gui);
        if (!(new OpenTargetContainer(COOP_CAP, bestCoopGob).run(gui).IsSuccess())) {
            return Results.FAIL();
        }

        // Get quality threshold from top hens
        ArrayList<WItem> topHens = gui.getInventory(COOP_CAP).getItems(HEN);
        ArrayList<Float> qtop = new ArrayList<>();
        for (WItem top : topHens) {
            qtop.add(((NGItem) top.item).quality);
        }

        if (qtop.isEmpty()) {
            return Results.ERROR("No duck hens in best coop");
        }

        qtop.sort(Float::compareTo);
        double duck_th = qtop.get(0);
        new CloseTargetContainer(COOP_CAP).run(gui);

        // Collect low quality eggs and dispose via FreeInventory2 (like Butcher)
        collectAndDisposeLowQualityEggs(gui, coopHashes, duck_th);

        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    private ArrayList<Container> getContainersFromHashes(ArrayList<String> hashes, NArea area) {
        ArrayList<Container> containers = new ArrayList<>();
        for (String hash : hashes) {
            Gob gob = Finder.findGob(hash);
            if (gob != null) {
                Container cand = new Container(gob, COOP_CAP, area);
                cand.initattr(Container.Space.class);
                containers.add(cand);
            }
        }
        return containers;
    }

    private void transferDucklings(NGameUI gui, ArrayList<String> coopHashes, ArrayList<String> incubatorHashes) throws InterruptedException {
        // Collect ducklings from duck coops
        context.goToArea(Specialisation.SpecName.duck);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, gob).run(gui).IsSuccess())) {
                continue;
            }

            // Transfer all ducklings to inventory
            ArrayList<WItem> ducklings = gui.getInventory(COOP_CAP).getItems(DUCKLING);
            for (WItem duckling : ducklings) {
                duckling.item.wdgmsg("transfer", Coord.z);
            }

            new CloseTargetContainer(COOP_CAP).run(gui);

            // If inventory getting full, transfer to incubators (don't kill yet)
            if (shouldDropOffItems(gui)) {
                transferDucklingsToIncubators(gui, incubatorHashes);
                context.goToArea(Specialisation.SpecName.duck);
            }
        }

        // Transfer all remaining ducklings to incubators (fills all available space)
        transferDucklingsToIncubators(gui, incubatorHashes);

        // Only after ALL incubators are full, kill excess ducklings
        killExcessDucklings(gui);
    }

    private void transferDucklingsToIncubators(NGameUI gui, ArrayList<String> incubatorHashes) throws InterruptedException {
        ArrayList<WItem> ducklings = gui.getInventory().getItems(DUCKLING);
        if (ducklings.isEmpty()) return;

        context.goToArea(Specialisation.SpecName.duckIncubator);
        for (String hash : incubatorHashes) {
            ducklings = gui.getInventory().getItems(DUCKLING);
            if (ducklings.isEmpty()) break;

            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            // Create container with ItemCount for duckling tracking
            Container incubatorContainer = new Container(gob, COOP_CAP, null);
            Container.ItemCount itemCount = incubatorContainer.initItemCount(DUCKLING, MAX_DUCKLINGS_PER_INCUBATOR);

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(incubatorContainer).run(gui).IsSuccess())) {
                continue;
            }

            // Update ItemCount to get current duckling count
            itemCount.update();
            int canAdd = itemCount.getNeeded();

            if (canAdd <= 0) {
                new CloseTargetContainer(incubatorContainer).run(gui);
                continue;
            }

            // Transfer ducklings to incubator (up to limit)
            int transferred = 0;
            for (WItem duckling : ducklings) {
                if (transferred >= canAdd) break;
                if (gui.getInventory(COOP_CAP).getNumberFreeCoord(new Coord(2, 2)) > 0) {
                    duckling.item.wdgmsg("transfer", Coord.z);
                    transferred++;
                } else {
                    break;
                }
            }

            new CloseTargetContainer(incubatorContainer).run(gui);
        }
    }

    /**
     * Kill excess ducklings that couldn't fit in incubators.
     * Wring neck -> wait for "A Bloody Mess" -> drop on ground
     */
    private void killExcessDucklings(NGameUI gui) throws InterruptedException {
        ArrayList<WItem> ducklings = gui.getInventory().getItems(DUCKLING);

        while (!ducklings.isEmpty()) {
            WItem duckling = ducklings.get(0);

            // Wring neck
            new SelectFlowerAction("Wring neck", duckling).run(gui);

            // Wait for "A Bloody Mess" to appear
            NUtils.addTask(new WaitItems((NInventory) gui.maininv, new NAlias("A Bloody Mess"), 1));

            // Drop the bloody mess on ground
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

            // Get remaining ducklings
            ducklings = gui.getInventory().getItems(DUCKLING);
        }
    }

    /**
     * Collect eggs with quality BELOW threshold and dispose them via FreeInventory2 (like Butcher).
     * Good quality eggs stay in coops for hatching.
     */
    private void collectAndDisposeLowQualityEggs(NGameUI gui, ArrayList<String> coopHashes, double qualityThreshold) throws InterruptedException {
        context.goToArea(Specialisation.SpecName.duck);
        for (String hash : coopHashes) {
            Gob gob = Finder.findGob(hash);
            if (gob == null) continue;

            new PathFinder(gob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, gob).run(gui).IsSuccess())) {
                continue;
            }

            // Collect eggs BELOW quality threshold (bad eggs to dispose)
            ArrayList<WItem> eggs = gui.getInventory(COOP_CAP).getItems(DUCK_EGG);
            for (WItem egg : eggs) {
                if (((NGItem) egg.item).quality < qualityThreshold) {
                    egg.item.wdgmsg("transfer", Coord.z);
                }
            }

            new CloseTargetContainer(COOP_CAP).run(gui);

            // If inventory getting full, dispose via FreeInventory2 and return to duck area
            if (shouldDropOffItems(gui)) {
                new FreeInventory2(context).run(gui);
                context.goToArea(Specialisation.SpecName.duck);
            }
        }
    }


    private Results processDrakes(NGameUI gui, ArrayList<CoopInfo> coopInfos, ArrayList<IncubatorInfo> qdrakes) throws InterruptedException {
        // Sort drakes by quality (best to worst)
        qdrakes.sort(incubatorComparator.reversed());

        for (IncubatorInfo drakeInfo : qdrakes) {
            // Navigate to incubator area and open the coop with drake
            context.goToArea(Specialisation.SpecName.duckIncubator);

            Gob drakeGob = Finder.findGob(drakeInfo.gobHash);
            if (drakeGob == null) continue;

            new PathFinder(drakeGob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, drakeGob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            // Get drake from coop inventory
            WItem drake = gui.getInventory(COOP_CAP).getItem(DRAKE);
            if (drake == null) {
                new CloseTargetContainer(COOP_CAP).run(gui);
                continue;
            }
            double drakeQuality = ((NGItem) drake.item).quality;

            Coord pos = drake.c.div(Inventory.sqsz);
            drake.item.wdgmsg("transfer", Coord.z);
            Coord finalPos1 = pos;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return gui.getInventory(COOP_CAP).isSlotFree(finalPos1);
                }
            });
            new CloseTargetContainer(COOP_CAP).run(gui);

            // Find coop with worse drake and replace it
            for (CoopInfo coopInfo : coopInfos) {
                if (coopInfo.drakeQuality < drakeQuality && coopInfo.drakeQuality != -1) {
                    drake = gui.getInventory().getItem(DRAKE);
                    if (drake == null) break;

                    // Navigate to duck area and open coop for replacement
                    context.goToArea(Specialisation.SpecName.duck);

                    Gob coopGob = Finder.findGob(coopInfo.gobHash);
                    if (coopGob == null) continue;

                    new PathFinder(coopGob).run(gui);
                    if (!(new OpenTargetContainer(COOP_CAP, coopGob).run(gui).IsSuccess())) {
                        return Results.FAIL();
                    }

                    // Get current drake in coop
                    WItem oldDrake = gui.getInventory(COOP_CAP).getItem(DRAKE);
                    if (oldDrake == null) {
                        new CloseTargetContainer(COOP_CAP).run(gui);
                        continue;
                    }

                    // Replace drake
                    pos = oldDrake.c.div(Inventory.sqsz);
                    oldDrake.item.wdgmsg("transfer", Coord.z);
                    Coord finalPos = pos;
                    NUtils.addTask(new NTask() {
                        @Override
                        public boolean check() {
                            return gui.getInventory(COOP_CAP).isSlotFree(finalPos);
                        }
                    });

                    NUtils.takeItemToHand(drake);
                    gui.getInventory(COOP_CAP).dropOn(pos, "Duck Drake");

                    // Update quality
                    coopInfo.drakeQuality = drakeQuality;
                    drakeQuality = ((NGItem) oldDrake.item).quality;
                    new CloseTargetContainer(COOP_CAP).run(gui);
                }
            }

            // Process the drake (butcher it)
            drake = gui.getInventory().getItem(DRAKE);
            if (drake != null) {
                butcherDuck(gui, drake, DEAD_DRAKE, PLUCKED_DRAKE);
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    private Results processHens(NGameUI gui, ArrayList<CoopInfo> coopInfos, ArrayList<IncubatorInfo> qhens) throws InterruptedException {
        // Sort hens by quality (best to worst)
        qhens.sort(incubatorComparator.reversed());

        for (IncubatorInfo henInfo : qhens) {
            // Navigate to incubator area and open coop with hen
            context.goToArea(Specialisation.SpecName.duckIncubator);

            Gob henGob = Finder.findGob(henInfo.gobHash);
            if (henGob == null) continue;

            new PathFinder(henGob).run(gui);
            if (!(new OpenTargetContainer(COOP_CAP, henGob).run(gui).IsSuccess())) {
                return Results.FAIL();
            }

            // Get hen from coop inventory
            WItem hen = gui.getInventory(COOP_CAP).getItem(HEN);
            if (hen == null) {
                new CloseTargetContainer(COOP_CAP).run(gui);
                continue;
            }
            float henQuality = ((NGItem) hen.item).quality;

            Coord pos = hen.c.div(Inventory.sqsz);
            hen.item.wdgmsg("transfer", Coord.z);
            Coord finalPos1 = pos;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return gui.getInventory(COOP_CAP).isSlotFree(finalPos1);
                }
            });
            new CloseTargetContainer(COOP_CAP).run(gui);

            // Find coop with worse hen and replace it
            for (CoopInfo coopInfo : coopInfos) {
                for (int i = 0; i < coopInfo.henQualities.size(); i++) {
                    if (coopInfo.henQualities.get(i) < henQuality) {
                        hen = gui.getInventory().getItem(HEN);
                        if (hen == null) break;

                        // Navigate to duck area and open coop for replacement
                        context.goToArea(Specialisation.SpecName.duck);

                        Gob coopGob = Finder.findGob(coopInfo.gobHash);
                        if (coopGob == null) continue;

                        new PathFinder(coopGob).run(gui);
                        if (!(new OpenTargetContainer(COOP_CAP, coopGob).run(gui).IsSuccess())) {
                            return Results.FAIL();
                        }

                        // Get current hen in coop
                        WItem oldHen = gui.getInventory(COOP_CAP).getItem(HEN, coopInfo.henQualities.get(i));
                        if (oldHen == null) {
                            new CloseTargetContainer(COOP_CAP).run(gui);
                            continue;
                        }

                        // Replace hen
                        pos = oldHen.c.div(Inventory.sqsz);
                        oldHen.item.wdgmsg("transfer", Coord.z);
                        Coord finalPos = pos;
                        NUtils.addTask(new NTask() {
                            @Override
                            public boolean check() {
                                return gui.getInventory(COOP_CAP).isSlotFree(finalPos);
                            }
                        });

                        NUtils.takeItemToHand(hen);
                        gui.getInventory(COOP_CAP).dropOn(pos, "Duck Hen");

                        // Update quality
                        coopInfo.henQualities.set(i, henQuality);
                        henQuality = ((NGItem) oldHen.item).quality;
                        new CloseTargetContainer(COOP_CAP).run(gui);
                        break;
                    }
                }
            }

            // Process the hen (butcher it)
            hen = gui.getInventory().getItem(HEN);
            if (hen != null) {
                butcherDuck(gui, hen, DEAD_HEN, PLUCKED_HEN);
            }
        }
        new FreeInventory2(context).run(gui);
        return Results.SUCCESS();
    }

    /**
     * Butcher a duck - wring neck, pluck, clean, butcher.
     * Unlike chickens, plucked ducks keep their sex in the name ("Plucked Duck Drake" /
     * "Plucked Duck Hen"), so the caller passes the exact name to wait for.
     */
    private void butcherDuck(NGameUI gui, WItem duck, String deadType, String pluckedType) throws InterruptedException {
        // Check inventory space before butchering
        if (gui.getInventory().getNumberFreeCoord(new Coord(1, 1)) < 2) {
            new FreeInventory2(context).run(gui);
        }

        new SelectFlowerAction("Wring neck", duck).run(gui);
        NUtils.addTask(new WaitItems((NInventory) gui.maininv, new NAlias(deadType), 1));

        WItem deadDuck = gui.getInventory().getItem(new NAlias(deadType));
        if (deadDuck == null) return;

        Boolean skipPluckDrakes = (Boolean) NConfig.get(NConfig.Key.skipPluckingDrakesInDuck);
        boolean isDrake = DEAD_DRAKE.equals(deadType);
        if (skipPluckDrakes != null && skipPluckDrakes && isDrake) {
            // Leave as Dead Duck Drake
        } else {
            new SelectFlowerAction("Pluck", deadDuck).run(gui);
            NUtils.addTask(new WaitItems((NInventory) gui.maininv, new NAlias(pluckedType), 1));

            WItem plucked = gui.getInventory().getItem(new NAlias(pluckedType));
            if (plucked == null) return;

            new SelectFlowerAction("Clean", plucked).run(gui);
            NUtils.addTask(new WaitItems((NInventory) gui.maininv, new NAlias(CLEANED_DUCK), 1));

            WItem cleaned = gui.getInventory().getItem(new NAlias(CLEANED_DUCK));
            if (cleaned == null) return;

            Boolean skipButcher = (Boolean) NConfig.get(NConfig.Key.skipButcherInDuck);
            if (skipButcher == null || !skipButcher) {
                new SelectFlowerAction("Butcher", cleaned).run(gui);
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        try {
                            return gui.getInventory().getItems(new NAlias(CLEANED_DUCK)).isEmpty();
                        } catch (InterruptedException e) {
                            return false;
                        }
                    }
                });
            }
        }

        // Drop off if insufficient space for another duck
        if (shouldDropOffItems(gui)) {
            new FreeInventory2(context).run(gui);
        }
    }

    /**
     * Checks if inventory drop-off is needed based on available space.
     * Only drops off if insufficient space for another duck + buffer.
     *
     * @param gui Game UI interface
     * @return true if drop-off needed, false if can continue batching
     */
    private boolean shouldDropOffItems(NGameUI gui) throws InterruptedException {
        // Duck is 2x2 (4 cells) plus extra space for products.
        int availableSpaceForDuck = gui.getInventory().getNumberFreeCoord(new Coord(2, 2));
        return availableSpaceForDuck <= 2;
    }
}
