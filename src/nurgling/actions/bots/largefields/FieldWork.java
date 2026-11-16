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
import nurgling.actions.SetDir;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static haven.OCache.posres;

/**
 * One block of a large field at a time, worked the way the regular farmers work a whole field.
 *
 * Harvest: a port of nurgling.actions.HarvestCrop's regular path - the same back-and-forth walk
 * down 3-tile columns in 3x2 cells, the same stand-at-the-end-and-face-down-the-column trick,
 * produce dropped on the ground as it comes, seeds taken to the barrels when the inventory is
 * nearly full. After the block, what was dropped is picked up and stored before the next block.
 *
 * Planting: a port of nurgling.actions.SeedCrop's regular path - the same back-and-forth walk
 * down 2-tile columns, planting 2x2 cells by area selection, run as a separate lap after the
 * whole field is harvested.
 *
 * What differs from the originals: every scan is limited to one block that the caller has walked
 * to and waited for, the storage is reached by walking there and back, every wait has a time
 * limit, and a block is given up after a few passes instead of looping forever on a plant that
 * won't go. Fixes to HarvestCrop/SeedCrop should be checked against this class.
 */
final class FieldWork {
    private static final NAlias FALLOW = new NAlias("gfx/terobjs/plants/fallowplant");
    private static final NAlias MOUNDBED = new NAlias("gfx/terobjs/moundbed");
    private static final int PASSES = 3;

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

    // ===================================================================================
    // Moving between blocks and stores
    // ===================================================================================

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

    /** Unload trip in the middle of a block; comes back to where it started. */
    private boolean unloadTrip() throws InterruptedException {
        NGlobalCoord back = NUtils.bookmarkHere();
        if (!store())
            return false;
        return returnTo(back);
    }

    /** Takes everything of the harvest to storage, without walking back (end of a block or a lap). */
    boolean store() throws InterruptedException {
        String err = stores.unload(gui);
        if (err != null) {
            error = err;
            return false;
        }
        return true;
    }

    private boolean ensureRoom(int free) throws InterruptedException {
        if (gui.getInventory().getFreeSpace() >= free)
            return true;
        stores.dropHomeless(gui);
        stores.cutWhole(gui);
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

    /** The block in session tiles, half-open, as NArea.getArea() gives a whole field; null when not loaded. */
    private Area fieldArea() {
        Area s = map.toSessionInclusive(block, gui.map.glob.map);
        return (s == null) ? null : new Area(s.ul, s.br.add(1, 1));
    }

    private static Area inclusive(Area halfOpen) {
        return new Area(halfOpen.ul, halfOpen.br.sub(1, 1));
    }

    // ===================================================================================
    // Harvest lap - port of HarvestCrop
    // ===================================================================================

    private boolean stageAllowed(int stage) {
        return minStage == null || stage >= minStage;
    }

    private boolean anyLeft(Area fa) {
        Area in = inclusive(fa);
        for (CropRegistry.CropStage cs : CropRegistry.getStages(crop.plant))
            if (stageAllowed(cs.stage) && !unskipped(Finder.findGobs(in, crop.plant, cs.stage)).isEmpty())
                return true;
        return !unskipped(Finder.findGobs(in, FALLOW, 0)).isEmpty();
    }

    private ArrayList<Gob> unskipped(ArrayList<Gob> gobs) {
        gobs.removeIf(g -> skipped.contains(g.id));
        return gobs;
    }

    /** Harvests the block: HarvestCrop's walk, limited to the block. */
    boolean harvest() throws InterruptedException {
        for (int pass = 0; pass < PASSES; pass++) {
            Area fa = fieldArea();
            if (fa == null || !anyLeft(fa))
                break;
            if (!harvestPass(fa))
                return false;
        }
        return dropOff(false);
    }

    private boolean harvestPass(Area fa) throws InterruptedException {
        Coord2d me = gui.map.player().rc;
        Coord start = me.dist(fa.br.mul(MCache.tilesz)) < me.dist(fa.ul.mul(MCache.tilesz)) ? fa.br.sub(1, 1) : fa.ul;
        Coord pos = new Coord(start);
        boolean rev = pos.equals(fa.ul);
        boolean revdir = rev;

        if (!rev) {
            while (pos.x >= fa.ul.x) {
                AtomicBoolean setDir = new AtomicBoolean(true);
                if (revdir) {
                    while (pos.y <= fa.br.y - 1) {
                        Coord endPos = new Coord(Math.max(pos.x - 2, fa.ul.x), Math.min(pos.y + 1, fa.br.y - 1));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d plantGobEndpoint = harea.ul.mul(MCache.tilesz).add(MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y).sub(0, MCache.tileqsz.y);
                        Coord2d pathfinderEndpoint = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                        if (!harvestCell(harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir))
                            return false;
                        pos.y += 2;
                    }
                    pos.y = fa.br.y - 1;
                } else {
                    while (pos.y >= fa.ul.y) {
                        Coord endPos = new Coord(Math.max(pos.x - 2, fa.ul.x), Math.max(pos.y - 1, fa.ul.y));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d plantGobEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).sub(MCache.tilesz.x, 0).add(0, MCache.tileqsz.y);
                        Coord2d pathfinderEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).sub(MCache.tilesz.x, 0).add(0, MCache.tileqsz.y);
                        if (!harvestCell(harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir))
                            return false;
                        pos.y -= 2;
                    }
                    pos.y = fa.ul.y;
                }
                revdir = !revdir;
                pos.x -= 3;
            }
        } else {
            while (pos.x <= fa.br.x - 1) {
                AtomicBoolean setDir = new AtomicBoolean(true);
                if (revdir) {
                    while (pos.y <= fa.br.y - 1) {
                        Coord endPos = new Coord(Math.min(pos.x + 2, fa.br.x - 1), Math.min(pos.y + 1, fa.br.y - 1));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d plantGobEndpoint = harea.ul.mul(MCache.tilesz).add(MCache.tilehsz.x + MCache.tilesz.x, MCache.tilehqsz.y + MCache.tileqsz.y);
                        Coord2d pathfinderEndpoint = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                        if (!harvestCell(harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir))
                            return false;
                        pos.y += 2;
                    }
                    pos.y = fa.br.y - 1;
                } else {
                    while (pos.y >= fa.ul.y) {
                        Coord endPos = new Coord(Math.min(pos.x + 2, fa.br.x - 1), Math.max(pos.y - 1, fa.ul.y));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d plantGobEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).sub(MCache.tilesz.x, 0);
                        Coord2d pathfinderEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).sub(MCache.tilesz.x, 0).add(0, MCache.tileqsz.y);
                        if (!harvestCell(harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir))
                            return false;
                        pos.y -= 2;
                    }
                    pos.y = fa.ul.y;
                }
                revdir = !revdir;
                pos.x += 3;
            }
        }
        return true;
    }

    /** HarvestCrop.harvest: the plant at the cell's end from the column end, then the rest of the cell. */
    private boolean harvestCell(Area area, boolean rev, Coord2d pathfinderEndpoint, Coord2d plantGobEndpoint, AtomicBoolean setDir) throws InterruptedException {
        if (!dropOff(true) || !stamina())
            return false;

        Gob plant = null;
        Coord plantTile = plantGobEndpoint.div(MCache.tilesz).floor();
        for (CropRegistry.CropStage cs : CropRegistry.getStages(crop.plant)) {
            if (!stageAllowed(cs.stage))
                continue;
            plant = Finder.findGob(plantTile, crop.plant, cs.stage);
            if (plant != null)
                break;
        }
        if (plant == null)
            plant = Finder.findGob(plantTile, FALLOW, 0);
        if (plant != null && !skipped.contains(plant.id)) {
            if (PathFinder.isAvailable(pathfinderEndpoint)) {
                new PathFinder(pathfinderEndpoint).run(gui);
                if (setDir.get()) {
                    new SetDir(new Coord2d(0, rev ? 1 : -1)).run(gui);
                    setDir.set(false);
                }
            } else {
                new PathFinder(plant).run(gui);
            }
            if (!harvestOne(plant, false))
                return false;
        }

        for (CropRegistry.CropStage cs : CropRegistry.getStages(crop.plant)) {
            if (!stageAllowed(cs.stage))
                continue;
            ArrayList<Gob> left;
            while (!(left = unskipped(Finder.findGobs(area, crop.plant, cs.stage))).isEmpty())
                if (!harvestOne(left.get(0), true))
                    return false;
        }
        ArrayList<Gob> fallow;
        while (!(fallow = unskipped(Finder.findGobs(area, FALLOW, 0))).isEmpty())
            if (!harvestOne(fallow.get(0), true))
                return false;
        return dropOff(true);
    }

    private boolean harvestOne(Gob plant, boolean walk) throws InterruptedException {
        if (!dropOff(true))
            return false;
        if (walk)
            new PathFinder(plant).run(gui);
        new SelectFlowerAction("Harvest", plant).run(gui);
        FieldTasks.Gone gone = new FieldTasks.Gone(plant.id, 8000);
        NUtils.getUI().core.addTask(gone);
        if (gone.timedOut)
            skipped.add(plant.id);
        return dropOff(true);
    }

    /**
     * HarvestCrop.processHarvestedItems (regular path): drop what goes to piles straight away,
     * and take seeds (or champignons) to storage once the inventory is nearly full.
     *
     * @param onlyIfFull false at the end of the block: everything carried goes to storage
     */
    private boolean dropOff(boolean onlyIfFull) throws InterruptedException {
        if (gui.vhand != null) {
            if (crop.containerStore)
                NUtils.dropToInv();
            else
                NUtils.drop(gui.vhand);
        }
        List<String> dropNow = new ArrayList<>();
        for (WItem it : gui.getInventory().getItems()) {
            String name = ((NGItem) it.item).name();
            if (name != null && stores.dropsOnHarvest(name) && !dropNow.contains(name))
                dropNow.add(name);
        }
        for (String name : dropNow)
            for (WItem it : gui.getInventory().getWItems(dropAlias(name)))
                NUtils.drop(it);
        if (onlyIfFull && gui.getInventory().getFreeSpace() < 3)
            return ensureRoom(3);
        return true;
    }

    /**
     * HarvestCrop.dropAlias, plus one fix: the drop never takes the crop's seeds with it. The
     * original matches by substring, so dropping "Carrot" also dropped any "Carrot Seeds" held.
     */
    private NAlias dropAlias(String targetName) {
        ArrayList<String> exceptions = new ArrayList<>();
        if (!targetName.toLowerCase().contains("seed"))
            exceptions.add("seed");
        boolean unplantable = false;
        for (CropRegistry.CropStage stage : CropRegistry.getStages(crop.plant))
            if (!stage.plantable && stage.result.keys.contains(targetName))
                unplantable = true;
        if (unplantable) {
            for (CropRegistry.CropStage stage : CropRegistry.getStages(crop.plant))
                for (String key : stage.result.keys)
                    if (!targetName.toLowerCase().contains(key.toLowerCase()))
                        exceptions.add(key);
        }
        return new NAlias(Collections.singletonList(targetName), exceptions);
    }

    // ===================================================================================
    // After each block: pick up what was dropped and store it
    // ===================================================================================

    /**
     * Picks up everything the harvest left in the block (the dropped produce, straw and other
     * by-products with a put area, whole heads), then stores it all before the next block - the
     * old bots' CollectItemsToPile, per block. A tile's margin round the block catches what was
     * dropped while standing at a column end.
     */
    boolean collect() throws InterruptedException {
        Area fa = fieldArea();
        if (fa == null)
            return true;
        Pair<Coord2d, Coord2d> rc = new Pair<>(fa.ul.sub(1, 1).mul(MCache.tilesz), fa.br.add(1, 1).mul(MCache.tilesz));
        if (crop.whole != null && !collectWhole(rc))
            return false;
        for (NAlias kind : stores.groundKinds(gui)) {
            NAlias alias = new NAlias(new ArrayList<>(kind.keys), withContainers(kind.exceptions));
            while (true) {
                Gob item = nearest(rc, alias);
                if (item == null)
                    break;
                if (!ensureRoom(2) || !stamina())
                    return false;
                if (Finder.findGob(item.id) == null)
                    continue;
                pickUp(item);
            }
        }
        return store();
    }

    private Gob nearest(Pair<Coord2d, Coord2d> rc, NAlias alias) throws InterruptedException {
        Gob best = null;
        Coord2d me = gui.map.player().rc;
        for (Gob g : Finder.findGobs(rc, alias))
            if (!skipped.contains(g.id) && (best == null || g.rc.dist(me) < best.rc.dist(me)))
                best = g;
        return best;
    }

    /** Shift + right click, as NUtils.takeFromEarth (takes every one of the kind around), with a time limit on the wait. */
    private void pickUp(Gob item) throws InterruptedException {
        if (item.rc.dist(gui.map.player().rc) > MCache.tilesz2.x)
            new PathFinder(item).run(gui);
        gui.map.wdgmsg("click", Coord.z, item.rc.floor(posres), 3, 0, 1, (int) item.id, item.rc.floor(posres), 0, -1);
        FieldTasks.Gone gone = new FieldTasks.Gone(item.id, 5000);
        NUtils.getUI().core.addTask(gone);
        if (gone.timedOut)
            skipped.add(item.id);
        NUtils.getUI().core.addTask(new FieldTasks.Pause(300));
    }

    /**
     * Lettuce, pumpkin, watermelon: picks up the whole heads and cuts them, as
     * LettucePumpkinAndWatermelonCollector does - lettuce at half full, a pumpkin or watermelon
     * once no other fits.
     */
    private boolean collectWhole(Pair<Coord2d, Coord2d> rc) throws InterruptedException {
        LargeFieldCrop.Whole w = crop.whole;
        NAlias ground = new NAlias(new ArrayList<>(w.alias.keys), withContainers(w.alias.exceptions));
        int fits = 0;
        while (true) {
            Gob item = nearest(rc, ground);
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
            pickUp(item);
        }
        stores.cutWhole(gui);
        return true;
    }

    private static ArrayList<String> withContainers(List<String> exceptions) {
        ArrayList<String> res = new ArrayList<>(exceptions);
        res.addAll(Arrays.asList("stockpile", "barrel", "plants/"));
        return res;
    }

    // ===================================================================================
    // Planting lap - port of SeedCrop
    // ===================================================================================

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

    private int freeSoil(Area inclusive) {
        Area.Tile[][] tiles = Area.getTiles(inclusive, MOUNDBED);
        int free = 0;
        for (Area.Tile[] col : tiles)
            for (Area.Tile t : col)
                if (NParser.checkName(t.name, soil) && tileFree(t))
                    free++;
        return free;
    }

    /** Plants the block: SeedCrop's walk, limited to the block. */
    boolean seed() throws InterruptedException {
        for (int pass = 0; pass < PASSES; pass++) {
            Area fa = fieldArea();
            if (fa == null || freeSoil(inclusive(fa)) == 0)
                return true;
            if (!seedPass(fa))
                return false;
        }
        return true;
    }

    private boolean seedPass(Area fa) throws InterruptedException {
        Coord2d me = gui.map.player().rc;
        Coord start = me.dist(fa.br.mul(MCache.tilesz)) < me.dist(fa.ul.mul(MCache.tilesz)) ? fa.br.sub(1, 1) : fa.ul;
        Coord pos = new Coord(start);
        boolean rev = pos.equals(fa.ul);
        boolean revdir = rev;

        if (!rev) {
            while (pos.x >= fa.ul.x) {
                AtomicBoolean setDir = new AtomicBoolean(true);
                if (revdir) {
                    while (pos.y <= fa.br.y - 1) {
                        Coord endPos = new Coord(Math.max(pos.x - 1, fa.ul.x), Math.min(pos.y + 1, fa.br.y - 1));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d endp = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                        if (!seedCell(harea, revdir, endp, setDir))
                            return false;
                        pos.y += 2;
                    }
                    pos.y = fa.br.y - 1;
                } else {
                    while (pos.y >= fa.ul.y) {
                        Coord endPos = new Coord(Math.max(pos.x - 1, fa.ul.x), Math.max(pos.y - 1, fa.ul.y));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d endp = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).add(0, MCache.tileqsz.y);
                        if (!seedCell(harea, revdir, endp, setDir))
                            return false;
                        pos.y -= 2;
                    }
                    pos.y = fa.ul.y;
                }
                revdir = !revdir;
                pos.x -= 2;
            }
        } else {
            while (pos.x <= fa.br.x - 1) {
                AtomicBoolean setDir = new AtomicBoolean(true);
                if (revdir) {
                    while (pos.y <= fa.br.y - 1) {
                        Coord endPos = new Coord(Math.min(pos.x + 1, fa.br.x - 1), Math.min(pos.y + 1, fa.br.y - 1));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d endp = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                        if (!seedCell(harea, revdir, endp, setDir))
                            return false;
                        pos.y += 2;
                    }
                    pos.y = fa.br.y - 1;
                } else {
                    while (pos.y >= fa.ul.y) {
                        Coord endPos = new Coord(Math.min(pos.x + 1, fa.br.x - 1), Math.max(pos.y - 1, fa.ul.y));
                        Area harea = new Area(pos, endPos, true);
                        Coord2d endp = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).add(0, MCache.tileqsz.y);
                        if (!seedCell(harea, revdir, endp, setDir))
                            return false;
                        pos.y -= 2;
                    }
                    pos.y = fa.ul.y;
                }
                revdir = !revdir;
                pos.x += 2;
            }
        }
        return true;
    }

    /** SeedCrop.seedCrop: walk to the cell's end, face down the column, plant the cell by area selection. */
    private boolean seedCell(Area area, boolean rev, Coord2d target, AtomicBoolean setDir) throws InterruptedException {
        Area.Tile[][] tiles = Area.getTiles(area, MOUNDBED);
        int count = 0, total = 0;
        for (int i = 0; i <= area.br.x - area.ul.x; i++)
            for (int j = 0; j <= area.br.y - area.ul.y; j++)
                if (NParser.checkName(tiles[i][j].name, soil)) {
                    total++;
                    if (tileFree(tiles[i][j]))
                        count++;
                }
        if (count <= 0)
            return true;
        if (!stamina())
            return false;
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();

        Source src = crop.containerStore ? containerStockFor(count) : stockFor(count);
        if (src == null)
            return false;

        if (PathFinder.isAvailable(target)) {
            new PathFinder(target).run(gui);
            if (setDir.get()) {
                new SetDir(new Coord2d(0, rev ? 1 : -1)).run(gui);
                setDir.set(false);
            }
        } else {
            for (int i = 0; i <= area.br.x - area.ul.x; i++)
                for (int j = 0; j <= area.br.y - area.ul.y; j++)
                    if (NParser.checkName(tiles[i][j].name, soil))
                        new PathFinder(new Coord(area.ul.x + i, area.ul.y + j).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y)).run(gui);
        }
        if (src == MIXED)
            return seedTileByTile(area, tiles);

        WItem stack = usableStack(src);
        if (stack == null) {
            error = "no usable " + crop.subtype + " planting material";
            return false;
        }
        gui.getInventory().activateItem(stack);
        NUtils.getUI().core.addTask(new GetCurs("harvest"));
        if (rev)
            gui.map.wdgmsg("sel", area.ul, area.br, 1);
        else
            gui.map.wdgmsg("sel", area.br, area.ul, 1);
        FieldTasks.Planted planted = new FieldTasks.Planted(area, total, soil, 20000);
        NUtils.getUI().core.addTask(planted);
        if (planted.timedOut)
            System.out.println("[LargeField] cell " + area.ul + " was not fully planted in time");
        NUtils.getUI().core.addTask(new FieldTasks.Pause(200));
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
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
     * SeedCrop.ensureStockForBlock: a source holding enough for the cell, fetching a full
     * inventory from the nearest store when it runs short, and falling back to the next kind
     * (seeds -> vegetables) when even a fetch can't cover the cell. Null (error set) when every
     * source is spent.
     */
    private Source stockFor(int tiles) throws InterruptedException {
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
            Area fa = fieldArea();
            int blockNeeds = (fa == null) ? tiles : freeSoil(inclusive(fa));
            NGlobalCoord back = NUtils.bookmarkHere();
            stores.fetch(gui, null, Math.max(tiles, blockNeeds) - heldTotal());
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
}
