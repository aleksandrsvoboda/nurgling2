package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.MapStreamCodec;
import nurgling.db.dao.MapDataDao;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Service layer for the shared map tables.
 *
 * <p>Every call here is synchronous and expects to be made from a worker thread - the map transfer
 * is a long single operation driven by a button, not a stream of small background writes, so there
 * is nothing to queue or retry in the background. {@link nurgling.tools.MapDbTransfer} is the only
 * caller and already runs off the UI thread with a progress window the player can cancel.
 */
public class MapDbService {
    private final DatabaseManager databaseManager;
    private final MapDataDao dao = new MapDataDao();

    public MapDbService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // ------------------------------------------------------------------ export

    /**
     * Store one batch of grid payloads, as its own transaction.
     *
     * <p>Batch rather than whole map so that the caller can upload as it splits its export and
     * never hold the map twice over, and so that Cancel means something: the batches are what the
     * interrupt check sits between. Grids are keyed globally and merged by mtime, so stopping part
     * way leaves a smaller upload, never an inconsistent one.
     */
    public void publishGridBatch(String profile, String uploader,
                                 List<MapStreamCodec.GridChunk> batch) throws SQLException {
        if (batch.isEmpty())
            return;
        databaseManager.executeOperation(adapter -> {
            dao.upsertGrids(adapter, profile, uploader, batch);
            return (Void) null;
        });
    }

    /**
     * Replace this player's placements and markers together, in one transaction.
     *
     * <p>They must not be able to land separately: placements are deleted before being rewritten,
     * and a set that was half replaced would describe two different segment origins at once, which
     * is precisely what makes an import fail.
     */
    public void publishLayout(String profile, String uploader,
                              List<MapDataDao.Placement> placements,
                              List<MapStreamCodec.MarkChunk> marks) throws SQLException {
        databaseManager.executeOperation(adapter -> {
            dao.replacePlacements(adapter, profile, uploader, placements);
            dao.replaceMarkers(adapter, profile, uploader,
                               (marks == null) ? List.of() : marks);
            return (Void) null;
        });
    }

    // ------------------------------------------------------------------ import

    /** Every grid the world has in the database, as gid -> mtime. */
    public Map<Long, Long> manifest(String profile) throws SQLException {
        return databaseManager.executeOperation(adapter -> dao.loadGridManifest(adapter, profile));
    }

    /** Players who have exported to this world, other than {@code exclude}. */
    public List<String> uploaders(String profile, String exclude) throws SQLException {
        return databaseManager.executeOperation(adapter -> dao.loadUploaders(adapter, profile, exclude));
    }

    /** One player's placement set, ordered by segment. */
    public List<MapDataDao.Placement> placements(String profile, String uploader) throws SQLException {
        return databaseManager.executeOperation(adapter -> dao.loadPlacements(adapter, profile, uploader));
    }

    /** Payloads for one page of grid ids. */
    public Map<Long, byte[]> payloads(String profile, List<Long> gids) throws SQLException {
        return databaseManager.executeOperation(adapter -> dao.loadGridPayloads(adapter, profile, gids));
    }

    /** One player's markers. */
    public List<MapDataDao.MarkerRow> markers(String profile, String uploader) throws SQLException {
        return databaseManager.executeOperation(adapter -> dao.loadMarkers(adapter, profile, uploader));
    }
}
