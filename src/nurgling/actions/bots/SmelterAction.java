package nurgling.actions.bots;

import haven.Gob;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tasks.WaitBurnoutOrBellows;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.*;

public class SmelterAction implements Action {



    static NAlias ores = new NAlias ( new ArrayList<> (
            Arrays.asList ( "Cassiterite", "Lead Glance", "Wine Glance", "Chalcopyrite", "Malachite", "Peacock Ore", "Cinnabar", "Heavy Earth", "Iron Ochre",
                    "Bloodstone", "Black Ore", "Galena", "Silvershine", "Horn Silver", "Direvein", "Schrifterz", "Leaf Ore", "Meteorite", "Dross") ) );


    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        NArea.Specialisation ofuelc = new NArea.Specialisation(Specialisation.SpecName.fuel.toString(), "coal");

        NArea.Specialisation ofuelb = new NArea.Specialisation(Specialisation.SpecName.fuel.toString(), "branch");
        NArea.Specialisation rsmelter = new NArea.Specialisation(Specialisation.SpecName.smelter.toString());
        NArea.Specialisation rore = new NArea.Specialisation(Specialisation.SpecName.ore.toString());
        NArea.Specialisation omercury = new NArea.Specialisation(Specialisation.SpecName.barrel.toString(),"Quicksilver");

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(rsmelter);
        req.add(rore);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        opt.add(ofuelb);
        opt.add(ofuelc);
        opt.add(omercury);

        if(new Validator(req, opt).run(gui).IsSuccess()) {
            // The player's client-side stacking toggle silently breaks item stacking on deposit
            // if left off; force it on for the run and always restore it, even on interrupt.
            boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;
            NUtils.stackSwitch(true);
            try {
                return runSmelter(gui);
            } finally {
                NUtils.stackSwitch(oldStackingValue);
            }
        }
        return Results.FAIL();
    }

    private Results runSmelter(NGameUI gui) throws InterruptedException {
        NContext context = new NContext(gui);
        NArea smelters = NContext.findSpec(Specialisation.SpecName.smelter.toString());

        ArrayList<Container> containers = new ArrayList<>();
        /* Stack furnaces hold their bellows, and only they are pumped. */
        ArrayList<String> furnaces = new ArrayList<>();

        /* NAlias matches by substring and "gfx/terobjs/primsmelter" contains "gfx/terobjs/smelter",
         * so without this exception every stack furnace was also registered as an Ore Smelter - a
         * container whose cap never matches its window, which stalled FreeContainers before the loop
         * could light anything. LightObject.getConfig documents the same trap. */
        NAlias oreSmelter = new NAlias(new ArrayList<>(Arrays.asList("gfx/terobjs/smelter")),
                new ArrayList<>(Arrays.asList("primsmelter")));

        for (Gob sm : Finder.findGobs(smelters, oreSmelter)) {
            Container cand = new Container(sm, ((sm.ngob.getModelAttribute() & 128) == 128) ? "Smith's Smelter" : "Ore Smelter", smelters);

            cand.initattr(Container.Space.class);
            cand.initattr(Container.FuelLvl.class);
            cand.getattr(Container.FuelLvl.class).setMaxlvl(12);
            cand.getattr(Container.FuelLvl.class).setCredolvl(9);
            cand.getattr(Container.FuelLvl.class).setFueltype("coal");

            cand.initattr(Container.TargetItems.class);
            cand.getattr(Container.TargetItems.class).addTarget("Slag");
            cand.getattr(Container.TargetItems.class).addTarget("Quicksilver");
            containers.add(cand);
        }

        for (Gob sm : Finder.findGobs(smelters, new NAlias("gfx/terobjs/primsmelter"))) {
            Container cand = new Container(sm, "Stack furnace", smelters);

            cand.initattr(Container.Space.class);
            cand.initattr(Container.FuelLvl.class);
            /* Branch counts, since fuelmod is 1. Lowered from 23/18 now that the bellows keeps the
             * furnace boosted for most of a burn: a measured batch started at 23 and still had 11 left
             * when the ore finished, so a boosted smelt costs about 12. The rest burned with nothing to
             * smelt while the bot waited out the burnout.
             *
             * The 12 already carries headroom: that batch spent its first few minutes burning
             * unboosted, so a run boosted throughout costs less. Worth keeping, because the two error
             * directions are not equal - leftover fuel only wastes branches and idle time, whereas
             * running out early trips the burnout wait and has FreeContainers pull half-smelted ore
             * back out. Credo level keeps the original ~0.78 ratio, well-mined ore needing less. */
            cand.getattr(Container.FuelLvl.class).setMaxlvl(12);
            cand.getattr(Container.FuelLvl.class).setCredolvl(9);
            cand.getattr(Container.FuelLvl.class).setFueltype("branch");

            cand.initattr(Container.TargetItems.class);
            cand.getattr(Container.TargetItems.class).addTarget("Slag");
            cand.getattr(Container.TargetItems.class).addTarget("Quicksilver");
            containers.add(cand);
            furnaces.add(cand.gobHash);
        }

        ArrayList<String> lighted = new ArrayList<>();
        for (Container cont : containers) {
            lighted.add(cont.gobHash);

        }
        if(containers.isEmpty())
            return Results.ERROR("NO SMELTERS");

        Results res = null;
        while (res == null || res.IsSuccess()) {
            /* Wait out the burn, but wake early whenever a stack furnace's boost lapses so it can be
             * pumped again. The boost covers roughly half a burn, so under a plain burnout barrier the
             * back half of every burn would run unboosted with the bot parked in a blocking wait. */
            while (true) {
                WaitBurnoutOrBellows burn = new WaitBurnoutOrBellows(lighted, furnaces);
                NUtils.getUI().core.addTask(burn);
                if (!burn.needsPump())
                    break;
                synchronized (NUtils.getGameUI()) {
                    new WorkBellows(furnaces).run(gui);
                }
            }

            synchronized (NUtils.getGameUI()) {
                new FreeContainers(containers).run(gui);
                NUtils.navigateToArea(smelters);
                new CollectQuickSilver(containers).run(gui);
                NUtils.navigateToArea(smelters);
                new DropTargets(containers, new NAlias("Slag")).run(gui);

                // TakeItems2.takeAny already searches both piles and containers in the area (NContext.getSpecStorages).
                // Total need is computed once per round across all furnaces still short, and
                // fetched in one trip, rather than doing a separate ore-then-furnace round trip
                // per container.
                boolean oreExhausted = false;
                ArrayList<Container> stillNeeding = new ArrayList<>();
                for (Container cont : containers)
                    if (!cont.isFull())
                        stillNeeding.add(cont);

                while (!stillNeeding.isEmpty()) {
                    int totalNeeded = 0;
                    for (Container cont : stillNeeding)
                        totalNeeded += cont.freeSpace();
                    if (totalNeeded == 0)
                        break;

                    int stillToFetch = totalNeeded - gui.getInventory().getItems(ores).size();
                    if (stillToFetch > 0)
                        new TakeItems2(context, stillToFetch, Specialisation.SpecName.ore, NInventory.QualityType.High).takeAny(ores, gui);
                    if (gui.getInventory().getItems(ores).isEmpty()) {
                        oreExhausted = true;
                        break;
                    }

                    context.goToArea(Specialisation.SpecName.smelter);
                    ArrayList<Container> nextRound = new ArrayList<>();
                    for (Container cont : stillNeeding) {
                        if (cont.isFull())
                            continue;
                        if (gui.getInventory().getItems(ores).isEmpty()) {
                            nextRound.add(cont);
                            continue;
                        }
                        new TransferToContainer(cont, ores).run(gui);
                        new CloseTargetContainer(cont).run(gui);
                        if (!cont.isFull())
                            nextRound.add(cont);
                    }
                    stillNeeding = nextRound;
                }
                res = oreExhausted ? Results.ERROR("NO ORE") : Results.SUCCESS();
                ArrayList<Container> forFuel = new ArrayList<>();

                for (Container container : containers) {
                    Container.Space space = container.getattr(Container.Space.class);
                    if (!space.isEmpty())
                        forFuel.add(container);
                }

                if (!new FuelToContainers(forFuel).run(gui).IsSuccess())
                    return Results.ERROR("NO FUEL");

                ArrayList<String> flighted = new ArrayList<>();
                for (Container cont : forFuel) {
                    flighted.add(cont.gobHash);
                }

                if (!new LightGob(flighted, 2).run(gui).IsSuccess())
                    return Results.ERROR("I can't start a fire");

                new WorkBellows(furnaces).run(gui);
            }
        }
        return Results.SUCCESS();
    }
}
