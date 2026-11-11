package nurgling.navigation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One world's chunk graph and its files, shared by every session playing in that world. Each session used to load its own
 * copy of the whole graph (~135 KB a chunk in memory), so eight sessions held eight copies. Sessions acquire the store when
 * their map view starts and release it when it is disposed; the last one out saves what changed and drops the graph, and the
 * next one to come in loads it again from disk. Saving happens here, once for all sessions, so two sessions never write the
 * same chunk file at the same time.
 */
final class ChunkNavStore {
    private static final Map<String, ChunkNavStore> stores = new HashMap<>();

    final String genus;
    final ChunkNavGraph graph = new ChunkNavGraph();
    final ChunkNavFileStore files;
    private int refs = 0;
    // Chunks updated at or after this time haven't been written yet.
    private long savedUpTo;

    private ChunkNavStore(String genus) {
        this.genus = genus;
        this.files = new ChunkNavFileStore(genus);
    }

    /** The world's store, loading it from disk if no session holds it yet. */
    static synchronized ChunkNavStore acquire(String genus) {
        ChunkNavStore store = stores.get(genus);
        if (store == null) {
            store = new ChunkNavStore(genus);
            store.load();
            stores.put(genus, store);
        }
        store.refs++;
        return store;
    }

    /** A session is done with the store; the last one saves what is left and drops the graph. */
    static synchronized void release(ChunkNavStore store) {
        if (--store.refs > 0)
            return;
        stores.remove(store.genus);
        store.save();
    }

    /** Writes every chunk updated since the last save. Synchronized so overlapping saves never share a chunk's temp file. */
    synchronized void save() {
        long now = System.currentTimeMillis();
        long since = savedUpTo;
        List<ChunkNavData> changed = new ArrayList<>();
        for (ChunkNavData chunk : graph.getAllChunks()) {
            if (chunk.lastUpdated >= since)
                changed.add(chunk);
        }
        for (ChunkNavData chunk : changed) {
            try {
                files.saveChunk(chunk);
            } catch (IOException e) {
                System.err.println("ChunkNav: Failed to save chunk " + chunk.gridId + ": " + e.getMessage());
            }
        }
        savedUpTo = now;
    }

    private void load() {
        // Loaded chunks keep the time they were last updated, so nothing loaded counts as unsaved.
        savedUpTo = System.currentTimeMillis();
        try {
            files.cleanupTempFiles();

            if (files.needsMigration()) {
                System.out.println("ChunkNav: Migrating from JSON to binary format...");
                migrateFromJson();
            }

            List<ChunkNavData> loadedChunks = files.loadAllChunks();
            for (ChunkNavData chunk : loadedChunks)
                graph.addChunk(chunk);

            // One-time migration: wipe ALL neighbor data from legacy V1 format.
            if (files.needsInstanceMigration() && !loadedChunks.isEmpty()) {
                int cleared = wipeAllNeighborData();
                System.out.println("ChunkNav: Instance migration - wiped " + cleared +
                    " legacy neighbor links (will re-learn correctly as you explore)");
                files.markInstanceMigrationDone();
                for (ChunkNavData chunk : loadedChunks) {
                    try {
                        files.saveChunk(chunk);
                    } catch (IOException e) {
                        System.err.println("ChunkNav: Failed to save chunk " + chunk.gridId + ": " + e.getMessage());
                    }
                }
            }

            int repaired = repairCrossInstanceLinks();
            if (repaired > 0)
                System.out.println("ChunkNav: Repaired " + repaired + " false cross-instance neighbor links");

            int stalePortals = invalidateStalePortalConnections();
            if (stalePortals > 0)
                System.out.println("ChunkNav: Invalidated " + stalePortals + " stale portal connections");

            graph.rebuildAllConnections();

            System.out.println("ChunkNav: Loaded " + loadedChunks.size() + " chunks from binary format");
        } catch (Exception e) {
            System.err.println("ChunkNav: Failed to load data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Migrate from old JSON format to new binary format. */
    private void migrateFromJson() {
        Path oldJsonPath = files.getOldJsonFilePath();
        if (!Files.exists(oldJsonPath))
            return;

        try {
            String content = new String(Files.readAllBytes(oldJsonPath), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);

            String savedGenus = root.optString("genus", "");
            if (!genus.equals(savedGenus)) {
                System.err.println("ChunkNav: Migration skipped - genus mismatch");
                return;
            }

            JSONArray chunksArray = root.getJSONArray("chunks");
            int migratedCount = 0;
            int errorCount = 0;

            for (int i = 0; i < chunksArray.length(); i++) {
                try {
                    ChunkNavData chunk = ChunkNavData.fromJson(chunksArray.getJSONObject(i));
                    files.saveChunk(chunk);
                    migratedCount++;
                } catch (Exception e) {
                    errorCount++;
                    if (errorCount <= 3)
                        System.err.println("ChunkNav: Failed to migrate chunk " + i + ": " + e.getMessage());
                }
            }

            System.out.println("ChunkNav: Migrated " + migratedCount + " chunks" +
                (errorCount > 0 ? " (" + errorCount + " errors)" : ""));

            if (migratedCount > 0 && errorCount == 0)
                files.deleteOldJsonFile();
        } catch (Exception e) {
            System.err.println("ChunkNav: Migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Wipe ALL neighbor data from all chunks (V1 -> V2 migration: the links may cross instances). */
    private int wipeAllNeighborData() {
        int cleared = 0;
        for (ChunkNavData chunk : graph.getAllChunks()) {
            if (chunk.neighborNorth != -1 || chunk.neighborSouth != -1 ||
                chunk.neighborEast != -1 || chunk.neighborWest != -1) {
                chunk.neighborNorth = -1;
                chunk.neighborSouth = -1;
                chunk.neighborEast = -1;
                chunk.neighborWest = -1;
                chunk.connectedChunks.clear();
                cleared++;
            }
        }
        return cleared;
    }

    /** Remove neighbor links between chunks with different non-zero instanceIds. */
    private int repairCrossInstanceLinks() {
        int repaired = 0;
        for (ChunkNavData chunk : graph.getAllChunks()) {
            if (chunk.instanceId == 0) continue;
            long[] neighbors = {chunk.neighborNorth, chunk.neighborSouth, chunk.neighborEast, chunk.neighborWest};
            for (int i = 0; i < neighbors.length; i++) {
                if (neighbors[i] == -1) continue;
                ChunkNavData other = graph.getChunk(neighbors[i]);
                if (other != null && other.instanceId != 0 && other.instanceId != chunk.instanceId) {
                    switch (i) {
                        case 0: chunk.neighborNorth = -1; break;
                        case 1: chunk.neighborSouth = -1; break;
                        case 2: chunk.neighborEast = -1; break;
                        case 3: chunk.neighborWest = -1; break;
                    }
                    chunk.connectedChunks.remove(other.gridId);
                    repaired++;
                }
            }
        }
        return repaired;
    }

    /** Remove portal connections where portal type is incompatible with target chunk layer. */
    private int invalidateStalePortalConnections() {
        int invalidated = 0;
        for (ChunkNavData chunk : graph.getAllChunks()) {
            Iterator<ChunkPortal> it = chunk.portals.iterator();
            while (it.hasNext()) {
                ChunkPortal portal = it.next();
                if (portal.connectsToGridId == -1) continue;
                ChunkNavData target = graph.getChunk(portal.connectsToGridId);
                if (target == null) continue;
                if (!ChunkNavManager.isPortalTargetLayerValid(portal.type, target.layer)) {
                    portal.connectsToGridId = -1;
                    portal.exitLocalCoord = null;
                    invalidated++;
                }
            }
        }
        return invalidated;
    }
}
