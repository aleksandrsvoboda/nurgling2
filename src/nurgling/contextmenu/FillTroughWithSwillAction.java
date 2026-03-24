package nurgling.contextmenu;

import haven.*;
import nurgling.*;
import nurgling.actions.Action;
import nurgling.actions.PathFinder;
import nurgling.actions.Results;
import nurgling.actions.TakeItemsFromContainer;
import nurgling.actions.TakeItemsFromPile;
import nurgling.actions.OpenTargetContainer;
import nurgling.actions.TransferToTrough;
import nurgling.actions.bots.SelectArea;
import nurgling.actions.bots.SwillItemRegistry;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.StockpileUtils;

import java.util.*;

public class FillTroughWithSwillAction implements GobContextAction {
    private static final NAlias TROUGH = new NAlias("gfx/terobjs/trough");

    @Override
    public boolean appliesTo(Gob gob) {
        return NParser.checkName(gob.ngob.name, TROUGH);
    }

    @Override
    public String label() {
        return "Fill with swill";
    }

    @Override
    public Action create(Gob gob) {
        return gui -> {
            // Check if trough is already full
            if (gob.ngob.getModelAttribute() == 7) {
                return Results.ERROR("Trough is already full");
            }

            // Select area for swill collection
            SelectArea insa;
            gui.msg("Please select area for swill collection");
            (insa = new SelectArea(Resource.loadsimg("baubles/inputArea"))).run(gui);
            Pair<Coord2d, Coord2d> area = insa.getRCArea();

            if (area == null) {
                return Results.ERROR("No area selected");
            }

            NAlias swillAlias = createSwillAlias();

            // Process containers
            ArrayList<Container> containers = new ArrayList<>();
            for (Gob sm : Finder.findGobs(area, new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
                Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), null);
                containers.add(cand);
            }

            for (Container container : containers) {
                if (gob.ngob.getModelAttribute() == 7) {
                    gui.msg("Trough is full");
                    return Results.SUCCESS();
                }

                new PathFinder(Finder.findGob(container.gobid)).run(gui);
                new OpenTargetContainer(container.cap, Finder.findGob(container.gobid)).run(gui);

                HashSet<String> swillNames = getSwillNames(swillAlias);
                if (!swillNames.isEmpty()) {
                    new TakeItemsFromContainer(container, swillNames, swillAlias).run(gui);
                }

                if (gui.getInventory().getFreeSpace() == 0) {
                    if (!deliverToTrough(gui, gob, swillAlias)) {
                        return Results.SUCCESS();
                    }
                }
            }

            // Process stockpiles
            ArrayList<Gob> piles;
            while (!(piles = Finder.findGobs(area, new NAlias("stockpile"))).isEmpty()) {
                for (Gob pile : piles) {
                    if (gob.ngob.getModelAttribute() == 7) {
                        gui.msg("Trough is full");
                        return Results.SUCCESS();
                    }

                    if (PathFinder.isAvailable(pile)) {
                        Coord size = StockpileUtils.itemMaxSize.get(pile.ngob.name);
                        new PathFinder(pile).run(gui);
                        new OpenTargetContainer("Stockpile", pile).run(gui);

                        while (Finder.findGob(pile.id) != null) {
                            if (gui.getInventory().getNumberFreeCoord((size != null) ? size : new Coord(1, 1)) > 0) {
                                NISBox spbox = gui.getStockpile();
                                if (spbox != null && spbox.calcCount() > 0) {
                                    int target_size = gui.getInventory().getNumberFreeCoord((size != null) ? size : new Coord(1, 1));
                                    if (target_size == 0) {
                                        if (!deliverToTrough(gui, gob, swillAlias)) {
                                            return Results.SUCCESS();
                                        }
                                        if (Finder.findGob(pile.id) != null) {
                                            new PathFinder(pile).run(gui);
                                            new OpenTargetContainer("Stockpile", pile).run(gui);
                                        } else break;
                                    } else {
                                        target_size = Math.min(spbox.calcCount(), target_size);
                                        if (target_size > 0) {
                                            new TakeItemsFromPile(pile, spbox, target_size).run(gui);
                                        }
                                    }
                                } else {
                                    break;
                                }
                            } else {
                                if (!deliverToTrough(gui, gob, swillAlias)) {
                                    return Results.SUCCESS();
                                }
                                if (Finder.findGob(pile.id) != null) {
                                    new PathFinder(pile).run(gui);
                                    new OpenTargetContainer("Stockpile", pile).run(gui);
                                }
                            }
                        }
                    }
                }
                if (Finder.findGobs(area, new NAlias("stockpile")).isEmpty()) {
                    break;
                }
            }

            // Final delivery
            deliverToTrough(gui, gob, swillAlias);
            return Results.SUCCESS();
        };
    }

    private static NAlias createSwillAlias() {
        HashSet<String> all = new HashSet<>();
        all.addAll(SwillItemRegistry.HIGH_VALUE_SWILL);
        all.addAll(SwillItemRegistry.STANDARD_SWILL);
        all.addAll(SwillItemRegistry.LOW_VALUE_SWILL);
        all.addAll(SwillItemRegistry.SEED_SWILL);
        return new NAlias(all.toArray(new String[0]));
    }

    private static HashSet<String> getSwillNames(NAlias swillAlias) {
        HashSet<String> names = new HashSet<>();
        for (String name : swillAlias.keys) {
            names.add(name);
        }
        return names;
    }

    private static boolean deliverToTrough(NGameUI gui, Gob trough, NAlias swillAlias) throws InterruptedException {
        ArrayList<WItem> swillItems = new ArrayList<>();
        for (WItem item : gui.getInventory().getItems()) {
            String itemName = ((NGItem) item.item).name();
            if (itemName != null && SwillItemRegistry.isSwillItem(itemName)) {
                swillItems.add(item);
            }
        }

        if (swillItems.isEmpty()) {
            return true;
        }

        new TransferToTrough(trough, swillAlias).run(gui);

        if (trough.ngob.getModelAttribute() == 7) {
            ArrayList<WItem> remaining = gui.getInventory().getItems(swillAlias);
            if (!remaining.isEmpty()) {
                gui.msg("Trough is full, stopping collection");
                return false;
            }
        }

        return true;
    }
}
