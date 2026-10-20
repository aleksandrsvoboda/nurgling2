package nurgling.widgets.storage;

import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.areas.NArea;
import nurgling.db.dao.StorageItemDao;
import nurgling.navigation.ChunkNavManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Which recorded storage counts as the village's: items in the piece of the world holding the most
 * areas (see {@link nurgling.navigation.ChunkNavGraph#pieceWithMostAreas}), so storage seen in another
 * village or at a bot's spot stays out. The Storage Items window and the cookbook share this rule.
 */
public final class VillageStorage {
    private final ChunkNavManager nav;
    private final List<Set<Long>> areaGrids;

    private VillageStorage(ChunkNavManager nav, List<Set<Long>> areaGrids) {
        this.nav = nav;
        this.areaGrids = areaGrids;
    }

    /** Reads what the rule needs. On the UI thread; {@link #village} then runs off it. */
    public static VillageStorage snapshot(NGameUI gui) {
        ChunkNavManager nav = (gui != null && gui.map instanceof NMapView) ? ((NMapView) gui.map).getChunkNavManager() : null;
        List<Set<Long>> areaGrids = new ArrayList<>();
        if (gui != null && gui.map != null) {
            for (NArea area : gui.map.glob.map.areas.values())
                if (area.space != null)
                    areaGrids.add(new HashSet<>(area.space.space.keySet()));
        }
        return new VillageStorage(nav, areaGrids);
    }

    /**
     * Whether a village can be worked out yet: ChunkNav has loaded and areas are known. Until then
     * {@link #village} is empty and every item counts, which right after login is only temporary.
     */
    public boolean knowable() {
        return nav != null && nav.isInitialized() && !areaGrids.isEmpty();
    }

    /** The village's recorded chunks; empty when there's nothing to go by. */
    public Set<Long> village() {
        return (nav != null && nav.isInitialized()) ? nav.getGraph().pieceWithMostAreas(areaGrids) : Collections.emptySet();
    }

    /** The "Only items in the village" setting of the Storage Items window. */
    public static boolean onlyVillage() {
        Object conf = NConfig.get(NConfig.Key.storageOnlyVillage);
        return !(conf instanceof Boolean) || (Boolean) conf;
    }

    /** Drops items with a negative quality, which shouldn't be in the database. */
    public static List<StorageItemDao.StorageItemData> valid(List<StorageItemDao.StorageItemData> items) {
        return items.stream().filter(item -> item.getQuality() >= 0).collect(Collectors.toList());
    }

    /**
     * The items stored in the village. With no village found (ChunkNav unused, or no area on a
     * recorded chunk) filtering would hide everything, so then all items count.
     */
    public static List<StorageItemDao.StorageItemData> inVillage(List<StorageItemDao.StorageItemData> items, Set<Long> village) {
        if (village.isEmpty())
            return items;
        return items.stream()
                .filter(item -> inVillage(item.getGridId(), village))
                .collect(Collectors.toList());
    }

    /** Whether storage on gridId is the village's, by the same rule as the list version. */
    public static boolean inVillage(Long gridId, Set<Long> village) {
        return village.isEmpty() || (gridId != null && village.contains(gridId));
    }
}
