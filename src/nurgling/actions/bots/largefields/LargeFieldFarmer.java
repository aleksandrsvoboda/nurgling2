package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord;
import haven.MCache;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.actions.bots.EquipTravellersSacksFromBelt;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.widgets.Specialisation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harvests and replants crop fields of any size, including fields far bigger than the client
 * loads at once. Uses the same areas as the regular farmer of the crop (see {@link LargeFieldCrop}).
 *
 * Every crop area of the crop is farmed, nearest first, so a field too big for one area can be
 * covered by several. Each area is cut into blocks that fit well inside the view (24 tiles; objects
 * load about 40 tiles out), walked in a serpentine. Harvest lap: walk to a block, wait for it to
 * load, harvest it exactly as the regular farmer harvests a field (rows walked back and forth,
 * produce dropped, seeds to the barrels when nearly full), then pick up what was dropped and
 * store it before the next block. Planting lap: the blocks again in reverse, each planted as the
 * regular farmer plants a field. Every store trip goes to the nearest seed / put / trough area.
 *
 * A run that stops part way (stop button, full barrels) is carried on by the next run of the same
 * crop within six hours, from the block it was working on (see {@link FieldProgress}).
 *
 * "Validate all crops before harvest" is not honoured here: it would need a walk over the whole
 * field just to look. The field's minimum harvest stage still applies, per plant.
 */
public class LargeFieldFarmer implements Action {
    /** Block side in tiles; a multiple of 6 so 3-wide harvest and 2-wide seed strips never straddle blocks. */
    static final int DEFAULT_BLOCK = 24;

    /** Crop id -> crop name, in menu order, for the bot registry. */
    public static Map<String, String> crops() {
        Map<String, String> res = new java.util.LinkedHashMap<>();
        for (LargeFieldCrop c : LargeFieldCrop.ALL.values())
            res.put(c.id, c.subtype);
        return res;
    }

    private final String cropId;
    private final int blockSize;

    public LargeFieldFarmer(Map<String, Object> settings) {
        Object c = settings == null ? null : settings.get("crop");
        this.cropId = (c == null) ? null : c.toString();
        Object b = settings == null ? null : settings.get("block");
        int size = (b instanceof Number) ? ((Number) b).intValue() : DEFAULT_BLOCK;
        this.blockSize = Math.max(6, size - size % 6);
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        LargeFieldCrop crop = (cropId == null) ? null : LargeFieldCrop.ALL.get(cropId);
        if (crop == null)
            return Results.ERROR("Unknown crop for the large field farmer: " + cropId);

        List<NArea> fields = NContext.findAllSpec(Specialisation.SpecName.crop.toString(), crop.subtype);
        if (fields.isEmpty())
            return Results.ERROR("No reachable crop area for " + crop.subtype);

        boolean ignoreStraw = (Boolean) NConfig.get(NConfig.Key.ignoreStrawInFarmers);
        FieldStores stores = new FieldStores(crop, ignoreStraw);
        if (stores.seedArea() == null)
            return Results.ERROR("No reachable seed area for " + crop.subtype);
        String missing = stores.missingRequired(gui);
        if (missing != null)
            return Results.ERROR(missing);

        if ((Boolean) NConfig.get(NConfig.Key.validateAllCropsBeforeHarvest))
            gui.msg("Large field farmer: \"validate all crops before harvest\" is ignored on large fields.");
        if ((Boolean) NConfig.get(NConfig.Key.autoEquipTravellersSacks))
            new EquipTravellersSacksFromBelt().run(gui);

        // A run of this crop that stopped part way: finish that field first, skip the ones it finished.
        FieldProgress.Mark resume = FieldProgress.load(crop.id);
        Set<String> done = new LinkedHashSet<>();
        if (resume != null) {
            done.addAll(resume.done);
            for (int i = 0; i < fields.size(); i++) {
                if (FieldProgress.key(fields.get(i)).equals(resume.field)) {
                    fields.add(0, fields.remove(i));
                    break;
                }
            }
            long mins = (System.currentTimeMillis() - resume.savedAt) / 60000;
            gui.msg("Large field farmer: carrying on with the " + crop.subtype + " run stopped " + mins + " min ago.");
        }

        boolean oldStacking = ((NInventory) gui.maininv).bundle.a;
        NUtils.stackSwitch(true);
        try {
            for (NArea area : fields) {
                String key = FieldProgress.key(area);
                if (done.contains(key))
                    continue;
                FieldProgress.Mark from = (resume != null && key.equals(resume.field)) ? resume : null;
                String err = farm(gui, area, crop, stores, ignoreStraw, from, done);
                if (err != null)
                    return Results.ERROR(crop.subtype + " (" + area.name + "): " + err);
                done.add(key);
            }
        } finally {
            NUtils.stackSwitch(oldStacking);
        }
        FieldProgress.clear(crop.id);
        return Results.SUCCESS();
    }

    /**
     * Farms one crop area; null on success, else why the run stopped. The progress record is
     * moved on after every block, so a stopped run resumes at the block it was working on.
     */
    private String farm(NGameUI gui, NArea area, LargeFieldCrop crop, FieldStores stores, boolean ignoreStraw,
                        FieldProgress.Mark from, Set<String> done) throws InterruptedException {
        String[] why = new String[1];
        FieldMap map = FieldMap.of(area, gui, why);
        if (map == null) {
            gui.msg("Skipping " + area.name + ": " + why[0]);
            return null;
        }
        FieldWork work = new FieldWork(gui, map, crop, stores, ignoreStraw);
        if (!work.hasPlantingSource())
            return "the crop has no planting material";

        boolean[] dir = direction(gui, map);
        int first = 0;
        int lap = FieldProgress.HARVEST;
        List<Area> blocks = FieldGeometry.blocks(map.field, blockSize, blockSize, dir[0], dir[1]);
        // Resume only into the same cut of the same field; a changed area or block size starts over.
        if (from != null && from.blocks == blocks.size() && from.blockSize == blockSize) {
            dir[0] = from.fromRight;
            dir[1] = from.fromBottom;
            blocks = FieldGeometry.blocks(map.field, blockSize, blockSize, dir[0], dir[1]);
            first = Math.min(from.block, blocks.size());
            lap = from.lap;
        }
        String key = FieldProgress.key(area);
        gui.msg("Large field " + area.name + ": " + (map.field.br.x - map.field.ul.x) + "x"
                + (map.field.br.y - map.field.ul.y) + " tiles, " + blocks.size() + " blocks"
                + (first > 0 || lap == FieldProgress.PLANT
                    ? ", carrying on with the " + (lap == FieldProgress.PLANT ? "planting" : "harvest") + " at block " + (first + 1) : ""));
        int unreachable = 0;

        // Harvest lap: each block harvested like the old farmers, then its drops picked up and stored.
        if (lap == FieldProgress.HARVEST) {
            for (int i = first; i < blocks.size(); i++) {
                save(crop, key, FieldProgress.HARVEST, i, blocks.size(), dir, done);
                if (!work.enter(blocks.get(i))) {
                    unreachable++;
                    continue;
                }
                System.out.println("[LargeField] " + area.name + " harvest " + (i + 1) + "/" + blocks.size());
                if (!work.harvest() || !work.collect())
                    return work.error;
            }
            first = 0;
        }

        // Planting lap, back the way the harvest came so it starts where the harvest ended.
        List<Area> back = new ArrayList<>(blocks);
        Collections.reverse(back);
        for (int i = first; i < back.size(); i++) {
            save(crop, key, FieldProgress.PLANT, i, blocks.size(), dir, done);
            if (!work.enter(back.get(i))) {
                unreachable++;
                continue;
            }
            System.out.println("[LargeField] " + area.name + " planting " + (i + 1) + "/" + back.size());
            if (!work.seed())
                return work.error;
        }
        // Leftover seeds and vegetables go back to their stores, as SeedCrop does.
        if (!work.store())
            return work.error;
        if (unreachable > 0)
            gui.msg("Large field " + area.name + ": " + unreachable + " block visits could not reach their block");
        return null;
    }

    private void save(LargeFieldCrop crop, String key, int lap, int block, int blocks, boolean[] dir, Set<String> done) {
        FieldProgress.Mark at = new FieldProgress.Mark(key, block, blocks, blockSize, dir[0], dir[1], System.currentTimeMillis());
        at.lap = lap;
        at.done.addAll(done);
        FieldProgress.save(crop.id, at);
    }

    /** {fromRight, fromBottom}: start from the corner nearest the player when the field is loaded. */
    private static boolean[] direction(NGameUI gui, FieldMap map) {
        boolean[] dir = new boolean[2];
        Coord me = gui.map.player() == null ? null
                : map.toField(gui.map.player().rc.floor(MCache.tilesz), gui.map.glob.map);
        if (me != null) {
            dir[0] = me.x > (map.field.ul.x + map.field.br.x) / 2;
            dir[1] = me.y > (map.field.ul.y + map.field.br.y) / 2;
        }
        return dir;
    }
}
