package nurgling.actions.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

import static nurgling.NUtils.getGameUI;

public class HoneyAndWaxCollector implements Action {

    private static final NAlias BEEHIVE = new NAlias("gfx/terobjs/beehive");
    private static final NAlias HONEY_OVERLAY = new NAlias("Honey");
    private static final NAlias BARREL_ALIAS = new NAlias("barrel");
    private static final NAlias CISTERN_ALIAS = new NAlias("cistern");
    private static final NAlias BEESWAX_ALIAS = new NAlias("Beeswax");

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
            Results waxResult = collectAllWax(gui, beeSkepAreas);
            if (!waxResult.IsSuccess())
                return waxResult;
        }

        return Results.SUCCESS();
    }

    private Results collectAllHoney(NGameUI gui, NContext context, ArrayList<NArea> beeSkepAreas) throws InterruptedException {
        NArea cisternArea = context.getSpecArea(Specialisation.SpecName.cistern, "Honey");
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

        // Lift barrel
        new LiftObject(barrel).run(gui);

        // If barrel already has honey, empty it first
        if (NUtils.isOverlay(barrel, HONEY_OVERLAY)) {
            emptyBarrelAtCistern(gui, barrel, cistern);
        }

        // Visit each bee skep area
        for (int areaIdx = 0; areaIdx < beeSkepAreas.size(); areaIdx++) {
            NArea beeArea = beeSkepAreas.get(areaIdx);
            NUtils.navigateToArea(beeArea);

            // Collect honey from closest skep repeatedly
            Gob skep;
            while ((skep = findClosestHoneySkep()) != null) {
                PathFinder pf = new PathFinder(skep);
                pf.isHardMode = true;
                if (!pf.run(gui).IsSuccess())
                    continue;

                long attrBefore = skep.ngob.getModelAttribute();
                NUtils.activateGob(skep);

                WaitModelAttributeChange waitAttr = new WaitModelAttributeChange(skep, attrBefore);
                NUtils.getUI().core.addTask(waitAttr);

                if (!waitAttr.changed) {
                    // Barrel is full - empty at cistern and come back
                    NUtils.navigateToArea(cisternArea);
                    emptyBarrelAtCistern(gui, barrel, cistern);
                    NUtils.navigateToArea(beeArea);
                }
            }
        }

        // Final: empty barrel if it has honey
        if (NUtils.isOverlay(barrel, HONEY_OVERLAY)) {
            NUtils.navigateToArea(cisternArea);
            emptyBarrelAtCistern(gui, barrel, cistern);
        } else {
            NUtils.navigateToArea(cisternArea);
        }

        // Place barrel back
        new PlaceObject(barrel, barrelOriginalPos, 0).run(gui);

        getGameUI().msg("Honey collection done!");
        return Results.SUCCESS();
    }

    private void emptyBarrelAtCistern(NGameUI gui, Gob barrel, Gob cistern) throws InterruptedException {
        new PathFinder(cistern).run(gui);
        NUtils.activateGob(cistern);
        NUtils.getUI().core.addTask(new NTask() {
            @Override
            public boolean check() {
                return !NUtils.isOverlay(barrel, HONEY_OVERLAY);
            }
        });
    }

    private Results collectAllWax(NGameUI gui, ArrayList<NArea> beeSkepAreas) throws InterruptedException {
        NContext context = new NContext(gui);

        for (NArea beeArea : beeSkepAreas) {
            NUtils.navigateToArea(beeArea);

            Gob skep;
            while ((skep = findClosestWaxSkep()) != null) {
                if (gui.getInventory().getNumberFreeCoord(Coord.of(1, 1)) < 5) {
                    new FreeInventory2(context).run(gui);
                    NUtils.navigateToArea(beeArea);
                    continue;
                }

                PathFinder pf = new PathFinder(skep);
                pf.isHardMode = true;
                if (!pf.run(gui).IsSuccess())
                    continue;
                new SelectFlowerAction("Harvest wax", skep).run(gui);
                NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/bushpickan"));
                NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/idle"));
            }
        }

        new FreeInventory2(context).run(gui);
        getGameUI().msg("Wax collection done!");
        return Results.SUCCESS();
    }

    private Gob findClosestHoneySkep() {
        ArrayList<Gob> skeps = Finder.findGobs(BEEHIVE);
        skeps.removeIf(s -> !hasHoney(s));
        if (skeps.isEmpty()) return null;
        skeps.sort(NUtils.d_comp);
        return skeps.get(0);
    }

    private Gob findClosestWaxSkep() {
        ArrayList<Gob> skeps = Finder.findGobs(BEEHIVE);
        skeps.removeIf(s -> !hasWax(s));
        if (skeps.isEmpty()) return null;
        skeps.sort(NUtils.d_comp);
        return skeps.get(0);
    }

    private boolean hasHoney(Gob skep) {
        long attr = skep.ngob.getModelAttribute();
        return attr == 35 || attr == 39;
    }

    private boolean hasWax(Gob skep) {
        long attr = skep.ngob.getModelAttribute();
        return attr == 39 || attr == 6;
    }

    private static class WaitModelAttributeChange extends NTask {
        private final long gobId;
        private final long initialAttr;
        public boolean changed = false;
        private int ticks = 0;

        WaitModelAttributeChange(Gob gob, long initialAttr) {
            this.gobId = gob.id;
            this.initialAttr = initialAttr;
            this.infinite = true;
        }

        @Override
        public boolean check() {
            ticks++;
            Gob gob = Finder.findGob(gobId);
            if (gob == null)
                return true;
            if (gob.ngob.getModelAttribute() != initialAttr) {
                changed = true;
                return true;
            }
            return ticks >= 300;
        }
    }
}
