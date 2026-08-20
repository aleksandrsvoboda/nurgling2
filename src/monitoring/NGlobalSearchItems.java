package monitoring;

import nurgling.NConfig;
import nurgling.db.DatabaseManager;
import nurgling.tools.NSearchItem;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NGlobalSearchItems implements Runnable {
    private final NSearchItem item;
    private final DatabaseManager databaseManager;

    /**
     * One container that holds items matching the current query, with everything needed
     * to route to it: the grid it stands on and its offset within that grid. Both come
     * straight from the containers table, which is why a container can be pointed at
     * without its gob being loaded.
     */
    public static class ContainerHit {
        public final String hash;
        public final long gridId;
        public final String coord;      // in-grid offset, posres units, as "(x, y)"
        public final int count;         // matching items in this container
        public final double maxQuality;

        public ContainerHit(String hash, long gridId, String coord, int count, double maxQuality) {
            this.hash = hash;
            this.gridId = gridId;
            this.coord = coord;
            this.count = count;
            this.maxQuality = maxQuality;
        }
    }

    public static final ArrayList<String> containerHashes = new ArrayList<>();
    /**
     * Same result set as containerHashes, but keeping the coordinates. Published as an
     * immutable snapshot so readers on the UI thread never see a half-written list.
     */
    private static volatile List<ContainerHit> hits = Collections.emptyList();
    public static volatile long updateVersion = 0; // Incremented when containerHashes changes

    /** Latest search results, newest complete set. Never null. */
    public static List<ContainerHit> hits() {
        return hits;
    }

    /** Drop every result. Called when the query is reset so nothing stale stays highlighted. */
    public static void clearResults() {
        resultsValid = false;
        hits = Collections.emptyList();
        synchronized (containerHashes) {
            containerHashes.clear();
            updateVersion++;
        }
    }
    
    // Cache for last search query to avoid duplicate DB queries
    private static volatile String lastSearchQuery = "";
    private static volatile long lastQueryTime = 0;
    /**
     * Whether the cached query still has its results. Every keystroke resets the search,
     * which wipes them - so the dedup below must not skip a re-query that would refill
     * them, or retyping the same text inside the cache window leaves the results empty
     * until the periodic refresh happens seconds later.
     */
    private static volatile boolean resultsValid = false;
    private static final long QUERY_CACHE_DURATION_MS = 2000; // Cache results for 2 seconds

    public NGlobalSearchItems(NSearchItem item, DatabaseManager databaseManager) {
        this.item = item;
        this.databaseManager = databaseManager;
    }

    @Override
    public void run() {
        if (item.name.isEmpty() && item.q.isEmpty()) {
            return;
        }
        
        // Build search signature for deduplication
        String searchSignature = buildSearchSignature();
        long now = System.currentTimeMillis();
        
        // Skip if same search was just performed (within cache duration)
        if (resultsValid && searchSignature.equals(lastSearchQuery) && (now - lastQueryTime) < QUERY_CACHE_DURATION_MS) {
            nurgling.db.DatabaseManager.incrementSkippedSearch();
            return;
        }

        try {
            databaseManager.executeOperation(adapter -> {
                boolean isSQLite = adapter instanceof nurgling.db.SqliteAdapter;

                String nameOp = isSQLite ? "LIKE" : "ILIKE";
                String collation = isSQLite ? " COLLATE NOCASE" : "";

                StringBuilder dynamicSql = new StringBuilder()
                        .append("SELECT c.hash, c.grid_id, c.coord, ")
                        .append("COUNT(*) AS match_count, MAX(si.quality) AS max_quality ")
                        .append("FROM containers c ")
                        .append("JOIN storageitems si ON c.hash = si.container ")
                        .append("WHERE si.name ").append(nameOp).append(" ?").append(collation);

                if (!item.q.isEmpty()) {
                    dynamicSql.append(" AND (");
                    for (int i = 0; i < item.q.size(); i++) {
                        if (i > 0) {
                            dynamicSql.append(" OR ");
                        }
                        dynamicSql.append("(");
                        switch (item.q.get(i).type) {
                            case MORE:
                                dynamicSql.append("si.quality > ?");
                                break;
                            case LOW:
                                dynamicSql.append("si.quality < ?");
                                break;
                            case EQ:
                                dynamicSql.append("si.quality = ?");
                                break;
                        }
                        dynamicSql.append(")");
                    }
                    dynamicSql.append(")");
                }

                dynamicSql.append(" GROUP BY c.hash, c.grid_id, c.coord");

                Object[] params = new Object[1 + item.q.size()];
                params[0] = "%" + item.name + "%";

                for (int i = 0; i < item.q.size(); i++) {
                    params[i + 1] = item.q.get(i).val;
                }

                try (java.sql.ResultSet resultSet = adapter.executeQuery(dynamicSql.toString(), params)) {
                    ArrayList<ContainerHit> found = new ArrayList<>();
                    ArrayList<String> foundHashes = new ArrayList<>();
                    while (resultSet.next()) {
                        String hash = resultSet.getString("hash");
                        foundHashes.add(hash);
                        found.add(new ContainerHit(
                                hash,
                                resultSet.getLong("grid_id"),
                                resultSet.getString("coord"),
                                resultSet.getInt("match_count"),
                                resultSet.getDouble("max_quality")));
                    }
                    hits = Collections.unmodifiableList(found);
                    synchronized (containerHashes) {
                        containerHashes.clear();
                        containerHashes.addAll(foundHashes);
                        updateVersion++;
                    }
                }

                return null;
            });
            
            // Update cache after successful query
            lastSearchQuery = searchSignature;
            lastQueryTime = System.currentTimeMillis();
            resultsValid = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Build a signature representing the current search parameters
     */
    private String buildSearchSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(item.name);
        for (NSearchItem.Quality quality : item.q) {
            sb.append("|").append(quality.type).append(":").append(quality.val);
        }
        return sb.toString();
    }
    
    /**
     * Clear the query cache - called when container data changes
     */
    public static void clearQueryCache() {
        lastSearchQuery = "";
        lastQueryTime = 0;
        resultsValid = false;
    }
}
