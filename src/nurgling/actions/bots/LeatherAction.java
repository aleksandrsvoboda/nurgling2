package nurgling.actions.bots;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.Pair;
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
                // findSpec only locates an already-loaded area; it returns null from another cell.
                NArea ttubsarea = context.goToArea(Specialisation.SpecName.ttub);
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

                /* Resolve the tanning area first, then come back: FillFluid reads every tub's gob
                 * with no null check, and if resolving tanning walked us into another cell the
                 * tubs would have unloaded. goToArea is a no-op when we're already in range. */
                Pair<Coord2d, Coord2d> tanningArea = context.goToArea(Specialisation.SpecName.tanning).getRCArea();
                context.goToArea(Specialisation.SpecName.ttub);
                new FillFluid(containers, tanningArea, new NAlias("tanfluid"), 2).run(gui);
                new FreeContainers(containers, new NAlias("Leather")).run(gui);

                // TakeItems2.takeAny already searches both piles and containers in the area (NContext.getSpecStorages); TransferToContainer already handles the tub's Tetris shapes.
                // Total need is summed across every tub still short and fetched in one trip,
                // rather than a separate source-then-tub round trip per container.
                ArrayList<Container> stillNeeding = new ArrayList<>();
                for (Container cont : containers)
                    if (!cont.isFull())
                        stillNeeding.add(cont);

                Coord hideShape = null;
                while (!stillNeeding.isEmpty()) {
                    int totalNeeded = capacityFor(stillNeeding, hideShape);
                    // takeAny's count is an absolute inventory target, not a delta. Handing it the
                    // tubs lets it re-target once it sees a hide's real size, so the worst-case
                    // guess above only ever applies before the first hide has been seen.
                    if (totalNeeded > gui.getInventory().getItems(notraw).size()) {
                        TakeItems2 fetch = new TakeItems2(context, totalNeeded, Specialisation.SpecName.readyHides, NInventory.QualityType.High);
                        fetch.fillTargets = stillNeeding;
                        fetch.takeAny(notraw, gui);
                        if (hideShape == null)
                            hideShape = fetch.getObservedShape();
                    }
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

                // TransferToPiles drops hides on the ground when they live in a chest, so offer
                // containers first and keep piles as the fallback.
                if (!gui.getInventory().getItems(notraw).isEmpty()) {
                    ArrayList<NContext.ObjectStorage> storages = context.getSpecStorages(Specialisation.SpecName.readyHides);
                    if (storages != null) {
                        for (NContext.ObjectStorage storage : storages) {
                            if (gui.getInventory().getItems(notraw).isEmpty())
                                break;
                            if (storage instanceof Container) {
                                Container back = (Container) storage;
                                // pathTo first: TransferToContainer just fails if the gob isn't
                                // streamed in, which would drop the hides to the pile fallback.
                                if (Container.pathTo(gui, back) == null)
                                    continue;
                                new TransferToContainer(back, notraw).run(gui);
                                new CloseTargetContainer(back).run(gui);
                            }
                        }
                    }
                }
                if (!gui.getInventory().getItems(notraw).isEmpty()) {
                    NArea readyHidesArea = context.goToArea(Specialisation.SpecName.readyHides);
                    if (readyHidesArea != null)
                        new TransferToPiles(readyHidesArea.getRCArea(), notraw).run(gui);
                }

                return Results.SUCCESS();
            } finally {
                NUtils.stackSwitch(oldStackingValue);
            }
        }
        return Results.FAIL();
    }

    private static int capacityFor(ArrayList<Container> conts, Coord shape) {
        int total = 0;
        for (Container cont : conts)
            total += (shape != null) ? cont.freeSpace(shape) : cont.freeSpace();
        return total;
    }
}
