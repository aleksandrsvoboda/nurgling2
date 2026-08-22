package nurgling.db.service;

import haven.Coord;
import nurgling.FishLocation;
import nurgling.FishLocationService;
import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.FishLocationDao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service layer for shared fish locations.
 *
 * <p>Records are immutable - created once, deleted once - so there is no merge engine here. A save is an
 * idempotent upsert on a deterministic id, a delete is a tombstone, and the {@code version} column exists
 * only so the delta poll can ask what changed.
 *
 * <p>The sync worker walks every live session on each tick, binding {@code ThreadLocalUI} so the per-session
 * {@link FishLocationService} it updates is the right one. The first tick for a session bulk-loads; later
 * ticks poll the version map.
 */
public class FishLocationDbService {
    private final DatabaseManager databaseManager;
    private final FishLocationDao dao = new FishLocationDao();

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;

    /** Sessions that have had their bulk load. Clearing forces every session to bulk-load again. */
    private final Set<String> bulkLoadedSessions =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Per-session view of the row versions we have already applied, so a poll only fetches changes. */
    private final ConcurrentHashMap<String, Map<String, Integer>> knownVersions = new ConcurrentHashMap<>();

    public FishLocationDbService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // -------------------- Write path --------------------

    public CompletableFuture<Void> upsertAsync(FishLocation loc, String profile) {
        if (!loc.hasGrid()) {
            // Nothing to key a row on. Only reachable for a legacy record that never got converted;
            // the seeder reports those rather than silently dropping them.
            return CompletableFuture.completedFuture(null);
        }
        final String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.upsert(adapter, loc.getLocationId(), loc.getGridId(),
                loc.getOffset().x, loc.getOffset().y,
                loc.getFishName(), loc.getFishResource(), loc.getPercentage(),
                loc.getGameTime(), loc.getMoonPhase(),
                loc.getFishingRod(), loc.getHook(), loc.getLine(), loc.getBait(),
                loc.getTimestamp(), profile, touchedBy);
            return (Void) null;
        }, "save fish location " + loc.getFishName());
    }

    public CompletableFuture<Void> tombstoneAsync(String id, String profile) {
        final String touchedBy = currentPlayerName();
        return databaseManager.executeWithRetry(adapter -> {
            dao.tombstone(adapter, id, profile, touchedBy);
            return (Void) null;
        }, "delete fish location " + id);
    }

    // -------------------- Read path --------------------

    public List<FishLocation> loadAll(String profile) throws SQLException {
        List<FishLocationDao.FishRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadAll(adapter, profile));
        List<FishLocation> out = new ArrayList<>(rows.size());
        for (FishLocationDao.FishRow row : rows) out.add(toLocation(row));
        return out;
    }

    /** Rehydrate a database row into the record the render path uses. */
    public static FishLocation toLocation(FishLocationDao.FishRow row) {
        return new FishLocation(row.id, row.gridId, new Coord(row.ox, row.oy),
            row.fishName, row.fishRes, row.percentage, row.caughtAt,
            row.gameTime, row.moonPhase, row.rod, row.hook, row.line, row.bait);
    }

    public FishLocationDao getDao() { return dao; }

    public DatabaseManager getDatabaseManager() { return databaseManager; }

    // -------------------- Sync --------------------

    public void startSync(long intervalSeconds) {
        if (syncEnabled) stopSync();
        this.syncEnabled = true;
        this.bulkLoadedSessions.clear();
        this.knownVersions.clear();
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Fish-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleAtFixedRate(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Fish location sync started, interval=" + intervalSeconds + "s (multi-session)");
    }

    public void stopSync() {
        syncEnabled = false;
        bulkLoadedSessions.clear();
        knownVersions.clear();
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            try {
                syncScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                syncScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            syncScheduler = null;
        }
        System.out.println("Fish location sync stopped");
    }

    public boolean isSyncRunning() { return syncEnabled; }

    /** Force every session to bulk-load again on its next tick. */
    public void requestReload() {
        bulkLoadedSessions.clear();
        knownVersions.clear();
    }

    private void syncTick() {
        if (!syncEnabled) return;
        if (databaseManager == null || !databaseManager.isReady()) return;

        java.util.Collection<nurgling.sessions.SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) return;

        // Drop tracking for sessions that are gone, so these maps don't grow across logouts.
        Set<String> liveIds = new HashSet<>();
        for (nurgling.sessions.SessionContext s : sessions) {
            if (s != null && s.sessionId != null) liveIds.add(s.sessionId);
        }
        bulkLoadedSessions.retainAll(liveIds);
        knownVersions.keySet().retainAll(liveIds);

        for (nurgling.sessions.SessionContext sc : sessions) {
            if (sc == null || sc.ui == null || sc.sessionId == null) continue;

            NGameUI gui = sc.getGameUI();
            if (gui == null || gui.fishLocationService == null) continue;

            String profile = gui.getGenus();
            if (profile == null || profile.isEmpty()) profile = "global";

            // Bind ThreadLocalUI so anything resolving the "current" session inside the sync gets this one.
            nurgling.sessions.ThreadLocalUI.set(sc.ui);
            try {
                // Push anything that was queued while the database was unavailable.
                gui.fishLocationService.flushPending();

                if (bulkLoadedSessions.add(sc.sessionId)) {
                    runBulkLoad(gui, profile, sc.sessionId);
                } else {
                    runDeltaPoll(gui, profile, sc.sessionId);
                }
            } catch (SQLException | RuntimeException e) {
                // Let the next tick retry (bulk-loading again if that is what failed).
                bulkLoadedSessions.remove(sc.sessionId);
                String msg = e.getMessage();
                // "no such table" is SQLite, "does not exist" is PostgreSQL: the same condition, and one
                // the table check at startup should already have caught. Do not spam it every tick.
                if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")
                    && !msg.contains("does not exist")) {
                    System.err.println("Fish sync error (session=" + sc.sessionId + "): " + msg);
                }
            } finally {
                nurgling.sessions.ThreadLocalUI.clear();
            }
        }
    }

    private void runBulkLoad(NGameUI gui, String profile, String sessionId) throws SQLException {
        long t0 = System.currentTimeMillis();
        List<FishLocationDao.FishRow> rows = databaseManager.executeOperation(
            adapter -> dao.loadAll(adapter, profile));

        List<FishLocation> locs = new ArrayList<>(rows.size());
        Map<String, Integer> versions = new HashMap<>();
        for (FishLocationDao.FishRow row : rows) {
            locs.add(toLocation(row));
            versions.put(row.id, row.version);
        }
        gui.fishLocationService.applyFullSync(locs);
        knownVersions.put(sessionId, versions);

        System.out.println("Fish sync: bulk-loaded " + locs.size() + " fish locations in "
            + (System.currentTimeMillis() - t0) + "ms (session=" + sessionId + ")");
    }

    private void runDeltaPoll(NGameUI gui, String profile, String sessionId) throws SQLException {
        Map<String, Integer> known = knownVersions.computeIfAbsent(sessionId, k -> new HashMap<>());

        Map<String, FishLocationDao.VersionInfo> dbVersions = databaseManager.executeOperation(
            adapter -> dao.getAllVersions(adapter, profile));

        List<String> fetch = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, FishLocationDao.VersionInfo> e : dbVersions.entrySet()) {
            String id = e.getKey();
            FishLocationDao.VersionInfo info = e.getValue();
            Integer localVersion = known.get(id);

            if (info.tombstoned) {
                if (localVersion != null) {
                    removed.add(id);
                    known.remove(id);
                }
                continue;
            }
            if (localVersion == null || info.version > localVersion) {
                fetch.add(id);
            }
        }

        if (fetch.isEmpty() && removed.isEmpty()) return;

        final List<String> toFetch = fetch;
        List<FishLocationDao.FishRow> rows = toFetch.isEmpty()
            ? Collections.emptyList()
            : databaseManager.executeOperation(adapter -> dao.loadByIds(adapter, profile, toFetch));

        List<FishLocation> updated = new ArrayList<>(rows.size());
        for (FishLocationDao.FishRow row : rows) {
            updated.add(toLocation(row));
            known.put(row.id, row.version);
        }

        gui.fishLocationService.applyDelta(updated, removed);
    }

    /** Best-effort player name for the last_touched_by column. */
    static String currentPlayerName() {
        try {
            if (nurgling.NUtils.getUI() != null && nurgling.NUtils.getUI().sess != null
                && nurgling.NUtils.getUI().sess.user != null) {
                String name = nurgling.NUtils.getUI().sess.user.name;
                if (name != null && !name.isEmpty()) return name;
            }
            if (nurgling.NUtils.getGameUI() != null && nurgling.NUtils.getGameUI().chrid != null) {
                return nurgling.NUtils.getGameUI().chrid;
            }
        } catch (RuntimeException ignore) {
        }
        return "unknown";
    }
}
