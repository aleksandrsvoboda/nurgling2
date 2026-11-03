package mapv4;

import haven.Coord;
import haven.Gob;
import haven.MapFile;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class NMappingClient {
    private static final int MAX_CACHE_SIZE = 10000;
    private static final long CACHE_CLEANUP_INTERVAL_MS = 300000; // 5 minutes
    
    Coord lastGC = Coord.z;

    public Connector connector;
    public Requestor requestor;
    public AtomicBoolean done = new AtomicBoolean(false);
    private Boolean autoMapper = null;
    public final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<Long, Integer> overlayHashes = new ConcurrentHashMap<>();
    private volatile boolean overlaySupported = true;
    public Thread reqTread = null;
    public Thread conTread = null;
    long lastTracking = -1;
    private long lastCacheCleanup = System.currentTimeMillis();
    
    static class CacheEntry {
        final MapRef mapRef;
        long lastAccess;
        
        CacheEntry(MapRef mapRef) {
            this.mapRef = mapRef;
            this.lastAccess = System.currentTimeMillis();
        }
    }
    public void tick(double dt)
    {
        /* ui.gui is never cleared, so on the character list it still points at the destroyed game UI;
         * a destroyed widget has no parent. Only map for a live one. */
        NGameUI gui = NUtils.getGameUI();
        boolean liveGui = gui != null && gui.parent != null;
        if(liveGui)
        {
            Boolean newState = (Boolean)NConfig.get(NConfig.Key.autoMapper);
            if(newState != null && !newState.equals(autoMapper)) {
                if(newState)
                {
                    /* After a stop the previous pair may still be finishing an upload; they read the
                     * same done flag, so start the new pair only once they are gone. */
                    if(!threadsAlive())
                        start();
                }
                else
                {
                    autoMapper = false;
                    stopThreads();
                }
            }
        }

        // Periodic cache cleanup
        long currentTimeMs = System.currentTimeMillis();
        if(currentTimeMs - lastCacheCleanup > CACHE_CLEANUP_INTERVAL_MS) {
            cleanupCache();
            lastCacheCleanup = currentTimeMs;
        }

        Gob player = liveGui ? NUtils.player() : null;
        if(player!=null) {

            Coord current = NUtils.toGC(player.rc);
            if(!current.equals(lastGC)) {
                lastGC = current;
                requestor.senGridRequest(lastGC);
            }

            if(currentTimeMs-lastTracking>2000)
            {
                lastTracking = currentTimeMs;
                requestor.track();
            }
        }
    }

    private boolean threadsAlive() {
        return (reqTread != null && reqTread.isAlive()) || (conTread != null && conTread.isAlive());
    }

    private void start() {
        autoMapper = true;
        done.set(false);

        (reqTread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    requestor.run(NUtils.getGameUI());
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }, "automapper-requestor")).start();

        (conTread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    connector.run(NUtils.getGameUI());
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }, "automapper-connector")).start();
    }

    private void stopThreads() {
        done.set(true);
        if(reqTread != null)
            reqTread.interrupt();
        if(conTread != null)
            conTread.interrupt();
    }

    /**
     * The game UI is gone (logout, bed, or the whole UI closing). Stop the mapper threads but forget the
     * running state, so tick() starts them again for the next character's game UI, and forget the last
     * grid and track time so that character's first grid is requested even if it logs in on the same one.
     */
    public void stop() {
        stopThreads();
        autoMapper = null;
        lastGC = Coord.z;
        lastTracking = -1;
    }

    public NMappingClient() {
        requestor = new Requestor(this);
        connector = new Connector(this);
        done.set(false);
    }

    public static class MapRef {
        public Coord gc;
        public long mapID;

        MapRef(long mapID, Coord gc) {
            this.gc = gc;
            this.mapID = mapID;
        }

        public String toString() {
            return (gc.toString() + " in map space " + mapID);
        }
    }

    public MapRef GetMapRef() {
        Gob player = NUtils.player();
        if(player == null) return null;
        
        Coord gc = NUtils.toGC(player.rc);
        try {
            long id = NUtils.getGameUI().map.glob.map.getgrid(gc).id;
            CacheEntry entry = cache.get(id);
            if(entry != null) {
                entry.lastAccess = System.currentTimeMillis();
                return entry.mapRef;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    private void cleanupCache() {
        if(cache.size() <= MAX_CACHE_SIZE) {
            return;
        }

        // Remove oldest entries
        List<Map.Entry<Long, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().lastAccess));

        int toRemove = cache.size() - (MAX_CACHE_SIZE * 3 / 4);
        for(int i = 0; i < toRemove && i < entries.size(); i++) {
            cache.remove(entries.get(i).getKey());
        }

        // Also cleanup overlay hashes if too large
        if (overlayHashes.size() > MAX_CACHE_SIZE) {
            overlayHashes.clear();
        }
    }

    public boolean hasOverlayChanged(long gridId, int newHash) {
        Integer oldHash = overlayHashes.get(gridId);
        if (oldHash == null || !oldHash.equals(newHash)) {
            overlayHashes.put(gridId, newHash);
            return true;
        }
        return false;
    }

    public boolean isOverlaySupported() {
        return overlaySupported;
    }

    public void setOverlayUnsupported() {
        overlaySupported = false;
    }

    public void resetOverlaySupport() {
        overlaySupported = true;
        overlayHashes.clear();
    }


    public void OpenMap() {
        MapRef mapRef = GetMapRef();
        if (mapRef != null) {
            try {
                String urlString = String.format((String) NConfig.get(NConfig.Key.endpoint) + "/#/grid/%d/%d/%d/6", 
                    mapRef.mapID, mapRef.gc.x, mapRef.gc.y);
                NUtils.getGameUI().ui.wnd.toolkit().browse(URI.create(urlString));
            } catch (Exception e) {
                NUtils.getGameUI().error("Invalid URL: " + e.getMessage());
            }
        }
        else
        {
            NUtils.getGameUI().error("Can't open Map");
        }
    }

    public void uploadSMarker(Gob gob, MapFile.SMarker marker) {
        if (requestor != null) {
            requestor.uploadSMarker(gob, marker);
        }
    }

    public void uploadPMarker(MapFile file, MapFile.PMarker marker) {
        if (requestor != null) {
            requestor.uploadPMarker(file, marker);
        }
    }
}
