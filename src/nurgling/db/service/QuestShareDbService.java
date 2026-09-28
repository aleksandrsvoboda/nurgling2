package nurgling.db.service;

import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.QuestShareDao;
import nurgling.sessions.SessionContext;
import nurgling.widgets.quest.VillageQuestStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes this client's characters' quests and reads back every other sharing character's.
 *
 * <p>Writes are driven by change: each session's {@link VillageQuestStore} carries what its character
 * wants published, and a write goes out once that has been quiet for {@link #WRITE_DEBOUNCE_MS} (or
 * has kept changing for {@link #WRITE_MAX_DELAY_MS}). Reads run every {@link #READ_INTERVAL_MS}, and
 * sooner when the Village tab is opened ({@link #requestRead}), but never more than once per
 * {@link #READ_ON_DEMAND_MS}. The heartbeat that tells readers a character is online rides on the
 * read tick.
 *
 * <p>Grouped by profile like {@link PeerPositionDbService}: every character of this client in one
 * world is one batched write and one read, however many sessions are open.
 *
 * <p>Everything runs on this service's single worker thread, including withdrawals, so a withdraw
 * can never be overtaken by a write that was already in flight.
 */
public class QuestShareDbService {
    private static final long TICK_MS = 1000;
    static final long WRITE_DEBOUNCE_MS = 2000;
    static final long WRITE_MAX_DELAY_MS = 10_000;
    static final long READ_INTERVAL_MS = 60_000;
    static final long READ_ON_DEMAND_MS = 10_000;
    /** Touch a character's row when it was last written longer ago than this. */
    static final long HEARTBEAT_MS = 50_000;
    static final int PURGE_DAYS = 30;

    private final DatabaseManager databaseManager;
    private final QuestShareDao dao = new QuestShareDao();

    private ScheduledExecutorService scheduler = null;
    private volatile boolean readRequested = false;

    /** Worker-thread-only state for one world. */
    private static final class ProfileState {
        /** Last data this client wrote per character, so unchanged snapshots cost nothing. */
        final Map<String, String> written = new HashMap<>();
        final Map<String, Long> writtenAt = new HashMap<>();
        /** Decoded rows by character, reused while their version does not move. */
        final Map<String, VillageQuestStore.Villager> cache = new HashMap<>();
        long lastRead = 0;
        Boolean canWrite = null;
        boolean purged = false;
    }

    private final Map<String, ProfileState> profiles = new HashMap<>();

    public QuestShareDbService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public synchronized void startSync() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Quest-Share-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 1000, TICK_MS, TimeUnit.MILLISECONDS);
        System.out.println("Quest share sync started");
    }

    public synchronized void stopSync() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        profiles.clear();
        System.out.println("Quest share sync stopped");
    }

    /** Read on the next tick rather than waiting for the interval; rate-limited. Any thread. */
    public void requestRead() {
        readRequested = true;
    }

    /**
     * Take one character's quests out of the database, on the worker so it lands after any write that
     * is already in flight. The caller must already have marked the session as not sharing, so no
     * later tick writes the row back.
     */
    public synchronized void withdraw(String profile, String charName) {
        if (scheduler == null || charName == null || charName.isEmpty()) {
            return;
        }
        final String p = profileKey(profile);
        scheduler.execute(() -> {
            ProfileState st = profiles.get(p);
            if (st != null) {
                st.written.remove(charName);
                st.writtenAt.remove(charName);
            }
            try {
                databaseManager.executeOperation(adapter -> {
                    dao.delete(adapter, p, charName);
                    return (Void) null;
                });
            } catch (SQLException | RuntimeException e) {
                /* The next read tick deletes rows of non-sharing characters anyway. */
                System.err.println("Quest share withdraw failed for " + charName + ": " + e.getMessage());
            }
            readRequested = true;
        });
    }

    private static String profileKey(String genus) {
        return (genus == null || genus.isEmpty()) ? "global" : genus;
    }

    private void tick() {
        if (databaseManager == null || !databaseManager.isReady()) {
            return;
        }
        Collection<SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        Map<String, List<NGameUI>> byProfile = new LinkedHashMap<>();
        for (SessionContext sc : sessions) {
            if (sc == null || sc.ui == null) continue;
            NGameUI gui = sc.getGameUI();
            if (gui == null || gui.villageQuests == null || gui.chrid == null || gui.chrid.isEmpty()) continue;
            byProfile.computeIfAbsent(profileKey(gui.getGenus()), k -> new ArrayList<>()).add(gui);
        }

        boolean wantRead = readRequested;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<NGameUI>> e : byProfile.entrySet()) {
            ProfileState st = profiles.computeIfAbsent(e.getKey(), k -> new ProfileState());
            try {
                tickProfile(e.getKey(), st, e.getValue(), now, wantRead);
            } catch (SQLException | RuntimeException ex) {
                /* A throw out of a scheduled task would cancel it for good, and with it every
                 * session's sharing. */
                String msg = ex.getMessage();
                if (msg == null || (!msg.contains("no such table") && !msg.contains("does not exist"))) {
                    System.err.println("Quest share sync error (profile=" + e.getKey() + "): " + msg);
                }
            }
        }
    }

    private void tickProfile(String profile, ProfileState st, List<NGameUI> guis, long now, boolean wantRead)
            throws SQLException {
        if (st.canWrite == null) {
            st.canWrite = databaseManager.executeOperation(dao::canWrite);
        }

        /* ---------- write what changed */
        List<QuestShareDao.Push> pushes = new ArrayList<>();
        Set<String> sharing = new HashSet<>();
        Set<String> notSharing = new HashSet<>();
        for (NGameUI gui : guis) {
            VillageQuestStore.Outgoing out = gui.villageQuests.outgoing();
            if (out == null || out.charName == null || !out.charName.equals(gui.chrid)) {
                continue;
            }
            if (!out.share) {
                notSharing.add(out.charName);
                continue;
            }
            sharing.add(out.charName);
            if (!st.canWrite || out.data == null || out.data.equals(st.written.get(out.charName))) {
                continue;
            }
            Long last = st.writtenAt.get(out.charName);
            boolean quiet = now - out.changedAt >= WRITE_DEBOUNCE_MS;
            boolean overdue = last != null && now - last >= WRITE_MAX_DELAY_MS;
            if (quiet || overdue) {
                pushes.add(new QuestShareDao.Push(out.charName, out.data));
            }
        }
        if (!pushes.isEmpty()) {
            databaseManager.executeOperation(adapter -> {
                dao.upsertBatch(adapter, profile, pushes);
                return (Void) null;
            });
            for (QuestShareDao.Push p : pushes) {
                st.written.put(p.charName, p.data);
                st.writtenAt.put(p.charName, now);
            }
        }

        /* ---------- read, heartbeat and clean up, once a minute or on request */
        boolean due = st.lastRead == 0
            || now - st.lastRead >= READ_INTERVAL_MS
            || (wantRead && now - st.lastRead >= READ_ON_DEMAND_MS);
        if (!due) {
            return;
        }
        readRequested = false;
        st.lastRead = now;

        List<String> touch = new ArrayList<>();
        if (st.canWrite) {
            for (String n : sharing) {
                Long last = st.writtenAt.get(n);
                if (last != null && now - last >= HEARTBEAT_MS) {
                    touch.add(n);
                }
            }
        }
        if (!touch.isEmpty()) {
            databaseManager.executeOperation(adapter -> {
                dao.touch(adapter, profile, touch);
                return (Void) null;
            });
            for (String n : touch) {
                st.writtenAt.put(n, now);
            }
        }

        List<QuestShareDao.Head> heads = databaseManager.executeOperation(a -> dao.loadHeads(a, profile));

        /* Opt-out wins: a character of ours that is not sharing must not have a row, whatever
         * happened to the delete that should have removed it (database down at the time, a crash). */
        List<String> stray = new ArrayList<>();
        Set<String> present = new HashSet<>();
        for (QuestShareDao.Head h : heads) {
            present.add(h.charName);
            if (st.canWrite && notSharing.contains(h.charName)) {
                stray.add(h.charName);
            }
        }
        if (!stray.isEmpty()) {
            databaseManager.executeOperation(adapter -> {
                for (String n : stray) {
                    dao.delete(adapter, profile, n);
                }
                return (Void) null;
            });
            present.removeAll(stray);
        }
        /* A row of ours that vanished (another machine purged it, a manual delete) must be written
         * again even though its content has not changed. */
        for (String n : sharing) {
            if (!present.contains(n)) {
                st.written.remove(n);
            }
        }

        List<String> fetch = new ArrayList<>();
        for (QuestShareDao.Head h : heads) {
            if (stray.contains(h.charName)) continue;
            VillageQuestStore.Villager v = st.cache.get(h.charName);
            if (v == null || v.version != h.version) {
                fetch.add(h.charName);
            }
        }
        Map<String, QuestShareDao.Body> bodies = fetch.isEmpty()
            ? new HashMap<>()
            : databaseManager.executeOperation(a -> dao.loadBodies(a, profile, fetch));

        Map<String, VillageQuestStore.Villager> next = new HashMap<>();
        for (QuestShareDao.Head h : heads) {
            if (stray.contains(h.charName)) continue;
            QuestShareDao.Body b = bodies.get(h.charName);
            VillageQuestStore.Villager v;
            if (b != null) {
                v = VillageQuestStore.Villager.of(h.charName, b.version, h.ageMillis, b.data);
            } else {
                VillageQuestStore.Villager old = st.cache.get(h.charName);
                if (old == null) continue;   // deleted between the two queries
                v = old.withAge(h.ageMillis);
            }
            next.put(h.charName, v);
        }
        st.cache.clear();
        st.cache.putAll(next);

        for (NGameUI gui : guis) {
            gui.villageQuests.apply(next.values(), gui.chrid, st.canWrite);
        }

        if (!st.purged && st.canWrite) {
            st.purged = true;
            int n = databaseManager.executeOperation(a -> dao.purgeOlderThan(a, profile, PURGE_DAYS));
            if (n > 0) {
                System.out.println("Quest share: purged " + n + " abandoned row(s) in " + profile);
            }
        }
    }
}
