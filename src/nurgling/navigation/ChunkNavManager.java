package nurgling.navigation;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.areas.NArea;
import nurgling.overlays.map.MinimapChunkNavRenderer;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static nurgling.navigation.ChunkNavConfig.*;

/**
 * Manages the chunk navigation system lifecycle.
 * Handles initialization, persistence, and coordination between components.
 */
public class ChunkNavManager {
    private ChunkNavGraph graph;
    private ChunkNavRecorder recorder;
    private ChunkNavPlanner planner;
    private PortalTraversalTracker portalTracker;
    // The world's graph and files, shared with every other session in that world; null until initialized.
    private ChunkNavStore store;
    // This manager's session: the recording thread runs bound to it, so the recorder sees this session's player.
    private NUI ui;

    private boolean enabled = true;
    private boolean initialized = false;
    private String currentGenus = null;

    // Throttle saves to avoid excessive disk writes
    private long lastSaveTime = 0;
    private static final long SAVE_THROTTLE_MS = 60000; // Save to disk once per minute

    // Throttle grid recording to avoid excessive CPU usage
    private long lastRecordTime = 0;
    private static final long RECORD_THROTTLE_MS = 2000; // Record visible grids every 2 seconds

    // Portal visualization
    private boolean showPortalsEnabled = true; // Show portals by default
    private long lastPortalRefreshTime = 0;
    private static final long PORTAL_REFRESH_MS = 2000; // Refresh portal display every 3 seconds

    // Background thread for recording (to avoid FPS drops)
    private ExecutorService recordingExecutor;
    private volatile boolean recordingInProgress = false;
    private volatile boolean saveInProgress = false;

    // Last planned path (for visualization)
    private ChunkPath lastPlannedPath;

    // Current world instance context.
    // All chunks recorded while this value is active belong to the same instance.
    // 1 = surface (default), other values = unique per mine level / building interior / cellar.
    // Updated by PortalTraversalTracker when player traverses a portal.
    public static final long SURFACE_INSTANCE = 1;
    private volatile long currentInstanceId = SURFACE_INSTANCE;

    public ChunkNavManager() {
        this.graph = new ChunkNavGraph();
        this.recorder = new ChunkNavRecorder(graph);
        this.recorder.setManager(this);
        this.planner = new ChunkNavPlanner(graph);
        this.portalTracker = new PortalTraversalTracker(graph, recorder, this);

        // Create single-thread executor for background recording
        this.recordingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChunkNav-Recorder");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // Low priority to not affect game
            return t;
        });

        // NOTE: No shutdown hook - we save every 2 seconds, so losing at most 2 seconds
        // of data on abrupt shutdown is acceptable. Shutdown hooks caused more problems
        // than they solved (race conditions with background saves).
    }

    /**
     * Initialize the navigation system for a specific world. The graph is the world's shared one: the first session in the
     * world loads it from disk, the others reuse it.
     * @param ui this manager's session, which the recording thread is bound to
     */
    public void initialize(String genus, NUI ui) {
        if (genus == null || genus.isEmpty()) {
            return;
        }
        this.ui = ui;

        // Check if already initialized for this world
        if (initialized && genus.equals(currentGenus)) {
            return;
        }

        // Switching worlds: let go of the previous one's graph (the last session out saves it)
        if (store != null) {
            ChunkNavStore.release(store);
            store = null;
        }

        // Clear renderer cache to avoid stale textures from previous genus
        MinimapChunkNavRenderer.clearCache();

        this.currentGenus = genus;
        this.store = ChunkNavStore.acquire(genus);
        this.graph = store.graph;
        this.recorder = new ChunkNavRecorder(graph);
        this.recorder.setManager(this);
        this.planner = new ChunkNavPlanner(graph);
        this.portalTracker = new PortalTraversalTracker(graph, recorder, this);
        this.currentInstanceId = SURFACE_INSTANCE;
        this.initialized = true;
    }

    /**
     * Called periodically to check for portal traversals and record visible grids.
     * Should be called from game loop or a polling task (NMapView.tick).
     */
    public void tick() {
        if (!enabled || !initialized) return;

        portalTracker.tick();

        // Save after portal tracking (throttled - won't save every tick)
        saveThrottled();

        // Periodically record all visible grids (not just newly loaded ones)
        long now = System.currentTimeMillis();
        if (now - lastRecordTime >= RECORD_THROTTLE_MS) {
            lastRecordTime = now;
            recordVisibleGrids();
        }

        // Periodically refresh portal visualization
        if (showPortalsEnabled && now - lastPortalRefreshTime >= PORTAL_REFRESH_MS) {
            lastPortalRefreshTime = now;
            refreshPortalVisualization();
        }
    }

    /**
     * Record all currently visible grids.
     * This catches grids that were already loaded when player walks through them.
     * Runs in a background thread to avoid FPS drops.
     */
    private void recordVisibleGrids() {
        // Skip if ChunkNav overlay is disabled
        Object val = NConfig.get(NConfig.Key.chunkNavOverlay);
        if (!(val instanceof Boolean) || !(Boolean) val) {
            return;
        }

        // Skip if recording is already in progress
        if (recordingInProgress) {
            return;
        }

        // Capture grid data on main thread (quick), then process in background
        try {
            NGameUI gui = NUtils.getGameUI();
            if (gui == null || gui.map == null || gui.map.glob == null) return;

            MCache mcache = gui.map.glob.map;
            if (mcache == null) return;

            // Capture the glob reference for background thread - this ensures
            // the recorder uses the correct session's gob data
            final Glob capturedGlob = gui.map.glob;

            // Capture list of grids to record (quick operation on main thread)
            List<MCache.Grid> gridsToRecord = new ArrayList<>();
            synchronized (mcache.grids) {
                for (MCache.Grid grid : mcache.grids.values()) {
                    if (grid != null && grid.ul != null) {
                        gridsToRecord.add(grid);
                    }
                }
            }

            if (gridsToRecord.isEmpty()) return;

            // Submit recording task to background thread with captured glob
            recordingInProgress = true;
            recordingExecutor.submit(bound(() -> {
                try {
                    for (MCache.Grid grid : gridsToRecord) {
                        recorder.recordGrid(grid, capturedGlob);
                    }
                    saveThrottled();
                } catch (Exception e) {
                    // Ignore background recording errors
                } finally {
                    recordingInProgress = false;
                }
            }));
        } catch (Exception e) {
            recordingInProgress = false;
            // Ignore - game state may not be ready
        }
    }

    /**
     * Ensure the player's current chunk is recorded in the graph.
     * This is called before path planning to handle cases where the player
     * teleported (e.g., via Hearth Fire skill) to an unrecorded chunk.
     */
    private void ensurePlayerChunkRecorded() {
        // Skip if ChunkNav overlay is disabled
        Object val = NConfig.get(NConfig.Key.chunkNavOverlay);
        if (!(val instanceof Boolean) || !(Boolean) val) {
            return;
        }

        try {
            NGameUI gui = NUtils.getGameUI();
            if (gui == null || gui.map == null || gui.map.glob == null || gui.map.glob.map == null) {
                return;
            }

            Gob player = NUtils.player();
            if (player == null) return;

            MCache mcache = gui.map.glob.map;
            Coord tileCoord = player.rc.floor(MCache.tilesz);
            MCache.Grid playerGrid = mcache.getgridt(tileCoord);

            if (playerGrid == null) return;

            // Check if this chunk is already recorded
            if (!graph.hasChunk(playerGrid.id)) {
                recorder.recordGrid(playerGrid);
            }
        } catch (Exception e) {
            // Ignore - best effort
        }
    }

    /**
     * Force synchronous recording of all visible grids.
     * Used before planning and after navigation to ensure fresh data.
     * No overlay check, no throttle - immediate recording.
     */
    public void forceRecordVisibleGrids() {
        try {
            NGameUI gui = NUtils.getGameUI();
            if (gui == null || gui.map == null || gui.map.glob == null || gui.map.glob.map == null) {
                return;
            }

            MCache mcache = gui.map.glob.map;

            List<MCache.Grid> gridsToRecord = new ArrayList<>();
            synchronized (mcache.grids) {
                for (MCache.Grid grid : mcache.grids.values()) {
                    if (grid != null && grid.ul != null) {
                        gridsToRecord.add(grid);
                    }
                }
            }

            for (MCache.Grid grid : gridsToRecord) {
                recorder.recordGrid(grid);
            }
        } catch (Exception e) {
            // Ignore - best effort
        }
    }

    /**
     * Plan a path to an area.
     * Returns the chunk-level path. PathFinder handles actual navigation within grids.
     */
    public ChunkPath planToArea(NArea area) {
        if (!enabled || !initialized) return null;

        // Record all visible grids to ensure fresh data before planning
        forceRecordVisibleGrids();

        ChunkPath path = planner.planToArea(area);
        if (path == null) {
            return null;
        }

        // Store for visualization
        this.lastPlannedPath = path;

        // Export path visualization
        exportPathVisualization(path, area);

        // Note: path.isEmpty() is valid when already in the target chunk
        // The executor handles this case by navigating directly to the area
        return path;
    }
    
    /**
     * Plan a path to a specific world coordinate.
     * Used for planning paths to specific corners of an area.
     * NOTE: Only works reliably within the same layer/area!
     */
    public ChunkPath planToCoord(haven.Coord2d worldCoord) {
        if (!enabled || !initialized) return null;

        // Record all visible grids to ensure fresh data before planning
        forceRecordVisibleGrids();

        ChunkPath path = planner.planToCoord(worldCoord);
        return path;
    }
    
    /**
     * Plan a path to a specific corner of an area using gridId + local coordinates.
     * This works correctly across different layers/areas.
     * @param area The target area
     * @param cornerIndex 0=top-left, 1=bottom-right, 2=bottom-left, 3=top-right
     */
    public ChunkPath planToAreaCorner(nurgling.areas.NArea area, int cornerIndex) {
        if (!enabled || !initialized) return null;

        // Record all visible grids to ensure fresh data before planning
        forceRecordVisibleGrids();

        ChunkPath path = planner.planToAreaCorner(area, cornerIndex);
        return path;
    }

    /**
     * Plan a path to a specific grid + local coordinate.
     * Works correctly across different layers because it uses gridId directly.
     * @param gridId The target grid ID
     * @param localCoord Local tile coordinate within the grid
     */
    public ChunkPath planToGridCoord(long gridId, haven.Coord localCoord) {
        if (!enabled || !initialized) return null;

        // Record all visible grids to ensure fresh data before planning
        forceRecordVisibleGrids();

        ChunkPath path = planner.planToGridCoord(gridId, localCoord);
        return path;
    }

    /**
     * Export path visualization data to JSON file for the Python visualizer.
     */
    private void exportPathVisualization(ChunkPath path, NArea area) {
        if (path == null) return;

        try {
            JSONObject pathJson = new JSONObject();
            pathJson.put("timestamp", System.currentTimeMillis());
            pathJson.put("targetArea", area != null ? area.name : "unknown");
            pathJson.put("totalCost", path.totalCost);
            pathJson.put("confidence", path.confidence);
            pathJson.put("requiresPortals", path.requiresPortals);

            // Export waypoints
            JSONArray waypointsArray = new JSONArray();
            for (ChunkPath.ChunkWaypoint wp : path.waypoints) {
                JSONObject wpJson = new JSONObject();
                wpJson.put("gridId", wp.gridId);
                wpJson.put("localX", wp.localCoord != null ? wp.localCoord.x : CHUNK_SIZE / 2);
                wpJson.put("localY", wp.localCoord != null ? wp.localCoord.y : CHUNK_SIZE / 2);
                wpJson.put("type", wp.type.name());
                if (wp.portal != null) {
                    wpJson.put("portalName", wp.portal.gobName);
                    wpJson.put("portalType", wp.portal.type.name());
                }
                waypointsArray.put(wpJson);
            }
            pathJson.put("waypoints", waypointsArray);

            // Export segments with tile-level detail
            JSONArray segmentsArray = new JSONArray();
            for (ChunkPath.PathSegment seg : path.segments) {
                JSONObject segJson = new JSONObject();
                segJson.put("gridId", seg.gridId);
                segJson.put("worldTileOriginX", seg.worldTileOrigin != null ? seg.worldTileOrigin.x : 0);
                segJson.put("worldTileOriginY", seg.worldTileOrigin != null ? seg.worldTileOrigin.y : 0);
                segJson.put("type", seg.type.name());

                // Export tile steps
                JSONArray stepsArray = new JSONArray();
                for (ChunkPath.TileStep step : seg.steps) {
                    JSONObject stepJson = new JSONObject();
                    stepJson.put("localX", step.localCoord.x);
                    stepJson.put("localY", step.localCoord.y);
                    stepsArray.put(stepJson);
                }
                segJson.put("steps", stepsArray);
                segmentsArray.put(segJson);
            }
            pathJson.put("segments", segmentsArray);

            // Write to file next to chunknav directory
            Path pathFile = store.files.getChunkDirectory().getParent().resolve("chunknav_path.json");
            NFileUtils.writeAtomically(pathFile.toString(), pathJson.toString(2));

        } catch (Exception e) {
            // Ignore export errors
        }
    }

    /**
     * Navigate to an area using the chunk navigation system.
     */
    public nurgling.actions.Results navigateToArea(NArea area, NGameUI gui) throws InterruptedException {
        if (!enabled || !initialized) {
            return nurgling.actions.Results.FAIL();
        }

        ChunkPath path = planToArea(area);
        if (path == null) {
            return nurgling.actions.Results.FAIL();
        }

        // Note: path.isEmpty() is valid when we're already in the target chunk
        // The executor handles this case by navigating directly to the area
        ChunkNavExecutor executor = new ChunkNavExecutor(path, area, this);
        nurgling.actions.Results result = executor.run(gui);

        if (result.IsSuccess()) {
            forceRecordVisibleGrids();
        }

        return result;
    }
    
    /**
     * Navigate using a pre-planned path, with area for final adjustments.
     */
    public nurgling.actions.Results navigateWithPath(ChunkPath path, NArea area, NGameUI gui) throws InterruptedException {
        if (!enabled || !initialized || path == null) {
            return nurgling.actions.Results.FAIL();
        }

        ChunkNavExecutor executor = new ChunkNavExecutor(path, area, this);
        nurgling.actions.Results result = executor.run(gui);

        if (result.IsSuccess()) {
            forceRecordVisibleGrids();
        }

        return result;
    }

    /**
     * Fast check: is there a chunk-level path from the player's chunk to any of the area's chunks?
     * Uses BFS through connectedChunks and portal connections.
     * If this returns false, tile-level A* will also fail.
     */
    public boolean isAreaReachableByChunks(NArea area) {
        if (!enabled || !initialized || area == null) return false;
        if (area.space == null || area.space.space == null || area.space.space.isEmpty()) return false;

        // Collect area chunk IDs that are in our graph
        Set<Long> targetChunks = new HashSet<>();
        for (Long gridId : area.space.space.keySet()) {
            if (graph.getChunk(gridId) != null) {
                targetChunks.add(gridId);
            }
        }
        if (targetChunks.isEmpty()) return false;

        return chunkHopsToAny(targetChunks) >= 0;
    }

    /**
     * Fast check: is there a chunk-level path from the player's chunk to this grid?
     * If this returns false, tile-level A* will also fail, so it is worth calling before
     * paying for a plan.
     */
    public boolean isGridReachable(long gridId) {
        if (!enabled || !initialized) return false;
        if (graph.getChunk(gridId) == null) return false;
        return chunkHopsToAny(Collections.singleton(gridId)) >= 0;
    }

    /**
     * Number of chunk hops from the player to this grid, or -1 if there is no chunk-level
     * route. Cheap enough to call for every candidate before deciding which ones are worth
     * a real path search.
     *
     * Zero means "already there" - and also means "the player's own chunk is not recorded
     * yet", which is the same leniency {@link #isAreaReachableByChunks} applies: a chunk
     * that has just been entered must not be pruned, because the planner records visible
     * grids before searching and can still find a path.
     */
    public int chunkHopsTo(long gridId) {
        if (!enabled || !initialized) return -1;
        if (graph.getChunk(gridId) == null) return -1;
        return chunkHopsToAny(Collections.singleton(gridId));
    }

    /**
     * Hop count from the player's chunk to every chunk reachable from it.
     *
     * Callers that need to rank many candidates at once must use this rather than calling
     * {@link #chunkHopsTo} per candidate: that runs a whole BFS each time, so scoring N
     * containers costs N graph traversals on the calling thread. This pays for one.
     *
     * Returns null when the player's own chunk is not recorded yet - just entered,
     * teleported, or inside the record throttle window. That is not "nothing is reachable";
     * it means the question cannot be answered, and callers should stay lenient, because
     * the planner records visible grids before searching and can still find a path.
     */
    public Map<Long, Integer> chunkHopsFromPlayer() {
        if (!enabled || !initialized) return null;
        long start = playerChunkId();
        if (start == -1 || graph.getChunk(start) == null) return null;

        Map<Long, Integer> hops = new HashMap<>();
        Queue<Long> queue = new LinkedList<>();
        hops.put(start, 0);
        queue.add(start);

        while (!queue.isEmpty()) {
            long current = queue.poll();
            int next = hops.get(current) + 1;
            ChunkNavData chunk = graph.getChunk(current);
            if (chunk == null) continue;

            for (long connected : chunk.connectedChunks) {
                if (hops.containsKey(connected)) continue;
                hops.put(connected, next);
                queue.add(connected);
            }
            for (ChunkPortal portal : chunk.portals) {
                long target = portal.connectsToGridId;
                if (target == -1 || hops.containsKey(target)) continue;
                if (graph.getChunk(target) != null) {
                    hops.put(target, next);
                    queue.add(target);
                }
            }
        }
        return hops;
    }

    /** Grid the player is standing on, or -1 when it cannot be resolved. */
    private long playerChunkId() {
        try {
            Gob player = NUtils.player();
            if (player == null) return -1;
            NGameUI gui = NUtils.getGameUI();
            if (gui == null || gui.map == null || gui.map.glob == null) return -1;
            MCache mcache = gui.map.glob.map;
            MCache.Grid playerGrid = mcache.getgridt(player.rc.floor(MCache.tilesz));
            return (playerGrid == null) ? -1 : playerGrid.id;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * BFS from the player's chunk through connectedChunks and portal links to the nearest
     * chunk in the target set. Returns hop count, or -1 if none is reachable.
     */
    private int chunkHopsToAny(Set<Long> targetChunks) {
        if (targetChunks == null || targetChunks.isEmpty()) return -1;

        long playerChunkId = playerChunkId();
        if (playerChunkId == -1) return -1;
        if (targetChunks.contains(playerChunkId)) return 0;
        // Player's chunk isn't recorded yet (just entered, teleported, or within the
        // record throttle window). Don't prune here - fall through to the full pathfinder,
        // which calls forceRecordVisibleGrids() before searching and can still find a path.
        if (graph.getChunk(playerChunkId) == null) return 0;

        Set<Long> visited = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        queue.add(playerChunkId);
        visited.add(playerChunkId);
        int hops = 0;

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            hops++;
            for (int i = 0; i < levelSize; i++) {
                long current = queue.poll();
                ChunkNavData chunk = graph.getChunk(current);
                if (chunk == null) continue;

                for (long connected : chunk.connectedChunks) {
                    if (visited.contains(connected)) continue;
                    if (targetChunks.contains(connected)) return hops;
                    visited.add(connected);
                    queue.add(connected);
                }
                for (ChunkPortal portal : chunk.portals) {
                    long target = portal.connectsToGridId;
                    if (target == -1 || visited.contains(target)) continue;
                    if (targetChunks.contains(target)) return hops;
                    if (graph.getChunk(target) != null) {
                        visited.add(target);
                        queue.add(target);
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Check if we have navigation data for an area.
     */
    public boolean hasDataForArea(NArea area) {
        if (!enabled || !initialized || area == null) return false;

        Set<Long> chunks = graph.getChunksForArea(area.id);
        return !chunks.isEmpty();
    }

    /**
     * Save navigation data to disk (throttled version).
     * Won't save more often than SAVE_THROTTLE_MS.
     * Runs on background thread to avoid FPS drops.
     */
    public void saveThrottled() {
        if (!initialized) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < SAVE_THROTTLE_MS) {
            return; // Too soon since last save
        }
        if (saveInProgress) {
            return; // Already saving
        }
        lastSaveTime = now; // Update immediately to prevent rapid re-triggers

        saveInProgress = true;
        ChunkNavStore target = store;
        recordingExecutor.submit(() -> {
            try {
                target.save();
            } catch (Exception e) {
                System.err.println("ChunkNav: Failed to save data: " + e.getMessage());
            } finally {
                saveInProgress = false;
            }
        });
    }

    /** Runs the task with this manager's session bound, so NUtils lookups inside it resolve to this session, not the active one. */
    private Runnable bound(Runnable task) {
        NUI session = ui;
        return () -> {
            if (session != null)
                nurgling.sessions.ThreadLocalUI.set(session);
            try {
                task.run();
            } finally {
                if (session != null)
                    nurgling.sessions.ThreadLocalUI.clear();
            }
        };
    }

    /**
     * Clear all navigation data.
     */
    public void clear() {
        graph.clear();
    }

    /**
     * Delete a specific chunk from memory and disk.
     * Also cleans up all references to it from neighboring chunks and portals.
     * @param gridId The grid ID of the chunk to delete
     * @return true if deletion was successful
     */
    public boolean deleteChunk(long gridId) {
        if (!initialized) {
            return false;
        }

        // Remove from graph and get list of modified chunks
        List<ChunkNavData> modifiedChunks = graph.removeChunk(gridId);

        // Save all modified chunks (neighbors that had references cleared)
        for (ChunkNavData modified : modifiedChunks) {
            try {
                store.files.saveChunk(modified);
            } catch (IOException e) {
                System.err.println("ChunkNav: Failed to save modified chunk " + modified.gridId + ": " + e.getMessage());
            }
        }

        // Delete the chunk file from disk
        store.files.deleteChunkFile(gridId);

        System.out.println("ChunkNav: Deleted chunk " + gridId + " (modified " + modifiedChunks.size() + " neighbors)");
        return true;
    }

    /**
     * Delete ALL chunks from memory and disk.
     * This is a destructive operation that clears all navigation data.
     * @return The number of chunks deleted
     */
    public int deleteAllChunks() {
        if (!initialized) {
            return 0;
        }

        // Get count before clearing
        int count = graph.getAllChunks().size();

        // Clear all chunks from memory
        graph.clear();

        // Delete all chunk files from disk
        int filesDeleted = store.files.deleteAllChunkFiles();

        // Reset the portal tracker to clear any cached state
        if (portalTracker != null) {
            portalTracker.reset();
        }

        System.out.println("ChunkNav: Deleted all chunks (" + count + " from memory, " + filesDeleted + " files)");
        return count;
    }

    /**
     * Get statistics about the navigation system.
     */
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("ChunkNav Status:\n");
        sb.append("  Enabled: ").append(enabled).append("\n");
        sb.append("  Initialized: ").append(initialized).append("\n");
        sb.append("  World: ").append(currentGenus).append("\n");
        sb.append("  ").append(graph.getStats()).append("\n");
        sb.append("  ").append(recorder.getStats());
        return sb.toString();
    }

    // Getters

    public ChunkNavGraph getGraph() {
        return graph;
    }

    public ChunkNavPlanner getPlanner() {
        return planner;
    }

    public ChunkPath getLastPlannedPath() {
        return lastPlannedPath;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Stops recording and lets go of the world's shared graph; the last session in the world saves it and drops it.
     */
    public void shutdown() {
        initialized = false;

        // Shutdown recording executor
        if (recordingExecutor != null) {
            recordingExecutor.shutdown();
            try {
                if (!recordingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    recordingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                recordingExecutor.shutdownNow();
            }
        }
        if (store != null) {
            ChunkNavStore.release(store);
            store = null;
        }
    }

    /**
     * Refresh portal visualization (called from tick).
     */
    private void refreshPortalVisualization() {
        try {
            NGameUI gui = NUtils.getGameUI();
            if (gui != null && gui.map != null) {
                ((nurgling.NMapView) gui.map).createPortalLabels();
            }
        } catch (Exception e) {
            // Ignore - UI might not be ready
        }
    }

    // ============== Instance ID Management ==============

    public long getCurrentInstanceId() {
        return currentInstanceId;
    }

    public void setCurrentInstanceId(long id) {
        this.currentInstanceId = id;
    }

    /**
     * Check if a portal type is compatible with a target chunk layer.
     */
    static boolean isPortalTargetLayerValid(ChunkPortal.PortalType type, String targetLayer) {
        if (type == null || targetLayer == null) return true;
        switch (type) {
            case MINEHOLE:
            case MINE_ENTRANCE:
                return "outside".equals(targetLayer);
            case CELLAR:
                return "cellar".equals(targetLayer) || "inside".equals(targetLayer);
            case LADDER:
            case CAVEIN:
            case CAVEOUT:
                return "outside".equals(targetLayer);
            case STAIRS_UP:
            case STAIRS_DOWN:
                return "inside".equals(targetLayer);
            case DOOR:
                return "inside".equals(targetLayer) || "outside".equals(targetLayer);
            default:
                return true;
        }
    }
}
