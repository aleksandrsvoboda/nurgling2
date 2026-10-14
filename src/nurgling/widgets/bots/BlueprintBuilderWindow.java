package nurgling.widgets.bots;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.bots.blueprint.BlueprintBuilder;
import nurgling.actions.bots.blueprint.BuildPreflight;
import nurgling.i18n.L10n;
import nurgling.planning.PlanningFolder;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayer;
import nurgling.planning.PlanningLayerManager;
import nurgling.planning.PlanningNode;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Blueprint Builder pre-flight: what the selection amounts to (buildings per type, what can't be
 * built and why, estimated materials and where they come from), with Start / Cancel. Started from
 * the bot menu it also lists the planner folders and layers to pick from.
 */
public class BlueprintBuilderWindow extends Window implements Checkable
{
    private static final int W = UI.scale(440);
    private static final Color WARN = new Color(233, 156, 84);
    private static final Color DIM = new Color(160, 170, 170);

    public boolean started = false;
    public boolean removeBuilt = true;
    public boolean stopOnProblem = false;

    private boolean done = false;
    private final boolean picker;
    private final Set<String> pickedNodes = new LinkedHashSet<>();
    /** Building resources the user unticked; survives rebuilding the type list. */
    private final Set<String> disabledTypes = new java.util.HashSet<>();
    private List<PlanningGhost> selection;
    private BuildPreflight preflight;
    private Widget body;

    /** {@code initial} = the ghosts to build, or null to pick plan parts in the window. */
    public BlueprintBuilderWindow(List<PlanningGhost> initial)
    {
        super(new Coord(W, UI.scale(100)), L10n.get("bbuilder.title"));
        this.picker = (initial == null);
        this.selection = picker ? new ArrayList<>() : initial;
        rebuild();
    }

    private static PlanningLayerManager plan()
    {
        return NUtils.getUI().core.planningLayer;
    }

    private void rebuild()
    {
        if (body != null) body.destroy();
        preflight = new BuildPreflight((NGameUI) NUtils.getGameUI(), plan(), selection);
        for (BuildPreflight.TypeRow row : preflight.types.values())
            if (disabledTypes.contains(row.res)) row.enabled = false;
        body = add(new Widget(new Coord(W, 1)), Coord.z);
        int y = 0;
        if (picker)
        {
            y = section(y, L10n.get("bbuilder.parts"));
            Scrollport sp = body.add(new Scrollport(new Coord(W, UI.scale(110))), new Coord(0, y));
            int ry = 0;
            for (PlanningNode n : plan().getRoots())
            {
                ry = nodeRow(sp.cont, n, 0, ry);
                if (n instanceof PlanningFolder)
                    for (PlanningLayer layer : ((PlanningFolder) n).layers)
                        ry = nodeRow(sp.cont, layer, 1, ry);
            }
            y += sp.sz.y + UI.scale(6);
        }

        y = section(y, L10n.get("bbuilder.selected", selection.size()));
        Scrollport types = body.add(new Scrollport(new Coord(W, UI.scale(110))), new Coord(0, y));
        int ty = 0;
        for (BuildPreflight.TypeRow row : preflight.types.values())
        {
            if (row.problem != null)
            {
                types.cont.add(new Label(row.name + " x" + row.ghosts.size() + ": " + row.problem), new Coord(UI.scale(4), ty))
                        .setcolor(WARN);
            } else
            {
                String text = L10n.get("bbuilder.type_row", row.name, row.ghosts.size() - row.alreadyBuilt, row.alreadyBuilt, row.halfBuilt);
                CheckBox cb = types.cont.add(new CheckBox(text), new Coord(UI.scale(4), ty));
                cb.a = row.enabled;
                cb.changed(v -> {
                    row.enabled = v;
                    if (v) disabledTypes.remove(row.res);
                    else disabledTypes.add(row.res);
                    materialsDirty = true;
                });
            }
            ty += UI.scale(20);
        }
        y += types.sz.y + UI.scale(6);

        y = section(y, L10n.get("bbuilder.materials"));
        materials = body.add(new Scrollport(new Coord(W, UI.scale(110))), new Coord(0, y));
        fillMaterials();
        y += materials.sz.y + UI.scale(8);

        CheckBox rb = body.add(new CheckBox(L10n.get("bbuilder.remove_built")), new Coord(0, y));
        rb.a = removeBuilt;
        rb.changed(v -> removeBuilt = v);
        CheckBox sb = body.add(new CheckBox(L10n.get("bbuilder.stop_on_problem")), new Coord(W / 2, y));
        sb.a = stopOnProblem;
        sb.changed(v -> stopOnProblem = v);
        y += UI.scale(24);

        Button start = body.add(new Button(UI.scale(120), L10n.get("botwnd.start"))
        {
            @Override
            public void click()
            {
                super.click();
                if (chosenGhosts().isEmpty())
                {
                    NUtils.getGameUI().msg(L10n.get("bbuilder.nothing"));
                    return;
                }
                started = true;
                done = true;
            }
        }, new Coord(0, y));
        body.add(new Button(UI.scale(120), L10n.get("bbuilder.cancel"))
        {
            @Override
            public void click()
            {
                super.click();
                done = true;
            }
        }, start.pos("ur").adds(UI.scale(10), 0));
        body.resize(new Coord(W, y + start.sz.y));
        pack();
    }

    private Scrollport materials;
    /* Checkbox callbacks run inside the click; the rows they sit in are rebuilt on the next tick. */
    private boolean dirty = false;
    private boolean materialsDirty = false;

    @Override
    public void tick(double dt)
    {
        super.tick(dt);
        if (dirty)
        {
            dirty = materialsDirty = false;
            rebuild();
        } else if (materialsDirty)
        {
            materialsDirty = false;
            rebuildMaterials();
        }
    }

    private void rebuildMaterials()
    {
        for (Widget c = materials.cont.child; c != null; )
        {
            Widget next = c.next;
            c.destroy();
            c = next;
        }
        fillMaterials();
    }

    private void fillMaterials()
    {
        int my = 0;
        for (Map.Entry<String, Integer> e : preflight.materials().entrySet())
        {
            String source = BuildPreflight.sourceOf(e.getKey());
            String count = (e.getValue() < 0) ? "?" : String.valueOf(e.getValue());
            String text = e.getKey() + "  " + count + "  " + ((source != null) ? source : L10n.get("bbuilder.source.none"));
            Label l = materials.cont.add(new Label(text), new Coord(UI.scale(4), my));
            if (source == null) l.setcolor(WARN);
            my += UI.scale(18);
        }
        if (my == 0)
            materials.cont.add(new Label(L10n.get("bbuilder.materials_unknown")), new Coord(UI.scale(4), 0)).setcolor(DIM);
        materials.cont.update();
    }

    private int section(int y, String title)
    {
        body.add(new Label(title), new Coord(0, y)).setcolor(DIM);
        return y + UI.scale(18);
    }

    private int nodeRow(Widget cont, PlanningNode n, int depth, int y)
    {
        int count = plan().ghostsOf(n.id).size();
        CheckBox cb = cont.add(new CheckBox(n.name + " (" + count + ")"), new Coord(UI.scale(4 + 16 * depth), y));
        cb.a = pickedNodes.contains(n.id);
        cb.changed(v -> {
            if (v) pickedNodes.add(n.id);
            else pickedNodes.remove(n.id);
            List<PlanningGhost> all = new ArrayList<>();
            for (String id : pickedNodes) all.addAll(plan().ghostsOf(id));
            selection = BlueprintBuilder.dedupe(all);
            dirty = true;
        });
        return y + UI.scale(20);
    }

    /** Ghosts of the types left ticked. */
    public List<PlanningGhost> chosenGhosts()
    {
        return preflight.enabledGhosts();
    }

    @Override
    public boolean check()
    {
        return done;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args)
    {
        if (msg.equals("close"))
        {
            done = true;
            hide();
        } else
        {
            super.wdgmsg(sender, msg, args);
        }
    }
}
