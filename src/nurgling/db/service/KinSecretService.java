package nurgling.db.service;

import nurgling.db.DatabaseManager;
import nurgling.db.dao.KinSecretDao;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service layer for shared hearth secrets.
 * <p>
 * Writes go through {@link DatabaseManager#executeWithRetry} so a transient connection problem
 * queues the publish instead of dropping it - a character's secret reaching the database late is
 * fine, never reaching it is not.
 */
public class KinSecretService {
    private final DatabaseManager databaseManager;
    private final KinSecretDao kinSecretDao;

    public KinSecretService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.kinSecretDao = new KinSecretDao();
    }

    /** Every secret published for a world. */
    public List<KinSecretDao.KinSecret> load(String profile) throws SQLException {
        return databaseManager.executeOperation(adapter -> kinSecretDao.loadByProfile(adapter, profile));
    }

    public CompletableFuture<List<KinSecretDao.KinSecret>> loadAsync(String profile) {
        return databaseManager.executeWithRetry(adapter -> kinSecretDao.loadByProfile(adapter, profile),
                                                "load kin secrets for " + profile);
    }

    /** Publish (or update) one character's secret. */
    public CompletableFuture<Void> publishAsync(String profile, String charName, String secret) {
        return databaseManager.executeWithRetry(adapter -> {
            kinSecretDao.upsert(adapter, profile, charName, secret);
            return (Void) null;
        }, "publish kin secret for " + charName);
    }

    /** Remove one character's secret, e.g. after the player clears it. */
    public CompletableFuture<Void> deleteAsync(String profile, String charName) {
        return databaseManager.executeWithRetry(adapter -> {
            kinSecretDao.delete(adapter, profile, charName);
            return (Void) null;
        }, "delete kin secret for " + charName);
    }
}
