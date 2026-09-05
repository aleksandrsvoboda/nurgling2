package nurgling.actions;

import haven.*;
import nurgling.*;
import nurgling.areas.NArea;
import nurgling.conf.CropRegistry;
import nurgling.tasks.NoGob;
import nurgling.tasks.WaitMoreItems;
import nurgling.tools.Container;
import nurgling.areas.NContext;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class HarvestCrop implements Action {

    final NArea field;
    final NArea seed;

    final NArea trougha;
    NArea swill = null;

    final NAlias crop;
    boolean isQualityGrid = false;

    /**
     * How many times the field is walked over. One pass covers every strip; the extra passes only
     * exist to pick up what a failed harvest or a plant that regrew mid-pass left behind, and the
     * cap keeps an unharvestable leftover from looping forever.
     */
    private static final int MAX_SWEEPS = 3;

    public HarvestCrop(NArea field, NArea seed, NArea trough, NAlias crop) {
        this.field = field;
        this.seed = seed;
        this.trougha = trough;
        this.crop = crop;
    }

    public HarvestCrop(NArea field, NArea seed, NArea trough, NArea swill, NAlias crop) {
        this(field,seed,trough,crop, false);
        this.swill = swill;
    }

    public HarvestCrop(NArea field, NArea seed, NArea trough, NAlias crop, boolean isQualityGrid) {
        this(field,seed,trough,crop);
        this.isQualityGrid = isQualityGrid;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {

        ArrayList<Gob> barrels = Finder.findGobs(seed, new NAlias("barrel"));

        Gob trough = null;

        // The trough area is optional (farmers register it via Validator's opt list), so trougha
        // is null whenever none is configured or one is out of findSpec()'s range. Everything
        // downstream already handles a null trough gob - TransferToTrough returns early on it,
        // and the quality-grid path leaves it null deliberately.
        if(!isQualityGrid && trougha != null) {
            trough = Finder.findGob(trougha, new NAlias("gfx/terobjs/trough"));
        }

        Gob cistern = null;
        if(this.swill!=null)
        {
            cistern = Finder.findGob(swill, new NAlias("gfx/terobjs/cistern"));
        }
        HashMap<Gob, AtomicBoolean> barrelInfo = new HashMap();
        if (barrels.isEmpty() && requiresBarrel(crop)) {
            return Results.ERROR("No barrel for seed");
        }

        for (CropRegistry.CropStage stage : CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList())) {
            if (stage.storageBehavior == CropRegistry.StorageBehavior.BARREL) {
                // For each barrel, transfer all items of this type
                for (Gob barrel : barrels) {
                    TransferToBarrel tb = new TransferToBarrel(barrel, stage.result);
                    tb.run(gui);
                    barrelInfo.put(barrel, new AtomicBoolean(tb.isFull()));
                }
                // After barrels, transfer leftovers to trough/cistern
                if (!gui.getInventory().getItems(stage.result).isEmpty()) {
                    new TransferToTrough(trough, stage.result, cistern).run(gui);
                }
            }
        }

        // Every sweep starts over from the player's current corner. Re-entering the sweep with
        // the coordinates left over from the previous one would run the nested loops out of
        // bounds immediately, so a leftover plant used to spin this loop without doing any work.
        for (int pass = 0; pass < MAX_SWEEPS; pass++) {
            sweepField(gui, barrelInfo, trough, cistern);
            if (!hasAnyCropStage(field, crop) && Finder.findGobs(field, new NAlias("gfx/terobjs/plants/fallowplant"), 0).isEmpty())
                break;
        }

        finalCleanup(gui, barrelInfo.keySet(), trough, cistern);

        return Results.SUCCESS();
    }

    /** One full pass over the field, strip by strip. */
    private void sweepField(NGameUI gui, HashMap<Gob, AtomicBoolean> barrelInfo, Gob trough, Gob cistern) throws InterruptedException {
        Coord start = gui.map.player().rc.dist(field.getArea().br.mul(MCache.tilesz)) < gui.map.player().rc.dist(field.getArea().ul.mul(MCache.tilesz)) ? field.getArea().br.sub(1, 1) : field.getArea().ul;
        Coord pos = new Coord(start);
        boolean rev = (pos.equals(field.getArea().ul));

        boolean revdir = rev;

                if (!rev) {
                    while (pos.x >= field.getArea().ul.x) {
                        AtomicBoolean setDir = new AtomicBoolean(true);
                        if (revdir) {
                            while (pos.y <= field.getArea().br.y - 1) {
                                Coord endPos = new Coord(Math.max(pos.x - 2, field.getArea().ul.x), Math.min(pos.y + 1, field.getArea().br.y - 1));
                                Area harea = new Area(pos, endPos, true);

                                Coord2d plantGobEndpoint = harea.ul.mul(MCache.tilesz).add( MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y).sub(0,MCache.tileqsz.y);
                                Coord2d pathfinderEndpoint = harea.ul.sub(0, 1).mul(MCache.tilesz).add( MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y  + MCache.tileqsz.y);

                                harvest(gui, barrelInfo, trough, cistern, harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir);
                                pos.y += 2;
                            }
                            pos.y = field.getArea().br.y - 1;
                        } else {
                            while (pos.y >= field.getArea().ul.y) {
                                Coord endPos = new Coord(Math.max(pos.x - 2, field.getArea().ul.x), Math.max(pos.y - 1, field.getArea().ul.y));
                                Area harea = new Area(pos, endPos, true);
                                Coord2d plantGobEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).sub(MCache.tilesz.x, 0).add(0,MCache.tileqsz.y);
                                Coord2d pathfinderEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz.x, MCache.tilehsz.y).sub(MCache.tilesz.x, 0).add(0,MCache.tileqsz.y);
                                harvest(gui, barrelInfo, trough, cistern, harea, revdir, pathfinderEndpoint , plantGobEndpoint, setDir);
                                pos.y -= 2;
                            }
                            pos.y = field.getArea().ul.y;
                        }
                        revdir = !revdir;
                        pos.x -= 3;
                    }
                } else {
                    while (pos.x <= field.getArea().br.x - 1) {
                        AtomicBoolean setDir = new AtomicBoolean(true);
                        if (revdir) {
                            while (pos.y <= field.getArea().br.y - 1) {
                                Coord endPos = new Coord(Math.min(pos.x + 2, field.getArea().br.x - 1), Math.min(pos.y + 1, field.getArea().br.y - 1));
                                Area harea = new Area(pos, endPos, true);
                                Coord2d plantGobEndpoint = harea.ul.mul(MCache.tilesz).add(MCache.tilehsz.x+MCache.tilesz.x, MCache.tilehqsz.y + MCache.tileqsz.y);
                                Coord2d pathfinderEndpoint = harea.ul.sub(0, 1).mul(MCache.tilesz).add( MCache.tilesz.x + MCache.tilehsz.x, MCache.tilehsz.y + MCache.tileqsz.y);
                                harvest(gui, barrelInfo, trough, cistern, harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir);
                                pos.y += 2;
                            }
                            pos.y = field.getArea().br.y - 1;
                        } else {
                            while (pos.y >= field.getArea().ul.y) {
                                Coord endPos = new Coord(Math.min(pos.x + 2, field.getArea().br.x - 1), Math.max(pos.y - 1, field.getArea().ul.y));
                                Area harea = new Area(pos, endPos, true);
                                Coord2d plantGobEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).sub(MCache.tilesz.x, 0);
                                Coord2d pathfinderEndpoint = harea.br.mul(MCache.tilesz).add(MCache.tilehsz).sub(MCache.tilesz.x, 0).add(0,MCache.tileqsz.y);
                                harvest(gui, barrelInfo, trough, cistern, harea, revdir, pathfinderEndpoint, plantGobEndpoint, setDir);
                                pos.y -= 2;
                            }
                            pos.y = field.getArea().ul.y;
                        }
                        revdir = !revdir;
                        pos.x += 3;
                    }
                }
    }


    void harvest(NGameUI gui, HashMap<Gob,AtomicBoolean> barrelInfo, Gob trough, Gob cistern, Area area, boolean rev, Coord2d pathfinderEndpoint, Coord2d plantGobEndpoint, AtomicBoolean setDir) throws InterruptedException {
        dropOffSeed(gui, barrelInfo.keySet(), trough, cistern);

        if(NUtils.getStamina()<0.35) {
            if (!new Drink(0.9, false).run(gui).isSuccess)
                if ((Boolean) NConfig.get(NConfig.Key.harvestautorefill)) {
                    if (FillWaterskinsGlobal.checkIfNeed())
                        if (!(new FillWaterskinsGlobal().run(gui).IsSuccess()))
                            throw new InterruptedException();
                        else if (!new Drink(0.9, false).run(gui).isSuccess)
                            throw new InterruptedException();
                } else {
                    throw new InterruptedException();
                }
        }
        // Walk into the strip before looking at what is in it. Finder only sees gobs the server
        // has streamed to the client, so a strip beyond the load radius reads as empty and used
        // to be skipped without ever being visited - which lost most of a field larger than the
        // load radius, and all of it past the first trip to a barrel at the field's edge.
        approachStrip(gui, area, pathfinderEndpoint, rev, setDir);

        Gob plant;
        plant = null;
        for (CropRegistry.CropStage cropStage : CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList())) {
            plant = Finder.findGob(plantGobEndpoint.div(MCache.tilesz).floor(), crop, cropStage.stage);
            if(plant != null) {
                break;
            }
        }
        if(plant == null)
        {
            plant = Finder.findGob(plantGobEndpoint.div(MCache.tilesz).floor(),new NAlias("gfx/terobjs/plants/fallowplant"), 0);
        }
        if(plant!=null) {
            dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
            if(!PathFinder.isAvailable(pathfinderEndpoint))
            {
                new PathFinder(plant).run(NUtils.getGameUI());
            }
            new SelectFlowerAction("Harvest", plant).run(gui);
            NUtils.getUI().core.addTask(new NoGob(plant.id));

            if(crop.keys.contains("plants/beet")) {
                NUtils.getUI().core.addTask(new WaitMoreItems(NUtils.getGameUI().getInventory(), new NAlias("Beetroot"), 1));
            }

            dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
        }

        ArrayList<Gob> plants;
        List<CropRegistry.CropStage> cropStages = CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList());
        for (CropRegistry.CropStage cropStage : cropStages) {
            ArrayList<Gob> plantsToHarvest;
            while (!(plantsToHarvest = Finder.findGobs(area, crop, cropStage.stage)).isEmpty()) {
                dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
                Gob plantToHarvest = plantsToHarvest.get(0);
                new PathFinder(plantToHarvest).run(gui);
                new SelectFlowerAction("Harvest", plantToHarvest).run(gui);
                NUtils.getUI().core.addTask(new NoGob(plantToHarvest.id));

                if(crop.keys.contains("beet")) {
                    NUtils.getUI().core.addTask(new WaitMoreItems(NUtils.getGameUI().getInventory(), new NAlias("Beetroot"), 1));
                }

                dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
            }
        }

        while (!(plants = Finder.findGobs(area,new NAlias("gfx/terobjs/plants/fallowplant"), 0)).isEmpty())
        {
            dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
            plant = plants.get(0);
            new PathFinder(plant).run(gui);
            new SelectFlowerAction("Harvest", plant).run(gui);
            NUtils.getUI().core.addTask(new NoGob(plant.id));

            if(crop.keys.contains("beet")) {
                NUtils.getUI().core.addTask(new WaitMoreItems(NUtils.getGameUI().getInventory(), new NAlias("Beetroot"), 1));
            }

            dropOffSeed(gui, barrelInfo.keySet(), trough, cistern, area, pathfinderEndpoint, rev, setDir);
        }

        dropOffSeed(gui, barrelInfo.keySet(), trough, cistern);
    }

    private void dropOffSeed(NGameUI gui, Set<Gob> barrels, Gob trough, Gob cistern) throws InterruptedException {
        processHarvestedItems(gui, barrels, trough, cistern, true);
    }

    /**
     * Drop off what has been harvested and come back to the strip being worked on.
     *
     * A barrel or trough sitting at the edge of the field is a normal layout, and getting to it
     * takes the player out of the strip - and, on a field wider than the client's load radius,
     * out of range of the strip's plants entirely. Everything downstream looks for plants through
     * the object cache, so without walking back the rest of the strip reads as already harvested.
     */
    private void dropOffSeed(NGameUI gui, Set<Gob> barrels, Gob trough, Gob cistern,
                             Area area, Coord2d pathfinderEndpoint, boolean rev, AtomicBoolean setDir) throws InterruptedException {
        Coord2d before = gui.map.player().rc;
        processHarvestedItems(gui, barrels, trough, cistern, true);
        if (gui.map.player().rc.dist(before) > MCache.tilesz.x)
            approachStrip(gui, area, pathfinderEndpoint, rev, setDir);
    }

    /**
     * Walk to the strip. Prefers the strip's endpoint, and falls back to any reachable tile of the
     * strip when the endpoint itself is blocked, so that the strip is visited even when it holds
     * no plant the client currently knows about.
     */
    private void approachStrip(NGameUI gui, Area area, Coord2d pathfinderEndpoint, boolean rev, AtomicBoolean setDir) throws InterruptedException {
        if (PathFinder.isAvailable(pathfinderEndpoint)) {
            new PathFinder(pathfinderEndpoint).run(gui);
            if (setDir.get()) {
                new SetDir(new Coord2d(0, rev ? 1 : -1)).run(gui);
                setDir.set(false);
            }
            return;
        }
        for (int x = area.ul.x; x <= area.br.x; x++) {
            for (int y = area.ul.y; y <= area.br.y; y++) {
                Coord2d tile = new Coord(x, y).mul(MCache.tilesz).add(MCache.tilehsz);
                if (PathFinder.isAvailable(tile)) {
                    new PathFinder(tile).run(gui);
                    return;
                }
            }
        }
    }

    private void finalCleanup(NGameUI gui, Set<Gob> barrels, Gob trough, Gob cistern) throws InterruptedException {
        processHarvestedItems(gui, barrels, trough, cistern, false);
    }

    private void processHarvestedItems(
            NGameUI gui,
            Set<Gob> barrels,
            Gob trough,
            Gob cistern,
            boolean barrelOnlyIfInventoryFull
    ) throws InterruptedException {
        Map<NAlias, CropRegistry.StorageBehavior> resultStorage = new HashMap<>();
        for (CropRegistry.CropStage stage : CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList())) {
            resultStorage.put(stage.result, stage.storageBehavior);
        }

        List<WItem> barrelItems = new ArrayList<>();
        List<WItem> stockpileItems = new ArrayList<>();

        String name = "";
        for (WItem item : gui.getInventory().getItems()) {
            name = ((NGItem) item.item).name();
            CropRegistry.StorageBehavior behavior = resultStorage.get(new NAlias(name));
            if (behavior == null) continue;
            if (behavior == CropRegistry.StorageBehavior.BARREL) barrelItems.add(item);
            else if (behavior == CropRegistry.StorageBehavior.STOCKPILE) stockpileItems.add(item);
        }

        if(!isQualityGrid) {
            // In case item ends up in hand - drop it.
            if(NUtils.getGameUI().vhand!=null) {
                NUtils.drop(NUtils.getGameUI().vhand);
            }

            // 1. Always drop stockpile items
            if(!stockpileItems.isEmpty()) {
                dropAllItemsOfExactName(gui, stockpileItems);
            }
            // 2. Transfer barrel items if required
            boolean transferBarrel = !barrelItems.isEmpty()
                    && (!barrelOnlyIfInventoryFull || gui.getInventory().getFreeSpace() < 3);

            if (transferBarrel) {
                NAlias seedAlias = new NAlias(((NGItem) barrelItems.get(0).item).name());
                for (Gob barrel : barrels) {
                    TransferToBarrel tb = new TransferToBarrel(barrel, seedAlias);
                    tb.run(gui);
                    if (!tb.isFull()) break;
                }
                // 3. Leftover to trough/cistern
                if (!gui.getInventory().getItems(seedAlias).isEmpty()) {
                    new TransferToTrough(trough, seedAlias, cistern).run(gui);
                }
            }
        } else {
            if(!barrelOnlyIfInventoryFull || gui.getInventory().getFreeSpace() <= 7) {
                // Find all containers in the seed area
                ArrayList<Container> containers = new ArrayList<>();
                for (Gob sm : Finder.findGobs(seed.getRCArea(), new NAlias(new ArrayList<>(NContext.contcaps.keySet())))) {
                    Container cand = new Container(sm, NContext.contcaps.get(sm.ngob.name), null);
                    cand.initattr(Container.Space.class);
                    containers.add(cand);
                }

                if (containers.isEmpty())
                    throw new RuntimeException("No container found in seed area!");
                Container container = containers.get(0);

                List<WItem> allItems = new ArrayList<>();
                allItems.addAll(barrelItems);
                allItems.addAll(stockpileItems);

                Set<String> processed = new HashSet<>();

                for (WItem item : allItems) {
                    String itemName = ((NGItem) item.item).name();
                    if (processed.add(itemName)) {
                        new TransferToContainer(container, new NAlias(itemName)).run(gui);
                    }
                }

                new CloseTargetContainer(container).run(gui);
            }
        }

    }

    private boolean hasAnyCropStage(NArea field, NAlias crop) throws InterruptedException {
        List<CropRegistry.CropStage> cropStages = CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList());

        if (cropStages.isEmpty())
            return false;

        for (CropRegistry.CropStage cs : cropStages) {
            if (!Finder.findGobs(field, crop, cs.stage).isEmpty())
                return true;
        }

        return false;
    }

    private boolean requiresBarrel(NAlias crop) {
        if(isQualityGrid) {
            return false;
        }

        for (CropRegistry.CropStage stage : CropRegistry.HARVESTABLE.getOrDefault(crop, Collections.emptyList())) {
            if (stage.storageBehavior == CropRegistry.StorageBehavior.BARREL) {
                return true;
            }
        }
        return false;
    }

    private void dropAllItemsOfExactName(NGameUI gui, List<WItem> targetItems) throws InterruptedException {
        if(!targetItems.isEmpty()) {
            String targetName = ((NGItem) targetItems.get(0).item).name();

            ArrayList<WItem> items = gui.getInventory().getWItems(new NAlias(targetName));

            for (WItem item : items) {
                NUtils.drop(item);
            }
        }
    }
}
