package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.TimerDao;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import nurgling.timers.Timer;
import nurgling.timers.TimerStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps each world's {@link TimerStore} in step with the {@code timers} table.
 *
 * <p>Works per world, not per session: every session on a world shares one store, so walking the stores
 * that {@link SessionManager} has opened does each world once. Each tick pushes the store's pending edits,
 * then bulk-loads (first tick for a world) or polls the version map and fetches only changed rows.
 *
 * <p>Timers run for hours, so a 15 second poll is plenty; all of it runs on this service's own thread and
 * the game only ever reads the in-memory store.
 */
public class TimerSyncService {
    private static final int TOMBSTONE_DAYS = 14;
    /** Re-measure the database clock this often; drift between two machines changes slowly. */
    private static final long CLOCK_REFRESH_MS = 10 * 60 * 1000;
    private static final long CLOCK_TOLERANCE_MS = 2000;

    private final DatabaseManager databaseManager;
    private final TimerDao dao = new TimerDao();

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService scheduler = null;

    /** Per world: the row versions already applied. A world missing here gets a bulk load. */
    private final Map<String, Map<String, Integer>> knownVersions = new ConcurrentHashMap<>();
    private long clockOffset = 0;
    private long clockMeasuredAt = 0;

    public TimerSyncService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void startSync(long intervalSeconds) {
        if (syncEnabled)
            stopSync();
        syncEnabled = true;
        knownVersions.clear();
        clockMeasuredAt = 0;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Timer-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Timer sync started, interval=" + intervalSeconds + "s");
    }

    public void stopSync() {
        syncEnabled = false;
        knownVersions.clear();
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
        System.out.println("Timer sync stopped");
    }

    private void syncTick() {
        if (!syncEnabled || databaseManager == null || !databaseManager.isReady())
            return;
        try {
            long now = System.currentTimeMillis();
            if (now - clockMeasuredAt > CLOCK_REFRESH_MS) {
                long measured = databaseManager.executeOperation(dao::clockOffset);
                /* Each measurement jitters by the round trip. Only take a real change: a start time
                 * that moves by a few milliseconds reads as a different cycle and would undo a dismiss. */
                if (clockMeasuredAt == 0 || Math.abs(measured - clockOffset) > CLOCK_TOLERANCE_MS)
                    clockOffset = measured;
                clockMeasuredAt = now;
            }
        } catch (SQLException e) {
            System.err.println("Timer sync: could not read the database clock: " + e.getMessage());
            return;
        }
        for (TimerStore store : new ArrayList<>(SessionManager.getInstance().timerStores())) {
            try {
                syncWorld(store);
            } catch (SQLException | RuntimeException e) {
                // Bulk-load again next tick; the pending edits are still queued in the store.
                knownVersions.remove(store.genus());
                System.err.println("Timer sync error (world=" + profileOf(store) + "): " + e.getMessage());
            }
        }
    }

    private void syncWorld(TimerStore store) throws SQLException {
        String profile = profileOf(store);
        String touchedBy = characterOn(store.genus());
        long offset = clockOffset;

        for (TimerStore.Pending<Timer> p : store.pendingUpserts()) {
            Timer t = p.item;
            int version = databaseManager.executeOperation(adapter ->
                dao.upsert(adapter, t, t.startedAt + offset, profile, touchedBy));
            /* The version map is deliberately not advanced here: if the database kept a newer restart
             * over this one, the next poll has to fetch that row to put it right locally. Our own
             * accepted write comes back identical and costs one small fetch. */
            store.confirmUpsert(t.id, p.gen, version);
        }
        for (TimerStore.Pending<String> p : store.pendingDeletes()) {
            databaseManager.executeOperation(adapter -> {
                dao.tombstone(adapter, p.item, profile, touchedBy);
                return null;
            });
            store.confirmDelete(p.item, p.gen);
        }

        Map<String, Integer> known = knownVersions.get(store.genus());
        if (known == null) {
            bulkLoad(store, profile, offset);
        } else {
            poll(store, profile, offset, known);
        }
    }

    private void bulkLoad(TimerStore store, String profile, long offset) throws SQLException {
        databaseManager.executeOperation(adapter -> {
            dao.purgeTombstones(adapter, profile, TOMBSTONE_DAYS);
            return null;
        });
        List<TimerDao.Row> rows = databaseManager.executeOperation(adapter -> dao.loadAll(adapter, profile, offset));
        List<Timer> live = new ArrayList<>();
        Set<String> tombstoned = new HashSet<>();
        Map<String, Integer> versions = new HashMap<>();
        for (TimerDao.Row r : rows) {
            versions.put(r.timer.id, r.timer.version);
            if (r.tombstoned)
                tombstoned.add(r.timer.id);
            else
                live.add(r.timer);
        }
        store.applyRemote(live, tombstoned, true, TimerStore::isLocalCharacter);
        knownVersions.put(store.genus(), versions);
    }

    private void poll(TimerStore store, String profile, long offset, Map<String, Integer> known) throws SQLException {
        Map<String, TimerDao.VersionInfo> db = databaseManager.executeOperation(adapter -> dao.getAllVersions(adapter, profile));
        List<String> fetch = new ArrayList<>();
        Set<String> tombstoned = new HashSet<>();
        for (Map.Entry<String, TimerDao.VersionInfo> e : db.entrySet()) {
            Integer mine = known.get(e.getKey());
            if (mine != null && e.getValue().version <= mine)
                continue;
            known.put(e.getKey(), e.getValue().version);
            if (e.getValue().tombstoned)
                tombstoned.add(e.getKey());
            else
                fetch.add(e.getKey());
        }
        if (fetch.isEmpty() && tombstoned.isEmpty())
            return;
        List<TimerDao.Row> rows = databaseManager.executeOperation(adapter -> dao.loadByIds(adapter, profile, fetch, offset));
        List<Timer> live = new ArrayList<>();
        for (TimerDao.Row r : rows) {
            if (!r.tombstoned)
                live.add(r.timer);
        }
        store.applyRemote(live, tombstoned, false, TimerStore::isLocalCharacter);
    }

    private static String profileOf(TimerStore store) {
        return store.genus().isEmpty() ? "global" : store.genus();
    }

    /** A character of this client logged into the world, for last_touched_by. */
    private static String characterOn(String genus) {
        for (SessionContext ctx : SessionManager.getInstance().getAllSessions()) {
            if (ctx.characterName != null && genus.equals(ctx.genus == null ? "" : ctx.genus))
                return ctx.characterName;
        }
        return "unknown";
    }
}
