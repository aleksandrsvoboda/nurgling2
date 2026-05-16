package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.PlanningFolderDao;
import nurgling.db.dao.PlanningGhostDao;
import nurgling.db.dao.PlanningLayerDao;
import nurgling.planning.PlanningFolder;
import nurgling.planning.PlanningGhost;
import nurgling.planning.PlanningLayer;
import nurgling.planning.PlanningNode;
import nurgling.planning.PlanningSnapshot;
import nurgling.planning.PlanningFieldGroup;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service layer for Base planner DB sync. Owns three tables (folders, layers,
 * ghosts) but exposes a single sync loop and a single callback because the
 * in-memory tree depends on all three.
 *
 * Folders/layers use OCC + three-way merge ({@link PlanningMerger}). Ghosts
 * are atomic rows merged whole-row (LWW on field conflict, which is rare:
 * concurrent adds are independent inserts; deletes are tombstones).
 */
public class PlanningService {

    private static final int MAX_OCC_RETRIES = 4;

    private final DatabaseManager databaseManager;
    private final PlanningFolderDao folderDao;
    private final PlanningLayerDao layerDao;
    private final PlanningGhostDao ghostDao;

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;
    private PlanningSyncCallback syncCallback = null;
    private volatile long lastLocalEditAt = 0;
    private final Set<String> bulkLoadedSessions =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /** Snapshot of the whole tree for a profile, bundled for the bulk-load callback. */
    public static final class TreeSnapshot {
        public final List<PlanningFolder> folders;
        public final List<PlanningLayer> layers;
        public final List<PlanningGhost> ghosts;
        public final Map<String, String> ghostLayerByGhostId; // ghost.id -> layer.id

        public TreeSnapshot(List<PlanningFolder> folders, List<PlanningLayer> layers,
                            List<PlanningGhost> ghosts, Map<String, String> ghostLayerByGhostId) {
            this.folders = folders;
            this.layers = layers;
            this.ghosts = ghosts;
            this.ghostLayerByGhostId = ghostLayerByGhostId;
        }
    }

    public static final class SyncDelta {
        public final List<PlanningFolder> upsertedFolders = new ArrayList<>();
        public final List<PlanningLayer> upsertedLayers = new ArrayList<>();
        public final List<PlanningGhost> upsertedGhosts = new ArrayList<>();
        public final Map<String, String> ghostLayerByGhostId = new HashMap<>();
        public final Set<String> deletedFolderIds = new HashSet<>();
        public final Set<String> deletedLayerIds = new HashSet<>();
        public final Set<String> deletedGhostIds = new HashSet<>();
        public boolean isEmpty() {
            return upsertedFolders.isEmpty() && upsertedLayers.isEmpty() && upsertedGhosts.isEmpty()
                && deletedFolderIds.isEmpty() && deletedLayerIds.isEmpty() && deletedGhostIds.isEmpty();
        }
    }

    public interface PlanningSyncCallback {
        /** Called with the full server-side tree on first tick per session. */
        void onFullSync(TreeSnapshot snapshot);
        /** Called with incremental changes on subsequent ticks. */
        void onSyncDelta(SyncDelta delta);
    }

    public PlanningService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.folderDao = new PlanningFolderDao();
        this.layerDao = new PlanningLayerDao();
        this.ghostDao = new PlanningGhostDao();
    }

    // -------------------- Save (push) path --------------------

    public void saveFolder(PlanningFolder folder, String profile) throws SQLException {
        if (folder == null || profile == null) return;
        Set<PlanningFieldGroup> dirty = PlanningMerger.localDirtyGroups(folder);
        if (dirty.isEmpty() && folder.baselineVersion > 0) return;
        String touchedBy = currentPlayerName();

        for (int attempt = 0; attempt < MAX_OCC_RETRIES; attempt++) {
            final int expected = folder.baselineVersion;
            final String name = folder.name;
            final int order = folder.orderIndex;
            PlanningFolderDao.SaveResult result = databaseManager.executeOperation(adapter ->
                folderDao.saveOCC(adapter, folder.id, name, order, profile, expected, touchedBy));
            if (result.outcome != PlanningFolderDao.SaveOutcome.VERSION_CONFLICT) {
                folder.version = result.newVersion;
                folder.captureBaseline();
                return;
            }
            // Conflict: pull remote, merge, retry.
            PlanningFolderDao.FolderRow remote = databaseManager.executeOperation(adapter ->
                folderDao.loadIncludingTombstone(adapter, folder.id, profile));
            if (remote == null) { folder.baselineVersion = 0; folder.baselineSnapshot = null; continue; }
            if (remote.isTombstone()) return; // server deleted; we drop our change
            PlanningSnapshot remoteSnap = new PlanningSnapshot(
                remote.name, /*parent for folder*/ null, remote.orderIndex, remote.version);
            PlanningMerger.Result merged = PlanningMerger.merge(folder, remoteSnap);
            folder.name = merged.merged.name;
            folder.orderIndex = merged.merged.orderIndex;
            folder.version = remote.version;
            folder.baselineVersion = remote.version;
            folder.baselineSnapshot = remoteSnap;
            folder.dirtyGroups.clear();
            // Re-mark dirty against the new baseline so the retry actually pushes our changes.
            folder.dirtyGroups.addAll(PlanningMerger.localDirtyGroups(folder));
        }
        System.err.println("PlanningService.saveFolder: exceeded OCC retries for " + folder.id);
    }

    public void saveLayer(PlanningLayer layer, String profile) throws SQLException {
        if (layer == null || profile == null) return;
        Set<PlanningFieldGroup> dirty = PlanningMerger.localDirtyGroups(layer);
        if (dirty.isEmpty() && layer.baselineVersion > 0) return;
        String touchedBy = currentPlayerName();

        for (int attempt = 0; attempt < MAX_OCC_RETRIES; attempt++) {
            final int expected = layer.baselineVersion;
            final String parent = layer.parentId;
            final String name = layer.name;
            final int order = layer.orderIndex;
            PlanningLayerDao.SaveResult result = databaseManager.executeOperation(adapter ->
                layerDao.saveOCC(adapter, layer.id, parent, name, order, profile, expected, touchedBy));
            if (result.outcome != PlanningLayerDao.SaveOutcome.VERSION_CONFLICT) {
                layer.version = result.newVersion;
                layer.captureBaseline();
                return;
            }
            PlanningLayerDao.LayerRow remote = databaseManager.executeOperation(adapter ->
                layerDao.loadIncludingTombstone(adapter, layer.id, profile));
            if (remote == null) { layer.baselineVersion = 0; layer.baselineSnapshot = null; continue; }
            if (remote.isTombstone()) return;
            PlanningSnapshot remoteSnap = new PlanningSnapshot(
                remote.name, remote.parentFolderId, remote.orderIndex, remote.version);
            PlanningMerger.Result merged = PlanningMerger.merge(layer, remoteSnap);
            layer.name = merged.merged.name;
            layer.parentId = merged.merged.parentId;
            layer.orderIndex = merged.merged.orderIndex;
            layer.version = remote.version;
            layer.baselineVersion = remote.version;
            layer.baselineSnapshot = remoteSnap;
            layer.dirtyGroups.clear();
            layer.dirtyGroups.addAll(PlanningMerger.localDirtyGroups(layer));
        }
        System.err.println("PlanningService.saveLayer: exceeded OCC retries for " + layer.id);
    }

    public void saveGhost(PlanningGhost ghost, String layerId, String profile) throws SQLException {
        if (ghost == null || layerId == null || profile == null) return;
        String touchedBy = currentPlayerName();
        String sdtB64 = (ghost.sdt != null && ghost.sdt.length > 0)
            ? Base64.getEncoder().encodeToString(ghost.sdt) : null;
        // Ghosts are whole-row. Insert with expectedVersion=0; on conflict we
        // just overwrite (the row is functionally append-only in normal use).
        databaseManager.executeOperation(adapter -> {
            PlanningGhostDao.SaveResult r = ghostDao.saveOCC(adapter, ghost.id, layerId,
                ghost.resName, sdtB64, ghost.gridId, ghost.ox, ghost.oy, ghost.angle,
                profile, 0, touchedBy);
            return r;
        });
    }

    public void deleteFolder(String id, String profile) throws SQLException {
        final String by = currentPlayerName();
        databaseManager.executeOperation(adapter -> {
            folderDao.tombstone(adapter, id, profile, by);
            return null;
        });
    }

    public void deleteLayer(String id, String profile) throws SQLException {
        final String by = currentPlayerName();
        databaseManager.executeOperation(adapter -> {
            layerDao.tombstone(adapter, id, profile, by);
            return null;
        });
    }

    public void deleteGhost(String id, String profile) throws SQLException {
        final String by = currentPlayerName();
        databaseManager.executeOperation(adapter -> {
            ghostDao.tombstone(adapter, id, profile, by);
            return null;
        });
    }

    public CompletableFuture<Void> saveFolderAsync(PlanningFolder f, String profile) {
        return CompletableFuture.runAsync(() -> { try { saveFolder(f, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }
    public CompletableFuture<Void> saveLayerAsync(PlanningLayer l, String profile) {
        return CompletableFuture.runAsync(() -> { try { saveLayer(l, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }
    public CompletableFuture<Void> saveGhostAsync(PlanningGhost g, String layerId, String profile) {
        return CompletableFuture.runAsync(() -> { try { saveGhost(g, layerId, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }
    public CompletableFuture<Void> deleteFolderAsync(String id, String profile) {
        return CompletableFuture.runAsync(() -> { try { deleteFolder(id, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }
    public CompletableFuture<Void> deleteLayerAsync(String id, String profile) {
        return CompletableFuture.runAsync(() -> { try { deleteLayer(id, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }
    public CompletableFuture<Void> deleteGhostAsync(String id, String profile) {
        return CompletableFuture.runAsync(() -> { try { deleteGhost(id, profile); } catch (SQLException e) { throw new RuntimeException(e); } });
    }

    // -------------------- Load (bulk) path --------------------

    public TreeSnapshot loadAll(String profile) throws SQLException {
        List<PlanningFolderDao.FolderRow> fr = databaseManager.executeOperation(a -> folderDao.loadAll(a, profile));
        List<PlanningLayerDao.LayerRow> lr = databaseManager.executeOperation(a -> layerDao.loadAll(a, profile));
        List<PlanningGhostDao.GhostRow> gr = databaseManager.executeOperation(a -> ghostDao.loadAll(a, profile));

        // Visibility is NOT in the DB row — these objects come back with
        // visible=true; the caller (PlanningLayerManager) overlays its local
        // view file before exposing the tree to the UI.
        List<PlanningFolder> folders = new ArrayList<>(fr.size());
        for (PlanningFolderDao.FolderRow row : fr) {
            PlanningFolder f = new PlanningFolder(row.id, row.name, true, null);
            f.orderIndex = row.orderIndex;
            f.version = row.version;
            f.baselineVersion = row.version;
            f.baselineSnapshot = PlanningSnapshot.of(f);
            f.lastTouchedBy = row.lastTouchedBy;
            if (row.lastTouchedAt != null) f.lastTouchedAt = row.lastTouchedAt.getTime();
            folders.add(f);
        }

        List<PlanningLayer> layers = new ArrayList<>(lr.size());
        for (PlanningLayerDao.LayerRow row : lr) {
            PlanningLayer l = new PlanningLayer(row.id, row.name, true, row.parentFolderId);
            l.orderIndex = row.orderIndex;
            l.version = row.version;
            l.baselineVersion = row.version;
            l.baselineSnapshot = PlanningSnapshot.of(l);
            l.lastTouchedBy = row.lastTouchedBy;
            if (row.lastTouchedAt != null) l.lastTouchedAt = row.lastTouchedAt.getTime();
            layers.add(l);
        }

        List<PlanningGhost> ghosts = new ArrayList<>(gr.size());
        Map<String, String> ghostLayerByGhostId = new HashMap<>();
        for (PlanningGhostDao.GhostRow row : gr) {
            byte[] sdt = (row.sdtB64 != null && !row.sdtB64.isEmpty())
                ? Base64.getDecoder().decode(row.sdtB64) : null;
            PlanningGhost g = new PlanningGhost(
                row.id, row.resName, sdt, row.gridId, row.ox, row.oy, row.angle);
            ghosts.add(g);
            ghostLayerByGhostId.put(row.id, row.layerId);
        }

        return new TreeSnapshot(folders, layers, ghosts, ghostLayerByGhostId);
    }

    // -------------------- Bulk export (mid-session ndbenable toggle) --------------------

    /**
     * Push the entire in-memory tree to DB. Used when {@code ndbenable} flips
     * on mid-session: existing local content gets exported once, then sync
     * runs normally.
     */
    public int exportTreeToDatabase(Collection<PlanningNode> roots,
                                    Map<String, String> ghostLayerByGhostId,
                                    String profile) throws SQLException {
        int n = 0;
        for (PlanningNode node : roots) {
            if (node instanceof PlanningFolder) {
                PlanningFolder f = (PlanningFolder) node;
                saveFolder(f, profile);
                n++;
                for (PlanningLayer layer : f.layers) {
                    saveLayer(layer, profile);
                    n++;
                    for (PlanningGhost g : layer.ghosts) {
                        saveGhost(g, layer.id, profile);
                        n++;
                    }
                }
            } else if (node instanceof PlanningLayer) {
                PlanningLayer layer = (PlanningLayer) node;
                saveLayer(layer, profile);
                n++;
                for (PlanningGhost g : layer.ghosts) {
                    saveGhost(g, layer.id, profile);
                    n++;
                }
            }
        }
        return n;
    }

    // -------------------- Periodic sync poll --------------------

    public void startSync(long intervalSeconds, PlanningSyncCallback callback) {
        if (syncEnabled) stopSync();
        this.syncCallback = callback;
        this.syncEnabled = true;
        this.bulkLoadedSessions.clear();
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Planning-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleAtFixedRate(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Planning sync started, interval=" + intervalSeconds + "s (multi-session)");
    }

    public void stopSync() {
        syncEnabled = false;
        bulkLoadedSessions.clear();
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
        System.out.println("Planning sync stopped");
    }

    private void syncTick() {
        if (!syncEnabled) return;
        if (databaseManager == null || !databaseManager.isReady()) return;

        Collection<nurgling.sessions.SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (Exception e) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) return;

        Set<String> liveIds = new HashSet<>();
        for (nurgling.sessions.SessionContext s : sessions) {
            if (s != null && s.sessionId != null) liveIds.add(s.sessionId);
        }
        bulkLoadedSessions.retainAll(liveIds);

        for (nurgling.sessions.SessionContext sc : sessions) {
            if (sc == null || sc.ui == null || sc.sessionId == null) continue;
            nurgling.NGameUI gui = sc.getGameUI();
            if (gui == null) continue;

            String profile = gui.getGenus();
            if (profile == null || profile.isEmpty()) continue;

            nurgling.sessions.ThreadLocalUI.set(sc.ui);
            try {
                if (bulkLoadedSessions.add(sc.sessionId)) {
                    runBulkLoad(profile, sc.sessionId);
                } else {
                    runDeltaPoll(profile);
                }
            } catch (Exception e) {
                bulkLoadedSessions.remove(sc.sessionId);
                String msg = e.getMessage();
                if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")) {
                    System.err.println("Planning sync error (session=" + sc.sessionId + "): " + msg);
                }
            } finally {
                nurgling.sessions.ThreadLocalUI.clear();
            }
        }
    }

    private void runBulkLoad(String profile, String sessionId) throws SQLException {
        long t0 = System.currentTimeMillis();
        TreeSnapshot snap = loadAll(profile);
        System.out.println("Planning sync: bulk-loaded " + snap.folders.size() + " folders, "
            + snap.layers.size() + " layers, " + snap.ghosts.size() + " ghosts in "
            + (System.currentTimeMillis() - t0) + "ms (session=" + sessionId + ")");
        if (syncCallback != null) syncCallback.onFullSync(snap);
    }

    private void runDeltaPoll(String profile) throws SQLException {
        SyncDelta delta = computeDelta(profile);
        if (!delta.isEmpty() && syncCallback != null) {
            syncCallback.onSyncDelta(delta);
        }
    }

    /**
     * Compare DB versions against the in-memory tree (resolved via the
     * currently-bound ThreadLocalUI). Returns whatever needs to be applied
     * locally. Merge work for folders/layers happens here too.
     */
    private SyncDelta computeDelta(String profile) throws SQLException {
        SyncDelta delta = new SyncDelta();

        nurgling.NGameUI gui = nurgling.NUtils.getGameUI();
        if (gui == null || gui.ui == null || gui.ui.core == null) return delta;
        nurgling.planning.PlanningLayerManager mgr = gui.ui.core.planningLayer;
        if (mgr == null) return delta;

        // --- Folders ---
        Map<String, PlanningFolderDao.VersionInfo> dbFolders = databaseManager.executeOperation(
            a -> folderDao.getAllVersions(a, profile));
        for (Map.Entry<String, PlanningFolderDao.VersionInfo> e : dbFolders.entrySet()) {
            String id = e.getKey();
            PlanningFolderDao.VersionInfo info = e.getValue();
            PlanningFolder local = mgr.getFolder(id);
            int localVersion = (local != null) ? local.version : 0;
            if (info.tombstoned) {
                if (local != null) delta.deletedFolderIds.add(id);
                continue;
            }
            if (info.version <= localVersion) continue;
            PlanningFolderDao.FolderRow row = databaseManager.executeOperation(
                a -> folderDao.loadOne(a, id, profile));
            if (row == null) continue;
            // Default visible=true when we synthesize a fresh node; the
            // manager will preserve its existing local visibility if the node
            // already existed.
            PlanningFolder fresh = new PlanningFolder(row.id, row.name, true, null);
            fresh.orderIndex = row.orderIndex;
            fresh.version = row.version;
            fresh.baselineVersion = row.version;
            fresh.baselineSnapshot = PlanningSnapshot.of(fresh);
            fresh.lastTouchedBy = row.lastTouchedBy;
            if (row.lastTouchedAt != null) fresh.lastTouchedAt = row.lastTouchedAt.getTime();
            if (local != null && !PlanningMerger.localDirtyGroups(local).isEmpty()) {
                PlanningSnapshot remoteSnap = new PlanningSnapshot(
                    row.name, null, row.orderIndex, row.version);
                PlanningMerger.Result merged = PlanningMerger.merge(local, remoteSnap);
                fresh.name = merged.merged.name;
                fresh.orderIndex = merged.merged.orderIndex;
            }
            delta.upsertedFolders.add(fresh);
        }

        // --- Layers ---
        Map<String, PlanningLayerDao.VersionInfo> dbLayers = databaseManager.executeOperation(
            a -> layerDao.getAllVersions(a, profile));
        for (Map.Entry<String, PlanningLayerDao.VersionInfo> e : dbLayers.entrySet()) {
            String id = e.getKey();
            PlanningLayerDao.VersionInfo info = e.getValue();
            PlanningLayer local = mgr.getLayer(id);
            int localVersion = (local != null) ? local.version : 0;
            if (info.tombstoned) {
                if (local != null) delta.deletedLayerIds.add(id);
                continue;
            }
            if (info.version <= localVersion) continue;
            PlanningLayerDao.LayerRow row = databaseManager.executeOperation(
                a -> layerDao.loadOne(a, id, profile));
            if (row == null) continue;
            PlanningLayer fresh = new PlanningLayer(row.id, row.name, true, row.parentFolderId);
            fresh.orderIndex = row.orderIndex;
            fresh.version = row.version;
            fresh.baselineVersion = row.version;
            fresh.baselineSnapshot = PlanningSnapshot.of(fresh);
            fresh.lastTouchedBy = row.lastTouchedBy;
            if (row.lastTouchedAt != null) fresh.lastTouchedAt = row.lastTouchedAt.getTime();
            if (local != null && !PlanningMerger.localDirtyGroups(local).isEmpty()) {
                PlanningSnapshot remoteSnap = new PlanningSnapshot(
                    row.name, row.parentFolderId, row.orderIndex, row.version);
                PlanningMerger.Result merged = PlanningMerger.merge(local, remoteSnap);
                fresh.name = merged.merged.name;
                fresh.parentId = merged.merged.parentId;
                fresh.orderIndex = merged.merged.orderIndex;
            }
            delta.upsertedLayers.add(fresh);
        }

        // --- Ghosts ---
        Map<String, PlanningGhostDao.VersionInfo> dbGhosts = databaseManager.executeOperation(
            a -> ghostDao.getAllVersions(a, profile));
        for (Map.Entry<String, PlanningGhostDao.VersionInfo> e : dbGhosts.entrySet()) {
            String id = e.getKey();
            PlanningGhostDao.VersionInfo info = e.getValue();
            // We need the local version too; the manager keeps it inline on the ghost (no field today),
            // so we fall back to "if we already know this id, skip unless tombstoned or new version".
            boolean localHas = mgr.hasGhostById(id);
            if (info.tombstoned) {
                if (localHas) delta.deletedGhostIds.add(id);
                continue;
            }
            if (localHas) continue; // ghost rows are append-only-ish; skip if already present
            PlanningGhostDao.GhostRow row = databaseManager.executeOperation(
                a -> ghostDao.loadOne(a, id, profile));
            if (row == null) continue;
            byte[] sdt = (row.sdtB64 != null && !row.sdtB64.isEmpty())
                ? Base64.getDecoder().decode(row.sdtB64) : null;
            PlanningGhost g = new PlanningGhost(
                row.id, row.resName, sdt, row.gridId, row.ox, row.oy, row.angle);
            delta.upsertedGhosts.add(g);
            delta.ghostLayerByGhostId.put(g.id, row.layerId);
        }

        return delta;
    }

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
        } catch (Exception ignore) {}
        return "unknown";
    }
}
