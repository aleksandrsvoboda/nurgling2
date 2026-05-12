package nurgling.db.service;

import nurgling.areas.AreaFieldGroup;
import nurgling.areas.AreaSnapshot;
import nurgling.areas.NArea;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.AreaDao;
import org.json.JSONObject;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service layer for area operations with sync support.
 *
 * Writes go through {@link #saveArea(NArea, String)} which performs an OCC
 * UPDATE keyed on baselineVersion. On version conflict the row is re-read,
 * three-way merged with {@link AreaMerger}, and re-saved. Pulls compare the
 * full version map and run the same merge for areas with concurrent edits.
 */
public class AreaService {
    private final DatabaseManager databaseManager;
    private final AreaDao areaDao;

    private static final int MAX_OCC_RETRIES = 4;

    // Sync state
    private volatile Timestamp lastSyncTime = null;
    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;
    private AreaSyncCallback syncCallback = null;
    private volatile long lastLocalEditAt = 0;

    public interface AreaSyncCallback {
        /**
         * Called from sync with the merged result for each area that needs to
         * be applied locally. The supplied NArea has its baseline already
         * captured.
         */
        void onAreasUpdated(List<NArea> updatedAreas);
        /** Called when the server reports an area as tombstoned. */
        void onAreaDeleted(int areaId);
        /** Called on forced full sync. */
        void onFullSync(Map<Integer, NArea> allAreas);
    }

    public AreaService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.areaDao = new AreaDao();
    }

    // -------------------- Save (push) path --------------------

    /**
     * Save a single area to the database using OCC + three-way merge.
     * Idempotent: if the area is already in sync (no dirty groups), does
     * nothing. Publishes AreaSyncEvents for any user-visible outcomes.
     */
    public void saveArea(NArea area, String profile) throws SQLException {
        if (area == null || profile == null) return;

        // No-op if nothing changed since last sync.
        Set<AreaFieldGroup> dirty = AreaMerger.localDirtyGroups(area);
        if (dirty.isEmpty() && area.baselineVersion > 0) {
            return;
        }

        // Areas imported from JSON files or created before the migration may
        // not have a UUID yet. Assign one before first DB write so identity
        // stays stable across clients.
        if (area.uuid == null) {
            area.uuid = java.util.UUID.randomUUID().toString();
        }

        String touchedBy = currentPlayerName();

        for (int attempt = 0; attempt < MAX_OCC_RETRIES; attempt++) {
            JSONObject json = area.toJson();
            JSONObject dataJson = new JSONObject();
            if (json.has("space")) dataJson.put("space", json.get("space"));
            if (json.has("in")) dataJson.put("in", json.get("in"));
            if (json.has("out")) dataJson.put("out", json.get("out"));
            if (json.has("spec")) dataJson.put("spec", json.get("spec"));

            final int expectedVersion = area.baselineVersion;
            final String dataStr = dataJson.toString();
            final String uuidSnap = area.uuid;
            final String name = area.name;
            final String path = area.path;
            final boolean hide = area.hide;
            final int r = area.color.getRed();
            final int g = area.color.getGreen();
            final int b = area.color.getBlue();
            final int a = area.color.getAlpha();

            AreaDao.SaveResult result = databaseManager.executeOperation(adapter ->
                areaDao.saveAreaOCC(adapter, area.id, uuidSnap, name, path, hide,
                    r, g, b, a, dataStr, profile, expectedVersion, touchedBy));

            if (result.outcome != AreaDao.SaveOutcome.VERSION_CONFLICT) {
                // Success - capture baseline so subsequent saves don't no-op falsely.
                area.version = result.newVersion;
                area.baselineVersion = result.newVersion;
                area.baselineSnapshot = AreaSnapshot.of(area);
                area.dirtyGroups.clear();
                return;
            }

            // Version conflict: pull remote, three-way merge, then retry.
            AreaDao.AreaData remoteData = databaseManager.executeOperation(adapter ->
                areaDao.loadAreaIncludingTombstone(adapter, area.id, profile));
            if (remoteData == null) {
                // Row vanished (deleted). Treat as inserting a fresh row.
                area.baselineVersion = 0;
                area.baselineSnapshot = null;
                continue;
            }
            if (remoteData.isTombstone()) {
                // Server says this area was deleted while we held a live copy.
                AreaSyncEvents.publish(new AreaSyncEvent(
                    AreaSyncEvent.Kind.DELETED, area.id, area.name,
                    null, null, remoteData.getLastTouchedBy()));
                return;
            }

            AreaSnapshot remoteSnap = AreaSnapshot.fromStored(
                remoteData.getName(), remoteData.getPath(), remoteData.isHide(),
                remoteData.getColorR(), remoteData.getColorG(), remoteData.getColorB(), remoteData.getColorA(),
                remoteData.getData(), remoteData.getVersion());

            String mergedUuid = area.uuid != null ? area.uuid : remoteData.getUuid();
            AreaMerger.Result merged = AreaMerger.merge(area, remoteSnap, area.id, mergedUuid);

            // Apply merged state to the in-memory area so the next attempt pushes the merged value.
            applyMergeToLocal(area, merged.mergedJson, remoteData);

            // Tell the user what happened (only if remote brought anything).
            if (merged.hasRemoteWork) {
                AreaSyncEvent.Kind kind = merged.remoteOverrode.isEmpty()
                    ? AreaSyncEvent.Kind.AUTO_MERGED
                    : (merged.hasLocalWork ? AreaSyncEvent.Kind.AUTO_MERGED : AreaSyncEvent.Kind.REMOTE_OVERRODE);
                AreaSyncEvents.publish(new AreaSyncEvent(
                    kind, area.id, area.name,
                    merged.takeRemote, merged.remoteOverrode,
                    remoteData.getLastTouchedBy()));
            }
            // Loop to retry the save with the merged baseline.
        }

        System.err.println("AreaService.saveArea: exceeded OCC retries for area " + area.id);
    }

    /**
     * Apply a merged JSON onto an existing NArea reference and capture the new
     * baseline matching the remote row's version.
     */
    private static void applyMergeToLocal(NArea area, JSONObject mergedJson, AreaDao.AreaData remoteData) {
        // Construct a temporary area from the merged JSON and copy its fields.
        NArea temp = new NArea(mergedJson);
        area.updateFrom(temp);
        // baselineVersion is now whatever the server had; the merged copy will
        // be saved on the next loop iteration, bumping it again.
        area.baselineVersion = remoteData.getVersion();
        area.baselineSnapshot = AreaSnapshot.fromStored(
            remoteData.getName(), remoteData.getPath(), remoteData.isHide(),
            remoteData.getColorR(), remoteData.getColorG(), remoteData.getColorB(), remoteData.getColorA(),
            remoteData.getData(), remoteData.getVersion());
        area.dirtyGroups.clear();
        if (area.uuid == null && remoteData.getUuid() != null) {
            area.uuid = remoteData.getUuid();
        }
    }

    public CompletableFuture<Void> saveAreaAsync(NArea area, String profile) {
        return CompletableFuture.runAsync(() -> {
            try {
                saveArea(area, profile);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to save area", e);
            }
        });
    }

    // -------------------- Load (initial) path --------------------

    /**
     * Load all live areas for a profile. The returned NAreas have their
     * baselines captured.
     */
    public Map<Integer, NArea> loadAreas(String profile) throws SQLException {
        Map<Integer, NArea> areas = new HashMap<>();
        List<AreaDao.AreaData> areaDataList = databaseManager.executeOperation(
            adapter -> areaDao.loadAreasByProfile(adapter, profile));
        for (AreaDao.AreaData data : areaDataList) {
            NArea area = convertToNArea(data);
            areas.put(area.id, area);
        }
        return areas;
    }

    public CompletableFuture<Map<Integer, NArea>> loadAreasAsync(String profile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadAreas(profile);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to load areas", e);
            }
        });
    }

    // -------------------- Delete (tombstone) path --------------------

    public void deleteArea(int areaId, String profile) throws SQLException {
        final String byPlayer = currentPlayerName();
        databaseManager.executeOperation(adapter -> {
            areaDao.tombstoneArea(adapter, areaId, profile, byPlayer);
            return null;
        });
    }

    public CompletableFuture<Void> deleteAreaAsync(int areaId, String profile) {
        return CompletableFuture.runAsync(() -> {
            try {
                deleteArea(areaId, profile);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to delete area", e);
            }
        });
    }

    // -------------------- Bulk export (used by Export button) --------------------

    public int exportAreasToDatabase(Map<Integer, NArea> areas, String profile) throws SQLException {
        List<NArea> areasCopy = new ArrayList<>(areas.values());
        int count = 0;
        for (NArea area : areasCopy) {
            saveArea(area, profile);
            count++;
        }
        return count;
    }

    public CompletableFuture<Integer> exportAreasToDatabaseAsync(Map<Integer, NArea> areas, String profile) {
        List<NArea> areasCopy = new ArrayList<>(areas.values());
        return databaseManager.executeWithRetry(adapter -> {
            int count = 0;
            for (NArea area : areasCopy) {
                // Re-enter through the higher-level API so OCC + merge is applied per row.
                try {
                    saveArea(area, profile);
                } catch (SQLException e) {
                    throw e;
                }
                count++;
            }
            return count;
        }, "Export " + areasCopy.size() + " areas");
    }

    // -------------------- Periodic sync poll --------------------

    /**
     * Note about cadence (Phase 5): when the local user is actively editing
     * (any change in the last 60s), the actual poll cadence is intervalSeconds
     * directly. Otherwise we still poll at intervalSeconds but other clients'
     * presence updates feel less urgent. The configured value is the minimum
     * cadence.
     */
    public void startSync(String profile, long intervalSeconds, AreaSyncCallback callback) {
        if (syncEnabled) stopSync();

        this.syncCallback = callback;
        this.syncEnabled = true;
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Area-Sync-Worker");
            t.setDaemon(true);
            return t;
        });

        syncScheduler.scheduleAtFixedRate(() -> {
            if (!syncEnabled) return;
            if (!databaseManager.isReady()) return;

            String currentProfile = getCurrentProfile();
            if (currentProfile == null || currentProfile.isEmpty()) return;

            try {
                Map<Integer, NArea> localAreas = getLocalAreasSnapshot();
                List<NArea> updates = checkForUpdatesAndMerge(currentProfile, localAreas);
                if (!updates.isEmpty() && syncCallback != null) {
                    syncCallback.onAreasUpdated(updates);
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")) {
                    System.err.println("Area sync error: " + msg);
                }
            }
        }, 5, intervalSeconds, TimeUnit.SECONDS);

        System.out.println("Area sync started with interval: " + intervalSeconds + " seconds");
    }

    public void stopSync() {
        syncEnabled = false;
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            try {
                syncScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                syncScheduler.shutdownNow();
            }
            syncScheduler = null;
        }
        syncCallback = null;
        System.out.println("Area sync stopped");
    }

    public void forceFullSync(String profile) throws SQLException {
        lastSyncTime = null;
        Map<Integer, NArea> allAreas = loadAreas(profile);
        if (syncCallback != null) {
            syncCallback.onFullSync(allAreas);
        }
    }

    /**
     * Pull-side check: pull DB version map, fetch full rows for anything
     * newer than our local copy, run three-way merge for any local
     * concurrent edits, and notify the callback for tombstones.
     */
    public List<NArea> checkForUpdatesAndMerge(String profile, Map<Integer, NArea> localAreas) throws SQLException {
        List<NArea> updatedAreas = new ArrayList<>();
        if (!databaseManager.isReady()) return updatedAreas;

        Map<Integer, AreaDao.AreaVersionInfo> dbVersions;
        try {
            dbVersions = databaseManager.executeOperation(
                adapter -> areaDao.getAllAreaVersions(adapter, profile));
        } catch (Exception e) {
            System.err.println("Area sync: Failed to get DB versions: " + e.getMessage());
            return updatedAreas;
        }

        for (Map.Entry<Integer, AreaDao.AreaVersionInfo> entry : dbVersions.entrySet()) {
            int areaId = entry.getKey();
            AreaDao.AreaVersionInfo info = entry.getValue();
            NArea local = localAreas.get(areaId);
            int localVersion = local != null ? local.version : 0;

            if (info.tombstoned) {
                // Notify callback that this area is gone (it'll dedupe).
                if (local != null && syncCallback != null) {
                    AreaSyncEvents.publish(new AreaSyncEvent(
                        AreaSyncEvent.Kind.DELETED, areaId, local.name,
                        null, null, null));
                    syncCallback.onAreaDeleted(areaId);
                }
                continue;
            }

            if (info.version <= localVersion) continue;

            // DB has newer version. Fetch full row, then merge against local.
            AreaDao.AreaData data = databaseManager.executeOperation(
                adapter -> areaDao.loadArea(adapter, areaId, profile));
            if (data == null) continue;

            if (local == null) {
                NArea fresh = convertToNArea(data);
                updatedAreas.add(fresh);
                AreaSyncEvents.publish(new AreaSyncEvent(
                    AreaSyncEvent.Kind.ADDED, fresh.id, fresh.name,
                    null, null, data.getLastTouchedBy()));
                continue;
            }

            // Three-way merge using local's baseline as common ancestor.
            AreaSnapshot remoteSnap = AreaSnapshot.fromStored(
                data.getName(), data.getPath(), data.isHide(),
                data.getColorR(), data.getColorG(), data.getColorB(), data.getColorA(),
                data.getData(), data.getVersion());

            Set<AreaFieldGroup> localDirty = AreaMerger.localDirtyGroups(local);
            if (localDirty.isEmpty()) {
                // No local concurrent changes - simple replace.
                NArea merged = convertToNArea(data);
                updatedAreas.add(merged);
                AreaSyncEvents.publish(new AreaSyncEvent(
                    AreaSyncEvent.Kind.AUTO_MERGED, merged.id, merged.name,
                    EnumSet.allOf(AreaFieldGroup.class), null, data.getLastTouchedBy()));
                continue;
            }

            // Concurrent edits - run the merger.
            String uuidSnap = local.uuid != null ? local.uuid : data.getUuid();
            AreaMerger.Result mergeResult = AreaMerger.merge(local, remoteSnap, areaId, uuidSnap);
            NArea merged = new NArea(mergeResult.mergedJson);
            merged.baselineVersion = data.getVersion();
            merged.baselineSnapshot = remoteSnap;
            merged.lastTouchedBy = data.getLastTouchedBy();
            if (data.getLastTouchedAt() != null) merged.lastTouchedAt = data.getLastTouchedAt().getTime();
            updatedAreas.add(merged);

            AreaSyncEvent.Kind kind = mergeResult.remoteOverrode.isEmpty()
                ? AreaSyncEvent.Kind.AUTO_MERGED
                : AreaSyncEvent.Kind.REMOTE_OVERRODE;
            AreaSyncEvents.publish(new AreaSyncEvent(
                kind, areaId, merged.name,
                mergeResult.takeRemote, mergeResult.remoteOverrode,
                data.getLastTouchedBy()));
        }

        return updatedAreas;
    }

    private static NArea convertToNArea(AreaDao.AreaData data) {
        JSONObject json = data.toJson();
        NArea area = new NArea(json);
        // Capture baseline so subsequent local edits compute dirty groups against this state.
        area.baselineVersion = data.getVersion();
        area.baselineSnapshot = AreaSnapshot.fromStored(
            data.getName(), data.getPath(), data.isHide(),
            data.getColorR(), data.getColorG(), data.getColorB(), data.getColorA(),
            data.getData(), data.getVersion());
        area.lastTouchedBy = data.getLastTouchedBy();
        if (data.getLastTouchedAt() != null) area.lastTouchedAt = data.getLastTouchedAt().getTime();
        area.dirtyGroups.clear();
        return area;
    }

    public boolean isSyncRunning() { return syncEnabled; }
    public Timestamp getLastSyncTime() { return lastSyncTime; }
    public void resetSyncState() { lastSyncTime = null; }

    /** Called by user-facing edit sites so presence/poll cadence can react. */
    public void markLocalEdit() {
        this.lastLocalEditAt = System.currentTimeMillis();
    }

    public long getLastLocalEditAt() { return lastLocalEditAt; }

    private Map<Integer, NArea> getLocalAreasSnapshot() {
        Map<Integer, NArea> result = new HashMap<>();
        try {
            if (nurgling.NUtils.getGameUI() != null &&
                nurgling.NUtils.getGameUI().map != null &&
                nurgling.NUtils.getGameUI().map.glob != null &&
                nurgling.NUtils.getGameUI().map.glob.map != null) {
                Map<Integer, NArea> areas = nurgling.NUtils.getGameUI().map.glob.map.areas;
                if (areas != null) {
                    // Defensive copy under the same lock used for mutation.
                    synchronized (areas) {
                        result.putAll(areas);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private String getCurrentProfile() {
        try {
            if (nurgling.NUtils.getGameUI() != null) {
                String genus = nurgling.NUtils.getGameUI().getGenus();
                if (genus != null && !genus.isEmpty()) return genus;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** Best-effort player name for the last_touched_by column. */
    private static String currentPlayerName() {
        try {
            if (nurgling.NUtils.getUI() != null && nurgling.NUtils.getUI().sess != null
                && nurgling.NUtils.getUI().sess.user != null) {
                String name = nurgling.NUtils.getUI().sess.user.name;
                if (name != null && !name.isEmpty()) return name;
            }
            if (nurgling.NUtils.getGameUI() != null && nurgling.NUtils.getGameUI().chrid != null) {
                return nurgling.NUtils.getGameUI().chrid;
            }
        } catch (Exception ignore) {
        }
        return "unknown";
    }
}
