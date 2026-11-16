package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord;
import haven.Coord2d;
import haven.GItem;
import haven.Gob;
import haven.MCache;
import haven.Pair;
import haven.WItem;
import nurgling.NConfig;
import nurgling.NGItem;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Drink;
import nurgling.actions.FillWaterskinsGlobal;
import nurgling.actions.PathFinder;
import nurgling.actions.SelectFlowerAction;
import nurgling.areas.NArea;
import nurgling.areas.NGlobalCoord;
import nurgling.conf.CropRegistry;
import nurgling.tasks.GetCurs;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static haven.OCache.posres;

/**
 * Farming one block of a large field at a time: harvest, pick up the by-products, replant.
 *
 * Adapted from nurgling.actions.HarvestCrop and nurgling.actions.SeedCrop (regular path): the
 * same harvest stages, minimum harvest stage, planting sources and free-tile rule, but every
 * scan is limited to one block that the caller has walked to and waited for, so "nothing left"
 * is only ever decided while the whole block is in view. Fixes to those two classes should be
 * checked against this one.
 */
final class FieldWork {
    private static final NAlias FALLOW = new NAlias("gfx/terobjs/plants/fallowplant");
    private static final NAlias MOUNDBED = new NAlias("gfx/terobjs/moundbed");
    private static final int MIN_FREE = 3;

    /** A kind of planting material, cheapest first: barrel seeds, then pile vegetables. */
    static final class Source {
        final NAlias item;
        final boolean stacked;
        final int perTile;

        Source(NAlias item, boolean stacked) {
            this.item = item;
            this.stacked = stacked;
            this.perTile = stacked ? 5 : 1;
        }
    }

    private final NGameUI gui;
    private final FieldMap map;
    private final LargeFieldCrop crop;
    private final FieldStores stores;
    private final Integer minStage;
    private final boolean ignoreStraw;
    private final String soil;
    private final List<Source> sources = new ArrayList<>();
    private int sourceIdx = 0;

    /** The block being worked, in field tiles (half-open). */
    private Area block;
    private final Set<Long> skipped = new HashSet<>();

    /** Why the run must stop; set whenever a method returns false. */
    String error = null;

    FieldWork(NGameUI gui, FieldMap map, LargeFieldCrop crop, FieldStores stores, boolean ignoreStraw) {
        this.gui = gui;
        this.map = map;
        this.crop = crop;
        this.stores = stores;
        this.ignoreStraw = ignoreStraw;
        this.soil = CropRegistry.getSoilTile(crop.plant);
        NArea.Specialisation fs = map.area.getSpecialisation(
                new NArea.Specialisation(nurgling.widgets.Specialisation.SpecName.crop.toString(), crop.subtype));
        this.minStage = (fs == null) ? null : fs.minHarvestStage;

        // Champignons: each mushroom kind is its own source, cheapest first (registry order).
        for (NAlias kind : CropRegistry.getPlantingMaterials(crop.plant, CropRegistry.StorageBehavior.CONTAINER))
            sources.add(new Source(kind, false));
        CropRegistry.CropStage seed = CropRegistry.getPlantingMaterial(crop.plant, CropRegistry.StorageBehavior.BARREL);
        CropRegistry.CropStage veg = CropRegistry.getPlantingMaterial(crop.plant, CropRegistry.StorageBehavior.STOCKPILE);
        if (seed != null)
            sources.add(new Source(seed.result, true));
        if (veg != null)
            sources.add(new Source(new NAlias(new ArrayList<>(veg.result.keys), new ArrayList<>(Collections.singletonList("seed"))), false));
    }

    boolean hasPlantingSource() {
        return !sources.isEmpty();
    }

    // ----- moving between blocks and stores -----

    /**
     * Walks into the block and waits until it is loaded. Tries the centre first (closest to every
     * tile of the block), then spots just inside each corner in case the centre is blocked.
     */
    boolean enter(Area block) throws InterruptedException {
        this.block = block;
        this.skipped.clear();
        Coord[] spots = {FieldGeometry.centre(block), block.ul.add(1, 1), new Coord(block.br.x - 2, block.ul.y + 1),
                new Coord(block.ul.x + 1, block.br.y - 2), block.br.sub(2, 2)};
        for (Coord spot : spots) {
            if (!spot.isect(block.ul, block.br.sub(block.ul)))
                continue;
            NGlobalCoord target = map.bookmark(spot);
            if (target != null && NUtils.navigateTo(target)) {
                waitLoaded();
                return true;
            }
        }
        System.out.println("[LargeField] can't reach block " + block.ul + "-" + block.br + " of " + map.area.name);
        return false;
    }

    private void waitLoaded() throws InterruptedException {
        FieldTasks.BlockLoaded t = new FieldTasks.BlockLoaded(map, block);
        NUtils.getUI().core.addTask(t);
        if (t.timedOut)
            System.out.println("[LargeField] block " + block.ul + " did not settle in time; working with what is loaded");
    }

    private boolean returnTo(NGlobalCoord back) throws InterruptedException {
        if (back == null || !NUtils.navigateTo(back)) {
            error = "can't walk back to the field " + map.area.name;
            return false;
        }
        waitLoaded();
        return true;
    }

    /** Unload trip; comes back to where it started. */
    private boolean unloadTrip() throws InterruptedException {
        NGlobalCoord back = NUtils.bookmarkHere();
        String err = stores.unload(gui);
        if (err != null) {
            error = err;
            return false;
        }
        return returnTo(back);
    }

    /** Unloads everything; the last trip of a field, no walk back. */
    boolean finish() throws InterruptedException {
        String err = stores.unload(gui);
        if (err != null) {
            error = err;
            return false;
        }
        return true;
    }

    /**
     * Makes room in the inventory: drop by-products that have nowhere to go, then replant the
     * tiles already harvested in this block with what is carried (seeds go into a barrel all or
     * nothing, so unloading them would only mean fetching them straight back), and only then walk
     * to the stores.
     */
    private boolean ensureRoom(int free) throws InterruptedException {
        if (gui.getInventory().getFreeSpace() >= free)
            return true;
        stores.dropHomeless(gui);
        if (gui.getInventory().getFreeSpace() >= free)
            return true;
        stores.cutWhole(gui);
        if (!seed(false))
            return false;
        if (gui.getInventory().getFreeSpace() >= free)
            return true;
        if (!unloadTrip())
            return false;
        if (gui.getInventory().getFreeSpace() < free) {
            // Nothing of the harvest left to unload: the rest is the player's own things.
            error = "the inventory is full of items that aren't part of the harvest";
            return false;
        }
        return true;
    }

    /** Same rules as HarvestCrop: drink below 35%, refill waterskins if allowed, else stop. */
    private boolean stamina() throws InterruptedException {
        if (NUtils.getStamina() >= 0.35)
            return true;
        if (new Drink(0.9, false).run(gui).isSuccess)
            return true;
        if (!(Boolean) NConfig.get(NConfig.Key.harvestautorefill)) {
            error = "out of water";
            return false;
        }
        NGlobalCoord back = NUtils.bookmarkHere();
        if (FillWaterskinsGlobal.checkIfNeed() && !new FillWaterskinsGlobal().run(gui).IsSuccess()) {
            error = "can't refill the waterskins";
            return false;
        }
        if (!new Drink(0.9, false).run(gui).isSuccess) {
            error = "out of water";
            return false;
        }
        return returnTo(back);
    }

    private Area session() {
        return map.toSessionInclusive(block, gui.map.glob.map);
    }

    private boolean fromRight(Area s) {
        return gui.map.player().rc.floor(MCache.tilesz).x > (s.ul.x + s.br.x) / 2;
    }

    // ----- harvest -----

    private boolean stageAllowed(int stage) {
        return minStage == null || stage >= minStage;
    }

    private Gob nextPlant(Area strip) {
        List<Gob> cands = new ArrayList<>();
        Set<Integer> stages = new HashSet<>();
        for (CropRegistry.CropStage cs : CropRegistry.getStages(crop.plant))
            if (stageAllowed(cs.stage) && stages.add(cs.stage))
                cands.addAll(Finder.findGobs(strip, crop.plant, cs.stage));
        cands.addAll(Finder.findGobs(strip, FALLOW, 0));
        Coord2d me = gui.map.player().rc;
        Gob best = null;
        for (Gob g : cands) {
            if (skipped.contains(g.id))
                continue;
            if (best == null || g.rc.dist(me) < best.rc.dist(me))
                best = g;
        }
        return best;
    }

    /** Harvests every ripe plant of the block. */
    boolean harvest() throws InterruptedException {
        Area s = session();
        if (s == null)
            return true;
        for (Area strip : FieldGeometry.strips(new Area(s.ul, s.br.add(1, 1)), 3, fromRight(s))) {
            Gob plant;
            while ((plant = nextPlant(strip)) != null) {
                if (!ensureRoom(MIN_FREE) || !stamina())
                    return false;
                // A trip may have taken a while; the plant can be gone or replaced since.
                if (Finder.findGob(plant.id) == null)
                    continue;
                new PathFinder(plant).run(gui);
                new SelectFlowerAction("Harvest", plant).run(gui);
                FieldTasks.Gone gone = new FieldTasks.Gone(plant.id, 8000);
                NUtils.getUI().core.addTask(gone);
                if (gone.timedOut)
                    skipped.add(plant.id);
                if (gui.vhand != null) {
                    if (gui.getInventory().getFreeSpace() > 0)
                        NUtils.dropToInv();
                    else
                        NUtils.drop(gui.vhand);
                }
            }
        }
        return true;
    }

    // ----- by-products left on the ground -----

    /** Picks up the by-products that have a put area from the block. */
    boolean collect() throws InterruptedException {
        List<LargeFieldCrop.ByProduct> wanted = stores.collectedByProducts(gui);
        if (wanted.isEmpty() && crop.whole == null)
            return true;
        Area s = session();
        if (s == null)
            return true;
        Pair<Coord2d, Coord2d> rc = new Pair<>(s.ul.mul(MCache.tilesz), s.br.add(1, 1).mul(MCache.tilesz));
        if (crop.whole != null && !collectWhole(rc))
            return false;
        for (LargeFieldCrop.ByProduct bp : wanted) {
            NAlias alias = new NAlias(new ArrayList<>(bp.alias.keys), new ArrayList<>(Arrays.asList("stockpile", "barrel", "plants/")));
            while (true) {
                Gob item = null;
                Coord2d me = gui.map.player().rc;
                for (Gob g : Finder.findGobs(rc, alias))
                    if (!skipped.contains(g.id) && (item == null || g.rc.dist(me) < item.rc.dist(me)))
                        item = g;
                if (item == null)
                    break;
                if (!ensureRoom(2) || !stamina())
                    return false;
                if (Finder.findGob(item.id) == null)
                    continue;
                if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x)
                    new PathFinder(item).run(gui);
                // Shift + right click, as NUtils.takeFromEarth, with a time limit on the wait.
                gui.map.wdgmsg("click", Coord.z, item.rc.floor(posres), 3, 0, 1, (int) item.id, item.rc.floor(posres), 0, -1);
                FieldTasks.Gone gone = new FieldTasks.Gone(item.id, 5000);
                NUtils.getUI().core.addTask(gone);
                if (gone.timedOut)
                    skipped.add(item.id);
                NUtils.getUI().core.addTask(new FieldTasks.Pause(300));
            }
        }
        return true;
    }

    // ----- seeding -----

    private boolean tileFree(Area.Tile tile) {
        if (tile.gobs.isEmpty())
            return true;
        if (!ignoreStraw)
            return tile.isFree;
        for (Gob gob : tile.gobs) {
            String name = gob.ngob != null ? gob.ngob.name : "";
            if (!NParser.checkName(name, "straw"))
                return false;
        }
        return true;
    }

    private int amount(Source src) throws InterruptedException {
        int total = 0;
        for (WItem it : gui.getInventory().getItems(src.item)) {
            if (!src.stacked) {
                total++;
                continue;
            }
            GItem.Amount a = ((NGItem) it.item).getInfo(GItem.Amount.class);
            total += (a != null) ? a.itemnum() : 1;
        }
        return total;
    }

    private WItem usableStack(Source src) throws InterruptedException {
        for (WItem it : gui.getInventory().getItems(src.item)) {
            if (!src.stacked)
                return it;
            GItem.Amount a = ((NGItem) it.item).getInfo(GItem.Amount.class);
            if (a == null || a.itemnum() >= src.perTile)
                return it;
        }
        return null;
    }

    /**
     * A source holding enough for the tiles, fetching from the stores once if allowed. Null when
     * nothing carried covers the tiles and fetching isn't allowed, or (error set) every source is spent.
     */
    private Source stockFor(int tiles, boolean mayFetch) throws InterruptedException {
        if (!mayFetch) {
            for (int i = sourceIdx; i < sources.size(); i++)
                if (amount(sources.get(i)) >= tiles * sources.get(i).perTile)
                    return sources.get(i);
            return null;
        }
        if (crop.containerStore)
            return containerStockFor(tiles);
        while (sourceIdx < sources.size()) {
            Source src = sources.get(sourceIdx);
            if (amount(src) >= tiles * src.perTile)
                return src;
            NGlobalCoord back = NUtils.bookmarkHere();
            stores.fetch(gui, src, 0);
            if (!returnTo(back))
                return null;
            if (amount(src) >= tiles * src.perTile)
                return src;
            // Even a full fetch can't cover this cell: fall back to the next kind (seeds -> vegetables).
            sourceIdx++;
        }
        if (error == null)
            error = "no " + crop.subtype + " planting material left";
        return null;
    }

    /** Marks "plant this cell tile by tile, any kind" (champignons when no one kind covers a cell). */
    private static final Source MIXED = new Source(new NAlias("mixed"), false);

    /**
     * Champignons, as SeedCrop's store path: plant a cell with the cheapest kind that covers it;
     * if none does, tile by tile with whatever is held. One fetch from the store takes enough for
     * the rest of the block.
     */
    private Source containerStockFor(int tiles) throws InterruptedException {
        for (Source src : sources)
            if (amount(src) >= tiles)
                return src;
        if (heldTotal() < tiles) {
            NGlobalCoord back = NUtils.bookmarkHere();
            stores.fetch(gui, null, Math.max(tiles, freeSoilInBlock()) - heldTotal());
            if (!returnTo(back))
                return null;
            for (Source src : sources)
                if (amount(src) >= tiles)
                    return src;
        }
        if (heldTotal() > 0)
            return MIXED;
        if (error == null)
            error = "no " + crop.subtype + " planting material left";
        return null;
    }

    private int heldTotal() throws InterruptedException {
        int n = 0;
        for (Source src : sources)
            n += amount(src);
        return n;
    }

    private int freeSoilInBlock() throws InterruptedException {
        Area s = session();
        if (s == null)
            return 0;
        Area.Tile[][] tiles = Area.getTiles(s, MOUNDBED);
        int free = 0;
        for (Area.Tile[] col : tiles)
            for (Area.Tile t : col)
                if (NParser.checkName(t.name, soil) && tileFree(t))
                    free++;
        return free;
    }

    /**
     * Plants every free soil tile of the block, 2x2 tiles at a time as SeedCrop does.
     *
     * @param mayFetch false: plant only from what is carried and skip the cells it can't cover
     */
    boolean seed(boolean mayFetch) throws InterruptedException {
        Area s = session();
        if (s == null)
            return true;
        boolean up = gui.map.player().rc.floor(MCache.tilesz).y > (s.ul.y + s.br.y) / 2;
        for (Area strip : FieldGeometry.strips(new Area(s.ul, s.br.add(1, 1)), 2, fromRight(s))) {
            for (Area cell : FieldGeometry.cells(strip, 2, up)) {
                if (!seedCell(cell, mayFetch))
                    return false;
            }
            up = !up;
        }
        return true;
    }

    private boolean seedCell(Area cell, boolean mayFetch) throws InterruptedException {
        Area.Tile[][] tiles = Area.getTiles(cell, MOUNDBED);
        int total = 0, free = 0;
        for (int i = 0; i <= cell.br.x - cell.ul.x; i++)
            for (int j = 0; j <= cell.br.y - cell.ul.y; j++)
                if (NParser.checkName(tiles[i][j].name, soil)) {
                    total++;
                    if (tileFree(tiles[i][j]))
                        free++;
                }
        if (free == 0)
            return true;
        if (!stamina())
            return false;
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        Source src = stockFor(free, mayFetch);
        if (src == null)
            return !mayFetch;

        if (src == MIXED)
            return seedTileByTile(cell, tiles);
        Coord2d at = Coord2d.of(cell.ul.add(cell.br).add(1, 1)).mul(MCache.tilesz).div(2);
        if (PathFinder.isAvailable(at))
            new PathFinder(at).run(gui);
        WItem stack = usableStack(src);
        if (stack == null) {
            error = "no usable " + crop.subtype + " planting material";
            return false;
        }
        gui.getInventory().activateItem(stack);
        NUtils.getUI().core.addTask(new GetCurs("harvest"));
        gui.map.wdgmsg("sel", cell.ul, cell.br, 1);
        FieldTasks.Planted planted = new FieldTasks.Planted(cell, total, soil, 20000);
        NUtils.getUI().core.addTask(planted);
        if (planted.timedOut)
            System.out.println("[LargeField] cell " + cell.ul + " was not fully planted in time");
        NUtils.getUI().core.addTask(new FieldTasks.Pause(200));
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        return true;
    }

    /** One tile at a time, any kind held, cheapest first. */
    private boolean seedTileByTile(Area cell, Area.Tile[][] tiles) throws InterruptedException {
        for (int i = 0; i <= cell.br.x - cell.ul.x; i++) {
            for (int j = 0; j <= cell.br.y - cell.ul.y; j++) {
                if (!NParser.checkName(tiles[i][j].name, soil) || !tileFree(tiles[i][j]))
                    continue;
                Source kind = null;
                for (Source src : sources)
                    if (amount(src) > 0) {
                        kind = src;
                        break;
                    }
                if (kind == null)
                    return true;
                Coord tile = cell.ul.add(i, j);
                new PathFinder(tile.mul(MCache.tilesz).add(MCache.tilehsz)).run(gui);
                gui.getInventory().activateItem(gui.getInventory().getItems(kind.item).get(0));
                NUtils.getUI().core.addTask(new GetCurs("harvest"));
                gui.map.wdgmsg("sel", tile, tile, 1);
                NUtils.getUI().core.addTask(new FieldTasks.Planted(new Area(tile, tile), 1, soil, 10000));
                NUtils.getUI().core.addTask(new FieldTasks.Pause(200));
                if (!gui.hand.isEmpty())
                    NUtils.dropToInv();
            }
        }
        return true;
    }

    // ----- whole heads and fruit (lettuce, pumpkin, watermelon) -----

    /**
     * Picks up the whole heads or fruit the harvest left in the block and cuts them, as
     * LettucePumpkinAndWatermelonCollector does: lettuce at half full, a pumpkin or watermelon
     * once no other fits. The seeds that come out are what the block is replanted with.
     */
    private boolean collectWhole(Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        LargeFieldCrop.Whole w = crop.whole;
        NAlias ground = new NAlias(new ArrayList<>(w.alias.keys), withContainers(w.alias.exceptions));
        int fits = 0;
        while (true) {
            Gob item = null;
            Coord2d me = gui.map.player().rc;
            for (Gob g : Finder.findGobs(rc, ground))
                if (!skipped.contains(g.id) && (item == null || g.rc.dist(me) < item.rc.dist(me)))
                    item = g;
            if (item == null)
                break;
            ArrayList<WItem> held = gui.getInventory().getItems(w.alias);
            if (!held.isEmpty()) {
                int free = gui.getInventory().getNumberFreeCoord(held.get(0));
                fits = Math.max(fits, free + held.size());
                if (w.cutWhenHalfFull ? free <= fits / 2 : free == 0)
                    stores.cutWhole(gui);
            }
            // A whole pumpkin takes several cells.
            if (!ensureRoom(4) || !stamina())
                return false;
            if (Finder.findGob(item.id) == null)
                continue;
            if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x)
                new PathFinder(item).run(gui);
            gui.map.wdgmsg("click", Coord.z, item.rc.floor(posres), 3, 0, 1, (int) item.id, item.rc.floor(posres), 0, -1);
            FieldTasks.Gone gone = new FieldTasks.Gone(item.id, 5000);
            NUtils.getUI().core.addTask(gone);
            if (gone.timedOut)
                skipped.add(item.id);
            NUtils.getUI().core.addTask(new FieldTasks.Pause(300));
        }
        stores.cutWhole(gui);
        return true;
    }

    private static ArrayList<String> withContainers(List<String> exceptions) {
        ArrayList<String> res = new ArrayList<>(exceptions);
        res.addAll(Arrays.asList("stockpile", "barrel", "plants/"));
        return res;
    }
}
