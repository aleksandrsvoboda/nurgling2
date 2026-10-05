package nurgling.diagnostics;

import haven.*;
import haven.render.Homo3D;
import java.awt.event.KeyEvent;
import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import com.sun.management.GarbageCollectionNotificationInfo;
import nurgling.i18n.L10n;

/** Bounded, in-memory diagnostics. Never changes movement or waits for disk on a game thread. */
public final class MovementTrace implements Disposable {
    public static final KeyBinding capture = KeyBinding.get("movement-trace", KeyMatch.forcode(KeyEvent.VK_F9, KeyMatch.C));
    static final double HISTORY = 10, TAIL = 2, RETENTION = 15;
    static final int FIELDS = 60;
    static final String HEADER = "time_s,frame,player_id,subject_id,server_x,server_y,predicted_x,predicted_y,predicted_z,placed_x,placed_y,placed_z,lin_t,lin_server_t,lin_end,velocity_x,velocity_y,prediction_stopped,following_id,frame_ms,world_tick_ms,graphics_tick_ms,ui_tick_ms,draw_ms,sync_wait_ms,all_wait_ms,sample_ms,cam_0,cam_1,cam_2,cam_3,cam_4,cam_5,cam_6,cam_7,cam_8,cam_9,cam_10,cam_11,cam_12,cam_13,cam_14,cam_15,before_t,reported_t,reported_end,gc_duration_ms,gc_start_s,gc_end_s,phase_age_ms,received_s,queued_s,server_frame,packet_id,delta_type,gob_lock_ms,apply_ms,pending_attrs,stage_start_s,stage_ms";
    static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "movement-diagnostics"); t.setDaemon(true); return t;
    });
    private static volatile MovementTrace active;
    private static boolean started;
    private final Ring ring = new Ring(16384);
    private final Glob glob;
    private final double epoch = Utils.rtime();
    private final String wallEpoch = Instant.now().toString();
    private volatile long player = -1, subject = -1;
    private volatile Thread thread;
    private volatile haven.render.Environment renderer;
    private volatile String phase = "idle";
    private volatile double phaseStart, lastStack;
    private volatile boolean disposed;
    private final java.util.concurrent.atomic.AtomicReference<String> notice = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile String metadata = "";
    private boolean pending;
    private double previous = Double.NaN;

    public static boolean tracks(Glob glob, long id) {
        return tracks(active, glob, id);
    }
    private static boolean tracks(MovementTrace t, Glob glob, long id) {
        return t != null && !t.disposed && t.glob == glob && (id == t.player || id == t.subject);
    }

    /** Socket-read time is local receipt, not server send time or kernel packet arrival. */
    public static void received(Glob glob, OCache.ObjDelta delta) {
        MovementTrace t = active;
        if(!tracks(t, glob, delta.id)) return;
        double[] r = row(Utils.rtime()); r[3] = delta.id;
        r[50] = delta.receivedAt; r[52] = delta.frame; r[53] = delta.packet;
        t.ring.add("net-received", r, null);
    }

    public static void applied(Glob glob, long id, OCache.AttrDelta delta,
                               double start, double locked, int queued, boolean complete) {
        MovementTrace t = active;
        if(!tracks(t, glob, id)) return;
        double now = Utils.rtime();
        double[] r = row(now); r[3] = id;
        r[50] = delta.receivedAt; r[51] = delta.queuedAt;
        r[52] = delta.frame; r[53] = delta.packet; r[54] = delta.type;
        r[55] = (locked - start) * 1000; r[56] = (now - locked) * 1000;
        r[57] = queued; r[58] = start;
        t.ring.add(complete ? "net-applied" : "net-retry", r, null);
    }

    /** Slow CPU/native operations from the foreground session's rendering environment. */
    public static void renderStage(haven.render.Environment env, String name, double start, String detail) {
        MovementTrace t = active;
        double now = Utils.rtime();
        if(t == null || t.disposed || env == null || t.renderer != env || now - start < .002) return;
        double[] r = row(now); r[58] = start; r[59] = (now - start) * 1000;
        t.ring.add("render-stage", r, name + (detail == null ? "" : " " + detail));
    }

    /** Cut publication, including fast ones, to correlate boundary crossings with later uploads. */
    public static void mapCut(MCache map, Coord cc, double start, String detail) {
        MovementTrace t = active;
        if(t == null || t.disposed || t.glob.map != map || Thread.currentThread() != t.thread) return;
        double now = Utils.rtime();
        double[] r = row(now); r[58] = start; r[59] = (now - start) * 1000;
        t.ring.add("map-cut", r, "cut=" + cc + " " + detail);
    }

    /** Nested inclusive stage timings, restricted to the foreground UI thread. */
    public static Stage stage(UI ui, String name) {
        MovementTrace t = active;
        return t != null && !t.disposed && ui != null && ui.movementTrace == t &&
            Thread.currentThread() == t.thread ? new Stage(t, name) : null;
    }
    public static final class Stage implements AutoCloseable {
        private final MovementTrace trace;
        private final String name, parent;
        private final double start, parentStart;
        private Stage(MovementTrace t, String name) {
            trace = t; this.name = name; parent = t.phase; parentStart = t.phaseStart;
            start = Utils.rtime(); t.phase(name);
        }
        public void close() {
            double now = Utils.rtime();
            trace.phaseStart = parentStart; trace.phase = parent;
            if(now - start >= .001) {
                double[] r = row(now); r[58] = start; r[59] = (now - start) * 1000;
                trace.ring.add("stage", r, name);
            }
        }
    }

    public MovementTrace(Glob glob) { this.glob = glob; }

    /** Kept separate so retention, wraparound and export can be tested without a game. */
    static final class Ring {
        final double[][] values;
        final String[] types, details;
        int first, count;
        long overwritten;
        Ring(int capacity) { values = new double[capacity][FIELDS]; types = new String[capacity]; details = new String[capacity]; }
        synchronized void add(String type, double[] row, String detail) {
            while(count > 0 && values[first][0] < row[0] - RETENTION) {
                details[first] = null; first = (first + 1) % values.length; count--;
            }
            if(count == values.length) { first = (first + 1) % values.length; count--; overwritten++; }
            int at = (first + count++) % values.length;
            System.arraycopy(row, 0, values[at], 0, FIELDS); types[at] = type; details[at] = detail;
        }
        synchronized List<Row> snapshot(double from, double to) {
            List<Row> rows = new ArrayList<>();
            for(int i = 0; i < count; i++) {
                int at = (first + i) % values.length;
                if(values[at][0] >= from && values[at][0] <= to)
                    rows.add(new Row(types[at], values[at].clone(), details[at]));
            }
            rows.sort(Comparator.comparingDouble(r -> r.values[0]));
            return rows;
        }
    }
    static final class Row {
        final String type, detail;
        final double[] values;
        Row(String type, double[] values, String detail) { this.type = type; this.values = values; this.detail = detail; }
    }
    static double[] row(double time) { double[] r = new double[FIELDS]; Arrays.fill(r, Double.NaN); r[0] = time; return r; }

    public static MovementTrace begin(UILoop.Frame f) {
        if(f.ui.gui == null || f.ui.gui.map == null) { active = null; return null; }
        Glob glob = f.ui.gui.map.glob;
        MovementTrace t = f.ui.movementTrace;
        if(t == null || t.glob != glob || t.disposed) {
            if(t != null) t.dispose();
            f.ui.movementTrace = t = new MovementTrace(glob);
        }
        if(active != t) { t.previous = Double.NaN; t.ring.add("resume", row(Utils.rtime()), null); }
        active = t; t.thread = Thread.currentThread(); t.player = f.ui.gui.map.plgob;
        t.renderer = f.out.env();
        t.phase("frame"); startServices();
        return t;
    }

    private static synchronized void startServices() {
        if(started) return;
        started = true;
        worker.execute(() -> {
            final double uptimeEpoch = Utils.rtime() - ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
            for(GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if(!(bean instanceof NotificationEmitter)) continue;
                try {
                    ((NotificationEmitter)bean).addNotificationListener((notification, handback) -> {
                        MovementTrace t = active;
                        if(t == null || t.disposed || !notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) return;
                        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData)notification.getUserData());
                        double[] r = row(Utils.rtime());
                        r[46] = info.getGcInfo().getDuration();
                        r[47] = uptimeEpoch + info.getGcInfo().getStartTime() / 1000.0;
                        r[48] = uptimeEpoch + info.getGcInfo().getEndTime() / 1000.0;
                        t.ring.add("gc", r, info.getGcName() + ": " + info.getGcCause());
                    }, null, null);
                } catch(RuntimeException ignored) { /* Other diagnostics still work on restricted JVMs. */ }
            }
        });
        worker.scheduleWithFixedDelay(() -> {
            try {
                MovementTrace t = active;
                if(t == null || t.disposed || t.thread == null || t.phase.equals("idle")) return;
                double now = Utils.rtime(), age = now - t.phaseStart;
                if(age < .02 || now - t.lastStack < .1) return;
                t.lastStack = now;
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                ThreadInfo info = threads.getThreadInfo(t.thread.getId(), 48);
                if(info == null) return;
                StringBuilder stack = new StringBuilder(t.phase).append("\n");
                appendStack(stack, info);
                if(info.getLockOwnerId() > 0) appendStack(stack, threads.getThreadInfo(info.getLockOwnerId(), 32));
                if(t.renderer instanceof haven.render.vk.VkEnvironment) {
                    for(Thread renderThread : ((haven.render.vk.VkEnvironment)t.renderer).diagnosticThreads()) {
                        if(renderThread == null || renderThread == t.thread) continue;
                        ThreadInfo renderInfo = threads.getThreadInfo(renderThread.getId(), 48);
                        appendStack(stack, renderInfo);
                        if(renderInfo != null && renderInfo.getLockOwnerId() > 0 && renderInfo.getLockOwnerId() != t.thread.getId())
                            appendStack(stack, threads.getThreadInfo(renderInfo.getLockOwnerId(), 32));
                    }
                }
                double[] r = row(now); r[49] = age * 1000;
                t.ring.add("stall-stack", r, stack.toString());
            } catch(RuntimeException ignored) { /* Never stop sampling because a thread exited. */ }
        }, 10, 10, TimeUnit.MILLISECONDS);
    }
    private static void appendStack(StringBuilder out, ThreadInfo info) {
        if(info == null) return;
        out.append(info.getThreadName()).append(' ').append(info.getThreadState()).append(" lock=").append(info.getLockName()).append('\n');
        for(StackTraceElement frame : info.getStackTrace()) out.append("  at ").append(frame).append('\n');
    }
    public void phase(String name) { phaseStart = Utils.rtime(); phase = name; }

    /** Called before drawing: retain the placement actually used by this scene. */
    public double[] sample(UILoop.Frame f) {
        double start = Utils.rtime();
        double[] r = row(start); r[1] = f.frameno; r[2] = player;
        try { sample(f, r); }
        catch(RuntimeException e) { ring.add("sample-unavailable", row(Utils.rtime()), e.toString()); }
        r[26] = (Utils.rtime() - start) * 1000;
        return r;
    }
    private void sample(UILoop.Frame f, double[] r) {
        MapView map = f.ui.gui == null ? null : f.ui.gui.map;
        if(map == null || map.glob != glob) return;
        Gob gob = map.player();
        subject = -1;
        for(int i = 0; gob != null && i < 8; i++) {
            Gob next = null;
            synchronized(gob) {
                Following following = gob.getattr(Following.class);
                if(following != null) { r[18] = following.tgt; next = following.tgt(); }
                if(next == null) {
                    subject = gob.id; r[3] = subject; r[4] = gob.rc.x; r[5] = gob.rc.y;
                    Moving moving = gob.getattr(Moving.class);
                    if(moving instanceof LinMove) linear(r, (LinMove)moving);
                    try { position(r, 6, gob.getc()); } catch(Loading ignored) {}
                    position(r, 9, gob.placed.getc());
                }
            }
            if(next == null) break;
            gob = next;
        }
        Matrix4f camera = Homo3D.camxf(map.basic.state());
        for(int i = 0; i < 16; i++) r[27+i] = camera.m[i];
        if(metadata.isEmpty() || f.frameno % 60 == 0) metadata = "renderer=" + f.out.env().getClass().getName() + "\ncamera=" + map.camera.getClass().getName() +
            "\nsync=" + f.ui.gprefs.syncmode.val + "\nfps_limit=" + f.ui.gprefs.hz.val +
            "\nbackground_fps_limit=" + f.ui.gprefs.bghz.val + "\nvsync=" + f.ui.gprefs.vsync.val +
            "\nparallel=" + Config.par.get() + "\ntaa=" + nurgling.render.Temporal.taa + "\nrender_reconciliation=true\n";
    }
    private static void position(double[] row, int offset, Coord3f c) {
        if(c != null) { row[offset] = c.x; row[offset+1] = c.y; row[offset+2] = c.z; }
    }
    private static void linear(double[] r, LinMove m) {
        r[12] = m.t; r[13] = m.lt; r[14] = m.e; r[15] = m.v.x; r[16] = m.v.y; r[17] = m.ts ? 1 : 0;
    }
    public void finish(UILoop.Frame f, double now) {
        phase("idle");
        if(disposed || f.movementSample == null) return;
        double[] r = f.movementSample;
        r[19] = Double.isNaN(previous) ? Double.NaN : (now - previous) * 1000; previous = now;
        r[20] = f.worldMs; r[21] = f.graphicsMs; r[22] = f.uiMs; r[23] = f.drawMs;
        r[24] = f.syncMs; r[25] = f.waited * 1000;
        ring.add("frame", r, null);
    }
    /** Network delta hooks run under the gob monitor; do not acquire UI/render locks here. */
    public static void server(Gob gob, String type, Coord2d pos, Coord2d velocity, double before, double reported, double end) {
        MovementTrace t = active;
        if(t == null || t.disposed || gob.glob != t.glob || (gob.id != t.player && gob.id != t.subject)) return;
        double[] r = row(Utils.rtime()); r[2] = t.player; r[3] = gob.id;
        if(pos != null) { r[4] = pos.x; r[5] = pos.y; }
        Moving moving = gob.getattr(Moving.class);
        if(moving instanceof LinMove) linear(r, (LinMove)moving);
        if(velocity != null) { r[15] = velocity.x; r[16] = velocity.y; }
        r[43] = before; r[44] = reported; r[45] = end;
        t.ring.add(type, r, null);
    }
    public synchronized boolean request(Path directory) {
        if(pending || disposed) return false;
        pending = true;
        final double mark = Utils.rtime();
        ring.add("mark", row(mark), "Ctrl+F9");
        worker.schedule(() -> {
            try {
                List<Row> rows = ring.snapshot(mark - HISTORY, mark + TAIL);
                Path archive = write(directory, rows, epoch, mark, "wall_epoch_utc=" + wallEpoch + "\n" + metadata +
                    "\nring_overwrites=" + ring.overwritten + "\nclosed_during_capture=" + disposed + "\n");
                notice.set(L10n.get("movement.trace.saved", archive.toAbsolutePath()));
            } catch(Exception e) { notice.set(L10n.get("movement.trace.failed", e.toString())); }
            finally { synchronized(MovementTrace.this) { pending = false; } }
        }, (long)(TAIL * 1000), TimeUnit.MILLISECONDS);
        return true;
    }
    static Path write(Path directory, List<Row> rows, double epoch, double mark, String metadata) throws IOException {
        Files.createDirectories(directory);
        Path archive = directory.resolve("movement-" + Instant.now().toString().replace(':','-') + "-" + UUID.randomUUID().toString().substring(0,8) + ".zip");
        try(ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
            writeCsv(zip, "trace.csv", rows, epoch);
            FrameStats stats = new FrameStats(rows);
            writeCsv(zip, "slow-frames.csv", stats.worst, epoch);
            zip.putNextEntry(new ZipEntry("frame-summary.txt"));
            zip.write(stats.report().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            String readme = metadata + "\nmark_time_s=" + (mark-epoch) + "\nrows=" + rows.size() + "\n" +
                "Window: 10 seconds before mark, 2 seconds after. Early login may have less history.\n" +
                "Times are monotonic seconds from wall_epoch_utc (approximate wall clock anchor).\n" +
                "Schema v2. Movement events are application times. before_t/reported_t distinguish corrections. net-received records dispatch; received_s is socket-read completion before decryption/parsing (not kernel arrival or server-send time). NaN/blank means unavailable.\n" +
                "Frame position sampled before draw; frame_ms measured after submission/pacing, not GPU presentation.\n" +
                "placed_* is the scene placement. While mounted, subject_id/positions follow the mount; player_id remains the rider.\n" +
                "cam_0..15 is the column-major world-to-view matrix (render Y is negative map Y).\n" +
                "Blank fields mean unavailable/not applicable. GC duration is collector-event duration, not necessarily an STW pause.\n" +
                "net-applied/net-retry: stage_start_s is attempt start, queued_s is enqueue time, gob_lock_ms is monitor wait, apply_ms is delta handler time; correlate by subject_id/server_frame/packet_id/delta_type. Retry includes failed or Loading attempts.\n" +
                "stage rows are inclusive nested UI timings >=1ms, ending at time_s; do not sum parents and children.\n" +
                "map-cut rows record cut attachment/replacement/removal with coordinates, grid class and stage_start_s/stage_ms, including fast operations.\n" +
                "render-stage rows time buffer/texture and draw-list preparation, upload batches, ring allocation/reset, GPU-fence and presentation waits >=2ms; stage_start_s/stage_ms hold their interval, detail includes result codes or allocation sizes. These are CPU call durations, not GPU execution times.\n" +
                "Stacks sample the UI thread/monitor owner and this Vulkan environment's render/callback threads during phases over 20ms (at most 10Hz). They are not a full CPU profile.\n" +
                "The bounded ring can truncate history at extreme frame/event rates; ring_overwrites reports capacity loss.\n";
            zip.write(readme.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return archive;
    }
    private static void writeCsv(ZipOutputStream zip, String name, List<Row> rows, double epoch) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Writer csv = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        csv.write("event," + HEADER + ",detail\n");
        for(Row row : rows) {
            csv.write(row.type);
            for(int i = 0; i < row.values.length; i++) {
                csv.write(','); double v = row.values[i];
                if(i == 0 || i == 47 || i == 48 || i == 50 || i == 51 || i == 58) v -= epoch;
                if(Double.isFinite(v)) csv.write(Double.toString(v));
            }
            csv.write(','); csv.write('"');
            if(row.detail != null) csv.write(row.detail.replace("\"", "\"\""));
            csv.write("\"\n");
        }
        csv.flush(); zip.closeEntry();
    }
    public static void trigger(UI ui) {
        MovementTrace t = ui.movementTrace;
        if(ui.gui == null) return;
        if(t == null) { ui.gui.msg(L10n.get("movement.trace.wait")); return; }
        if(t.request(Paths.get("diagnostics"))) ui.gui.msg(L10n.get("movement.trace.recording"));
        else ui.gui.msg(L10n.get("movement.trace.busy"));
    }
    public static void poll(UI ui) {
        MovementTrace t = ui.movementTrace;
        if(t != null && ui.gui != null) { String message = t.notice.getAndSet(null); if(message != null) ui.gui.msg(message); }
    }
    public void dispose() { disposed = true; if(active == this) active = null; }
}
