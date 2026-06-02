package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.areas.NArea;
import nurgling.conf.CropRegistry;
import nurgling.tasks.GetCurs;
import nurgling.tasks.WaitAnotherAmount;
import nurgling.tasks.WaitGobsInField;
import nurgling.tools.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SeedCrop implements Action {

    final NArea field;
    final NArea seed;

    // Where vegetables used for seeding are taken from and returned to: the "put"
    // area where the harvested crop is dropped off. For pure-vegetable crops this is
    // the seed area itself; for dual-product crops (carrot/turnip/leek) it is the
    // crop's output area. May be null when the crop has no vegetable put area.
    final NArea vegArea;

    final NAlias crop;
    // iseed is only used by the quality-grid path; the regular path derives its
    // planting material(s) from CropRegistry.
    final NAlias iseed;

    boolean isQualityGrid = false;

    // Regular planting sources, in priority order (barrel seeds first, then
    // stockpile vegetables), derived from CropRegistry in run().
    private final ArrayList<PlantingSource> sources = new ArrayList<>();
    private int sourceIdx = 0;
    private ArrayList<Gob> barrels = new ArrayList<>();
    private ArrayList<Gob> stockPiles = new ArrayList<>();

    private enum SourceType { BARREL, STOCKPILE }

    private static class PlantingSource {
        final SourceType type;
        final NAlias item;   // exact alias for this product
        final int perTile;   // units consumed per tile (5 for stacked seeds, 1 for vegetables)

        PlantingSource(SourceType type, NAlias item, int perTile) {
            this.type = type;
            this.item = item;
            this.perTile = perTile;
        }

        boolean isStacked() { return type == SourceType.BARREL; }
    }

    // Regular (registry-driven) planting where vegetables (if any) are stored in the
    // seed area. Used by pure-vegetable crops (the seed area is also the put area).
    public SeedCrop(NArea field, NArea seed, NAlias crop) {
        this(field, seed, crop, seed);
    }

    // Regular planting where the vegetable put area differs from the seed area
    // (dual-product crops): plant from barrel seeds first, then fall back to
    // vegetables taken from vegArea.
    public SeedCrop(NArea field, NArea seed, NAlias crop, NArea vegArea) {
        this.field = field;
        this.seed = seed;
        this.crop = crop;
        this.vegArea = vegArea;
        this.iseed = null;
    }

    // Quality-grid planting (plants individual high-quality items from a container).
    public SeedCrop(NArea field, NArea seed, NAlias crop, NAlias iseed, boolean isQualityGrid) {
        this.field = field;
        this.seed = seed;
        this.crop = crop;
        this.iseed = iseed;
        this.isQualityGrid = isQualityGrid;
        this.vegArea = seed;
    }


    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        boolean ignoreStraw = (Boolean) NConfig.get(NConfig.Key.ignoreStrawInFarmers);

        if (isQualityGrid) {
            seedForQuality(gui);
            return Results.SUCCESS();
        }

        barrels = Finder.findGobs(seed, new NAlias("barrel"));
        // Vegetables for seeding live in the crop's put area (vegArea), which is where
        // the harvest is dropped off.
        stockPiles = (vegArea != null) ? Finder.findGobs(vegArea, new NAlias("stockpile")) : new ArrayList<>();

        // Build planting sources from the crop registry, in priority order:
        // barrel seeds first, then stockpile vegetables. Each product is matched by
        // an exact alias so seeds and vegetables can never be conflated even when
        // both are present in the inventory at once.
        sources.clear();
        sourceIdx = 0;
        CropRegistry.CropStage barrelStage = CropRegistry.getProductByStorage(crop, CropRegistry.StorageBehavior.BARREL);
        CropRegistry.CropStage stockStage = CropRegistry.getProductByStorage(crop, CropRegistry.StorageBehavior.STOCKPILE);
        if (barrelStage != null && !barrels.isEmpty())
            sources.add(new PlantingSource(SourceType.BARREL, seedItemAlias(barrelStage.result), 5));
        if (stockStage != null && vegArea != null && !stockPiles.isEmpty())
            sources.add(new PlantingSource(SourceType.STOCKPILE, vegItemAlias(stockStage.result), 1));

        if (sources.isEmpty())
            return Results.ERROR("No planting source (barrel/stockpile) available for crop");

        Area fieldArea = field.getArea();
        if (fieldArea == null) {
            return Results.ERROR("Field area is not available - GUI not ready");
        }

        ArrayList<Coord2d> tiles = field.getTiles(new NAlias("field"));

        Coord start = gui.map.player().rc.dist(fieldArea.br.mul(MCache.tilesz)) < gui.map.player().rc.dist(fieldArea.ul.mul(MCache.tilesz)) ? fieldArea.br.sub(1, 1) : fieldArea.ul;
        Coord pos = new Coord(start);
        boolean rev = (pos.equals(field.getArea().ul));

        boolean revdir = rev;

        do {
            if (!rev) {
                while (pos.x >= field.getArea().ul.x) {
                    AtomicBoolean setDir = new AtomicBoolean(true);
                    if (revdir) {
                        while (pos.y <= field.getArea().br.y - 1) {
                            Coord endPos = new Coord(Math.max(pos.x - 1, field.getArea().ul.x), Math.min(pos.y + 1, field.getArea().br.y - 1));
                            Area harea = new Area(pos, endPos, true);
                            Coord2d endp = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                            seedCrop(gui, barrels, stockPiles, harea, revdir, endp, setDir, ignoreStraw);
                            pos.y += 2;

                        }
                        pos.y = field.getArea().br.y - 1;
                    } else {
                        while (pos.y >= field.getArea().ul.y) {
                            Coord endPos = new Coord(Math.max(pos.x - 1, field.getArea().ul.x), Math.max(pos.y - 1, field.getArea().ul.y));
                            Area harea = new Area(pos, endPos, true);
                            Coord2d endp = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).add(0, MCache.tileqsz.y);
                            seedCrop(gui, barrels, stockPiles, harea, revdir, endp, setDir, ignoreStraw);
                            pos.y -= 2;
                        }
                        pos.y = field.getArea().ul.y;
                    }
                    revdir = !revdir;
                    pos.x -= 2;
                }
            } else {
                while (pos.x <= field.getArea().br.x - 1) {
                    AtomicBoolean setDir = new AtomicBoolean(true);
                    if (revdir) {
                        while (pos.y <= field.getArea().br.y - 1) {
                            Coord endPos = new Coord(Math.min(pos.x + 1, field.getArea().br.x - 1), Math.min(pos.y + 1, field.getArea().br.y - 1));
                            Area harea = new Area(pos, endPos, true);
                            Coord2d endp = harea.ul.sub(0, 1).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                            seedCrop(gui, barrels, stockPiles, harea, revdir, endp, setDir, ignoreStraw);
                            pos.y += 2;

                        }
                        pos.y = field.getArea().br.y - 1;
                    } else {
                        while (pos.y >= field.getArea().ul.y) {
                            Coord endPos = new Coord(Math.min(pos.x + 1, field.getArea().br.x - 1), Math.max(pos.y - 1, field.getArea().ul.y));
                            Area harea = new Area(pos, endPos, true);
                            Coord2d endp = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).add(0, MCache.tileqsz.y);
                            seedCrop(gui, barrels, stockPiles, harea, revdir, endp, setDir, ignoreStraw);
                            pos.y -= 2;
                        }
                        pos.y = field.getArea().ul.y;
                    }
                    revdir = !revdir;
                    pos.x += 2;
                }
            }
        } while (Finder.findGobs(field, crop).size() != tiles.size());

        dropOffSeeds(gui);

        return Results.SUCCESS();
    }

    // Return every leftover planting product to its home container by exact name:
    // seeds -> barrel, vegetables -> the seed-area stockpile. Each product is handled
    // independently, so a mix of carrots and carrot seeds can never be misrouted.
    private void dropOffSeeds(NGameUI gui) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        for (PlantingSource src : sources)
            returnLeftovers(gui, src);
    }

    void seedCrop(NGameUI gui, ArrayList<Gob> barrels, ArrayList<Gob> stockpiles, Area area, boolean rev, Coord2d target_coord, AtomicBoolean setDir, boolean ignoreStraw) throws InterruptedException {
        Area.Tile[][] tiles = area.getTiles(area, new NAlias("gfx/terobjs/moundbed"));
        int count = 0;
        int total = 0;
        for (int i = 0; i <= area.br.x - area.ul.x; i++) {
            for (int j = 0; j <= area.br.y - area.ul.y; j++) {
                if (NParser.checkName(tiles[i][j].name, "field")) {
                    total++;
                    if (isTileFreeForSeeding(tiles[i][j], ignoreStraw))
                        count++;
                }
            }
        }
        if (count <= 0)
            return;

        // Make sure the current source can supply this block; advance to the next
        // source (e.g. seeds -> stockpile vegetables) when one is exhausted. Aborts
        // via InterruptedException if no source can supply.
        ensureStockForBlock(gui, count);
        PlantingSource source = currentSource();
        if (source == null)
            return;

        if (PathFinder.isAvailable(target_coord)) {
            new PathFinder(target_coord).run(NUtils.getGameUI());
            if (setDir.get()) {
                if (rev)
                    new SetDir(new Coord2d(0, 1)).run(gui);
                else
                    new SetDir(new Coord2d(0, -1)).run(gui);
                setDir.set(false);
            }
        } else {
            for (int i = 0; i <= area.br.x - area.ul.x; i++) {
                for (int j = 0; j <= area.br.y - area.ul.y; j++) {
                    if (NParser.checkName(tiles[i][j].name, "field")) {
                        new PathFinder(new Coord(area.ul.x + i, area.ul.y + j).mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y)).run(gui);
                    }
                }
            }
        }

        int stacks_size;
        if (source.isStacked())
            stacks_size = NUtils.getGameUI().getInventory().getTotalAmountItems(source.item);
        else
            stacks_size = NUtils.getGameUI().getInventory().getItems(source.item).size();

        WItem seedItem = findUsableSeedStack(gui, source);
        if (seedItem == null) {
            NUtils.getGameUI().msg("No usable planting material available");
            return;
        }
        NUtils.getGameUI().getInventory().activateItem(seedItem);
        NUtils.getUI().core.addTask(new GetCurs("harvest"));

        if (rev) {
            NUtils.getGameUI().map.wdgmsg("sel", area.ul, area.br, 1);
        } else {
            NUtils.getGameUI().map.wdgmsg("sel", area.br, area.ul, 1);
        }
        NUtils.getUI().core.addTask(new WaitGobsInField(area, total));

        if (source.isStacked()) {
            NUtils.getUI().core.addTask(new WaitAnotherAmount(NUtils.getGameUI().getInventory(), source.item, stacks_size));
        } else {
            // Individual (vegetable) planting: opportunistically top up from the stockpile.
            fetchFromSource(gui, source);
        }
    }

    // ----- planting-source helpers -----

    private PlantingSource currentSource() {
        return (sourceIdx < sources.size()) ? sources.get(sourceIdx) : null;
    }

    // The seed product name ("X Seeds") is self-exact under substring matching, so a
    // vegetable name can never match it.
    private NAlias seedItemAlias(NAlias result) {
        return result;
    }

    // The vegetable product must exclude its seed sibling: "Carrot" + exception "seed"
    // matches Carrot but never "Carrot Seeds" / "...seed-carrot".
    private NAlias vegItemAlias(NAlias result) {
        return new NAlias(new ArrayList<>(result.keys), new ArrayList<>(Collections.singletonList("seed")));
    }

    // Ensure the current source holds enough planting material for cellsNeeded tiles,
    // advancing to the next source when the current one is exhausted. Aborts only when
    // no source can supply at all.
    private void ensureStockForBlock(NGameUI gui, int cellsNeeded) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        while (sourceIdx < sources.size()) {
            PlantingSource src = sources.get(sourceIdx);
            // The whole block must be plantable from a single source in one selection
            // (WaitGobsInField waits for the entire block to fill), so require enough
            // for every free tile before committing.
            if (!hasSufficient(gui, src, cellsNeeded))
                fetchFromSource(gui, src);
            if (hasSufficient(gui, src, cellsNeeded))
                return;
            // This source cannot cover the block even after taking everything it has;
            // file its leftovers home and fall back to the next source (seeds -> veg).
            returnLeftovers(gui, src);
            sourceIdx++;
        }
        gui.error("NO SEEDS: ABORT");
        throw new InterruptedException();
    }

    private void fetchFromSource(NGameUI gui, PlantingSource src) throws InterruptedException {
        if (src.type == SourceType.BARREL)
            fetchFromBarrel(gui, src.item);
        else
            fetchFromStockpile(gui, src.item);
    }

    private void fetchFromBarrel(NGameUI gui, NAlias item) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        for (Gob barrel : barrels) {
            if (gui.getInventory().getFreeSpace() <= 0)
                break;
            if (NUtils.barrelHasContent(barrel))
                new TakeFromBarrel(barrel, item).run(gui);
        }
    }

    private void fetchFromStockpile(NGameUI gui, NAlias item) throws InterruptedException {
        if (gui.getInventory().getItems(item).size() >= 9)
            return;
        ArrayList<Gob> piles = Finder.findGobs(vegArea, new NAlias("stockpile"));
        for (Gob stockpile : piles) {
            if (gui.getInventory().getFreeSpace() <= 0)
                break;
            new PathFinder(stockpile).run(gui);
            new OpenTargetContainer("Stockpile", stockpile).run(gui);

            int numberOfItemsToFetch = gui.getInventory().getFreeSpace();
            if (((NInventory) NUtils.getGameUI().maininv).bundle.a) {
                numberOfItemsToFetch = numberOfItemsToFetch * StackSupporter.getFullStackSize(item.getDefault());
            }

            new TakeItemsFromPile(stockpile, gui.getStockpile(), numberOfItemsToFetch).run(gui);
        }
    }

    private void returnLeftovers(NGameUI gui, PlantingSource src) throws InterruptedException {
        if (!gui.hand.isEmpty())
            NUtils.dropToInv();
        if (gui.getInventory().getItems(src.item).isEmpty())
            return;
        if (src.type == SourceType.BARREL) {
            for (Gob barrel : barrels) {
                TransferToBarrel tb;
                (tb = new TransferToBarrel(barrel, src.item)).run(gui);
                if (!tb.isFull())
                    break;
            }
        } else {
            new TransferToPiles(vegArea.getRCArea(), src.item).run(gui);
        }
    }

    private void seedForQuality(NGameUI gui) throws InterruptedException {
        int[] seedingPattern = getQualitySeedingPattern();
        int patX = seedingPattern[0];
        int patY = seedingPattern[1];

        Area.Tile[][] tiles = field.getArea().getTiles(field.getArea(), new NAlias("gfx/terobjs/moundbed"));

        // Try both orientations
        int patchesXY = countPossiblePatches(field.getArea(), tiles, patX, patY);
        int patchesYX = countPossiblePatches(field.getArea(), tiles, patY, patX);

        boolean useRotated = patchesYX > patchesXY;

        int useX = useRotated ? patY : patX;
        int useY = useRotated ? patX : patY;

        ArrayList<Coord> toSeed = findFirstFreePatch(field.getArea(), tiles, useX, useY);

        if (toSeed == null) {
            gui.msg("No empty patch of " + useX + "x" + useY + " found for quality seeding!");
            return;
        }

        // 2. Find all containers in the seed area (chests, cupboards, etc)
        ArrayList<Container> containers = new ArrayList<>();
        for (Gob sm : Finder.findGobs(seed.getRCArea(), new NAlias(new ArrayList<>(nurgling.areas.NContext.contcaps.keySet())))) {
            Container cand = new Container(sm, nurgling.areas.NContext.contcaps.get(sm.ngob.name), null);
            cand.initattr(Container.Space.class);
            containers.add(cand);
        }
        if (containers.isEmpty())
            throw new RuntimeException("No container found in seed area!");
        Container container = containers.get(0);

        new PathFinder(Finder.findGob(container.gobid)).run(gui);

        // 3. Get all seeds in the container
        new OpenTargetContainer(container).run(gui);
        ArrayList<WItem> seeds = gui.getInventory(container.cap).getItems(iseed);

        int canSeedCells = getSeedingCapacity(seeds);

        if (canSeedCells < toSeed.size()) {
            gui.error("Not enough seeds in container for quality seeding!");
            throw new InterruptedException();
        }

        int fetchCount = Math.min(seeds.size(), gui.getInventory().getFreeSpace());

        // 4. Fetch top seeds to inventory
        new TakeAvailableItemsFromContainer(container, iseed, fetchCount, NInventory.QualityType.High).run(gui);

        List<WItem> plantingOrder = getPlantingOrder(NUtils.getGameUI().getInventory().getItems(iseed), toSeed.size());

        // 5. Seed just those x*y tiles, individually
        for (int i = 0; i < toSeed.size(); i++) {
            WItem itemToPlant = plantingOrder.get(i);
            Coord tile = toSeed.get(i);

            new PathFinder(tile.mul(MCache.tilesz).add(MCache.tilehsz)).run(gui);
            NUtils.getGameUI().getInventory().activateItem(itemToPlant);
            NUtils.getGameUI().map.wdgmsg("sel", tile, tile, 1);
            NUtils.getUI().core.addTask(new WaitGobsInField(new Area(tile, tile), 1));
        }

        // Return seeds to the chest
        for (Gob sm : Finder.findGobs(seed.getRCArea(), new NAlias(new ArrayList<>(nurgling.areas.NContext.contcaps.keySet())))) {
            Container cand = new Container(sm, nurgling.areas.NContext.contcaps.get(sm.ngob.name), null);
            cand.initattr(Container.Space.class);
            containers.add(cand);
        }

        List<WItem> crops = new ArrayList<>();
        List<WItem> seedsList = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (WItem item : seeds) {
            String name = ((NGItem) item.item).name().toLowerCase();
            if (name.contains("seed")) {
                seedsList.add(item);
            } else {
                crops.add(item);
            }
        }

        // We have to return crops before seeds (example Carrot before Carrot Seed) because otherwise it will try to
        // stack crop into seed.

        // Crops first (exclude seed items)
        for (WItem item : crops) {
            String itemName = ((NGItem) item.item).name();
            if (processed.add(itemName)) {
                new TransferToContainer(
                        container,
                        new NAlias(Collections.singletonList(itemName), Collections.singletonList("seed"))
                ).run(gui);
            }
        }

        // Seeds second
        for (WItem item : seedsList) {
            String itemName = ((NGItem) item.item).name();
            if (processed.add(itemName)) {
                new TransferToContainer(
                        container,
                        new NAlias(Collections.singletonList(itemName), Collections.emptyList())
                ).run(gui);
            }
        }

        new CloseTargetContainer(container).run(gui);
    }

    private int[] getQualitySeedingPattern() {
        String pat = (String) nurgling.NConfig.get(nurgling.NConfig.Key.qualityGrindSeedingPatter);
        if (pat == null || !pat.matches("\\d+x\\d+")) return new int[]{1, 4}; // default
        String[] parts = pat.split("x");
        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            return new int[]{x, y};
        } catch (Exception e) {
            return new int[]{1, 4};
        }
    }

    // Counts how many non-overlapping patches of size patX x patY fit in the area
    private int countPossiblePatches(Area area, Area.Tile[][] tiles, int patX, int patY) {
        int patches = 0;
        boolean[][] used = new boolean[tiles.length][tiles[0].length];
        for (int i = 0; i <= tiles.length - patX; i++) {
            for (int j = 0; j <= tiles[0].length - patY; j++) {
                boolean ok = true;
                for (int dx = 0; dx < patX; dx++) {
                    for (int dy = 0; dy < patY; dy++) {
                        if (used[i + dx][j + dy]) ok = false;
                        Area.Tile tile = tiles[i + dx][j + dy];
                        if (!nurgling.tools.NParser.checkName(tile.name, "field") || !tile.isFree)
                            ok = false;
                    }
                }
                if (ok) {
                    patches++;
                    for (int dx = 0; dx < patX; dx++)
                        for (int dy = 0; dy < patY; dy++)
                            used[i + dx][j + dy] = true;
                }
            }
        }
        return patches;
    }

    private ArrayList<Coord> findFirstFreePatch(Area area, Area.Tile[][] tiles, int patX, int patY) {
        for (int i = 0; i <= tiles.length - patX; i++) {
            for (int j = 0; j <= tiles[0].length - patY; j++) {
                boolean ok = true;
                ArrayList<Coord> patch = new ArrayList<>();
                for (int dx = 0; dx < patX; dx++) {
                    for (int dy = 0; dy < patY; dy++) {
                        Area.Tile tile = tiles[i + dx][j + dy];
                        if (!nurgling.tools.NParser.checkName(tile.name, "field") || !tile.isFree)
                            ok = false;
                        patch.add(new Coord(area.ul.x + i + dx, area.ul.y + j + dy));
                    }
                }
                if (ok) return patch;
            }
        }
        return null;
    }

    private int getSeedingCapacity(List<WItem> seeds) {
        int totalCells = 0;
        for (WItem item : seeds) {
            int itemCount = 1; // Default: crops count as 1

            for (ItemInfo info : item.item.info()) {
                if (info instanceof GItem.Amount) {
                    int qty = ((GItem.Amount) info).itemnum();
                    itemCount = qty / 5; // Each 5 seeds = 1 cell

                    break;
                }
            }
            // If it's a crop, itemCount remains 1
            totalCells += itemCount;
        }
        return totalCells;
    }

    private List<WItem> getPlantingOrder(List<WItem> seeds, int tilesToPlant) {
        List<WItem> order = new ArrayList<>();
        int planted = 0;

        List<Integer> amounts = new ArrayList<>();
        for (WItem item : seeds) {
            int amt = -1;
            List<ItemInfo> infoList = item.item.info;
            if (infoList != null) {
                for (ItemInfo info : infoList) {
                    if (info instanceof GItem.Amount) {
                        amt = ((GItem.Amount) info).itemnum();
                        break;
                    }
                }
            }
            amounts.add(amt);
        }

        for (int i = 0; i < seeds.size() && planted < tilesToPlant; i++) {
            WItem item = seeds.get(i);
            int amt = amounts.get(i);

            if (amt == -1) {
                order.add(item);
                planted++;
            } else {
                while (amt >= 5 && planted < tilesToPlant) {
                    order.add(item);
                    amt -= 5;
                    planted++;
                }
            }
        }
        return order;
    }

    private boolean isTileFreeForSeeding(Area.Tile tile, boolean ignoreStraw) {
        if (tile.gobs.isEmpty()) {
            return true;
        }
        if (!ignoreStraw) {
            return tile.isFree;
        }
        // Only straw allowed (any number)
        for (Gob gob : tile.gobs) {
            String name = gob.ngob != null ? gob.ngob.name : "";
            if (!NParser.checkName(name, "straw"))
                return false;
        }
        return true;
    }

    /**
     * Finds the first stack of the source's exact item that can be used for planting.
     * Stacked (barrel) sources need amount >= perTile to plant at least one cell;
     * individual (stockpile) items are always usable.
     */
    private WItem findUsableSeedStack(NGameUI gui, PlantingSource source) throws InterruptedException {
        for (WItem it : gui.getInventory().getItems(source.item)) {
            if (!source.isStacked())
                return it;
            GItem.Amount amount = ((NGItem) it.item).getInfo(GItem.Amount.class);
            if (amount == null)
                return it; // no amount info - treat as usable
            if (amount.itemnum() >= source.perTile)
                return it;
        }
        return null;
    }

    /**
     * Whether the inventory holds enough of the source's exact item to plant cellsNeeded
     * tiles. Stacked sources need cellsNeeded * perTile total units; individual sources
     * need cellsNeeded items.
     */
    private boolean hasSufficient(NGameUI gui, PlantingSource source, int cellsNeeded) throws InterruptedException {
        if (!source.isStacked())
            return gui.getInventory().getItems(source.item).size() >= cellsNeeded;
        int total = 0;
        for (WItem it : gui.getInventory().getItems(source.item)) {
            GItem.Amount amount = ((NGItem) it.item).getInfo(GItem.Amount.class);
            total += (amount != null) ? amount.itemnum() : 1;
        }
        return total >= cellsNeeded * source.perTile;
    }
}

