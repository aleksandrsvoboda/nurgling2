package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.TodoDao;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Database side of the shared To-Do list: thin wrappers over {@link TodoDao}, plus the poll loop.
 *
 * <p>The loop runs on its own thread and hands each live session's {@link nurgling.todo.TodoStore} one
 * {@link nurgling.todo.TodoStore#syncOnce} per tick. The store owns the pending edits and the merge; this
 * class only moves rows. Nothing here runs on the UI thread.
 */
public class TodoService {
    private final DatabaseManager databaseManager;
    private final TodoDao dao = new TodoDao();
    private ScheduledExecutorService scheduler = null;

    public TodoService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public synchronized void startSync(long intervalSeconds) {
        if (scheduler != null)
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Todo-Sync-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::syncTick, 1, intervalSeconds, TimeUnit.SECONDS);
        System.out.println("Todo sync started, interval=" + intervalSeconds + "s");
    }

    public synchronized void stopSync() {
        if (scheduler == null)
            return;
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        System.out.println("Todo sync stopped");
    }

    private void syncTick() {
        if (databaseManager == null || !databaseManager.isReady())
            return;
        for (nurgling.sessions.SessionContext sc : nurgling.sessions.SessionManager.getInstance().getAllSessions()) {
            if (sc == null || sc.ui == null)
                continue;
            nurgling.NGameUI gui = sc.getGameUI();
            if (gui == null || gui.todoStore == null)
                continue;
            /* A throw here would cancel the scheduled task for good, and with it sync for every session. */
            try {
                gui.todoStore.syncOnce(this);
            } catch (RuntimeException e) {
                System.err.println("Todo sync error: " + e);
                e.printStackTrace();
            }
        }
    }

    public List<TodoDao.Row> loadAll(String profile) throws SQLException {
        return databaseManager.executeOperation(a -> dao.loadAll(a, profile));
    }

    public Map<Integer, Integer> versions(String profile) throws SQLException {
        return databaseManager.executeOperation(a -> dao.versions(a, profile));
    }

    public List<TodoDao.Row> load(String profile, Collection<Integer> ids) throws SQLException {
        return databaseManager.executeOperation(a -> dao.load(a, profile, ids));
    }

    public TodoDao.Row loadOne(String profile, int id) throws SQLException {
        return databaseManager.executeOperation(a -> dao.loadOne(a, profile, id));
    }

    public boolean insert(String profile, int id, String path, String name, String data) throws SQLException {
        return databaseManager.executeOperation(a -> dao.insert(a, profile, id, path, name, data));
    }

    public int update(String profile, int id, String name, String data, int expectedVersion) throws SQLException {
        return databaseManager.executeOperation(a -> dao.update(a, profile, id, name, data, expectedVersion));
    }

    public boolean canWrite() throws SQLException {
        return databaseManager.executeOperation(dao::canWrite);
    }

    public int purgeTombstones(String profile, long cutoffMillis) throws SQLException {
        return databaseManager.executeOperation(a -> dao.purgeTombstones(a, profile, cutoffMillis));
    }
}
