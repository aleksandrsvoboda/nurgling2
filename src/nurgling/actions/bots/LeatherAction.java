package nurgling.actions.bots;

import haven.Coord;
import haven.Gob;
import haven.UI;
import haven.WItem;
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
                return fillTubs(gui);
            } finally {
                NUtils.stackSwitch(oldStackingValue);
            }
        }
        return Results.FAIL();
    }

    private Results fillTubs(NGameUI gui) throws InterruptedException {
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
        // Total need is computed once per round across all tubs still short, and fetched in one
        // trip, rather than doing a separate source-then-tub round trip per container.
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

            int stillToFetch = totalNeeded - gui.getInventory().getItems(notraw).size();
            if (stillToFetch > 0)
                new TakeItems2(context, stillToFetch, Specialisation.SpecName.readyHides, NInventory.QualityType.High).takeAny(notraw, gui);
            if (gui.getInventory().getItems(notraw).isEmpty())
                break;

            context.goToArea(Specialisation.SpecName.ttub);
            ArrayList<Container> nextRound = new ArrayList<>();
            for (Container cont : stillNeeding) {
                if (cont.isFull())
                    continue;
                if (gui.getInventory().getItems(notraw).isEmpty()) {
                    nextRound.add(cont);
                    continue;
                }
                /* A held item whose shape matches none of the tub's TARGET_COORD sizes would
                 * never get selected by TransferToContainer's per-shape filter, so it can
                 * never leave inventory - without this check a stuck item would keep this
                 * container (and the outer while loop) spinning forever (FillContainersFromPiles
                 * had the same "hole" guard for this reason). Drop it from future rounds instead. */
                if (!hasMatchingHole(cont, notraw, gui))
                    continue;
                new TransferToContainer(cont, notraw).run(gui);
                new CloseTargetContainer(cont).run(gui);
                if (!cont.isFull())
                    nextRound.add(cont);
            }
            stillNeeding = nextRound;
        }

        new TransferToPiles(NContext.findSpec(Specialisation.SpecName.readyHides.toString()).getRCArea(), notraw).run(gui);

        return Results.SUCCESS();
    }

    private boolean hasMatchingHole(Container cont, NAlias alias, NGameUI gui) throws InterruptedException {
        Container.Tetris tetris = cont.getattr(Container.Tetris.class);
        if (tetris == null)
            return true;
        for (WItem witem : gui.getInventory().getItems(alias)) {
            if (witem.item.spr != null && tetris.calcNumberFreeCoord(Container.Tetris.SRC, witem.item.spr.sz().div(UI.scale(32)).swapXY()) > 0)
                return true;
        }
        return false;
    }
}
