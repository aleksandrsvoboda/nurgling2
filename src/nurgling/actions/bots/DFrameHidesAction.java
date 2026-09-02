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
import java.util.Comparator;

public class DFrameHidesAction implements Action {

    NAlias raw = new NAlias("Fresh");
    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NArea.Specialisation rdframe = new NArea.Specialisation(Specialisation.SpecName.dframe.toString(), "Hides");
        NArea.Specialisation rrawhides = new NArea.Specialisation(Specialisation.SpecName.rawhides.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(rdframe);
        req.add(rrawhides);
        ArrayList<NArea.Specialisation> opt = new ArrayList<>();
        if(new Validator(req, opt).run(gui).IsSuccess()) {
            // The player's client-side stacking toggle silently breaks item stacking on deposit
            // if left off; force it on for the run and always restore it, even on interrupt.
            boolean oldStackingValue = ((NInventory) NUtils.getGameUI().maininv).bundle.a;
            NUtils.stackSwitch(true);
            try {
                NContext context = new NContext(gui);

                ArrayList<Container> containers = new ArrayList<>();

                NArea dframesarea = context.goToArea(Specialisation.SpecName.dframe, "Hides");
                for (Gob dframe : Finder.findGobs(dframesarea,
                        new NAlias("gfx/terobjs/dframe"))) {
                    Container cand = new Container(dframe,"Frame" , dframesarea);

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
                Pair<Coord2d,Coord2d> rca = dframesarea.getRCArea();
                boolean dir = rca.b.x - rca.a.x > rca.b.y - rca.a.y;
                containers.sort(new Comparator<Container>() {
                    @Override
                    public int compare(Container o1, Container o2) {
                        Gob gob1 = Finder.findGob(o1.gobid);
                        Gob gob2 = Finder.findGob(o2.gobid);
                        if(dir)
                        {
                            int res = Double.compare(gob1.rc.y,gob2.rc.y);
                            if(res == 0)
                                return Double.compare(gob1.rc.x,gob2.rc.x);
                            else
                                return res;
                        }
                        else
                        {
                            int res = Double.compare(gob1.rc.x,gob2.rc.x);
                            if(res == 0)
                                return Double.compare(gob1.rc.y,gob2.rc.y);
                            else
                                return res;
                        }
                    }
                });


                new FreeContainers(containers, new NAlias(new ArrayList<>(Arrays.asList("Fur", "Hide", "Scale", "Tail", "skin", "hide")), new ArrayList<>(Arrays.asList("Fresh", "Raw")))).run(gui);

                // TakeItems2.takeAny already searches both piles and containers in the area (NContext.getSpecStorages); TransferToContainer already handles the frame's Tetris shapes.
                // Total need is summed across every frame still short and fetched in one trip,
                // rather than a separate source-then-frame round trip per container.
                ArrayList<Container> stillNeeding = new ArrayList<>();
                for (Container cont : containers)
                    if (!cont.isFull())
                        stillNeeding.add(cont);

                Coord hideShape = null;
                while (!stillNeeding.isEmpty()) {
                    int totalNeeded = capacityFor(stillNeeding, hideShape);
                    // takeAny's count is an absolute inventory target, not a delta. Handing it the
                    // frames lets it re-target once it sees a hide's real size, so the worst-case
                    // guess above only ever applies before the first hide has been seen.
                    if (totalNeeded > gui.getInventory().getItems(raw).size()) {
                        TakeItems2 fetch = new TakeItems2(context, totalNeeded, Specialisation.SpecName.rawhides, NInventory.QualityType.High);
                        fetch.fillTargets = stillNeeding;
                        fetch.takeAny(raw, gui);
                        if (hideShape == null)
                            hideShape = fetch.getObservedShape();
                    }
                    int held = gui.getInventory().getItems(raw).size();
                    if (held == 0)
                        break;

                    context.goToArea(Specialisation.SpecName.dframe, "Hides");
                    ArrayList<Container> nextRound = new ArrayList<>();
                    for (Container cont : stillNeeding) {
                        if (cont.isFull())
                            continue;
                        // A held hide fitting none of this frame's shapes can't be placed now, but a
                        // later round may fetch one that does, so keep the frame for next round.
                        if (!gui.getInventory().getItems(raw).isEmpty() && cont.hasMatchingHole(raw, gui)) {
                            new TransferToContainer(cont, raw).run(gui);
                            new CloseTargetContainer(cont).run(gui);
                        }
                        if (!cont.isFull())
                            nextRound.add(cont);
                    }
                    // A whole pass that placed nothing means the held hides fit no remaining hole,
                    // so repeating the same round would spin forever.
                    if (gui.getInventory().getItems(raw).size() == held)
                        break;
                    stillNeeding = nextRound;
                }

                NArea rawhidesArea = context.goToArea(Specialisation.SpecName.rawhides);
                new TransferToPiles(rawhidesArea.getRCArea(), new NAlias("Fresh")).run(gui);

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
