package nurgling.actions.bots.blueprint;

import haven.Coord2d;
import haven.Gob;
import haven.MenuGrid;
import nurgling.NGameUI;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.conf.ConstructionMaterialsRegistry;
import nurgling.i18n.L10n;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayerManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a selection of planner ghosts amounts to before anything moves: buildings per type, which
 * ones can't be built and why, and an estimate of the materials. Reads only loaded data.
 */
public final class BuildPreflight
{
    public static final class TypeRow
    {
        public final String res;
        public String name;
        public final List<PlanningGhost> ghosts = new ArrayList<>();
        public int alreadyBuilt;
        public int halfBuilt;
        /** Why this type can't be built, or null. */
        public String problem;
        public boolean enabled;
        public BuildRecipes.Recipe recipe;

        TypeRow(String res) { this.res = res; }
    }

    public final Map<String, TypeRow> types = new LinkedHashMap<>();

    public BuildPreflight(NGameUI gui, PlanningLayerManager plan, List<PlanningGhost> ghosts)
    {
        for (PlanningGhost g : ghosts)
            types.computeIfAbsent(g.resName, TypeRow::new).ghosts.add(g);
        for (TypeRow row : types.values())
        {
            List<MenuGrid.Pagina> buttons = BuildPaginae.candidates(gui, row.res);
            String known = BuildingNames.nameOf(row.res);
            row.name = !buttons.isEmpty() ? buttons.get(0).button().name()
                     : (known != null) ? known : nurgling.widgets.NBasePlannerWidget.shortName(row.res);
            row.recipe = BuildRecipes.forBuilding(row.res, buttons.isEmpty() ? null : row.name);   // falls back to the building's own name
            if (buttons.isEmpty())
                row.problem = L10n.get("bbuilder.problem.no_button");
            else if (row.recipe != null && !row.recipe.fromWindow && row.recipe.hasUnknownCounts())
                row.problem = L10n.get("bbuilder.problem.unsupported");
            row.enabled = row.problem == null;
            for (PlanningGhost g : row.ghosts)
            {
                Coord2d wp = plan.worldPosOf(g);
                Gob there = (wp == null) ? null : GhostConstruction.realGobAt(gui, wp);
                if (there == null) continue;
                if (row.res.equals(there.ngob.name)) row.alreadyBuilt++;
                else if (GhostConstruction.isSign(there) && row.res.equals(GhostConstruction.signTarget(there))) row.halfBuilt++;
            }
        }
    }

    /** Ghosts of the enabled types, in selection order. */
    public List<PlanningGhost> enabledGhosts()
    {
        List<PlanningGhost> out = new ArrayList<>();
        for (TypeRow row : types.values())
            if (row.enabled) out.addAll(row.ghosts);
        return out;
    }

    /** Material name to estimated total for the enabled types still to build; -1 = unknown. */
    public Map<String, Integer> materials()
    {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (TypeRow row : types.values())
        {
            if (!row.enabled || row.recipe == null) continue;
            int n = row.ghosts.size() - row.alreadyBuilt;
            if (n <= 0) continue;
            for (Map.Entry<String, Integer> e : row.recipe.counts.entrySet())
            {
                int add = (e.getValue() < 0) ? -1 : e.getValue() * n;
                out.merge(e.getKey(), add, (a, b) -> (a < 0 || b < 0) ? -1 : a + b);
            }
        }
        return out;
    }

    /** Where the bot will look for a material, or null when there is no source area. */
    public static String sourceOf(String material)
    {
        ConstructionMaterialsRegistry.MaterialType type = BuildRecipes.zoneTypeFor(material);
        if (type != null)
        {
            NArea zone = ConstructionMaterialsRegistry.findMaterialZone(type);
            if (zone != null) return L10n.get("bbuilder.source.zone", zone.name);
        }
        for (String name : BuildRecipes.aliasFor(material).getKeys())
        {
            NArea area = NContext.findInGlobal(name);
            if (area != null) return L10n.get("bbuilder.source.area", area.name);
        }
        return null;
    }
}
