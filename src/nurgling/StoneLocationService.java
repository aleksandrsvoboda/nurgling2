package nurgling;

import haven.*;
import nurgling.profiles.ConfigFactory;
import nurgling.profiles.ProfileAwareService;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing discovered stone/ore locations in mines.
 * Records tile types as they are mined by tunneling bots.
 * Follows the same pattern as TreeLocationService.
 */
public class StoneLocationService implements ProfileAwareService {
    private final Map<String, StoneLocation> stoneLocations = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private String dataFile;
    private final NGameUI gui;
    private String genus;

    public StoneLocationService(NGameUI gui) {
        this.gui = gui;
        this.dataFile = NUtils.getDataFile("stone_locations.nurgling.json");
        loadStoneLocations();
    }

    public StoneLocationService(NGameUI gui, String genus) {
        this.gui = gui;
        this.genus = genus;
        initializeForProfile(genus);
    }

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig config = ConfigFactory.getConfig(genus);
        this.dataFile = config.getStoneLocationsPath();
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    @Override
    public void load() {
        loadStoneLocations();
    }

    @Override
    public void save() {
        lock.writeLock().lock();
        try {
            saveStoneLocations();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Save a stone/ore location discovered during mining.
     * @param stoneResource tile resource path, e.g. "gfx/tiles/rocks/feldspar"
     * @param worldPosition player world position (Coord2d)
     */
    public void saveStoneLocation(String stoneResource, Coord2d worldPosition) {
        try {
            if (gui.map == null) return;

            String stoneName = extractStoneName(stoneResource);
            if (stoneName == null) return;

            MCache mcache = gui.map.glob.map;
            Coord tc = worldPosition.floor(MCache.tilesz);
            Coord gridCoord = tc.div(MCache.cmaps);
            MCache.Grid grid = mcache.getgrid(gridCoord);

            MapFile mapFile = gui.mmap.file;
            MapFile.GridInfo info = mapFile.gridinfo.get(grid.id);
            if (info == null) return;

            long segmentId = info.seg;
            Coord segmentCoord = tc.add(info.sc.sub(grid.gc).mul(MCache.cmaps));

            lock.writeLock().lock();
            try {
                StoneLocation location = new StoneLocation(segmentId, segmentCoord, stoneName, stoneResource);
                stoneLocations.put(location.getLocationId(), location);
                saveStoneLocations();
            } finally {
                lock.writeLock().unlock();
            }

        } catch (Exception e) {
            System.err.println("Error saving stone location: " + e);
        }
    }

    /**
     * Extract a friendly display name from a tile resource path.
     * "gfx/tiles/rocks/feldspar" -> "Feldspar"
     * "gfx/tiles/rocks/lead-glance" -> "Lead Glance"
     */
    private String extractStoneName(String resourcePath) {
        if (resourcePath == null) return null;
        String prefix = "gfx/tiles/rocks/";
        if (!resourcePath.startsWith(prefix)) return null;

        String raw = resourcePath.substring(prefix.length());
        // Handle hyphenated names like "lead-glance"
        String[] parts = raw.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            String part = parts[i];
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    public List<StoneLocation> getStoneLocationsForSegment(long segmentId) {
        lock.readLock().lock();
        try {
            List<StoneLocation> result = new ArrayList<>();
            for (StoneLocation loc : stoneLocations.values()) {
                if (loc.getSegmentId() == segmentId) {
                    result.add(loc);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Collection<StoneLocation> getAllStoneLocations() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(stoneLocations.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean removeStoneLocation(String locationId) {
        lock.writeLock().lock();
        try {
            boolean removed = stoneLocations.remove(locationId) != null;
            if (removed) {
                saveStoneLocations();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadStoneLocations() {
        lock.writeLock().lock();
        try {
            stoneLocations.clear();
            String content = NFileUtils.readWithBackupFallback(dataFile);
            if (content != null && !content.isEmpty()) {
                try {
                    JSONObject main = new JSONObject(content);
                    JSONArray array = main.getJSONArray("stoneLocations");
                    for (int i = 0; i < array.length(); i++) {
                        StoneLocation location = new StoneLocation(array.getJSONObject(i));
                        stoneLocations.put(location.getLocationId(), location);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse stone locations JSON: " + e.getMessage());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveStoneLocations() {
        try {
            JSONObject main = new JSONObject();
            JSONArray jLocations = new JSONArray();
            for (StoneLocation location : stoneLocations.values()) {
                jLocations.put(location.toJson());
            }
            main.put("stoneLocations", jLocations);
            main.put("version", 1);
            main.put("lastSaved", java.time.Instant.now().toString());

            NFileUtils.writeAtomically(dataFile, main.toString(2));
        } catch (IOException e) {
            System.err.println("Failed to save stone locations: " + e.getMessage());
        }
    }

    public void dispose() {
        lock.writeLock().lock();
        try {
            saveStoneLocations();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
