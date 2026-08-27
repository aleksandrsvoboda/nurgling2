package nurgling.db;

import nurgling.NConfig;
import nurgling.db.service.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main database manager that provides unified access to all database operations.
 * Manages connection pool, database adapters, and service layer.
 */
public class DatabaseManager {
    private ExecutorService executorService;
    private final int threadPoolSize;
    private ConnectionPoolManager connectionPoolManager;
    private DatabaseAdapter adapter;
    private volatile boolean initialized = false;
    private volatile boolean shutdown = false;

    // Service layer
    private RecipeService recipeService;
    private FavoriteRecipeService favoriteRecipeService;
    private ContainerService containerService;
    private StorageItemService storageItemService;
    private AreaService areaService;
    private nurgling.db.service.PlanningService planningService;
    private KinSecretService kinSecretService;
    private nurgling.db.service.FishLocationDbService fishLocationService;
    private nurgling.db.service.PeerPositionDbService peerPositionService;
    private nurgling.db.service.FishLocationSeeder fishLocationSeeder;
    private nurgling.db.service.MapDbService mapDbService;
    private nurgling.db.service.VillagerService villagerService;

    /**
     * Optional migrations the database refused, as version -> reason. Their features report
     * themselves unavailable; everything else initialises normally.
     */
    private volatile java.util.Map<Integer, String> skippedMigrations = java.util.Collections.emptyMap();

    /* Which tables this role can actually see, and the schema version, read once per connect.
     * information_schema already filters by privilege, so membership here means the same thing the
     * old per-table probe meant - at one round trip for all of them instead of one each. */
    private volatile java.util.Set<String> visibleTables = java.util.Collections.emptySet();
    private volatile int schemaVersionSeen = -1;

    // Task queue for retry logic
    private final BlockingQueue<QueuedTask<?>> taskQueue = new LinkedBlockingQueue<>(1000);
    private ScheduledExecutorService queueProcessor;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000; // 2 seconds between retries
    private final AtomicInteger queuedTaskCount = new AtomicInteger(0);
    
    // ========== DEBUG STATISTICS ==========
    private static final AtomicInteger totalOperations = new AtomicInteger(0);
    private static final AtomicInteger operationsPerSecond = new AtomicInteger(0);
    private static final AtomicInteger lastSecondOperations = new AtomicInteger(0);
    private static final AtomicInteger pendingTasks = new AtomicInteger(0);
    private static final AtomicInteger failedOperations = new AtomicInteger(0);
    private static final AtomicInteger skippedByCache = new AtomicInteger(0);
    private static final AtomicInteger skippedContainerCache = new AtomicInteger(0);
    private static final AtomicInteger skippedRecipeCache = new AtomicInteger(0);
    private static final AtomicInteger skippedSearchCache = new AtomicInteger(0);
    private static volatile long lastStatsResetTime = System.currentTimeMillis();
    
    /**
     * Debug statistics holder for UI display
     */
    public static class DbStats {
        public int totalOps;
        public int opsPerSecond;
        public int pending;
        public int failed;
        public int skippedCache;
        public int skippedContainer;
        public int skippedRecipe;
        public int skippedSearch;
        public int queueSize;
        public boolean isReady;
        
        @Override
        public String toString() {
            return String.format("DB: %d/s | Total: %d | Pending: %d | Queue: %d | Skip: %d | Fail: %d | %s",
                opsPerSecond, totalOps, pending, queueSize, skippedCache, failed, 
                isReady ? "READY" : "NOT READY");
        }
    }
    
    /**
     * Get current database statistics for debug display
     */
    public static DbStats getStats() {
        DbStats stats = new DbStats();
        stats.totalOps = totalOperations.get();
        stats.opsPerSecond = operationsPerSecond.get();
        stats.pending = pendingTasks.get();
        stats.failed = failedOperations.get();
        stats.skippedCache = skippedByCache.get();
        stats.skippedContainer = skippedContainerCache.get();
        stats.skippedRecipe = skippedRecipeCache.get();
        stats.skippedSearch = skippedSearchCache.get();
        stats.queueSize = nurgling.NCore.databaseManager != null ? 
            nurgling.NCore.databaseManager.getQueuedTaskCount() : 0;
        stats.isReady = nurgling.NCore.databaseManager != null && 
            nurgling.NCore.databaseManager.isReady();
        return stats;
    }
    
    /**
     * Increment skipped by cache counter (called from caching layers)
     * @deprecated Use specific methods instead
     */
    public static void incrementSkippedByCache() {
        skippedByCache.incrementAndGet();
    }
    
    /** Skip from container item cache */
    public static void incrementSkippedContainer() {
        skippedByCache.incrementAndGet();
        skippedContainerCache.incrementAndGet();
    }
    
    /** Skip from recipe cache */
    public static void incrementSkippedRecipe() {
        skippedByCache.incrementAndGet();
        skippedRecipeCache.incrementAndGet();
    }
    
    /** Skip from search query cache */
    public static void incrementSkippedSearch() {
        skippedByCache.incrementAndGet();
        skippedSearchCache.incrementAndGet();
    }
    
    /**
     * Update operations per second (call periodically)
     */
    private static void updateOpsPerSecond() {
        long now = System.currentTimeMillis();
        if (now - lastStatsResetTime >= 1000) {
            operationsPerSecond.set(lastSecondOperations.getAndSet(0));
            lastStatsResetTime = now;
        }
    }
    
    /**
     * Record a completed operation
     */
    private static void recordOperation() {
        totalOperations.incrementAndGet();
        lastSecondOperations.incrementAndGet();
        updateOpsPerSecond();
    }
    
    /**
     * Record a failed operation
     */
    private static void recordFailure() {
        failedOperations.incrementAndGet();
    }
    
    /**
     * Wrapper for queued database tasks with retry support
     */
    private static class QueuedTask<T> {
        final DatabaseOperation<T> operation;
        final CompletableFuture<T> future;
        final String description;
        int retryCount = 0;
        long nextRetryTime = 0;
        
        QueuedTask(DatabaseOperation<T> operation, CompletableFuture<T> future, String description) {
            this.operation = operation;
            this.future = future;
            this.description = description;
        }
    }

    public DatabaseManager(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "DB-Worker");
            t.setDaemon(true);  // Daemon threads don't prevent JVM shutdown
            return t;
        });
        startQueueProcessor();
        initialize();
        
        // Register shutdown hook to ensure clean shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdown) {
                System.out.println("[DatabaseManager] Shutdown hook triggered, cleaning up...");
                shutdown();
            }
        }, "DB-Shutdown-Hook"));
    }
    
    /**
     * Start the background queue processor
     */
    private void startQueueProcessor() {
        queueProcessor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DB-Queue-Processor");
            t.setDaemon(true);
            return t;
        });
        
        queueProcessor.scheduleWithFixedDelay(() -> {
            if (shutdown || !initialized) return;
            
            try {
                processQueuedTasks();
            } catch (Exception e) {
                System.err.println("[DatabaseManager] Queue processor error: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    /**
     * Process queued tasks that are ready for retry
     */
    private void processQueuedTasks() {
        if (taskQueue.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        int processed = 0;
        int maxToProcess = 10; // Process up to 10 tasks per cycle
        
        while (processed < maxToProcess && !taskQueue.isEmpty()) {
            QueuedTask<?> task = taskQueue.peek();
            if (task == null) break;
            
            // Check if it's time to retry
            if (task.nextRetryTime > now) {
                break; // Tasks are ordered by time, so no point checking others
            }
            
            // Remove from queue and process
            task = taskQueue.poll();
            if (task == null) break;
            
            processTask(task);
            processed++;
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> void processTask(QueuedTask<T> task) {
        try {
            T result = executeOperation(task.operation);
            task.future.complete(result);
            queuedTaskCount.decrementAndGet();
        } catch (SQLException e) {
            task.retryCount++;
            if (task.retryCount < MAX_RETRIES) {
                // Schedule for retry
                task.nextRetryTime = System.currentTimeMillis() + RETRY_DELAY_MS * task.retryCount;
                taskQueue.offer(task);
                System.out.println("[DatabaseManager] Task '" + task.description + "' failed, retry " + 
                    task.retryCount + "/" + MAX_RETRIES + " scheduled");
            } else {
                // Max retries exceeded
                task.future.completeExceptionally(e);
                queuedTaskCount.decrementAndGet();
                System.err.println("[DatabaseManager] Task '" + task.description + "' failed after " + 
                    MAX_RETRIES + " retries: " + e.getMessage());
            }
        }
    }

    /**
     * Initialize database manager with connection pool and services
     */
    private synchronized void initialize() {
        if (initialized) {
            return;
        }

        if (!(Boolean) NConfig.get(NConfig.Key.ndbenable)) {
            return;
        }

        try {
            // Initialize connection pool manager
            this.connectionPoolManager = new ConnectionPoolManager();

            // Get a connection to create adapter and run migrations
            Connection conn = connectionPoolManager.getConnection();

            if (conn == null && DatabaseAdapterFactory.isPostgres()) {
                /* The most likely reason a first-time setup gets nowhere: the server is running and
                 * the credentials are right, but nobody ever created the database - its name is a
                 * constant in the URL, so only the bundled compose file creates it as a side effect.
                 * Decided from the failure the pool already recorded, so an unreachable server costs
                 * nothing extra on this path - which runs on the UI thread. */
                DatabaseBootstrap boot =
                    DatabaseBootstrap.createIfMissing(connectionPoolManager.getLastError());
                if (boot.result == DatabaseBootstrap.Result.CREATED) {
                    notifyPlayer("Created database " + ConnectionString.DEFAULT_DATABASE
                        + " and setting it up", java.awt.Color.YELLOW);
                    conn = connectionPoolManager.getConnection();
                } else if (boot.result == DatabaseBootstrap.Result.FAILED) {
                    notifyPlayer("Database unavailable: " + boot.detail, java.awt.Color.ORANGE);
                }
            }

            if (conn != null) {
                this.adapter = DatabaseAdapterFactory.createAdapter(conn);

                try {
                    // Run migrations FIRST using this connection
                    this.skippedMigrations = runMigrations(conn);

                    // One query up front; everything below reads it instead of asking per table.
                    loadSchemaSnapshot();

                    // Initialize services after migrations
                    initializeServices();

                    initialized = true;
                    reportReady();
                    /* After initialized = true, because this goes through the normal task path. It
                     * fills the Villagers panel's "last seen" column, which is what tells a host
                     * whether an account is still in use before they delete it. */
                    if (DatabaseAdapterFactory.isPostgres()) {
                        /* Both off the UI thread. They still run on every connect - an existing
                         * village is at the latest version yet still has the ungranted init.sql
                         * tables and sequences - just not while the player waits. */
                        repairPermissionsAsync();
                        if (villagerService != null) {
                            villagerService.ensureBookkeepingAsync();
                        }
                    }
                    System.out.println("DatabaseManager initialized successfully with " +
                                     DatabaseAdapterFactory.getDatabaseType());
                    reportSkippedMigrations();
                } catch (nurgling.db.migration.MigrationManager.SchemaTooNewException stne) {
                    // Schema mismatch - leave manager uninitialized so sync skips itself.
                    // Surface the error to any active game UI.
                    try {
                        if (nurgling.NUtils.getGameUI() != null) {
                            nurgling.NUtils.getGameUI().msg("Area sync disabled: " + stne.getMessage(),
                                java.awt.Color.RED);
                        }
                    } catch (Exception ignore) {}
                } finally {
                    connectionPoolManager.returnConnection(conn);
                }
            } else {
                System.err.println("Failed to initialize DatabaseManager: cannot get database connection");
            }
        } catch (Exception e) {
            /* The remaining way a first-time setup dies: the account can reach the database but may
             * not create anything in it, because somebody else owns it. That reads as an ordinary
             * migration failure in the log, and as nothing at all in the game. */
            if (e instanceof SQLException && "42501".equals(((SQLException) e).getSQLState())) {
                notifyPlayer("This account cannot create tables in "
                    + ConnectionString.DEFAULT_DATABASE
                    + ". Give it ownership of the database, or connect as the account that owns it.",
                    java.awt.Color.ORANGE);
            }
            System.err.println("Failed to initialize DatabaseManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initialize service layer
     */
    private void initializeServices() {
        // Services whose table could not be created are left null; callers treat that as
        // "feature unavailable" rather than "database down".
        /* Always present: managing accounts has to keep working on a database that is only half
         * set up, and the panel that uses it is exactly where a host goes to fix that. The service
         * degrades internally when its bookkeeping table is absent. */
        this.villagerService = new nurgling.db.service.VillagerService(this);
        this.recipeService = new RecipeService(this);
        this.favoriteRecipeService = new FavoriteRecipeService(this);
        this.containerService = new ContainerService(this);
        this.storageItemService = new StorageItemService(this);
        this.areaService = new AreaService(this);
        this.planningService = new nurgling.db.service.PlanningService(this);
        this.kinSecretService =
            skippedMigrations.containsKey(nurgling.db.migration.MigrationManager.MIGRATION_KIN_SECRETS)
                ? null : new KinSecretService(this);
        /* Ask the database whether the table is really there rather than trusting the skipped map.
         * Two ways it can be absent while nothing is reported as skipped:
         *   - an earlier OPTIONAL migration failed, which breaks the migration loop, so migration 10
         *     is deferred and never appears in skippedMigrations at all;
         *   - on PostgreSQL the table exists but this role has no privileges on it, in which case
         *     information_schema hides it and every query would fail anyway.
         * Both must degrade to "fish stay on their JSON file", never to "fish silently disappear". */
        boolean fishOk = tableUsable("fish_locations");
        this.fishLocationService = fishOk ? new nurgling.db.service.FishLocationDbService(this) : null;
        this.fishLocationSeeder = fishOk ? new nurgling.db.service.FishLocationSeeder(this) : null;
        if (!fishOk) {
            System.err.println("[DatabaseManager] fish_locations unavailable; "
                + "fish locations stay on their JSON file");
        }
        /* Checked the same way as fish_locations, and for the same two reasons: an earlier optional
         * migration failing defers this one without ever listing it as skipped, and on PostgreSQL a
         * table this role has no privileges on is hidden rather than reported. Either way the map
         * window keeps its file-based Export/Import and only the database buttons go quiet. */
        /* Checked like the others: an earlier optional migration failing defers this one without
         * listing it as skipped, and on PostgreSQL a table this role cannot touch is hidden rather
         * than reported. Either way the map simply stops showing other players. */
        boolean peerPosOk = tableUsable("peer_positions");
        this.peerPositionService = peerPosOk ? new nurgling.db.service.PeerPositionDbService(this) : null;
        if (!peerPosOk) {
            System.err.println("[DatabaseManager] peer_positions unavailable; "
                + "live player positions will not be shown");
        }

        boolean mapOk = tableUsable("map_grids")
            && tableUsable("map_grid_placements")
            && tableUsable("map_markers");
        this.mapDbService = mapOk ? new nurgling.db.service.MapDbService(this) : null;
        if (!mapOk) {
            System.err.println("[DatabaseManager] shared map tables unavailable; "
                + "map sharing stays on Export.../Import... files");
        }
    }

    /**
     * Whether a table is present AND reachable by this connection's role. Any failure answers "no":
     * a feature that cannot verify its own table must not be wired up.
     */
    /**
     * Read the whole schema in one go.
     *
     * <p>Called on the UI thread, so the count of round trips is what the player feels: this used to
     * be six separate probes, which is nothing locally and over a second against a server 180ms
     * away.
     */
    private void loadSchemaSnapshot() {
        java.util.Set<String> tables = new java.util.HashSet<>();
        int version = -1;
        try (java.sql.ResultSet rs = adapter.executeQuery(
                "SELECT (SELECT COALESCE(MAX(version), 0) FROM schema_version) AS ver,"
              + "       (SELECT string_agg(table_name, ',') FROM information_schema.tables"
              + "        WHERE table_schema = 'public') AS tabs")) {
            if (rs.next()) {
                version = rs.getInt(1);
                String tabs = rs.getString(2);
                if (tabs != null) {
                    java.util.Collections.addAll(tables, tabs.split(","));
                }
            }
        } catch (SQLException e) {
            System.err.println("[DatabaseManager] could not read the schema: " + e.getMessage());
        }
        this.visibleTables = tables;
        this.schemaVersionSeen = version;
    }

    private boolean tableUsable(String table) {
        return visibleTables.contains(table);
    }

    /**
     * Say, once, that setup finished - and name anything that did not get made.
     *
     * <p>Until now the whole of setup reported itself only to stderr, so "it silently does not sync"
     * and "it worked" looked identical from inside the game.
     */
    private void reportReady() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String table : nurgling.db.migration.MigrationManager.expectedTables()) {
            if (!visibleTables.contains(table)) {
                missing.add(table);
            }
        }

        if (missing.isEmpty()) {
            String line = "Database ready - schema v" + schemaVersionSeen
                        + ", " + visibleTables.size() + " tables";
            System.out.println("[DatabaseManager] " + line);
            notifyPlayer(line, java.awt.Color.GREEN);
        } else {
            /* Present-but-unreadable looks exactly like absent from here, because PostgreSQL hides a
             * table this role has no privileges on. Both need the same thing said. */
            String line = "Database set up, but " + missing.size() + " table(s) are missing or not"
                        + " readable by this account: " + String.join(", ", missing);
            System.err.println("[DatabaseManager] " + line);
            notifyPlayer(line, java.awt.Color.ORANGE);
        }
    }

    /**
     * Bring grants up to date without making the player wait for it.
     *
     * <p>Fifteen round trips - every {@code guarded()} statement is a savepoint, the statement, and a
     * release - which is nothing locally and nearly three seconds against a server 180ms away. It
     * fixes privileges for <em>other</em> accounts, so nothing in this session needs it to have
     * finished, and tables created during migration are granted inline by {@code createTable}
     * regardless.
     */
    private void repairPermissionsAsync() {
        executeWithRetry(adapter -> {
            nurgling.db.migration.MigrationManager.repairPermissions(adapter);
            return (Void) null;
        }, "repair permissions");
    }

    /** Post a line to the game window when there is one; the console always gets it either way. */
    private static void notifyPlayer(String text, java.awt.Color color) {
        try {
            if (nurgling.NUtils.getGameUI() != null) {
                nurgling.NUtils.getGameUI().msg(text, color);
            }
        } catch (Exception ignore) {
            // Reporting must never be what breaks startup.
        }
    }

    /** Tell the player once which feature the database would not let this client set up. */
    private void reportSkippedMigrations() {
        for (java.util.Map.Entry<Integer, String> e : skippedMigrations.entrySet()) {
            String feature;
            if (e.getKey() == nurgling.db.migration.MigrationManager.MIGRATION_KIN_SECRETS) {
                feature = "Kin secret sync";
            } else if (e.getKey() == nurgling.db.migration.MigrationManager.MIGRATION_FISH_LOCATIONS) {
                feature = "Fish location sync";
            } else if (e.getKey() == nurgling.db.migration.MigrationManager.MIGRATION_MAP_DATA) {
                feature = "Map sharing";
            } else if (e.getKey() == nurgling.db.migration.MigrationManager.MIGRATION_PEER_POSITIONS) {
                feature = "Player position sharing";
            } else {
                feature = "Schema update " + e.getKey();
            }
            System.err.println("[DatabaseManager] " + feature + " unavailable: " + e.getValue());
            try {
                if (nurgling.NUtils.getGameUI() != null) {
                    nurgling.NUtils.getGameUI().msg(feature + " unavailable: " + e.getValue(),
                        java.awt.Color.ORANGE);
                }
            } catch (Exception ignore) {}
        }
    }

    public nurgling.db.service.VillagerService getVillagerService() {
        return villagerService;
    }

    /** Optional migrations this database refused, as version -> reason. Empty when all applied. */
    public java.util.Map<Integer, String> getSkippedMigrations() {
        return skippedMigrations;
    }

    /**
     * Run database migrations using the provided connection
     */
    private java.util.Map<Integer, String> runMigrations(Connection conn) throws SQLException {
        System.out.println("DatabaseManager: Starting migration check...");
        try {
            // Create adapter for this specific connection
            DatabaseAdapter migrationAdapter = DatabaseAdapterFactory.createAdapter(conn);
            System.out.println("DatabaseManager: Running migrations...");
            nurgling.db.migration.MigrationManager migrationManager = new nurgling.db.migration.MigrationManager(conn, migrationAdapter);
            java.util.Map<Integer, String> skipped = migrationManager.runMigrations();
            conn.commit();
            System.out.println("DatabaseManager: Migrations completed");
            return skipped;
        } catch (nurgling.db.migration.MigrationManager.SchemaTooNewException stne) {
            // Hard stop: do not initialize services, do not allow sync.
            System.err.println("ABORT: " + stne.getMessage());
            try { conn.rollback(); } catch (SQLException ignore) {}
            throw stne;
        } catch (SQLException e) {
            System.err.println("Failed to run database migrations: " + e.getMessage());
            e.printStackTrace();
            try {
                conn.rollback();
            } catch (SQLException ignore) {
            }
            throw e;
        }
    }

    /**
     * Execute task asynchronously
     */
    public Future<?> submitTask(Runnable task) {
        if (shutdown || executorService == null || executorService.isShutdown()) {
            return null;
        }
        try {
            return executorService.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Executor was shut down between check and submit
            return null;
        }
    }
    
    /**
     * Execute operation with automatic retry on failure.
     * If connection is not available, the task is queued for later execution.
     * 
     * @param operation The database operation to execute
     * @param description Description for logging
     * @return CompletableFuture that completes when operation succeeds or max retries exceeded
     */
    public <T> CompletableFuture<T> executeWithRetry(DatabaseOperation<T> operation, String description) {
        CompletableFuture<T> future = new CompletableFuture<>();
        
        if (shutdown || !initialized) {
            future.completeExceptionally(new SQLException("Database not available"));
            return future;
        }
        
        // Try to execute immediately
        executorService.submit(() -> {
            try {
                T result = executeOperation(operation);
                future.complete(result);
            } catch (SQLException e) {
                // Failed - queue for retry
                QueuedTask<T> task = new QueuedTask<>(operation, future, description);
                task.retryCount = 1;
                task.nextRetryTime = System.currentTimeMillis() + RETRY_DELAY_MS;
                
                if (taskQueue.offer(task)) {
                    queuedTaskCount.incrementAndGet();
                    System.out.println("[DatabaseManager] Task '" + description + "' queued for retry (queue size: " + 
                        queuedTaskCount.get() + ")");
                } else {
                    // Queue is full
                    future.completeExceptionally(new SQLException("Task queue full, operation rejected: " + description));
                    System.err.println("[DatabaseManager] Task queue full, rejected: " + description);
                }
            }
        });
        
        return future;
    }
    
    /**
     * Get current queued task count
     */
    public int getQueuedTaskCount() {
        return queuedTaskCount.get();
    }

    /**
     * Execute database operation with automatic connection management
     */
    public <T> T executeOperation(DatabaseOperation<T> operation) throws SQLException {
        pendingTasks.incrementAndGet();
        Connection conn = null;
        boolean connectionBroken = false;
        try {
            conn = connectionPoolManager.getConnection();
            if (conn == null) {
                throw new SQLException("Unable to get database connection");
            }

            DatabaseAdapter operationAdapter = DatabaseAdapterFactory.createAdapter(conn);
            T result = operation.execute(operationAdapter);
            conn.commit();
            recordOperation(); // Stats: successful operation
            return result;
        } catch (SQLException e) {
            recordFailure(); // Stats: failed operation
            // Check if this is an I/O error (connection is broken)
            if (isConnectionBroken(e)) {
                connectionBroken = true;
                System.err.println("[DatabaseManager] Connection broken due to I/O error, will not return to pool");
            }
            if (conn != null && !connectionBroken) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    // Rollback failed, connection is likely broken
                    connectionBroken = true;
                }
            }
            throw e;
        } finally {
            pendingTasks.decrementAndGet();
            if (conn != null) {
                if (connectionBroken) {
                    // Close broken connection and notify pool
                    connectionPoolManager.closeBrokenConnection(conn);
                } else {
                    connectionPoolManager.returnConnection(conn);
                }
            }
        }
    }
    
    /**
     * Check if the SQLException indicates a broken connection
     */
    private boolean isConnectionBroken(SQLException e) {
        // Check for I/O errors, timeout errors, connection closed errors
        String message = e.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("i/o error") ||
                lowerMessage.contains("connection closed") ||
                lowerMessage.contains("connection reset") ||
                lowerMessage.contains("socket") ||
                lowerMessage.contains("timeout")) {
                return true;
            }
        }
        
        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.SocketException ||
                cause instanceof java.net.SocketTimeoutException ||
                cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        
        return false;
    }

    /**
     * Check if database is ready
     */
    public boolean isReady() {
        return initialized && !shutdown && connectionPoolManager != null && connectionPoolManager.isReady();
    }

    /**
     * Get recipe service
     */
    public RecipeService getRecipeService() {
        return recipeService;
    }

    /**
     * Get favorite recipe service
     */
    public FavoriteRecipeService getFavoriteRecipeService() {
        return favoriteRecipeService;
    }

    /**
     * Get container service
     */
    public ContainerService getContainerService() {
        return containerService;
    }

    /**
     * Get storage item service
     */
    public StorageItemService getStorageItemService() {
        return storageItemService;
    }

    /**
     * Get area service
     */
    public AreaService getAreaService() {
        return areaService;
    }

    /**
     * Get planning service (folders / layers / ghosts for the Base planner).
     */
    public nurgling.db.service.PlanningService getPlanningService() {
        return planningService;
    }

    /**
     * Get kin secret service (shared hearth secrets).
     */
    public KinSecretService getKinSecretService() {
        return kinSecretService;
    }

    /**
     * Get fish location service (shared fish spots). Null when the optional migration that creates the
     * table was refused, in which case fish locations stay on their JSON file.
     */
    public nurgling.db.service.PeerPositionDbService getPeerPositionService() {
        return peerPositionService;
    }

    public nurgling.db.service.FishLocationDbService getFishLocationService() {
        return fishLocationService;
    }

    /**
     * Get the file-to-database importer for fish locations (the manual seed action).
     */
    public nurgling.db.service.FishLocationSeeder getFishLocationSeeder() {
        return fishLocationSeeder;
    }

    /**
     * Get the shared map service. Null when the optional migration that creates the map tables was
     * refused, or when this role cannot see them; callers treat that as "map sharing unavailable"
     * and leave the file-based Export/Import alone.
     */
    public nurgling.db.service.MapDbService getMapDbService() {
        return mapDbService;
    }

    /**
     * Reconnect to database
     */
    public synchronized void reconnect() {
        // Shutdown existing resources but don't mark as permanently shut down
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        if (connectionPoolManager != null) {
            connectionPoolManager.shutdown();
            connectionPoolManager = null;
        }
        adapter = null;
        initialized = false;
        skippedMigrations = java.util.Collections.emptyMap();
        kinSecretService = null;
        mapDbService = null;
        
        // Create new executor and reinitialize
        this.executorService = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "DB-Worker");
            t.setDaemon(true);
            return t;
        });
        this.shutdown = false;
        initialize();
    }

    /**
     * Shutdown database manager and release all resources
     */
    public synchronized void shutdown() {
        shutdown = true;
        
        // Shutdown services first
        if (recipeService != null) {
            recipeService.shutdown();
        }
        
        // Stop queue processor
        if (queueProcessor != null) {
            queueProcessor.shutdown();
            try {
                queueProcessor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                queueProcessor.shutdownNow();
            }
        }
        
        // Cancel all queued tasks
        int cancelled = 0;
        QueuedTask<?> task;
        while ((task = taskQueue.poll()) != null) {
            task.future.completeExceptionally(new SQLException("Database shutdown"));
            cancelled++;
        }
        if (cancelled > 0) {
            System.out.println("[DatabaseManager] Cancelled " + cancelled + " queued tasks on shutdown");
        }
        
        if (executorService != null) {
            executorService.shutdown();
        }
        if (connectionPoolManager != null) {
            connectionPoolManager.shutdown();
            connectionPoolManager = null;
        }
        adapter = null;
        initialized = false;
    }

    /**
     * Functional interface for database operations
     */
    @FunctionalInterface
    public interface DatabaseOperation<T> {
        T execute(DatabaseAdapter adapter) throws SQLException;
    }
}
