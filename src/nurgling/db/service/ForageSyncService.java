package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.ForageFindDao;
import nurgling.forage.ForageFind;
import nurgling.forage.ForageStore;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps each world's {@link ForageStore} in step with the {@code forage_finds} table.
 *
 * <p>Works per world like {@link TimerSyncService}: every session on a world shares one store. Each tick
 * uploads the store's pending finds and deletes, then bulk-loads (first tick for a world) or asks for the
 * rows changed since the last look. Unlike timers it does not list every row's version on each poll:
 * Forager alone can record thousands of finds, so the poll goes by the database-clock {@code changed_at}.
 */
public class ForageSyncService {
    private static final int TOMBSTONE_DAYS = 14;
    /**
     * How far back each poll looks past the previous one. A row is stamped when its statement runs but is
     * only visible once its transaction commits, so a poll could otherwise step over a row committed just
     * after it looked. Rows seen twice are dropped by version in {@link ForageStore#applyRemote}.
     */
    private static final long OVERLAP_MS = 10_000;
    /** Finds per upload statement; keeps the bound parameter count (12 per find) well under driver limits. */
    private static final int UPLOAD_BATCH = 200;

    private final DatabaseManager databaseManager;
    private final ForageFindDao dao = new ForageFindDao();

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService scheduler = null;

    /** Per world: the database time up to which changes have been applied. A world missing here gets a bulk load. */
    private final Map<String, Long> watermarks = new ConcurrentHashMap<>();

    public ForageSyncService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void startSync(long intervalSeconds) {
        if (syncEnabled)
            stopSync();
        syncEnabled = true;
        watermarks.clear();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Forage-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Forage find sync started, interval=" + intervalSeconds + "s");
    }

    public void stopSync() {
        syncEnabled = false;
        watermarks.clear();
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        System.out.println("Forage find sync stopped");
    }

    private void syncTick() {
        if (!syncEnabled || databaseManager == null || !databaseManager.isReady())
            return;
        for (ForageStore store : new ArrayList<>(SessionManager.getInstance().forageStores())) {
            try {
                syncWorld(store);
            } catch (SQLException | RuntimeException e) {
                // Bulk-load again next tick; the pending edits are still queued in the store.
                watermarks.remove(store.genus());
                System.err.println("Forage sync error (world=" + store.profile() + "): " + e.getMessage());
            }
        }
    }

    private void syncWorld(ForageStore store) throws SQLException {
        String profile = store.profile();
        String who = characterOn(store.genus());

        List<ForageStore.Pending<ForageFind>> uploads = store.pendingUpserts();
        for (int i = 0; i < uploads.size(); i += UPLOAD_BATCH) {
            List<ForageStore.Pending<ForageFind>> batch = uploads.subList(i, Math.min(uploads.size(), i + UPLOAD_BATCH));
            List<ForageFind> finds = new ArrayList<>(batch.size());
            for (ForageStore.Pending<ForageFind> p : batch)
                finds.add(p.item);
            Map<String, Integer> versions = databaseManager.executeOperation(adapter -> dao.insertAll(adapter, finds, profile));
            // Deleted by someone else before a (retried) upload arrived: those stay deleted.
            Set<String> gone = new HashSet<>();
            for (Map.Entry<String, Integer> e : versions.entrySet()) {
                if (e.getValue() < 0)
                    gone.add(e.getKey());
            }
            store.confirmUpserts(batch, versions);
            if (!gone.isEmpty())
                store.applyRemote(new ArrayList<>(), gone, false);
        }
        for (ForageStore.Pending<String> p : store.pendingDeletes()) {
            databaseManager.executeOperation(adapter -> {
                dao.tombstone(adapter, p.item, profile, who);
                return null;
            });
            store.confirmDelete(p.item, p.gen);
        }

        Long since = watermarks.get(store.genus());
        long dbNow = databaseManager.executeOperation(dao::now);
        List<ForageFindDao.Row> rows;
        if (since == null) {
            databaseManager.executeOperation(adapter -> {
                dao.purgeTombstones(adapter, profile, TOMBSTONE_DAYS);
                return null;
            });
            rows = databaseManager.executeOperation(adapter -> dao.loadAll(adapter, profile));
        } else {
            final long from = since;
            rows = databaseManager.executeOperation(adapter -> dao.loadChangedSince(adapter, profile, from));
        }
        List<ForageFind> live = new ArrayList<>();
        Set<String> tombstoned = new HashSet<>();
        for (ForageFindDao.Row r : rows) {
            if (r.tombstoned)
                tombstoned.add(r.find.id);
            else
                live.add(r.find);
        }
        if (since == null || !live.isEmpty() || !tombstoned.isEmpty())
            store.applyRemote(live, tombstoned, since == null);
        watermarks.put(store.genus(), dbNow - OVERLAP_MS);
    }

    /** A character of this client logged into the world, for deleted_by. */
    private static String characterOn(String genus) {
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if (ctx.characterName != null && genus.equals(ctx.genus == null ? "" : ctx.genus))
                return ctx.characterName;
        }
        return "unknown";
    }
}
