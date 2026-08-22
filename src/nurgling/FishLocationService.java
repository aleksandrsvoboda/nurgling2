package nurgling;

import haven.*;
import nurgling.profiles.ConfigFactory;
import nurgling.profiles.ProfileAwareService;
import nurgling.tools.NFileUtils;
import nurgling.tools.VSpec;
import nurgling.widgets.NEquipory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns the saved fish locations for one session.
 *
 * <p>The in-memory map is always the single source of truth for the render path. Persistence is
 * <b>file OR database, never both</b>, decided by {@link NConfig.Key#ndbenable} - the same rule areas
 * follow. In database mode the JSON file is neither read nor written; the one bridge between the two
 * stores is the explicit seed action in
 * {@link nurgling.db.service.FishLocationSeeder}.
 *
 * <p>Supports world-specific profiles via {@link ProfileAwareService}.
 */
public class FishLocationService implements ProfileAwareService {
    private final Map<String, FishLocation> fishLocations = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private String dataFile;
    private final NGameUI gui;
    private String genus;

    /**
     * Writes that could not reach the database yet (it was down, or still starting up). Drained by
     * {@link #flushPending()} on the next save, delete, or sync tick. In-memory only: in database mode
     * nothing is ever spilled to the JSON file.
     */
    private final Map<String, FishLocation> pendingPush = new ConcurrentHashMap<>();
    private final Set<String> pendingTombstone =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Re-attempt interval for a record whose grid is not in the map file (yet). */
    private static final long RESOLVE_RETRY_MS = 60_000;

    /**
     * Which store the current in-memory set came from, or null before the first load. Watched so the
     * set is re-filled from the right place when the mode changes under us - the user toggling
     * {@code ndbenable} mid-session, or the database turning out not to host the fish table after all.
     */
    private volatile Boolean loadedInDbMode = null;

    public FishLocationService(NGameUI gui) {
        this.gui = gui;
        this.dataFile = NUtils.getDataFile("fish_locations.nurgling.json");
        load();
    }

    /**
     * Constructor for profile-aware initialization
     */
    public FishLocationService(NGameUI gui, String genus) {
        this.gui = gui;
        this.genus = genus;
        initializeForProfile(genus);
    }

    // ProfileAwareService implementation

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig config = ConfigFactory.getConfig(genus);
        this.dataFile = config.getFishLocationsPath();
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    @Override
    public void load() {
        loadedInDbMode = null;
        ensureStoreCurrent();
    }

    /**
     * Make sure the in-memory set matches whichever store is currently in charge. Cheap enough for the
     * render path: in the steady state this is one volatile read and a comparison.
     *
     * <p>Must be called with no lock held - it takes the write lock when it has to re-fill.
     */
    private void ensureStoreCurrent() {
        boolean db = dbMode();
        Boolean current = loadedInDbMode;
        if (current != null && current == db) return;

        loadedInDbMode = db;
        if (db) {
            /* The sync worker owns the initial load in database mode: its first tick bulk-loads the rows
             * for this session. Reading the file here would race that tick and mix the two stores. */
            lock.writeLock().lock();
            try {
                fishLocations.clear();
            } finally {
                lock.writeLock().unlock();
            }
        } else {
            loadFishLocations();
        }
    }

    @Override
    public void save() {
        if (dbMode()) {
            flushPending();
            return;
        }
        lock.writeLock().lock();
        try {
            saveFishLocations();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** The profile (genus) these records belong to; the database scopes rows by it. */
    public String profile() {
        return (genus == null || genus.isEmpty()) ? "global" : genus;
    }

    /**
     * True when the database owns these records. False means the JSON file owns them.
     *
     * <p>Note the fallback: if the database is up but refused the optional migration that creates
     * {@code fish_locations}, the feature stays on its file rather than disappearing. While the database
     * is still starting up we cannot tell yet, so we assume database mode and leave the file alone.
     */
    private static boolean dbMode() {
        if (!(Boolean) NConfig.get(NConfig.Key.ndbenable)) return false;
        if (NCore.databaseManager != null && NCore.databaseManager.isReady()
            && NCore.databaseManager.getFishLocationService() == null) {
            return false;
        }
        return true;
    }

    /** The database service, or null when database mode is off or the database is not ready. */
    private static nurgling.db.service.FishLocationDbService db() {
        if (!dbMode()) return null;
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()) return null;
        return NCore.databaseManager.getFishLocationService();
    }

    /**
     * Save a fish location from the fishing menu.
     *
     * <p>The position is stored grid-relative: the server-assigned grid id plus the tile offset inside
     * that grid. No map-file lookup happens here - the segment coordinates the minimap draws with are
     * resolved lazily off the render thread by {@link #ensureResolved}.
     */
    public void saveFishLocation(String fishName, String percentage, Coord2d playerPosition) {
        try {
            if (gui.map == null) return;

            MCache mcache = gui.map.glob.map;
            Coord tc = playerPosition.floor(MCache.tilesz);   // world tile coordinate
            MCache.Grid grid = mcache.getgrid(tc.div(MCache.cmaps));
            if (grid == null) return;

            long gridId = grid.id;
            Coord offset = tc.sub(grid.ul);                   // 0..cmaps-1 inside the grid

            String fishResource = getFishResourcePath(fishName);
            if (fishResource == null) {
                gui.msg("Unknown fish: " + fishName, java.awt.Color.RED);
                return;
            }

            FishingEquipment equipment = getFishingEquipment();

            // Current in-game time and moon phase
            String gameTime = "Unknown";
            String moonPhase = "Unknown";
            try {
                if (gui.map != null && gui.map.glob != null && gui.map.glob.ast != null) {
                    haven.Astronomy ast = gui.map.glob.ast;
                    gameTime = String.format("%02d:%02d", ast.hh, ast.mm);

                    haven.Resource moon = haven.Resource.local().loadwait("gfx/hud/calendar/moon");
                    haven.Resource.Anim moonAnim = moon.layer(haven.Resource.animc);
                    int moonPhaseIndex = (int)Math.round(ast.mp * (double)moonAnim.f.length) % moonAnim.f.length;
                    moonPhase = haven.Astronomy.phase[moonPhaseIndex];
                }
            } catch (RuntimeException e) {
                System.err.println("Error getting time/moon phase: " + e);
            }

            FishLocation location = new FishLocation(profile(), gridId, offset,
                Long.MIN_VALUE, null,
                fishName, fishResource, percentage, gameTime, moonPhase,
                equipment.fishingRod, equipment.hook, equipment.line, equipment.bait);

            lock.writeLock().lock();
            try {
                fishLocations.put(location.getLocationId(), location);
                if (dbMode()) {
                    pendingTombstone.remove(location.getLocationId());
                    pendingPush.put(location.getLocationId(), location);
                } else {
                    saveFishLocations();
                }
            } finally {
                lock.writeLock().unlock();
            }
            if (dbMode()) flushPending();

            gui.msg("Saved " + fishName + " location (" + percentage + ")", java.awt.Color.GREEN);

        } catch (RuntimeException e) {
            System.err.println("Error saving fish location: " + e);
            e.printStackTrace();
        }
    }

    /**
     * Get fish resource path from VSpec
     */
    private String getFishResourcePath(String fishName) {
        try {
            ArrayList<JSONObject> fishList = VSpec.categories.get("Fish");
            if (fishList == null) return null;

            for (JSONObject fish : fishList) {
                if (fish.getString("name").equals(fishName)) {
                    return fish.getString("static");
                }
            }
        } catch (RuntimeException e) {
            System.err.println("Error getting fish resource path: " + e);
        }
        return null;
    }

    /**
     * Every fish location that draws in the given map-file segment.
     *
     * <p>Called from the render path on every frame, so it must stay cheap: unresolved records only queue
     * a deferred map-file lookup, they never block on one.
     */
    public List<FishLocation> getFishLocationsForSegment(long segmentId) {
        ensureStoreCurrent();
        lock.readLock().lock();
        try {
            List<FishLocation> result = new ArrayList<>();
            for (FishLocation loc : fishLocations.values()) {
                if (!loc.isResolved()) {
                    ensureResolved(loc);
                    continue;
                }
                if (loc.getSegmentId() == segmentId) {
                    result.add(loc);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Queue a map-file lookup that turns this record's grid id into the segment coordinates the minimap
     * draws with. The lookup reads {@code gridinfo}, which is a disk-backed cache, so it goes to the
     * loader rather than blocking the frame - the same approach {@link PingService} uses for pings.
     */
    private void ensureResolved(FishLocation loc) {
        if (loc.isResolved() || loc.resolving || !loc.hasGrid()) return;
        long now = System.currentTimeMillis();
        // A grid this client has never explored is not in the map file; re-check occasionally rather than
        // once per frame, so it starts drawing if the area is explored later.
        if (now - loc.lastResolveAttempt < RESOLVE_RETRY_MS) return;
        if (gui.mmap == null || gui.mmap.file == null) return;
        if (gui.ui == null || gui.ui.sess == null || gui.ui.sess.glob == null) return;

        final MapFile file = gui.mmap.file;
        loc.lastResolveAttempt = now;
        loc.resolving = true;
        gui.ui.sess.glob.loader.defer(() -> {
            try {
                file.lock.readLock().lock();
                try {
                    MapFile.GridInfo info = file.gridinfo.get(loc.getGridId());
                    if (info != null) {
                        loc.setResolved(info.seg, info.sc.mul(MCache.cmaps).add(loc.getOffset()));
                    }
                } finally {
                    file.lock.readLock().unlock();
                }
            } finally {
                loc.resolving = false;
            }
        }, null);
    }

    /**
     * Get all fish locations
     */
    public Collection<FishLocation> getAllFishLocations() {
        ensureStoreCurrent();
        lock.readLock().lock();
        try {
            return new ArrayList<>(fishLocations.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Remove a fish location
     */
    public boolean removeFishLocation(String locationId) {
        boolean removed;
        lock.writeLock().lock();
        try {
            removed = fishLocations.remove(locationId) != null;
            if (removed) {
                if (dbMode()) {
                    pendingPush.remove(locationId);
                    pendingTombstone.add(locationId);
                } else {
                    saveFishLocations();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (removed && dbMode()) flushPending();
        return removed;
    }

    /* -------------------- Database mode: sync worker entry points -------------------- */

    /**
     * Replace the whole in-memory set with what the database holds. Called by the sync worker's first
     * tick for this session (the bulk load).
     */
    public void applyFullSync(Collection<FishLocation> rows) {
        lock.writeLock().lock();
        try {
            for (FishLocation loc : rows) carryResolution(loc);
            fishLocations.clear();
            for (FishLocation loc : rows) {
                fishLocations.put(loc.getLocationId(), loc);
            }
            // Anything still waiting to be pushed is newer than the bulk load, so it stays in the map.
            fishLocations.putAll(pendingPush);
            for (String id : pendingTombstone) {
                fishLocations.remove(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Apply one delta poll: rows added or updated remotely, plus rows tombstoned remotely. */
    public void applyDelta(Collection<FishLocation> updated, Collection<String> removedIds) {
        lock.writeLock().lock();
        try {
            for (FishLocation loc : updated) {
                if (pendingTombstone.contains(loc.getLocationId())) continue;
                carryResolution(loc);
                fishLocations.put(loc.getLocationId(), loc);
            }
            for (String id : removedIds) {
                if (pendingPush.containsKey(id)) continue;
                fishLocations.remove(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Hand a freshly pulled record the segment position the copy it replaces had already worked out.
     *
     * <p>Without this, every poll that returns a row we already have - including the echo of our own
     * save - would drop back to an unresolved copy and the icon would blink out until the map-file
     * lookup completed.
     */
    private void carryResolution(FishLocation incoming) {
        if (incoming.isResolved()) return;
        FishLocation prev = fishLocations.get(incoming.getLocationId());
        if (prev == null || !prev.isResolved()) return;
        if (prev.getGridId() != incoming.getGridId()) return;
        incoming.setResolved(prev.getSegmentId(), prev.getTileCoords());
    }

    /**
     * Push everything queued while the database was unavailable. Safe to call from any thread; the
     * database calls themselves are asynchronous.
     */
    public void flushPending() {
        nurgling.db.service.FishLocationDbService svc = db();
        if (svc == null) return;
        String profile = profile();

        for (Map.Entry<String, FishLocation> e : new ArrayList<>(pendingPush.entrySet())) {
            final String id = e.getKey();
            svc.upsertAsync(e.getValue(), profile)
               .thenRun(() -> pendingPush.remove(id))
               .exceptionally(err -> {
                   System.err.println("Fish location push failed (" + id + "): " + err.getMessage());
                   return null;
               });
        }
        for (String id : new ArrayList<>(pendingTombstone)) {
            svc.tombstoneAsync(id, profile)
               .thenRun(() -> pendingTombstone.remove(id))
               .exceptionally(err -> {
                   System.err.println("Fish location delete failed (" + id + "): " + err.getMessage());
                   return null;
               });
        }
    }

    /* -------------------- File mode persistence -------------------- */

    /**
     * Load fish locations from JSON
     */
    private void loadFishLocations() {
        lock.writeLock().lock();
        try {
            fishLocations.clear();
            String content = NFileUtils.readWithBackupFallback(dataFile);
            if (content != null && !content.isEmpty()) {
                try {
                    JSONObject main = new JSONObject(content);
                    JSONArray array = main.getJSONArray("fishLocations");
                    for (int i = 0; i < array.length(); i++) {
                        FishLocation location = new FishLocation(array.getJSONObject(i));
                        fishLocations.put(location.getLocationId(), location);
                    }
                } catch (RuntimeException e) {
                    System.err.println("Failed to parse fish locations JSON: " + e.getMessage());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Save fish locations to JSON
     */
    private void saveFishLocations() {
        // Called within write lock - don't lock again
        try {
            JSONObject main = new JSONObject();
            JSONArray jLocations = new JSONArray();
            for (FishLocation location : fishLocations.values()) {
                jLocations.put(location.toJson());
            }
            main.put("fishLocations", jLocations);
            main.put("version", 2);
            main.put("lastSaved", java.time.Instant.now().toString());

            NFileUtils.writeAtomically(dataFile, main.toString(2));
        } catch (IOException e) {
            System.err.println("Failed to save fish locations: " + e.getMessage());
        }
    }

    /** Path of the JSON store for this profile; the seeder reads it directly. */
    public String getDataFile() {
        return dataFile;
    }

    /**
     * Dispose the service and cleanup resources
     */
    public void dispose() {
        if (dbMode()) {
            flushPending();
            return;
        }
        lock.writeLock().lock();
        try {
            saveFishLocations();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Helper class to hold fishing equipment information
     */
    private static class FishingEquipment {
        String fishingRod = "Unknown";
        String hook = "Unknown";
        String line = "Unknown";
        String bait = "Unknown";
    }

    /**
     * Extract fishing equipment information from equipped items
     */
    private FishingEquipment getFishingEquipment() {
        FishingEquipment equipment = new FishingEquipment();

        try {
            // Get fishing rod from equipment (same pattern as RepairFishingRot)
            NEquipory eq = NUtils.getEquipment();
            if (eq == null) return equipment;

            // Find fishing rod in equipment (check for both types)
            WItem rod = eq.findItem("Primitive Casting-Rod");
            if (rod == null) {
                rod = eq.findItem("Bushcraft Fishingpole");
            }

            if (rod != null && rod.item instanceof NGItem) {
                NGItem rodItem = (NGItem) rod.item;

                // Get fishing rod name
                equipment.fishingRod = rodItem.name() != null ? rodItem.name() : "Unknown";

                // Get fishing rod contents (hook, line, bait)
                ArrayList<NGItem.NContent> contents = rodItem.content();
                for (NGItem.NContent content : contents) {
                    String contentName = content.name();
                    if (contentName == null) continue;

                    // Identify item type based on name patterns
                    if (contentName.contains("Hook")) {
                        equipment.hook = contentName;
                    } else if (contentName.contains("line") || contentName.contains("Line")) {
                        equipment.line = contentName;
                    } else {
                        // Assume anything else is bait/lure
                        equipment.bait = contentName;
                    }
                }
            }

        } catch (InterruptedException e) {
            /* Reading the equipped rod can block, and this runs on the UI thread from the fishing
             * window's save button - there is no bot thread above us to propagate to. Restore the flag
             * so the interrupt is not lost, and save the spot with whatever equipment we know. */
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            System.err.println("Error extracting fishing equipment: " + e);
            e.printStackTrace();
        }

        return equipment;
    }
}
