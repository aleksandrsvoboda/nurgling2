package nurgling.actions.bots;

import haven.*;
import haven.res.lib.tree.TreeScale;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.actions.cart.CartHauler;
import nurgling.actions.cart.SelectCart;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.NChopperProp;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Chopper implements Action {
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        nurgling.widgets.bots.Chopper w = null;
        NChopperProp prop = null;
        try {
            NUtils.getUI().core.addTask(new WaitCheckable( NUtils.getGameUI().add((w = new nurgling.widgets.bots.Chopper()), UI.scale(200,200))));
            prop = w.prop;
        }
        catch (InterruptedException e)
        {
            throw e;
        }
        finally {
            if(w!=null)
                w.destroy();
        }
        if(prop == null)
        {
            return Results.ERROR("No config");
        }
        if((prop.stumps && prop.shovel==null) || (prop.tool == null))
        {
            return Results.ERROR("Not set required tools");
        }

        NContext context = new NContext(gui);

        NArea carrierOutArea = null;
        if (prop.carryLogsToCarrierOut) {
            carrierOutArea = context.findArea(Specialisation.SpecName.carrierout);
            if (carrierOutArea == null) {
                return Results.ERROR("No CarrierOut zone found! Please create a global zone with 'carrierout' specialization.");
            }
        }

        String treeArea = context.createArea("Please select area for deforestation", Resource.loadsimg("baubles/chopperArea"));

        Gob cart = (prop.carryLogsToCarrierOut && carrierOutArea != null) ? SelectCart.ask(gui) : null;
        CartHauler hauler = (cart != null) ? new CartHauler(gui, cart) : null;
        try {
            return chop(gui, prop, context, carrierOutArea, treeArea, hauler);
        } finally {
            if (hauler != null)
                hauler.detach();
        }
    }

    private Results chop(NGameUI gui, NChopperProp prop, NContext context, NArea carrierOutArea, String treeArea,
                         CartHauler hauler) throws InterruptedException {

        NAlias pattern = prop.stumps ? new NAlias(new ArrayList<String>(List.of("gfx/terobjs/tree")),new ArrayList<String>(Arrays.asList("log","oldtrunk"))) :
                new NAlias(new ArrayList<String>(List.of("gfx/terobjs/tree")),new ArrayList<String>(Arrays.asList("log", "oldtrunk", "stump")));

        if(!prop.bushes)
        {
            pattern.exceptions.add("bushes");
        }
        else
        {
            pattern.keys.add("gfx/terobjs/bushes");
        }
        pattern.buildCaches(); // Rebuild caches after modifying keys/exceptions
        ArrayList<Gob> trees;
        while (!(trees = context.getGobs(treeArea,pattern)).isEmpty()) {
            trees.sort(NUtils.y_min_comp);

            if(prop.ngrowth)
            {
                ArrayList<Gob> for_remove = new ArrayList<>();
                for (Gob tree: trees)
                {
                    if(tree.getattr(TreeScale.class)!=null)
                    {
                        for_remove.add(tree);
                    }
                }
                trees.removeAll(for_remove);
                if(trees.isEmpty())
                    break;
            }

            Gob tree = trees.get(0);
            long treeId = tree.id;
            context.setLastPos(tree.rc);
            PathFinder pf = new PathFinder(tree);
            pf.setMode(PathFinder.Mode.Y_MAX);
            pf.isHardMode = true;
            pf.run(gui);

            while (tree!=null && context.getGob(treeArea, treeId) != null) {
                boolean chopped = false;
                if (NParser.isIt(tree, new NAlias("stump"))) {
                    if(!new Equip(new NAlias(prop.shovel)).run(gui).IsSuccess())
                        return Results.ERROR("Equipment not found: " + prop.shovel);
                    new Destroy(tree,"gfx/borka/shoveldig").run(gui);
                } else {
                    chopped = true;
                    if(tree.getattr(TreeScale.class)!=null)
                    {
                        chopped = false;
                    }
                    if(tree.ngob.name.startsWith("gfx/terobjs/bushes"))
                        chopped = false;
                    if(!new Equip(new NAlias(prop.tool)).run(gui).IsSuccess())
                        return Results.ERROR("Equipment not found: " + prop.tool);
                    new SelectFlowerAction("Chop", tree).run(gui);
                    NUtils.getUI().core.addTask(new WaitPoseOrNoGob(NUtils.player(), tree, "gfx/borka/treechop"));
                }
                WaitChopperState wcs = new WaitChopperState(tree, prop);
                NUtils.getUI().core.addTask(wcs);
                switch (wcs.getState()) {
                    case TREENOTFOUND:
                        break;
                    case TIMEFORDRINK:
                    case TIMEFOREAT: {
                        context.setLastPos(tree.rc);
                        if (!new RestoreResources().run(gui).IsSuccess()) {
                            return Results.ERROR("No Drink or Eat");
                        }
                        tree = context.getGob(treeArea, treeId);

                        break;
                    }
                    case DANGER:
                        return Results.ERROR("SOMETHING WRONG, STOP WORKING");
                    case WOUND_DANGER:
                        return Results.ERROR("Scrapes & Cuts wound damage too high! Stopping for safety.");

                }
                if(chopped && context.getGob(treeArea, treeId) == null) {
                    NUtils.addTask(new NTask() {
                        @Override
                        public boolean check() {
                            try {
                                return Finder.findGob(context.getLastPosCoord(treeArea))!=null;
                            } catch (InterruptedException e) {
                                return true;
                            }
                        }
                    });
                }
            }

            if (prop.carryLogsToCarrierOut && carrierOutArea != null) {
                Results carried = carryLogsFromArea(gui, context, treeArea, carrierOutArea, hauler, false);
                if (hauler != null && !carried.IsSuccess())
                    return carried;
            }
        }
        // With a cart: haul the last part-load and put the cart back where it was clicked.
        if (hauler != null) {
            Results carried = carryLogsFromArea(gui, context, treeArea, carrierOutArea, hauler, true);
            if (!carried.IsSuccess())
                return carried;
        }
        new RunToSafe().run(gui);
        return Results.SUCCESS();
    }

    // Sweeps all log gobs from the deforestation area to the target area: one trip per log by hand, or six at a
    // time when a cart was clicked at the start (see CarryLiftablesToArea). flush=false keeps a partly filled cart
    // parked here for the next tree.
    private Results carryLogsFromArea(NGameUI gui, NContext context, String sourceAreaId, NArea targetArea,
                                      CartHauler hauler, boolean flush) throws InterruptedException {
        // Match all tree-family gobs, then post-filter to logs only. NAlias keys are OR
        // and substring-only, so prefix+suffix (starts with "gfx/terobjs/trees" AND ends
        // with "log") cannot be expressed as a single alias.
        NAlias treeFamilyAlias = new NAlias(
                new ArrayList<>(List.of("gfx/terobjs/trees")),
                new ArrayList<>(Arrays.asList("oldtrunk", "stump")));

        CarryLiftablesToArea.Source logs = new CarryLiftablesToArea.Source() {
            @Override
            public ArrayList<Gob> candidates() throws InterruptedException {
                ArrayList<Gob> found = new ArrayList<>();
                for (Gob g : context.getGobs(sourceAreaId, treeFamilyAlias)) {
                    if (g.ngob != null && g.ngob.name != null && g.ngob.name.endsWith("log")) {
                        found.add(g);
                    }
                }
                return found;
            }

            @Override
            public void goBack() throws InterruptedException {
                context.navigateToAreaIfNeeded(sourceAreaId);
            }
        };
        return new CarryLiftablesToArea(logs, targetArea, 0,
                "Can't reach any logs in chopping area, skipping carry.", hauler, flush).run(gui);
    }
}
