package nurgling.colony;

import haven.error.FileLogger;
import nurgling.NCore;
import nurgling.db.dao.StorageItemDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * How many of each building material the colony's Construction Materials zones hold, from the Storage Items index
 * (containers and stockpiles the client has seen, with where they stand). Only rows inside a zone of the material's
 * subtype count, wherever the zone is (inside a house too): what is kept anywhere else is never the colony's to spend. Refreshed in
 * the background at most every {@link #EVERY_MS}; the counts are what was seen last, which is all a plan needs.
 */
public final class ColonyStorage {
    public enum State { OFF, LOADING, READY }

    private static final long EVERY_MS = 30000;

    private volatile Map<ColonyItems.Material, Integer> counts = Collections.emptyMap();
    private volatile State state = State.OFF;
    private volatile long at;
    private volatile boolean busy;
    private volatile ColonyZones zones = ColonyZones.NONE;
    private volatile String lastSummary;

    public State state() {
        return state;
    }

    /** Counts per material in its zones, or null while nothing has been read (database off or first read pending). */
    public Map<ColonyItems.Material, Integer> counts() {
        return (state == State.READY) ? counts : null;
    }

    /** The zones of the last {@link #refresh}. */
    public ColonyZones zones() {
        return zones;
    }

    /** Reads the index again when the last read is old or the zones changed. Never blocks. */
    public void refresh(ColonyZones z) {
        if (NCore.databaseManager == null) {
            state = State.OFF;
            return;
        }
        long now = System.currentTimeMillis();
        if (busy || (now - at < EVERY_MS && z.equals(zones)))
            return;
        busy = true;
        if (state == State.OFF)
            state = State.LOADING;
        zones = z;
        Set<Long> grids = z.grids();
        List<Long> ids = new ArrayList<>(grids);
        CompletableFuture.runAsync(() -> {
            try {
                List<StorageItemDao.LocatedItem> rows = NCore.databaseManager.getStorageItemService().loadLocatedOnGrids(ids);
                Map<ColonyItems.Material, Integer> c = count(rows, grids, z);
                counts = c;
                state = State.READY;
                summarize(rows, grids, z, c);
            } catch (SQLException e) {
                FileLogger.log("[Colony] storage index read failed: " + e.getMessage());
            }
        }).whenComplete((v, err) -> {
            if (err != null)
                FileLogger.log("[Colony] storage index read failed: " + err);
            at = System.currentTimeMillis();
            busy = false;
        });
    }

    /** One log line when what was found changes: how many rows, how many count, and what in the zones did not. */
    private void summarize(List<StorageItemDao.LocatedItem> rows, Set<Long> grids, ColonyZones z,
                           Map<ColonyItems.Material, Integer> c) {
        Map<ColonyItems.Material, Set<String>> other = new TreeMap<>();
        for (StorageItemDao.LocatedItem it : rows)
            for (ColonyItems.Material m : ColonyZones.SUBTYPE.keySet())
                if (z.contains(m, it.gridId, it.coord) && !counts(m, it.name))
                    other.computeIfAbsent(m, k -> new TreeSet<>()).add(it.name);
        StringBuilder sb = new StringBuilder("[Colony] materials: ").append(rows.size()).append(" index rows on ")
            .append(grids.size()).append(" zone grids; in zones ").append(c)
            .append("; zones ").append(z);
        for (ColonyItems.Material m : ColonyZones.SUBTYPE.keySet())
            if (!z.has(m))
                sb.append("; no ").append(ColonyZones.SUBTYPE.get(m)).append(" zone");
        if (!other.isEmpty())
            sb.append("; not counted in zones: ").append(other);
        String line = sb.toString();
        if (!line.equals(lastSummary)) {
            lastSummary = line;
            FileLogger.log(line);
        }
    }

    /** Read again on the next {@link #refresh}, e.g. after a run took items out. */
    public void stale() {
        at = 0;
    }

    static Map<ColonyItems.Material, Integer> count(List<StorageItemDao.LocatedItem> rows, Set<Long> grids, ColonyZones z) {
        Map<ColonyItems.Material, Integer> c = new EnumMap<>(ColonyItems.Material.class);
        for (StorageItemDao.LocatedItem it : rows) {
            if (!grids.contains(it.gridId))
                continue;
            for (ColonyItems.Material m : ColonyZones.SUBTYPE.keySet()) {
                if (counts(m, it.name) && z.contains(m, it.gridId, it.coord)) {
                    c.merge(m, 1, Integer::sum);
                    break;
                }
            }
        }
        return c;
    }

    /**
     * Whether a row of this name counts as m: an item of the material, or a stockpile row, which carries only the
     * pile window's general kind ("Board", "Stone"). Tools ("Stone Axe") never count.
     */
    static boolean counts(ColonyItems.Material m, String name) {
        if (name == null || ColonyItems.tool(name) != null)
            return false;
        if (ColonyItems.material(name) == m)
            return true;
        return name.toLowerCase(java.util.Locale.ROOT).contains(ColonyZones.SUBTYPE.get(m).toLowerCase(java.util.Locale.ROOT));
    }
}
