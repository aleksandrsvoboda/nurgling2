package nurgling.actions.bots;

import haven.Area;
import haven.Coord;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MenuGrid;
import haven.Resource;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.areas.NGlobalCoord;
import nurgling.tasks.NTask;
import nurgling.tools.NAlias;
import nurgling.tools.StackSupporter;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.List;

/**
 * Paves every area tagged with the {@link Specialisation.SpecName#paving} specialisation.
 *
 * Each paving area carries the stone type to lay in its specialisation subtype
 * (e.g. "Soapstone", "Diabase"). For every such area the bot:
 *   1. navigates to the area (global / chunk navigation),
 *   2. scans which tiles are not yet paved (tile resource does not start with
 *      {@value #PAVING_PREFIX}),
 *   3. fetches only as much of the configured stone as the unpaved tiles need
 *      (1 stone per tile), capped by free inventory space, from that stone's
 *      Take area,
 *   4. arms the "Lay Stone Paving" action and drag-selects the area rectangle,
 *      letting the character pave the tiles (stone is consumed from inventory),
 *   5. restores stamina/energy via {@link RestoreResources} (which bookmarks the
 *      current spot and returns to it afterwards),
 * repeating until the area has no paveable unpaved tiles left, then moving on.
 */
public class PaveAreas implements Action
{
    private static final String PAVING_ACTION = "Lay Stone Paving";
    private static final String PAVING_PREFIX = "gfx/tiles/paving/";
    private static final Coord STONE_SIZE = new Coord(1, 1);

    @Override
    public Results run(NGameUI gui) throws InterruptedException
    {
        ArrayList<NArea> zones = NContext.findAllSpec(Specialisation.SpecName.paving.toString());
        if (zones == null || zones.isEmpty())
            return Results.ERROR("No areas with the Paving specialisation found");

        NContext context = new NContext(gui);
        for (NArea zone : zones)
        {
            gui.msg("Paving area '" + zone.name + "'...");
            Results r = paveZone(gui, context, zone);
            if (!r.IsSuccess())
                return r;
        }
        gui.msg("Paving complete for all areas");
        return Results.SUCCESS();
    }

    private Results paveZone(NGameUI gui, NContext context, NArea zone) throws InterruptedException
    {
        String stone = stoneOf(zone);
        if (stone == null)
        {
            gui.msg("Paving area '" + zone.name + "' has no stone type configured, skipping");
            return Results.SUCCESS();
        }
        NAlias stoneAlias = new NAlias(stone);

        NUtils.navigateToArea(zone);

        while (true)
        {
            // Top up stamina/energy; this bookmarks the current (in-area) spot and returns to it.
            new RestoreResources().run(gui);

            List<Area> rects = zoneRects(gui, zone);
            if (rects.isEmpty())
            {
                NUtils.navigateToArea(zone);
                rects = zoneRects(gui, zone);
                if (rects.isEmpty())
                    return Results.ERROR("Cannot load paving area '" + zone.name + "'");
            }

            int unpaved = countUnpaved(gui, rects);
            if (unpaved == 0)
            {
                gui.msg("Paving area '" + zone.name + "' complete");
                return Results.SUCCESS();
            }

            // Fetch just enough stone (1 per tile), capped by free inventory space.
            int have = gui.getInventory().getWItems(stoneAlias).size();
            if (have < unpaved)
            {
                ensureStones(gui, context, stone, unpaved);
                NUtils.navigateToArea(zone);
                have = gui.getInventory().getWItems(stoneAlias).size();
                if (have == 0)
                    return Results.ERROR("No '" + stone + "' available in its Take area");
            }

            int before = unpaved;
            for (Area rect : rects)
            {
                if (gui.getInventory().getWItems(stoneAlias).size() == 0)
                    break;
                paveRect(gui, rect, stoneAlias);
            }

            int after = countUnpaved(gui, rects);
            if (after >= before)
            {
                // A full pass with stone in hand made no progress: the remaining
                // tiles cannot be paved (water / cliff / built-on).
                gui.msg("Paving area '" + zone.name + "': " + after + " tile(s) cannot be paved, skipping");
                return Results.SUCCESS();
            }
        }
    }

    /** Lay stone over one rectangle, consuming stone from inventory. */
    private void paveRect(NGameUI gui, Area rect, NAlias stoneAlias) throws InterruptedException
    {
        if (!activatePaving(gui))
        {
            gui.msg("Could not find the '" + PAVING_ACTION + "' action in the menu");
            return;
        }

        // Wait for the area-select (shovel) cursor to arm.
        NUtils.addTask(new NTask()
        {
            { infinite = false; maxCounter = 600; }
            @Override
            public boolean check()
            {
                String c = NUtils.getUI().root.cursorRes;
                return c != null && !c.isEmpty() && !c.contains("arw");
            }
        });

        // The area's br corner is exclusive (one past the last tile), but the
        // "sel" protocol treats both corners as inclusive, so step it back by one.
        gui.map.wdgmsg("sel", rect.ul, rect.br.sub(1, 1), 1);
        waitPavingDone(gui, stoneAlias);
    }

    /**
     * Wait until paving settles: the player goes idle and the stone count stops
     * dropping. Bounded by per-step task timeouts so it can never hang.
     */
    private void waitPavingDone(NGameUI gui, NAlias stoneAlias) throws InterruptedException
    {
        // Wait for paving to begin (player leaves the idle pose), bounded.
        NUtils.addTask(new NTask()
        {
            { infinite = false; maxCounter = 600; }
            @Override
            public boolean check()
            {
                Gob p = NUtils.player();
                if (p == null) return true;
                String pose = p.pose();
                return pose != null && !pose.contains("idle");
            }
        });

        int prev = gui.getInventory().getWItems(stoneAlias).size();
        while (true)
        {
            // Wait for the player to settle (idle for a stretch) or run low on resources.
            NUtils.addTask(new NTask()
            {
                int idle = 0;
                { infinite = false; maxCounter = 6000; }
                @Override
                public boolean check()
                {
                    Gob p = NUtils.player();
                    if (p == null) return true;
                    String pose = p.pose();
                    if (pose == null || pose.contains("idle")) idle++;
                    else idle = 0;
                    if (idle >= 60) return true;
                    double stam = NUtils.getStamina();
                    double nrj = NUtils.getEnergy();
                    return (stam >= 0 && stam < 0.2) || (nrj >= 0 && nrj < 0.25);
                }
            });

            int now = gui.getInventory().getWItems(stoneAlias).size();
            if (now == prev || now == 0)
                break; // no more stone consumed since last settle, or out of stone
            prev = now;
        }
    }

    /** Activate the "Lay Stone Paving" menu action. */
    private boolean activatePaving(NGameUI gui)
    {
        for (MenuGrid.Pagina pag : gui.menu.paginae)
        {
            MenuGrid.PagButton btn = pag.button();
            if (btn == null)
                continue;
            String nm;
            try
            {
                nm = btn.name();
            }
            catch (Loading l)
            {
                continue;
            }
            if (nm != null && nm.equals(PAVING_ACTION))
            {
                btn.use(new MenuGrid.Interaction(1, 0));
                return true;
            }
        }
        return false;
    }

    /**
     * Fetch the configured stone from its Take area until the inventory holds
     * {@code targetTotal} stones, capped by free inventory space. Bookmarks the
     * current spot and returns to it afterwards.
     */
    private void ensureStones(NGameUI gui, NContext context, String stone, int targetTotal) throws InterruptedException
    {
        NAlias stoneAlias = new NAlias(stone);
        int have = gui.getInventory().getWItems(stoneAlias).size();
        if (have >= targetTotal)
            return;

        // Cap the target by what the inventory can actually hold. Stones stack,
        // so capacity = already-held + freeCells * stackSize.
        int cells = gui.getInventory().getNumberFreeCoord(STONE_SIZE);
        int capacity = have + cells * Math.max(1, StackSupporter.getFullStackSize(stone));
        int want = Math.min(targetTotal, capacity);
        if (want <= have)
            return;

        // TakeItems2 locates the stone's Take area and takes up to `want`,
        // handling stockpiles / containers / barter itself.
        NGlobalCoord work = NUtils.bookmarkHere();
        context.addInItem(stone, null);
        new TakeItems2(context, stone, want).run(gui);
        if (work != null)
            NUtils.navigateTo(work);
    }

    /** Absolute tile-coordinate rectangles that make up the area (one per grid). */
    private List<Area> zoneRects(NGameUI gui, NArea zone)
    {
        List<Area> rects = new ArrayList<>();
        if (zone.space == null)
            return rects;
        for (Long gid : zone.space.space.keySet())
        {
            MCache.Grid grid = gui.map.glob.map.findGrid(gid);
            if (grid == null)
                continue;
            Area a = zone.space.space.get(gid).area;
            rects.add(new Area(a.ul.add(grid.ul), a.br.add(grid.ul)));
        }
        return rects;
    }

    /** Count tiles in the area whose resource is not a paving tile. */
    private int countUnpaved(NGameUI gui, List<Area> rects)
    {
        MCache mc = gui.ui.sess.glob.map;
        int count = 0;
        for (Area rect : rects)
        {
            for (int x = rect.ul.x; x < rect.br.x; x++)
            {
                for (int y = rect.ul.y; y < rect.br.y; y++)
                {
                    Resource res;
                    try
                    {
                        res = mc.tilesetr(mc.gettile(new Coord(x, y)));
                    }
                    catch (Loading l)
                    {
                        res = null;
                    }
                    if (res != null && res.name != null && !res.name.startsWith(PAVING_PREFIX))
                        count++;
                }
            }
        }
        return count;
    }

    private String stoneOf(NArea zone)
    {
        for (NArea.Specialisation s : zone.spec)
        {
            if (s.name.equals(Specialisation.SpecName.paving.toString()))
                return s.subtype;
        }
        return null;
    }
}
