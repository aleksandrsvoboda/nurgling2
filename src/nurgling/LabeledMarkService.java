package nurgling;

import haven.*;
import nurgling.profiles.ConfigFactory;
import nurgling.profiles.ProfileAwareService;
import nurgling.tools.NFileUtils;
import nurgling.widgets.LabeledMinimapMark;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.awt.image.BufferedImage;

/**
 * Service for managing labeled minimap marks (water/soil quality marks from Checker bots).
 * Supports persistence and world-specific profiles via ProfileAwareService.
 *
 * Reads are lock-free: the render thread asks for a segment's marks every frame, so
 * {@link #segIndex} holds pre-grouped immutable lists that are swapped in on mutation.
 * Writes to disk are coalesced onto a background thread and never run while a lock is
 * held, so a long save cannot stall rendering.
 */
public class LabeledMarkService implements ProfileAwareService {
    private final Map<String, LabeledMinimapMark> labeledMarks = new ConcurrentHashMap<>();
    /** Immutable per-segment view of {@link #labeledMarks}, replaced wholesale on change. */
    private volatile Map<Long, List<LabeledMinimapMark>> segIndex = Collections.emptyMap();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private String dataFile;
    private final NGameUI gui;
    private String genus;

    /* Background writer. saveQueued gates enqueuing so a burst of samples collapses
     * into a single write; it is cleared as the write starts, so a sample taken during
     * a write still queues the next one. */
    private final Object writeLock = new Object();
    /** Serializes the actual file writes so the background and shutdown writers cannot overlap. */
    private final Object fileLock = new Object();
    private Thread writer;
    private boolean saveQueued = false;
    private boolean shutdown = false;

    public LabeledMarkService(NGameUI gui) {
        this.gui = gui;
        this.dataFile = NUtils.getDataFile("labeled_marks.nurgling.json");
        loadLabeledMarks();
    }

    /**
     * Constructor for profile-aware initialization
     */
    public LabeledMarkService(NGameUI gui, String genus) {
        this.gui = gui;
        this.genus = genus;
        initializeForProfile(genus);
    }

    // ProfileAwareService implementation

    @Override
    public void initializeForProfile(String genus) {
        this.genus = genus;
        NConfig config = ConfigFactory.getConfig(genus);
        this.dataFile = config.getLabeledMarksPath();
        load();
    }

    @Override
    public String getGenus() {
        return genus;
    }

    @Override
    public void load() {
        loadLabeledMarks();
    }

    @Override
    public void save() {
        writeSnapshot(snapshot());
    }

    /**
     * Add a labeled mark (e.g., water or soil quality).
     * Removes any existing mark at the same location.
     */
    public void addLabeledMark(String label, String resourceType, double quality, long segmentId,
                               Coord tileCoords, BufferedImage iconImage) {
        lock.writeLock().lock();
        try {
            // Remove any existing mark at similar location
            final Coord tc = tileCoords;
            final long segId = segmentId;
            labeledMarks.entrySet().removeIf(e -> e.getValue().isNear(segId, tc, 2));

            // Create and add new mark
            LabeledMinimapMark mark = new LabeledMinimapMark(label, resourceType, quality, segmentId, tileCoords, iconImage);
            labeledMarks.put(mark.getLocationId(), mark);
            reindex();
        } finally {
            lock.writeLock().unlock();
        }
        scheduleSave();
    }

    /**
     * Get all labeled marks for a segment (for map rendering).
     * The returned list is immutable and safe to iterate without copying.
     */
    public List<LabeledMinimapMark> getMarksForSegment(long segmentId) {
        List<LabeledMinimapMark> marks = segIndex.get(segmentId);
        return (marks == null) ? Collections.emptyList() : marks;
    }

    /**
     * Get all labeled marks.
     */
    public Collection<LabeledMinimapMark> getAllMarks() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(labeledMarks.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Remove a labeled mark by location ID.
     */
    public boolean removeMark(String locationId) {
        boolean removed;
        lock.writeLock().lock();
        try {
            removed = labeledMarks.remove(locationId) != null;
            if (removed) {
                reindex();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (removed) {
            scheduleSave();
        }
        return removed;
    }

    /**
     * Remove a labeled mark object.
     */
    public boolean removeMark(LabeledMinimapMark mark) {
        if (mark == null) return false;
        return removeMark(mark.getLocationId());
    }

    /**
     * Find a mark at given segment and tile coordinates.
     */
    public LabeledMinimapMark findMarkAt(long segmentId, Coord tileCoords, int radiusTiles) {
        for (LabeledMinimapMark mark : getMarksForSegment(segmentId)) {
            if (mark.isNear(segmentId, tileCoords, radiusTiles)) {
                return mark;
            }
        }
        return null;
    }

    /**
     * Rebuild the per-segment render index. Called under the write lock.
     */
    private void reindex() {
        Map<Long, List<LabeledMinimapMark>> next = new HashMap<>();
        for (LabeledMinimapMark mark : labeledMarks.values()) {
            next.computeIfAbsent(mark.segmentId, k -> new ArrayList<>()).add(mark);
        }
        for (Map.Entry<Long, List<LabeledMinimapMark>> e : next.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        segIndex = next;
    }

    /**
     * Load labeled marks from JSON.
     */
    private void loadLabeledMarks() {
        lock.writeLock().lock();
        try {
            labeledMarks.clear();
            String content = NFileUtils.readWithBackupFallback(dataFile);
            if (content != null && !content.isEmpty()) {
                try {
                    JSONObject main = new JSONObject(content);
                    /* Shared icon table (format 2). Registered before the marks so no
                     * per-mark legacy icon has to be decoded. */
                    JSONObject icons = main.optJSONObject("icons");
                    if (icons != null) {
                        for (String type : icons.keySet()) {
                            LabeledMinimapMark.registerIcon(type,
                                LabeledMinimapMark.decodeIcon(icons.optString(type, null)));
                        }
                    }
                    JSONArray array = main.getJSONArray("labeledMarks");
                    for (int i = 0; i < array.length(); i++) {
                        try {
                            LabeledMinimapMark mark = new LabeledMinimapMark(array.getJSONObject(i));
                            labeledMarks.put(mark.getLocationId(), mark);
                        } catch (RuntimeException e) {
                            System.err.println("Failed to parse labeled mark: " + e.getMessage());
                        }
                    }
                } catch (RuntimeException e) {
                    System.err.println("Failed to parse labeled marks JSON: " + e.getMessage());
                }
            }
            reindex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Take a consistent copy of the marks to serialize outside the lock.
     */
    private List<LabeledMinimapMark> snapshot() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(labeledMarks.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Serialize and write the given marks. Must not be called while holding a lock:
     * PNG encoding and file I/O here take long enough to stall the render thread.
     */
    private void writeSnapshot(List<LabeledMinimapMark> marks) {
        synchronized (fileLock) {
        try {
            JSONObject main = new JSONObject();
            JSONArray jMarks = new JSONArray();
            Set<String> types = new HashSet<>();
            for (LabeledMinimapMark mark : marks) {
                jMarks.put(mark.toJson());
                types.add(mark.resourceType);
            }
            /* One icon per resource type instead of one per mark: the old format
             * re-encoded every icon on every save, which grew with the sample count. */
            JSONObject icons = new JSONObject();
            for (String type : types) {
                String encoded = LabeledMinimapMark.iconBase64(type);
                if (encoded != null) {
                    icons.put(type, encoded);
                }
            }
            main.put("labeledMarks", jMarks);
            main.put("icons", icons);
            main.put("version", 2);
            main.put("lastSaved", java.time.Instant.now().toString());

            NFileUtils.writeAtomically(dataFile, main.toString());
        } catch (IOException e) {
            System.err.println("Failed to save labeled marks: " + e.getMessage());
        }
        }
    }

    /**
     * Request a save. Saves are coalesced and run on a background thread so that
     * sampling many spots in a row never blocks the game.
     */
    private void scheduleSave() {
        synchronized (writeLock) {
            if (shutdown || saveQueued) {
                return;
            }
            saveQueued = true;
            if (writer == null) {
                writer = new Thread(this::writeLoop, "labeled-marks-writer");
                writer.setDaemon(true);
                writer.start();
            }
            writeLock.notifyAll();
        }
    }

    /**
     * Drains save requests until the service is disposed. Interruption ends the
     * thread; it is a daemon and the final save happens in {@link #dispose()}.
     */
    private void writeLoop() {
        while (true) {
            synchronized (writeLock) {
                while (!saveQueued && !shutdown) {
                    try {
                        writeLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (shutdown) {
                    return;
                }
                saveQueued = false;
            }
            writeSnapshot(snapshot());
        }
    }

    /**
     * Dispose the service and cleanup resources.
     */
    public void dispose() {
        synchronized (writeLock) {
            shutdown = true;
            writer = null;
            writeLock.notifyAll();
        }
        /* Blocks on fileLock until any in-flight background write finishes, so the
         * final state always lands last. */
        writeSnapshot(snapshot());
    }
}
