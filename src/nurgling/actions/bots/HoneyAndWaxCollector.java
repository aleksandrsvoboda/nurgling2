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

        // Phase 1: Find all bee skep areas
        ArrayList<NArea> beeSkepAreas = NContext.findAllSpec(Specialisation.SpecName.beeSkep.toString());
        if (beeSkepAreas.isEmpty()) {
            getGameUI().error("No Bee Skep areas found");
            return Results.ERROR("No Bee Skep areas found");
        }

        // Phase 2: Honey collection
        if (collectHoney) {
            Results honeyResult = collectAllHoney(gui, beeSkepAreas);
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

    private Results collectAllHoney(NGameUI gui, ArrayList<NArea> beeSkepAreas) throws InterruptedException {
        // Find cistern area with Honey subspecialization
        NArea cisternArea = NContext.findSpecGlobal(
                Specialisation.SpecName.cistern.toString(), "Honey");
        if (cisternArea == null) {
            getGameUI().error("No Cistern area with Honey specialization found");
            return Results.ERROR("No Cistern area with Honey specialization");
        }

        // Navigate to cistern area and find barrel + cistern
        NUtils.navigateToArea(cisternArea);
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

            // Find all visible beehives (around the area, not bounded by it)
            ArrayList<Gob> skeps = findHoneySkeps();

            while (!skeps.isEmpty()) {
                skeps.sort(NUtils.d_comp);
                boolean barrelFull = false;

                for (Gob skep : skeps) {
                    if (!hasHoney(skep))
                        continue;

                    new PathFinder(skep).run(gui);

                    long attrBefore = skep.ngob.getModelAttribute();
                    NUtils.activateGob(skep);

                    // Wait for model attribute change with timeout
                    WaitModelAttributeChange waitAttr = new WaitModelAttributeChange(skep, attrBefore);
                    NUtils.getUI().core.addTask(waitAttr);

                    if (waitAttr.criticalExit) {
                        // Timeout - attribute didn't change, barrel is full
                        barrelFull = true;
                        break;
                    }
                }

                if (barrelFull) {
                    // Empty barrel at cistern and come back
                    NUtils.navigateToArea(cisternArea);
                    emptyBarrelAtCistern(gui, barrel, cistern);
                    NUtils.navigateToArea(beeArea);
                    skeps = findHoneySkeps();
                } else {
                    break;
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
            { infinite = false; maxCounter = 500; }
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

            boolean needRestart = true;
            while (needRestart) {
                needRestart = false;
                ArrayList<Gob> skeps = findWaxSkeps();
                skeps.sort(NUtils.d_comp);

                for (Gob skep : skeps) {
                    if (!hasWax(skep))
                        continue;

                    if (gui.getInventory().getNumberFreeCoord(Coord.of(1, 2)) < 1) {
                        new FreeInventory2(context).run(gui);
                        NUtils.navigateToArea(beeArea);
                        needRestart = true;
                        break;
                    }

                    new PathFinder(skep).run(gui);
                    new SelectFlowerAction("Harvest wax", skep).run(gui);
                    NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/bushpickan"));
                    NUtils.getUI().core.addTask(new WaitPose(NUtils.player(), "gfx/borka/idle"));
                }
            }
        }

        new FreeInventory2(context).run(gui);
        getGameUI().msg("Wax collection done!");
        return Results.SUCCESS();
    }

    private ArrayList<Gob> findHoneySkeps() {
        ArrayList<Gob> skeps = Finder.findGobs(BEEHIVE);
        skeps.removeIf(s -> !hasHoney(s));
        return skeps;
    }

    private ArrayList<Gob> findWaxSkeps() {
        ArrayList<Gob> skeps = Finder.findGobs(BEEHIVE);
        skeps.removeIf(s -> !hasWax(s));
        return skeps;
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
        private final Gob gob;
        private final long initialAttr;

        WaitModelAttributeChange(Gob gob, long initialAttr) {
            this.gob = gob;
            this.initialAttr = initialAttr;
            this.infinite = false;
            this.maxCounter = 300;
        }

        @Override
        public boolean check() {
            return gob.ngob.getModelAttribute() != initialAttr;
        }
    }
}
