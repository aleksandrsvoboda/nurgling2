package nurgling.actions.bots.blueprint;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.actions.TakeItems2;
import nurgling.areas.NContext;
import nurgling.conf.ConstructionMaterialsRegistry;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetch trips for construction materials. Sources, in order: the construction-materials zone for
 * the material, then any area that lists one of its item names as an input. Free inventory cells
 * are shared between the materials in proportion to what they still need, so one trip brings a
 * bit of everything instead of filling up on the first material.
 */
final class MaterialFetcher
{
    private final NContext context;
    private boolean fetchedAny = false;
    private final List<String> noRoom = new ArrayList<>();

    MaterialFetcher(NContext context)
    {
        this.context = context;
    }

    /** Materials the last fetch skipped because not one more of them fits in the inventory. */
    List<String> noRoom()
    {
        return noRoom;
    }

    /** True once a trip brought anything, so leftovers may need returning. */
    boolean fetchedAny()
    {
        return fetchedAny;
    }

    static int countInInventory(NGameUI gui, String material) throws InterruptedException
    {
        NInventory inv = gui.getInventory();
        return (inv == null) ? 0 : inv.getItems(BuildRecipes.aliasFor(material)).size();
    }

    /**
     * Brings up to {@code wanted} more of each material into the inventory. Returns the
     * materials that gained nothing, i.e. have no reachable stock.
     */
    List<String> fetch(NGameUI gui, Map<String, Integer> wanted) throws InterruptedException
    {
        List<String> empty = new ArrayList<>();
        int free = gui.getInventory().getFreeSpace();
        double totalCells = 0;
        Map<String, Integer> plan = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet())
            if (e.getValue() > 0) totalCells += e.getValue() * area(BuildRecipes.shapeOf(e.getKey()));
        for (Map.Entry<String, Integer> e : wanted.entrySet())
        {
            int want = e.getValue();
            if (want <= 0) continue;
            int cells = area(BuildRecipes.shapeOf(e.getKey()));
            int share = (totalCells <= free) ? want
                    : Math.max(1, (int) Math.floor(free * (want * cells / totalCells) / cells));
            plan.put(e.getKey(), Math.min(want, share));
        }
        noRoom.clear();
        for (Map.Entry<String, Integer> e : plan.entrySet())
        {
            String material = e.getKey();
            // Not one more fits (a 4x1 board in scattered free cells): don't walk to its piles for nothing.
            if (gui.getInventory().getNumberFreeCoord(BuildRecipes.shapeOf(material)) <= 0)
            {
                noRoom.add(material);
                empty.add(material);
                continue;
            }
            int before = countInInventory(gui, material);
            take(gui, material, before + e.getValue());
            if (countInInventory(gui, material) <= before) empty.add(material);
            else fetchedAny = true;
        }
        return empty;
    }

    private void take(NGameUI gui, String material, int target) throws InterruptedException
    {
        NAlias alias = BuildRecipes.aliasFor(material);
        ConstructionMaterialsRegistry.MaterialType type = BuildRecipes.zoneTypeFor(material);
        if (type != null && ConstructionMaterialsRegistry.hasMaterialZone(type))
        {
            TakeItems2 t = new TakeItems2(context, alias.getKeys().get(0), target,
                    Specialisation.SpecName.buildMaterials, type.getSubtype());
            t.takeAny(alias, gui);
            BuildRecipes.observeShape(material, t.getObservedShape());
            if (countInInventory(gui, material) >= target) return;
        }
        for (String name : alias.getKeys())
        {
            int have = countInInventory(gui, material);
            if (have >= target) return;
            if (NContext.findInGlobal(name) == null) continue;
            context.addInItem(name, null);
            int ofName = gui.getInventory().getItems(new NAlias(name)).size();
            new TakeItems2(context, name, ofName + (target - have)).run(gui);
        }
    }

    private static int area(Coord shape)
    {
        return Math.max(1, shape.x * shape.y);
    }
}
