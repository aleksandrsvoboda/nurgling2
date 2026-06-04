package nurgling.actions.bots;

import haven.Area;
import haven.Coord;
import haven.GItem;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MenuGrid;
import haven.Resource;
import haven.WItem;
import nurgling.NGItem;
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
    // Tiles per side of a single paving selection. Small enough that one batch
    // won't drain a full stamina bar, so the bot can drink/eat between chunks.
    private static final int CHUNK_SIDE = 4;

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
            List<Area> rects = zoneRects(gui, zone);
            if (rects.isEmpty())
            {
                NUtils.navigateToArea(zone);
                rects = zoneRects(gui, zone);
                if (rects.isEmpty())
                    return Results.ERROR("Cannot load paving area '" + zone.name + "'");
            }

            int before = countUnpaved(gui, rects);
            if (before == 0)
            {
                gui.msg("Paving area '" + zone.name + "' complete");
                return Results.SUCCESS();
            }

            // Pave in small batches so we have a recovery point between them:
            // before each batch we can top up stamina/energy (drink/eat) and
            // refill stone, instead of running a single huge selection out of
            // resources. Each batch is an all-unpaved rectangle, so we never
            // re-pave an already-paved tile within an unevenly-paved region.
            for (Area rect : rects)
            {
                for (Area pr : unpavedRectsOf(gui, rect))
                {
                    int need = (pr.br.x - pr.ul.x) * (pr.br.y - pr.ul.y);

                    // Keep stamina/energy up. If we genuinely cannot restore (no
                    // water/food reachable), stop with that error rather than
                    // grinding the player to exhaustion.
                    Results rr = new RestoreResources().run(gui);
                    if (!rr.IsSuccess())
                        return rr;

                    // Ensure enough stone for this batch; refill toward a full
                    // inventory when low so we are not constantly travelling.
                    if (stoneCount(gui, stoneAlias) < need)
                    {
                        ensureStones(gui, context, stone, before);
                        if (stoneCount(gui, stoneAlias) == 0)
                            return Results.ERROR("No '" + stone + "' available in its Take area");
                    }

                    paveRect(gui, pr, stoneAlias);
                }
            }

            int after = countUnpaved(gui, rects);
            if (after >= before)
            {
                // A full pass with stone and resources available made no progress:
                // the remaining tiles cannot be paved (water / cliff / built-on).
                gui.msg("Paving area '" + zone.name + "': " + after + " tile(s) cannot be paved, skipping");
                return Results.SUCCESS();
            }
        }
    }

    /**
     * Decompose the unpaved tiles of a grid-rectangle into a set of all-unpaved
     * sub-rectangles, each at most CHUNK_SIDE per side (greedy: grow width along
     * the row, then grow height while the whole span stays unpaved). Every
     * returned rectangle contains only unpaved tiles, so selecting it never
     * re-paves an already-paved tile; the CHUNK_SIDE cap keeps stamina batching.
     */
    private List<Area> unpavedRectsOf(NGameUI gui, Area rect)
    {
        MCache mc = gui.ui.sess.glob.map;
        int w = rect.br.x - rect.ul.x;
        int h = rect.br.y - rect.ul.y;
        List<Area> out = new ArrayList<>();
        if (w <= 0 || h <= 0)
            return out;

        // free[i][j] = unpaved tile not yet claimed by a rectangle.
        boolean[][] free = new boolean[w][h];
        for (int i = 0; i < w; i++)
            for (int j = 0; j < h; j++)
                free[i][j] = isUnpaved(mc, rect.ul.x + i, rect.ul.y + j);

        for (int j = 0; j < h; j++)
        {
            for (int i = 0; i < w; i++)
            {
                if (!free[i][j])
                    continue;

                // Grow width along this row, capped at CHUNK_SIDE.
                int rw = 0;
                while (rw < CHUNK_SIDE && i + rw < w && free[i + rw][j])
                    rw++;

                // Grow height while the whole [i, i+rw) span stays unpaved, capped.
                int rh = 0;
                boolean spanFree = true;
                while (rh < CHUNK_SIDE && j + rh < h && spanFree)
                {
                    for (int k = i; k < i + rw; k++)
                    {
                        if (!free[k][j + rh])
                        {
                            spanFree = false;
                            break;
                        }
                    }
                    if (spanFree)
                        rh++;
                }

                // Claim the tiles and emit the rectangle (br exclusive).
                for (int dx = 0; dx < rw; dx++)
                    for (int dy = 0; dy < rh; dy++)
                        free[i + dx][j + dy] = false;

                out.add(new Area(new Coord(rect.ul.x + i, rect.ul.y + j),
                                 new Coord(rect.ul.x + i + rw, rect.ul.y + j + rh)));
            }
        }
        return out;
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

        int prev = stoneCount(gui, stoneAlias);
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

            int now = stoneCount(gui, stoneAlias);
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
        int have = stoneCount(gui, stoneAlias);
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
            for (int x = rect.ul.x; x < rect.br.x; x++)
                for (int y = rect.ul.y; y < rect.br.y; y++)
                    if (isUnpaved(mc, x, y))
                        count++;
        return count;
    }

    /** True if the tile at (x,y) exists, is loaded, and is not yet paved. */
    private boolean isUnpaved(MCache mc, int x, int y)
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
        return res != null && res.name != null && !res.name.startsWith(PAVING_PREFIX);
    }

    /**
     * Actual number of stones in inventory (not slots): sums each stack's
     * Amount, counting a loose single stone (no Amount info) as 1. This avoids
     * both the slot-undercount of getWItems().size() and the hang of
     * getTotalAmountItems on un-stacked items.
     */
    private int stoneCount(NGameUI gui, NAlias stoneAlias) throws InterruptedException
    {
        int total = 0;
        for (WItem w : gui.getInventory().getItems(stoneAlias))
        {
            int n = 1;
            try
            {
                GItem.Amount am = ((NGItem) w.item).getInfo(GItem.Amount.class);
                if (am != null)
                    n = am.itemnum();
            }
            catch (Loading l)
            {
                n = 1;
            }
            total += n;
        }
        return total;
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
