package nurgling.actions.bots.blueprint;

import haven.Coord;
import haven.Coord2d;
import haven.UI;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.FreeInventory2;
import nurgling.actions.Results;
import nurgling.areas.NContext;
import nurgling.i18n.L10n;
import nurgling.navigation.ChunkNavManager;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayerManager;
import nurgling.tasks.WaitCheckable;
import nurgling.widgets.bots.BlueprintBuilderReport;
import nurgling.widgets.bots.BlueprintBuilderWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Base planner ghosts: a whole folder or layer, a dragged region, or any parts picked in
 * its window. Resolves each ghost to a build button, walks anywhere in the village, fetches the
 * materials (several buildings' worth per trip), places and finishes the sign, and removes the
 * ghost from the plan once the building stands. Ghosts it can't build stay in the plan and are
 * listed with the reason in the report, so running it again resumes where it stopped.
 */
public class BlueprintBuilder implements Action
{
    /** Scenario setting: id of the planner folder or layer to build. */
    public static final String SETTING_NODE = "nodeId";

    private final List<String> presetGhostIds;
    private final String scenarioNodeId;

    /** Bot menu: pick the plan parts in the window. */
    public BlueprintBuilder()
    {
        this(Collections.emptyMap());
    }

    /** Scenario step (with {@link #SETTING_NODE}), or the bot menu (empty settings). */
    public BlueprintBuilder(Map<String, Object> settings)
    {
        Object node = settings.get(SETTING_NODE);
        this.scenarioNodeId = (node instanceof String && !((String) node).isEmpty()) ? (String) node : null;
        this.presetGhostIds = null;
    }

    /** Base planner: build exactly these ghosts, after the pre-flight window. */
    public BlueprintBuilder(List<PlanningGhost> ghosts)
    {
        this.scenarioNodeId = null;
        this.presetGhostIds = new ArrayList<>();
        for (PlanningGhost g : ghosts) presetGhostIds.add(g.id);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        PlanningLayerManager plan = gui.ui.core.planningLayer;
        // Load the wiki catalog off the UI thread before the window asks for estimates.
        BuildRecipes.wikiEstimate("");

        List<PlanningGhost> ghosts;
        boolean removeBuilt = true;
        boolean stopOnProblem = false;
        if (scenarioNodeId != null)
        {
            ghosts = new BuildPreflight(gui, plan, plan.ghostsOf(scenarioNodeId)).enabledGhosts();
        } else
        {
            List<PlanningGhost> initial = null;
            if (presetGhostIds != null)
            {
                initial = new ArrayList<>();
                for (String id : presetGhostIds)
                {
                    PlanningGhost g = plan.getGhostById(id);
                    if (g != null) initial.add(g);
                }
            }
            BlueprintBuilderWindow wnd = new BlueprintBuilderWindow(initial);
            try
            {
                NUtils.getUI().core.addTask(new WaitCheckable(gui.add(wnd, UI.scale(new Coord(200, 120)))));
            } finally
            {
                wnd.destroy();
            }
            if (!wnd.started)
                return Results.SUCCESS();
            ghosts = wnd.chosenGhosts();
            removeBuilt = wnd.removeBuilt;
            stopOnProblem = wnd.stopOnProblem;
        }
        if (ghosts.isEmpty())
        {
            gui.msg(L10n.get("bbuilder.nothing"));
            return Results.SUCCESS();
        }

        List<GhostJob> jobs = new ArrayList<>();
        for (PlanningGhost g : ghosts)
            jobs.add(new GhostJob(g, plan.layerNameOf(g.id)));

        NContext context = new NContext(gui);
        MaterialFetcher fetcher = new MaterialFetcher(context);
        GhostConstruction construction = new GhostConstruction(gui, plan, fetcher);
        String fatal = null;
        int done = 0;
        try
        {
            for (List<GhostJob> group : order(gui, jobs))
            {
                prefetch(gui, fetcher, stillToBuild(gui, plan, group));
                for (GhostJob job : group)
                {
                    if (!plan.hasGhostById(job.ghost.id))
                    {
                        // Removed from the plan (by a villager or the user) since the run started.
                        job.skip(L10n.get("bbuilder.reason.removed"));
                        continue;
                    }
                    gui.msg(L10n.get("bbuilder.progress", ++done, jobs.size(), job.typeName()));
                    construction.build(job);
                    if (job.status == GhostJob.Status.BUILT || job.status == GhostJob.Status.ALREADY_BUILT)
                    {
                        if (removeBuilt && plan.removeGhost(job.ghost) && gui.basePlanner != null)
                            gui.basePlanner.refresh();
                    } else if (stopOnProblem)
                    {
                        fatal = L10n.get("bbuilder.stopped_at", job.typeName(), job.reason);
                        break;
                    }
                }
                if (fatal != null) break;
            }
        } catch (GhostConstruction.FatalException e)
        {
            fatal = e.getMessage();
        } finally
        {
            if (gui.map.placing != null)
                gui.map.uimsg("unplace");
        }

        if (fetcher.fetchedAny())
            new FreeInventory2(context).run(gui);
        gui.add(new BlueprintBuilderReport(jobs, fatal), UI.scale(new Coord(200, 120)));
        return (fatal == null) ? Results.SUCCESS() : Results.ERROR(fatal);
    }

    /**
     * Groups to build in order: one per (grid, building type), nearest grid first by chunk
     * hops, and inside a group nearest-neighbour by position.
     */
    private static List<List<GhostJob>> order(NGameUI gui, List<GhostJob> jobs)
    {
        ChunkNavManager nav = (gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        Map<Long, Integer> hops = (nav != null) ? nav.chunkHopsFromPlayer() : null;

        Map<Long, List<GhostJob>> byGrid = new LinkedHashMap<>();
        for (GhostJob j : jobs)
            byGrid.computeIfAbsent(j.ghost.gridId, k -> new ArrayList<>()).add(j);
        List<Long> grids = new ArrayList<>(byGrid.keySet());
        grids.sort(Comparator.comparingInt(id -> (hops == null) ? 0 : hops.getOrDefault(id, Integer.MAX_VALUE)));

        List<List<GhostJob>> out = new ArrayList<>();
        for (long grid : grids)
        {
            Map<String, List<GhostJob>> byType = new LinkedHashMap<>();
            for (GhostJob j : byGrid.get(grid))
                byType.computeIfAbsent(j.ghost.resName, k -> new ArrayList<>()).add(j);
            for (List<GhostJob> group : byType.values())
                out.add(nearestFirst(group));
        }
        return out;
    }

    private static List<GhostJob> nearestFirst(List<GhostJob> group)
    {
        List<GhostJob> left = new ArrayList<>(group);
        List<GhostJob> out = new ArrayList<>();
        Coord2d at = left.get(0).ghost.offset();
        while (!left.isEmpty())
        {
            GhostJob best = null;
            for (GhostJob j : left)
                if (best == null || j.ghost.offset().dist(at) < best.ghost.offset().dist(at)) best = j;
            left.remove(best);
            out.add(best);
            at = best.ghost.offset();
        }
        return out;
    }

    /** Jobs still in the plan and not visibly standing already (unloaded ghosts count as to-build). */
    private static List<GhostJob> stillToBuild(NGameUI gui, PlanningLayerManager plan, List<GhostJob> group)
    {
        List<GhostJob> out = new ArrayList<>();
        for (GhostJob j : group)
        {
            if (!plan.hasGhostById(j.ghost.id)) continue;
            Coord2d wp = plan.worldPosOf(j.ghost);
            haven.Gob there = (wp == null) ? null : GhostConstruction.realGobAt(gui, wp);
            if (there != null && j.ghost.resName.equals(there.ngob.name)) continue;
            out.add(j);
        }
        return out;
    }

    /** Stock up for the whole group in one trip when its recipe is known. */
    private static void prefetch(NGameUI gui, MaterialFetcher fetcher, List<GhostJob> group) throws InterruptedException
    {
        if (group.isEmpty()) return;
        String res = group.get(0).ghost.resName;
        List<haven.MenuGrid.Pagina> buttons = BuildPaginae.candidates(gui, res);
        BuildRecipes.Recipe recipe = BuildRecipes.forBuilding(res, buttons.isEmpty() ? null : buttons.get(0).button().name());
        if (recipe == null || recipe.hasUnknownCounts()) return;
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : recipe.counts.entrySet())
        {
            int want = e.getValue() * group.size() - MaterialFetcher.countInInventory(gui, e.getKey());
            if (want > 0) wanted.put(e.getKey(), want);
        }
        if (!wanted.isEmpty()) fetcher.fetch(gui, wanted);
    }

    /** Ghost ids in a stable order, without duplicates (folder and layer both ticked). */
    public static List<PlanningGhost> dedupe(List<PlanningGhost> ghosts)
    {
        Map<String, PlanningGhost> byId = new LinkedHashMap<>();
        for (PlanningGhost g : ghosts) byId.putIfAbsent(g.id, g);
        return new ArrayList<>(byId.values());
    }
}
