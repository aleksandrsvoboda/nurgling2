package mapv4;

import haven.*;
import haven.res.ui.obj.buddy.Buddy;
import nurgling.NAlarmManager;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.tools.Finder;
import nurgling.tools.NParser;
import nurgling.widgets.NAlarmWdg;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class Requestor implements Action {
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int PREPGRID_RETRY_LIMIT = 3;
    private static final long TASK_TIMEOUT_MS = 30000; // 30 seconds max lifetime for a task
    private static final long GRID_TASK_TIMEOUT_MS = 15000; // 15 seconds for grid-related tasks
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long GRID_RETRY_DELAY_MS = 250;
    private static final int MARKER_GRID_RETRIES = 40; // ~10s for a single marker
    private static final int SCAN_RETRIES = 240;       // ~60s for the login-time sweep
    private static final long LOCK_WAIT_MS = 2000;

    public final BlockingQueue<MapperTask> list = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private final List<MapperTask> deferred = new ArrayList<>();
    private final Map<String, Integer> prepGridRetries = new HashMap<>();
    private long lastCleanupTime = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL_MS = 5000; // Cleanup every 5 seconds
    NMappingClient parent;
    
    public Requestor(NMappingClient parent) {
        this.parent = parent;
    }

    private static boolean kamiCompat() {
        return Boolean.TRUE.equals(NConfig.get(NConfig.Key.kamiCompatMapper));
    }



    public class MapperTask
    {
        String type;
        Object[] args;
        final long createdAt;
        long dueAt = 0;
        int retries = 0;

        public MapperTask(String type, Object[] args) {
            this.type = type;
            this.args = args;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired() {
            long timeout = getTimeoutForType(type);
            return System.currentTimeMillis() - createdAt > timeout;
        }

        /**
         * The map sweep paces itself with its own retry budget and accumulates
         * results across passes, so discarding it mid-flight throws away
         * everything it has collected. It bounds itself instead.
         */
        public boolean droppableWhenExpired() {
            return !type.equals("processMap");
        }

        private long getTimeoutForType(String taskType) {
            switch (taskType) {
                case "prepGrid":
                case "reqGrid":
                case "overlayUpload":
                    return GRID_TASK_TIMEOUT_MS;
                default:
                    return TASK_TIMEOUT_MS;
            }
        }

        @Override
        public String toString() {
            return "MapperTask{" +
                    "type='" + type + '\'' +
                    ", age=" + (System.currentTimeMillis() - createdAt) + "ms" +
                    '}';
        }
    }




    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        while (!parent.done.get()) {
            // Periodic cleanup of expired tasks
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastCleanupTime > CLEANUP_INTERVAL_MS) {
                cleanupExpiredTasks();
                lastCleanupTime = currentTime;
            }
            
            // Re-arm tasks that were waiting on a grid load, and shorten the
            // poll so the next one is picked up on time rather than a second late.
            long wait = POLL_INTERVAL_MS;
            for (Iterator<MapperTask> di = deferred.iterator(); di.hasNext(); ) {
                MapperTask d = di.next();
                long left = d.dueAt - currentTime;
                if (left <= 0) {
                    // Keep it deferred rather than dropping it if the queue is full.
                    if (list.offer(d))
                        di.remove();
                    else
                        wait = Math.min(wait, GRID_RETRY_DELAY_MS);
                } else {
                    wait = Math.min(wait, left);
                }
            }

            MapperTask task = list.poll(wait, TimeUnit.MILLISECONDS);
            if (task != null) {
                // Skip expired tasks to prevent queue buildup
                if (task.isExpired() && task.droppableWhenExpired()) {
                    continue;
                }
                // GameUI may be transiently null on this background thread during
                // login / session transitions; skip the task until it is ready.
                NGameUI rgui = NUtils.getGameUI();
                if (rgui == null || rgui.map == null || rgui.map.glob == null) {
                    continue;
                }
                switch (task.type) {
                    case "reqGrid": {
                        String[][] gridMap = NUtils.getGameUI().map.glob.map.constructSection((Coord)task.args[0]);
                        if (gridMap == null) {
                            continue;
                        }
                        JSONObject data = new JSONObject();
                        data.put("grids", gridMap);
                        if (kamiCompat())
                            data.put("genus", NUtils.getGameUI().getGenus());
                        JSONObject msg = new JSONObject();
                        msg.put("data", data);
                        msg.put("reqMethod", "POST");
                        msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/gridUpdate");
                        msg.put("header", "GRIDREQ");
                        if (!parent.connector.msgs.offer(msg)) {
                            // Queue is full, drop oldest non-critical message
                        }
                        break;
                    }
                    case "prepGrid": {
                        String gridID = (String)task.args[0];
                        MCache.Grid g = (MCache.Grid)task.args[1];
                        NGameUI gameUI = NUtils.getGameUI();
                        if(g != null && gameUI != null && gameUI.map != null && gameUI.map.glob != null) {
                            try {
                                BufferedImage image = MinimapImageGenerator.drawmap(gameUI.map.glob.map, g);
                                if(image == null) {
                                    int retries = prepGridRetries.getOrDefault(gridID, 0);
                                    if (retries < PREPGRID_RETRY_LIMIT) {
                                        prepGridRetries.put(gridID, retries + 1);
                                        list.offer(task);
                                    } else {
                                        prepGridRetries.remove(gridID);
                                    }
                                    continue;
                                }
                                prepGridRetries.remove(gridID);
                                
                                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                                ImageIO.write(image, "png", outputStream);
                                ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
                                
                                JSONObject data = new JSONObject();
                                data.put("inputStream", inputStream);
                                data.put("gridID", gridID);
                                JSONObject msg = new JSONObject();
                                msg.put("data", data);
                                msg.put("reqMethod", "MULTI");
                                msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/gridUpload");
                                msg.put("header", "GRIDUPLOAD");
                                
                                if (!parent.connector.msgs.offer(msg)) {
                                    // Queue is full, image generation wasted but avoids blocking
                                }
                            } catch (IOException e) {
                                // Failed to generate image, don't retry
                                prepGridRetries.remove(gridID);
                            }
                        }
                        break;
                    }
                    case "track":
                    {
                        Gob player = NUtils.player();

                        if(player != null) {
                            MCache.Grid g = null;
                            try {
                                g = NUtils.getGameUI().map.glob.map.getgrid(NUtils.toGC(player.rc));

                            }
                            catch (MCache.LoadingMap e) {
                            }

                            if(g == null) {
                                continue;
                            }
                            Coord2d coords = NUtils.gridOffset(player.rc);
                            JSONObject data = new JSONObject();
                            JSONObject prop = new JSONObject();
                            prop.put("name", NUtils.getGameUI().chrid);
                            if (kamiCompat())
                                prop.put("genus", NUtils.getGameUI().getGenus());
                            prop.put("type", "player");
                            prop.put("gridID", String.valueOf(g.id));
                            JSONObject c = new JSONObject();
                            c.put("x", (int) (coords.x / MCache.tilesz.x));
                            c.put("y", (int) (coords.y / MCache.tilesz.y));
                            prop.put("coords", c);
                            data.put(String.valueOf(player.id), prop);

                            List<Long> borkas;
                            synchronized (NAlarmWdg.borkas) {
								borkas = new ArrayList<>(NAlarmWdg.borkas);
                            }
                            for(Long id: borkas)
                            {
                                Gob borka = Finder.findGob(id);
                                if(borka!=null)
                                {
                                    String pose=borka.pose();
                                    if(pose == null)
                                    {
                                        continue;
                                    }
                                    else
                                    {
                                        if(NParser.checkName(pose, "dead"))
                                            continue;
                                    }
                                    MCache.Grid gb = null;
                                    try {
                                        gb = NUtils.getGameUI().map.glob.map.getgrid(NUtils.toGC(borka.rc));
                                    } catch (MCache.LoadingMap e) {
                                        continue;
                                    }
                                    if(gb == null) {
                                        continue;
                                    }
                                    JSONObject propb = new JSONObject();
                                    propb.put("name", "???");
                                    if (kamiCompat())
                                        propb.put("genus", NUtils.getGameUI().getGenus());
                                    propb.put("type", "white");
                                    Buddy buddy = borka.getattr(Buddy.class);
                                    if (buddy != null &&  buddy.b!=null) {
                                        propb.put("name", buddy.b.name);
                                        propb.put("type", Integer.toHexString(BuddyWnd.gc[buddy.b.group].getRGB()));
                                    }
                                    propb.put("gridID", String.valueOf(gb.id));
                                    JSONObject cb = new JSONObject();
                                    Coord2d coordsb = NUtils.gridOffset(borka.rc);
                                    cb.put("x", (int) (coordsb.x / MCache.tilesz.x));
                                    cb.put("y", (int) (coordsb.y / MCache.tilesz.y));
                                    propb.put("coords", cb);
                                    data.put(String.valueOf(borka.id), propb);
                                }
                            }
                            JSONObject msg = new JSONObject();
                            msg.put("data", data);
                            msg.put("reqMethod", "POST");
                            msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/positionUpdate");
                            msg.put("header", "TRACKING");
                            if (!parent.connector.msgs.offer(msg)) {
                                // Queue full, tracking update dropped
                            }
                        }
                        break;
                    }
                    case "processMap":
                    {
                        MapScan scan = (MapScan)task.args[0];

                        // First pass: snapshot the markers we care about and kick
                        // off the load of every grid they sit on.
                        if (scan.pending == null) {
                            if (!scan.file.lock.readLock().tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                                retry(task, SCAN_RETRIES);
                                break;
                            }
                            try {
                                List<MarkerData> found = new ArrayList<>();
                                for (MapFile.Marker m : scan.file.markers) {
                                    if (!scan.filter.test(m))
                                        continue;
                                    try {
                                        MapFile.Segment.ByCoord ref = gridref(scan.file, m);
                                        if (ref != null)
                                            found.add(new MarkerData(m, ref));
                                    } catch (Message.BinError e) {
                                        // Unreadable segment; the other markers are still fine.
                                    }
                                }
                                scan.pending = found;
                            } finally {
                                scan.file.lock.readLock().unlock();
                            }
                        }

                        // Later passes: harvest every marker whose grid has arrived.
                        for (Iterator<MarkerData> mi = scan.pending.iterator(); mi.hasNext(); ) {
                            MarkerData md = mi.next();
                            MapFile.Segment.Cached cur = md.grid.cur;
                            if (cur == null) {
                                // No grid was ever mapped at that coordinate.
                                mi.remove();
                                continue;
                            }
                            if (!cur.loading.done())
                                continue;
                            mi.remove();
                            try {
                                MapFile.Grid g = cur.get();
                                if (g != null)
                                    scan.ready.add(markerJSON(md.m, g.id));
                            } catch (Loading | Defer.DeferredException e) {
                                // Grid failed to load; skip this marker.
                            }
                        }

                        if (!scan.pending.isEmpty() && (task.retries < SCAN_RETRIES)) {
                            retry(task, SCAN_RETRIES);
                            break;
                        }
                        if (scan.ready.isEmpty())
                            break;

                        JSONObject msg = new JSONObject();
                        msg.put("data", new JSONArray(scan.ready.toArray()));
                        msg.put("reqMethod", "POST");
                        msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/markerUpdate");
                        msg.put("header", "MARKERS");
                        if (!parent.connector.msgs.offer(msg)) {
                            // Queue full, markers update dropped
                        }
                        break;
                    }
                    case "uploadMarker":
                    {
                        Gob gob = (Gob)task.args[0];
                        MapFile.SMarker marker = (MapFile.SMarker)task.args[1];
                        try {
                            MCache.Grid grid = NUtils.getGameUI().map.glob.map.getgrid(NUtils.toGC(gob.rc));
                            Coord offset = NUtils.gridOffset2(gob.rc);

                            JSONObject obj = new JSONObject();
                            obj.put("name", marker.nm);
                            if (kamiCompat())
                                obj.put("genus", NUtils.getGameUI().getGenus());
                            obj.put("gridID", String.valueOf(grid.id));
                            obj.put("x", offset.x);
                            obj.put("y", offset.y);
                            obj.put("type", "shared");
                            obj.put("id", marker.oid.bits);
                            obj.put("image", marker.res.name);

                            JSONObject msg = new JSONObject();
                            msg.put("data", new JSONArray(List.of(obj)));
                            msg.put("reqMethod", "POST");
                            msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/markerUpdate");
                            msg.put("header", "SMARKER");
                            if (!parent.connector.msgs.offer(msg)) {
                                // Queue full, marker update dropped
                            }
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                    case "uploadPMarker":
                    {
                        // Player-placed flags carry no Gob, so the grid has to be
                        // resolved out of the map file's own segment index.
                        MapFile mapfile = (MapFile)task.args[0];
                        MapFile.PMarker marker = (MapFile.PMarker)task.args[1];
                        if (!uploadable(marker))
                            break;
                        MapFile.Segment.ByCoord ref;
                        if (!mapfile.lock.readLock().tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                            retry(task, MARKER_GRID_RETRIES);
                            break;
                        }
                        try {
                            ref = gridref(mapfile, marker);
                        } catch (Message.BinError e) {
                            break;
                        } finally {
                            mapfile.lock.readLock().unlock();
                        }
                        if ((ref == null) || (ref.cur == null))
                            break;
                        if (!ref.cur.loading.done()) {
                            retry(task, MARKER_GRID_RETRIES);
                            break;
                        }
                        MapFile.Grid grid;
                        try {
                            grid = ref.cur.get();
                        } catch (Loading | Defer.DeferredException e) {
                            break;
                        }
                        if (grid == null)
                            break;

                        JSONObject msg = new JSONObject();
                        msg.put("data", new JSONArray(List.of(markerJSON(marker, grid.id))));
                        msg.put("reqMethod", "POST");
                        msg.put("url", (String)NConfig.get(NConfig.Key.endpoint) + "/markerUpdate");
                        msg.put("header", "PMARKER");
                        if (!parent.connector.msgs.offer(msg)) {
                            // Queue full, marker update dropped
                        }
                        break;
                    }
                    case "overlayUpload":
                    {
                        long gridId = (Long) task.args[0];
                        MCache.Grid grid = (MCache.Grid) task.args[1];

                        if (grid == null) {
                            continue;
                        }

                        List<OverlayData> overlays = OverlayExtractor.extractOverlays(grid, gridId);
                        if (overlays.isEmpty()) {
                            continue;
                        }

                        // Check if changed since last send
                        int hash = OverlayExtractor.computeHash(overlays);
                        if (!parent.hasOverlayChanged(gridId, hash)) {
                            continue;
                        }

                        // Build JSON payload
                        JSONObject data = new JSONObject();
                        data.put("gridId", String.valueOf(gridId));
                        if (kamiCompat())
                            data.put("genus", NUtils.getGameUI().getGenus());
                        JSONArray overlayArray = new JSONArray();
                        for (OverlayData ol : overlays) {
                            overlayArray.put(ol.toJSON());
                        }
                        data.put("overlays", overlayArray);

                        // Queue for sending
                        JSONObject msg = new JSONObject();
                        msg.put("data", data);
                        msg.put("reqMethod", "POST");
                        msg.put("url", (String) NConfig.get(NConfig.Key.endpoint) + "/overlayUpload");
                        msg.put("header", "OVERLAY");

                        if (!parent.connector.msgs.offer(msg)) {
                            // Queue full, overlay update dropped
                        }
                        break;
                    }
                }
            }
        }
        return Results.SUCCESS();
    }

    public void senGridRequest(Coord lastGC) {
        // Avoid duplicate reqGrid tasks for same coordinates
        for(MapperTask task : list) {
            if(task.type.equals("reqGrid") && task.args != null && lastGC.equals(task.args[0])) {
                return;
            }
        }
        // Clean up expired tasks periodically when adding new ones
        cleanupExpiredTasks();
        list.offer(new MapperTask("reqGrid", new Object[]{lastGC}));
    }

    public void prepGrid(String string, MCache.Grid g) {
        // Avoid duplicate prepGrid tasks for same grid
        for(MapperTask task : list) {
            if(task.type.equals("prepGrid") && task.args != null && string.equals(task.args[0])) {
                return;
            }
        }
        list.offer(new MapperTask("prepGrid", new Object[]{string, g}));
    }

    public void track() {
        // Check if track task already exists to avoid duplicates
        for(MapperTask task : list) {
            if(task.type.equals("track")) {
                return;
            }
        }
        list.offer(new MapperTask("track", null));
    }
    
    /**
     * Removes expired tasks from the queue to prevent buildup.
     * Called periodically when adding new tasks.
     */
    private void cleanupExpiredTasks() {
        list.removeIf(t -> t.isExpired() && t.droppableWhenExpired());
    }

    /**
     * Steps a task aside until its map grid has had a chance to load, rather
     * than blocking the requestor thread or busy-requeueing it.
     */
    private void retry(MapperTask task, int limit) {
        if (task.retries++ >= limit)
            return;
        task.dueAt = System.currentTimeMillis() + GRID_RETRY_DELAY_MS;
        deferred.add(task);
    }

    /**
     * Locates the map-file grid a marker sits on. The caller must hold
     * {@code file.lock.readLock()} - the segment index checks for it.
     */
    private static MapFile.Segment.ByCoord gridref(MapFile file, MapFile.Marker m) {
        MapFile.Segment seg = file.segments.get(m.seg);
        if (seg == null)
            return null;
        Coord mgc = new Coord(Math.floorDiv(m.tc.x, 100), Math.floorDiv(m.tc.y, 100));
        Indir<MapFile.Grid> ind = seg.grid(mgc);
        return (ind instanceof MapFile.Segment.ByCoord) ? (MapFile.Segment.ByCoord)ind : null;
    }

    /**
     * Sharing player-placed flags is opt-in and limited to green ones - see the
     * "Upload green markers" setting. Resource markers are always shared.
     */
    public static boolean uploadable(MapFile.Marker m) {
        if (m instanceof MapFile.PMarker) {
            return Boolean.TRUE.equals(NConfig.get(NConfig.Key.unloadgreen))
                    && ((MapFile.PMarker)m).color.equals(Color.GREEN);
        }
        return true;
    }

    private static JSONObject markerJSON(MapFile.Marker m, long gridId) {
        Coord mgc = new Coord(Math.floorDiv(m.tc.x, 100), Math.floorDiv(m.tc.y, 100));
        Coord offset = m.tc.sub(mgc.mul(100));
        JSONObject o = new JSONObject();
        o.put("name", m.nm);
        if (kamiCompat())
            o.put("genus", NUtils.getGameUI().getGenus());
        o.put("gridID", String.valueOf(gridId));
        o.put("x", offset.x);
        o.put("y", offset.y);
        if (m instanceof MapFile.SMarker) {
            o.put("type", "shared");
            o.put("id", ((MapFile.SMarker)m).oid.bits);
            o.put("image", ((MapFile.SMarker)m).res.name);
        } else if (m instanceof MapFile.PMarker) {
            // Deliberately no "image": the server falls back to its custom-marker
            // icon for player flags. Colour goes out as hex like the tracking
            // payload does - a bare java.awt.Color stringifies into garbage.
            o.put("type", "player");
            o.put("color", Integer.toHexString(((MapFile.PMarker)m).color.getRGB()));
        }
        return o;
    }

    public void processMap(MapFile mapfile, Predicate<MapFile.Marker> uploadCheck) {
        list.offer(new MapperTask("processMap", new Object[]{new MapScan(mapfile, uploadCheck)}));
    }

    public void uploadSMarker(Gob gob, MapFile.SMarker marker) {
        list.offer(new MapperTask("uploadMarker", new Object[]{gob, marker}));
    }

    public void uploadPMarker(MapFile file, MapFile.PMarker marker) {
        for (MapperTask task : list) {
            if (task.type.equals("uploadPMarker") && task.args != null && task.args[1] == marker) {
                return;
            }
        }
        list.offer(new MapperTask("uploadPMarker", new Object[]{file, marker}));
    }

    public void sendOverlayUpdate(long gridId, MCache.Grid grid) {
        // Avoid duplicate tasks for the same grid
        for (MapperTask task : list) {
            if (task.type.equals("overlayUpload") &&
                task.args != null &&
                task.args.length > 0 &&
                Long.valueOf(gridId).equals(task.args[0])) {
                return;
            }
        }
        list.offer(new MapperTask("overlayUpload", new Object[]{gridId, grid}));
    }

    private static class MarkerData {
        final MapFile.Marker m;
        final MapFile.Segment.ByCoord grid;

        MarkerData(MapFile.Marker m, MapFile.Segment.ByCoord grid) {
            this.m = m;
            this.grid = grid;
        }
    }

    /**
     * State for the login-time marker sweep, carried across retries while the
     * grids the markers sit on are still loading.
     */
    private static class MapScan {
        final MapFile file;
        final Predicate<MapFile.Marker> filter;
        final List<JSONObject> ready = new ArrayList<>();
        List<MarkerData> pending = null;

        MapScan(MapFile file, Predicate<MapFile.Marker> filter) {
            this.file = file;
            this.filter = filter;
        }
    }
}
