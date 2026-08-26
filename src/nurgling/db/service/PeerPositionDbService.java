package nurgling.db.service;

import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.dao.PeerPositionDao;
import nurgling.sessions.SessionContext;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes this client's characters' positions and reads back those of every other player on the
 * same database - which is not the same set as anyone's Kin list; sharing is scoped by database
 * access, not by kinship.
 *
 * <p>The tick is grouped <b>by profile, not by session</b>, and that is the whole reason this is
 * cheap. Every character a client is logged in as shares a world, so one tick is one batched upsert
 * carrying all of them plus one read of the profile - two round trips no matter how many sessions
 * are running. Doing it per session would multiply both by the session count for no new information.
 *
 * <p>Nothing here goes through {@link DatabaseManager#executeWithRetry}. A position that arrives late
 * is not worth having, so a failed tick is dropped and the next one sends current coordinates rather
 * than replaying stale ones out of a queue.
 */
public class PeerPositionDbService {
    private final DatabaseManager databaseManager;
    private final PeerPositionDao dao = new PeerPositionDao();

    private volatile boolean syncEnabled = false;
    private ScheduledExecutorService syncScheduler = null;

    public PeerPositionDbService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void startSync(long intervalSeconds) {
        if (syncEnabled) stopSync();
        this.syncEnabled = true;
        this.syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Peer-Position-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        syncScheduler.scheduleAtFixedRate(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Peer position sync started, interval=" + intervalSeconds + "s");
    }

    public void stopSync() {
        syncEnabled = false;
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
        System.out.println("Peer position sync stopped");
    }

    public boolean isSyncRunning() { return syncEnabled; }

    private void syncTick() {
        if (!syncEnabled) return;
        if (databaseManager == null || !databaseManager.isReady()) return;

        java.util.Collection<SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) return;

        // Group the live sessions by the world they are in; everything below is per world.
        Map<String, List<SessionContext>> byProfile = new LinkedHashMap<>();
        for (SessionContext sc : sessions) {
            if (sc == null || sc.ui == null) continue;
            NGameUI gui = sc.getGameUI();
            if (gui == null || gui.peerPositionService == null) continue;
            String profile = gui.getGenus();
            if (profile == null || profile.isEmpty()) profile = "global";
            byProfile.computeIfAbsent(profile, k -> new ArrayList<>()).add(sc);
        }

        for (Map.Entry<String, List<SessionContext>> e : byProfile.entrySet()) {
            try {
                tickProfile(e.getKey(), e.getValue());
            } catch (SQLException | RuntimeException ex) {
                String msg = ex.getMessage();
                /* "no such table" is SQLite and "does not exist" is PostgreSQL for the same thing,
                 * which the table check at startup should already have caught. Do not spam it. */
                if (msg != null && !msg.contains("no such table") && !msg.contains("no such column")
                    && !msg.contains("does not exist")) {
                    System.err.println("Peer position sync error (profile=" + e.getKey() + "): " + msg);
                }
            }
        }
    }

    private void tickProfile(String profile, List<SessionContext> sessions) throws SQLException {
        // Collect what each session wants to say. ThreadLocalUI is bound per session because
        // ownPush resolves "the player" through it.
        List<PeerPositionDao.Push> pushes = new ArrayList<>(sessions.size());
        for (SessionContext sc : sessions) {
            nurgling.sessions.ThreadLocalUI.set(sc.ui);
            try {
                PeerPositionDao.Push push = sc.getGameUI().peerPositionService.ownPush();
                if (push != null) pushes.add(push);
            } catch (RuntimeException ignore) {
                /* One session failing to work out where it is must not stop the others publishing,
                 * and must not stop anybody reading. */
            } finally {
                nurgling.sessions.ThreadLocalUI.clear();
            }
        }

        if (!pushes.isEmpty()) {
            databaseManager.executeOperation(adapter -> {
                dao.upsertBatch(adapter, profile, pushes);
                return (Void) null;
            });
        }

        List<PeerPositionDao.Row> rows = databaseManager.executeOperation(
            adapter -> dao.loadByProfile(adapter, profile));

        // One read, distributed to every session in this world; each filters out only itself.
        for (SessionContext sc : sessions) {
            NGameUI gui = sc.getGameUI();
            if (gui == null || gui.peerPositionService == null) continue;
            try {
                gui.peerPositionService.apply(rows, gui.chrid);
            } catch (RuntimeException ignore) {
            }
        }
    }

    /**
     * Withdraw one character's row. Called when the player turns sharing off, so that the marker
     * disappears for everyone rather than lingering until it ages out.
     */
    public void withdraw(String profile, String charName) {
        if (charName == null || charName.isEmpty()) return;
        databaseManager.executeWithRetry(adapter -> {
            dao.delete(adapter, profile, charName);
            return (Void) null;
        }, "withdraw position for " + charName);
    }

    /**
     * Withdraw the row of every logged-in character that is no longer publishing.
     *
     * <p>Config is resolved per session rather than once, because {@code NConfig} is per profile:
     * two characters on this client can legitimately disagree about whether they share. Deleting
     * every row on one session's say-so would take characters that are still sharing off everyone
     * else's map - a wider blast radius than the setting the player actually touched.
     */
    public void withdrawOptedOut() {
        java.util.Collection<SessionContext> sessions;
        try {
            sessions = nurgling.sessions.SessionManager.getInstance().getAllSessions();
        } catch (RuntimeException e) {
            return;
        }
        if (sessions == null) return;
        Map<String, String> done = new HashMap<>();
        for (SessionContext sc : sessions) {
            if (sc == null) continue;
            NGameUI gui = sc.getGameUI();
            if (gui == null || gui.chrid == null) continue;
            String profile = gui.getGenus();
            if (profile == null || profile.isEmpty()) profile = "global";
            if (done.put(profile + "|" + gui.chrid, "") != null) continue;

            boolean sharing;
            nurgling.sessions.ThreadLocalUI.set(sc.ui);
            try {
                sharing = Boolean.TRUE.equals(nurgling.NConfig.get(nurgling.NConfig.Key.ndbenable))
                       && Boolean.TRUE.equals(nurgling.NConfig.get(nurgling.NConfig.Key.sharePosition));
            } catch (RuntimeException e) {
                /* Unable to tell what this character wants: withdraw. Erring toward publishing less
                 * is the right way to be wrong about a setting that broadcasts someone's location. */
                sharing = false;
            } finally {
                nurgling.sessions.ThreadLocalUI.clear();
            }
            if (sharing) continue;

            withdraw(profile, gui.chrid);
            if (gui.peerPositionService != null) {
                gui.peerPositionService.clear();
                gui.peerPositionService.resetPush();
            }
        }
    }
}
