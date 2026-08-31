package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Arrays;

public class LeatherAction implements Action {

    NAlias notraw = new NAlias(new ArrayList<>(Arrays.asList("hide", "Scale", "skin", "Hide", "Fur", "fur")), new ArrayList<>(Arrays.asList("Fresh", "Raw", "water")));
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea.Specialisation rdframe = new NArea.Specialisation(Specialisation.SpecName.ttub.toString());
        NArea.Specialisation rrawhides = new NArea.Specialisation(Specialisation.SpecName.readyHides.toString());
        NArea.Specialisation rtanning = new NArea.Specialisation(Specialisation.SpecName.tanning.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(rdframe);
        req.add(rtanning);
        req.add(rrawhides);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        if (new Validator(req, opt).run(gui).IsSuccess()) {
            // The player's client-side stacking toggle silently breaks item stacking on deposit
            // if left off; force it on for the run and always restore it, even on interrupt.
            boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;
            NUtils.stackSwitch(true);
            try {
                NContext context = new NContext(gui);
                ArrayList<Container> containers = new ArrayList<>();
                NArea ttubsarea = NContext.findSpec(Specialisation.SpecName.ttub.toString());
                for (Gob ttube : Finder.findGobs(ttubsarea,
                        new NAlias("gfx/terobjs/ttub"))) {
                    Container cand = new Container(ttube , "Tub",ttubsarea );

                    cand.initattr(Container.Space.class);
                    cand.initattr(Container.Tetris.class);
                    Container.Tetris tetris = cand.getattr(Container.Tetris.class);
                    ArrayList<Coord> coords = new ArrayList<>();

                    coords.add(new Coord(2, 2));
                    coords.add(new Coord(2, 1));
                    coords.add(new Coord(1, 1));

                    tetris.getRes().put(Container.Tetris.TARGET_COORD, coords);

                    containers.add(cand);
                }

                new FillFluid(containers, NContext.findSpec(Specialisation.SpecName.tanning.toString()).getRCArea(), new NAlias("tanfluid"), 2).run(gui);
                new FreeContainers(containers, new NAlias("Leather")).run(gui);

                // TakeItems2.takeAny already searches both piles and containers in the area (NContext.getSpecStorages); TransferToContainer already handles the tub's Tetris shapes.
                // Total need is summed across every tub still short and fetched in one trip,
                // rather than a separate source-then-tub round trip per container.
                ArrayList<Container> stillNeeding = new ArrayList<>();
                for (Container cont : containers)
                    if (!cont.isFull())
                        stillNeeding.add(cont);

                while (!stillNeeding.isEmpty()) {
                    int totalNeeded = 0;
                    for (Container cont : stillNeeding)
                        totalNeeded += cont.freeSpace();
                    if (totalNeeded > gui.getInventory().getItems(notraw).size())
                        new TakeItems2(context, totalNeeded - gui.getInventory().getItems(notraw).size(), Specialisation.SpecName.readyHides, NInventory.QualityType.High).takeAny(notraw, gui);
                    int held = gui.getInventory().getItems(notraw).size();
                    if (held == 0)
                        break;

                    context.goToArea(Specialisation.SpecName.ttub);
                    ArrayList<Container> nextRound = new ArrayList<>();
                    for (Container cont : stillNeeding) {
                        if (cont.isFull())
                            continue;
                        // A held hide fitting none of this tub's shapes can't be placed now, but a
                        // later round may fetch one that does, so keep the tub for next round.
                        if (!gui.getInventory().getItems(notraw).isEmpty() && cont.hasMatchingHole(notraw, gui)) {
                            new TransferToContainer(cont, notraw).run(gui);
                            new CloseTargetContainer(cont).run(gui);
                        }
                        if (!cont.isFull())
                            nextRound.add(cont);
                    }
                    // A whole pass that placed nothing means the held hides fit no remaining hole,
                    // so repeating the same round would spin forever.
                    if (gui.getInventory().getItems(notraw).size() == held)
                        break;
                    stillNeeding = nextRound;
                }

                new TransferToPiles(NContext.findSpec(Specialisation.SpecName.readyHides.toString()).getRCArea(), notraw).run(gui);

                return Results.SUCCESS();
            } finally {
                NUtils.stackSwitch(oldStackingValue);
            }
        }
        return Results.FAIL();
    }
}
