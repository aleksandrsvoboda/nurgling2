package nurgling.db.dao;

import nurgling.db.DatabaseAdapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for StorageItem entities
 */
public class StorageItemDao {

    /**
     * Storage item data class
     */
    public static class StorageItemData {
        private final String itemHash;
        private final String name;
        private final double quality;
        private final String coordinates;
        private final String container;
        private final Long gridId;

        public StorageItemData(String itemHash, String name, double quality, String coordinates, String container) {
            this(itemHash, name, quality, coordinates, container, null);
        }

        public StorageItemData(String itemHash, String name, double quality, String coordinates, String container,
                               Long gridId) {
            this.itemHash = itemHash;
            this.name = name;
            this.quality = quality;
            this.coordinates = coordinates;
            this.container = container;
            this.gridId = gridId;
        }

        public String getItemHash() { return itemHash; }
        public String getName() { return name; }
        public double getQuality() { return quality; }
        public String getCoordinates() { return coordinates; }
        public String getContainer() { return container; }
        /** Grid the container stands on; null when the container has no containers row or wasn't loaded with it. */
        public Long getGridId() { return gridId; }

        /** Stockpile items show no quality; they are stored without one and read back as 0. */
        public boolean hasQuality() { return quality > 0; }

        /** A barrel or cistern row: one row holding an amount (see BulkStorageWindowExtension). */
        public boolean isBulk() {
            return coordinates != null && coordinates.startsWith(nurgling.widgets.BulkStorageWindowExtension.BULK_TAG);
        }

        /** "l", "seeds", ... for a bulk row, null otherwise. */
        public String getBulkUnit() {
            if (!isBulk()) return null;
            int sp = coordinates.indexOf(' ');
            return sp > 0 ? coordinates.substring(sp + 1) : "";
        }

        /** The amount of a bulk row, 0 otherwise. */
        public double getBulkAmount() {
            if (!isBulk()) return 0;
            String tag = nurgling.widgets.BulkStorageWindowExtension.BULK_TAG;
            int sp = coordinates.indexOf(' ');
            try {
                return Double.parseDouble(coordinates.substring(tag.length(), sp > 0 ? sp : coordinates.length()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * Save or update storage item
     */
    public void saveStorageItem(DatabaseAdapter adapter, String itemHash, String name, Double quality,
                               String coordinates, String container) throws SQLException {
        // Use LinkedHashMap to preserve column order
        java.util.LinkedHashMap<String, Object> columns = new java.util.LinkedHashMap<>();
        columns.put("item_hash", itemHash);
        columns.put("name", name);
        columns.put("quality", quality);
        columns.put("coordinates", coordinates);
        columns.put("container", container);
        
        String sql = adapter.getUpsertSql("storageitems", columns, java.util.List.of("item_hash"));
        adapter.executeUpdate(sql, itemHash, name, quality, coordinates, container);
    }

    /**
     * Load all storage items, each with the grid its container stands on (same query, no extra round trip)
     */
    public List<StorageItemData> loadAllStorageItems(DatabaseAdapter adapter) throws SQLException {
        List<StorageItemData> items = new ArrayList<>();

        try (ResultSet rs = adapter.executeQuery("SELECT si.item_hash, si.name, si.quality, si.coordinates, si.container, c.grid_id " +
                                                "FROM storageitems si LEFT JOIN containers c ON c.hash = si.container")) {
            while (rs.next()) {
                String itemHash = rs.getString("item_hash");
                String name = rs.getString("name");
                double quality = rs.getDouble("quality");
                String coordinates = rs.getString("coordinates");
                String container = rs.getString("container");
                long gridId = rs.getLong("grid_id");
                items.add(new StorageItemData(itemHash, name, quality, coordinates, container,
                    rs.wasNull() ? null : gridId));
            }
        }

        return items;
    }

    /**
     * Load storage items by container
     */
    public List<StorageItemData> loadStorageItemsByContainer(DatabaseAdapter adapter, String containerHash) throws SQLException {
        List<StorageItemData> items = new ArrayList<>();

        try (ResultSet rs = adapter.executeQuery("SELECT item_hash, name, quality, coordinates, container " +
                                                "FROM storageitems WHERE container = ?", containerHash)) {
            while (rs.next()) {
                items.add(new StorageItemData(
                    rs.getString("item_hash"),
                    rs.getString("name"),
                    rs.getDouble("quality"),
                    rs.getString("coordinates"),
                    rs.getString("container")
                ));
            }
        }

        return items;
    }

    /**
     * Load storage item by hash
     */
    public StorageItemData loadStorageItem(DatabaseAdapter adapter, String itemHash) throws SQLException {
        try (ResultSet rs = adapter.executeQuery("SELECT item_hash, name, quality, coordinates, container " +
                                                "FROM storageitems WHERE item_hash = ?", itemHash)) {
            if (rs.next()) {
                return new StorageItemData(
                    rs.getString("item_hash"),
                    rs.getString("name"),
                    rs.getDouble("quality"),
                    rs.getString("coordinates"),
                    rs.getString("container")
                );
            }
        }
        return null;
    }

    /**
     * Delete storage item
     */
    public void deleteStorageItem(DatabaseAdapter adapter, String itemHash) throws SQLException {
        adapter.executeUpdate("DELETE FROM storageitems WHERE item_hash = ?", itemHash);
    }

    /**
     * Delete all storage items for a container
     */
    public void deleteStorageItemsByContainer(DatabaseAdapter adapter, String containerHash) throws SQLException {
        adapter.executeUpdate("DELETE FROM storageitems WHERE container = ?", containerHash);
    }

    /**
     * Check if storage item exists
     */
    public boolean storageItemExists(DatabaseAdapter adapter, String itemHash) throws SQLException {
        try (ResultSet rs = adapter.executeQuery("SELECT 1 FROM storageitems WHERE item_hash = ? LIMIT 1", itemHash)) {
            return rs.next();
        }
    }
}
