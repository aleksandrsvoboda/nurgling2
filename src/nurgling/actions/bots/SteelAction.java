package nurgling.actions.bots;

import haven.GItem;
import haven.Gob;
import haven.Loading;
import haven.WItem;
import haven.Widget;
import haven.Window;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.tools.Container;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;

public class SteelAction implements Action {

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        NArea.Specialisation ofuelb = new NArea.Specialisation(Specialisation.SpecName.fuel.toString(), "branch");
        NArea.Specialisation rsmelter = new NArea.Specialisation(Specialisation.SpecName.crucibles.toString());

        ArrayList<NArea.Specialisation> req = new ArrayList<>();
        req.add(rsmelter);
        req.add(ofuelb);

        if (new Validator(req, new ArrayList<>()).run(gui).IsSuccess()) {

            NArea smelters = NContext.findSpec(Specialisation.SpecName.crucibles.toString());
            Finder.findGobs(smelters, new NAlias("gfx/terobjs/steelcrucible"));

            ArrayList<Container> containers = new ArrayList<>();

            for (Gob sm : Finder.findGobs(smelters, new NAlias("gfx/terobjs/steelcrucible"))) {
                Container cand = new Container(sm, "Steelbox", smelters);

                cand.initattr(Container.FuelLvl.class);
                cand.getattr(Container.FuelLvl.class).setMaxlvl(17);
                cand.getattr(Container.FuelLvl.class).setAbsMaxlvl(18);
                cand.getattr(Container.FuelLvl.class).setFueltype("branch");

                containers.add(cand);
            }

            if (containers.isEmpty())
                return Results.ERROR("NO CRUCIBLES");

            ArrayList<Container> containersWithWroughtIron = new ArrayList<>();
            for (Container container : containers) {
                PathFinder pf = new PathFinder(Finder.findGob(container.gobHash));
                pf.isHardMode = true;
                pf.run(gui);
                new OpenTargetContainer(container).run(gui);
                if (hasWroughtIronBar(gui.getWindow(container.cap))) {
                    containersWithWroughtIron.add(container);
                }
                new CloseTargetContainer(container).run(gui);
            }

            if (containersWithWroughtIron.isEmpty())
                return Results.SUCCESS();

            if (!new FuelToContainers(containersWithWroughtIron).run(gui).IsSuccess())
                return Results.ERROR("NO FUEL");

            ArrayList<String> flighted = new ArrayList<>();
            for (Container cont : containersWithWroughtIron) {
                flighted.add(cont.gobHash);
            }

            if (!new LightGob(flighted, 4).run(gui).IsSuccess())
                return Results.ERROR("I can't start a fire");
        }
        return Results.SUCCESS();
    }

    static boolean hasWroughtIronBar(Window window) {
        if (window == null) {
            return false;
        }
        for (Widget widget = window.lchild; widget != null; widget = widget.prev) {
            if (widget instanceof NInventory && hasWroughtIronBar((NInventory) widget)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasWroughtIronBar(NInventory inventory) {
        return inventory != null && hasWroughtIronBar(inventory.child);
    }

    private static boolean hasWroughtIronBar(Widget first) {
        for (Widget widget = first; widget != null; widget = widget.next) {
            if (widget instanceof WItem) {
                GItem item = ((WItem) widget).item;
                if (item instanceof NGItem) {
                    NGItem nitem = (NGItem) item;
                    if (isWroughtIronBar(nitem.name(), resourceName(nitem))) {
                        return true;
                    }
                }
                if (item.contents != null && hasWroughtIronBar(item.contents.child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isWroughtIronBar(String itemName, String resourceName) {
        return "Bar of Wrought Iron".equalsIgnoreCase(itemName)
                || "gfx/invobjs/bar-wroughtiron".equals(resourceName);
    }

    private static String resourceName(NGItem item) {
        try {
            return item.res == null ? null : item.res.get().name;
        } catch (Loading ignored) {
            return null;
        }
    }
}
