package nurgling.tools;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.HackThread;
import haven.Label;
import haven.MapFile;
import haven.MessageBuf;
import haven.UI;
import haven.Utils;
import haven.Window;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.db.DatabaseManager;
import nurgling.db.MapMerge;
import nurgling.db.MapStreamCodec;
import nurgling.db.dao.MapDataDao;
import nurgling.db.service.MapDbService;
import nurgling.i18n.L10n;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Moves the explored map between this client and the village database.
 *
 * <p>The map window's stock Export.../Import... buttons write and read a {@code .hmap} file, and a
 * village that wants to pool its maps has to pass those files around by hand. These two actions do
 * the same thing through the shared database instead: one player uploads what they have explored,
 * and everyone else pulls in what all the others uploaded.
 *
 * <p>Both are driven by a button press and nothing else - there is no background sync. That is a
 * deliberate choice rather than a simplification. Nothing leaves a client without someone asking
 * for it, and, because every upload rewrites that player's placement rows in a single transaction,
 * the stored layout is always one coherent snapshot instead of a mixture of moments that MapFile's
 * importer would reject.
 *
 * <p>This class is the wiring: settings, identity, threads, windows, and the adapter that hands
 * {@link MapDbService} to the merge as a {@link MapMerge.Source}. The merge itself lives in
 * {@link MapMerge} and the decisions it makes in {@link nurgling.db.MapImportPlanner}, both of which
 * run without a database or a UI and are tested that way.
 *
 * <h2>Working set</h2>
 *
 * <p>Neither direction holds a whole map's worth of grid data. The export splits its stream chunk by
 * chunk and uploads a batch at a time; the import keeps only packed payloads, one uploader's worth,
 * and inflates them one at a time while assembling. What is unavoidably held is the export stream
 * itself, which is the compressed form and an order of magnitude smaller.
 */
public class MapDbTransfer {

    /** Grid chunks uploaded per database round trip; matches the DAO's batching. */
    private static final int PAGE = MapDataDao.BATCH;

    /**
     * One transfer at a time. Two of them share a {@link MapFile} and would interleave writes into
     * it, and a second import would plan against a map the first is still changing.
     */
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private MapDbTransfer() {}

    // ------------------------------------------------------------------ availability

    /**
     * The map service, or null when the database cannot serve this feature right now.
     *
     * <p>Null covers every "not ready" case there is - no database configured, still connecting,
     * connection lost, tables missing, or this role has no rights on them. Callers report it and
     * carry on; the file-based Export/Import is unaffected either way.
     */
    private static MapDbService service() {
        DatabaseManager dbm = NCore.databaseManager;
        if ((dbm == null) || !dbm.isReady())
            return null;
        return dbm.getMapDbService();
    }

    /** Whether the buttons should be shown at all. */
    public static boolean configured() {
        Object enabled = NConfig.get(NConfig.Key.ndbenable);
        Object pg = NConfig.get(NConfig.Key.postgres);
        return (enabled instanceof Boolean) && (Boolean) enabled
            && (pg instanceof Boolean) && (Boolean) pg;
    }

    private static boolean shareMarkers() {
        Object v = NConfig.get(NConfig.Key.mapShareMarkers);
        return !(v instanceof Boolean) || (Boolean) v;
    }

    /**
     * Check everything the transfer needs, reporting the first thing that is missing.
     *
     * @return the service to use, or null if the caller should not start
     */
    private static MapDbService begin(GameUI gui) {
        if (gui == null)
            return null;
        Object enabled = NConfig.get(NConfig.Key.ndbenable);
        if (!(enabled instanceof Boolean) || !(Boolean) enabled) {
            gui.msg(L10n.get("mapdb.err_disabled"), Color.ORANGE);
            return null;
        }
        Object pg = NConfig.get(NConfig.Key.postgres);
        if (!(pg instanceof Boolean) || !(Boolean) pg) {
            /* A local SQLite file has nobody to share with, so offering the buttons there would be
             * a promise the storage cannot keep. */
            gui.msg(L10n.get("mapdb.err_needs_postgres"), Color.ORANGE);
            return null;
        }
        MapDbService svc = service();
        if (svc == null) {
            DatabaseManager dbm = NCore.databaseManager;
            gui.msg(((dbm != null) && dbm.isReady())
                    ? L10n.get("mapdb.err_no_tables")
                    : L10n.get("mapdb.err_not_connected"), Color.ORANGE);
            return null;
        }
        if (profile(gui) == null) {
            gui.msg(L10n.get("mapdb.err_no_world"), Color.ORANGE);
            return null;
        }
        return svc;
    }

    /** World identity, the same partition key areas, routes and fish spots use. */
    private static String profile(GameUI gui) {
        if (!(gui instanceof NGameUI))
            return null;
        String genus = ((NGameUI) gui).getGenus();
        return ((genus == null) || genus.isEmpty()) ? null : genus;
    }

    /**
     * Who an upload is attributed to, and whose rows an import skips.
     *
     * <p>Taken from the window's own session rather than from any global "current" one, so that a
     * second client running in the same process cannot upload one character's map under another
     * character's name.
     *
     * <p>The account name alone is not enough: {@code GameUI.mapfilename} lets a character keep a
     * mapfile of its own, and two such characters on one account would otherwise delete each
     * other's placement rows on every export. Whatever distinguishes their map files distinguishes
     * their uploads too.
     */
    private static String uploader(GameUI gui) {
        String name = null;
        try {
            if ((gui != null) && (gui.ui != null) && (gui.ui.sess != null)
                && (gui.ui.sess.user != null))
                name = gui.ui.sess.user.name;
        } catch (RuntimeException ignore) {
        }
        if ((name == null) || name.isEmpty())
            name = ((gui != null) && (gui.chrid != null) && !gui.chrid.isEmpty()) ? gui.chrid : "unknown";
        String own = "";
        try {
            if ((gui != null) && (gui.chrid != null))
                own = Utils.getpref("mapfile/" + gui.chrid, "");
        } catch (RuntimeException ignore) {
        }
        return ((own == null) || own.isEmpty()) ? name : (name + "/" + own);
    }

    // ------------------------------------------------------------------ windows

    /**
     * Progress window for both directions.
     *
     * <p>Its own class rather than MapWnd's ExportWindow because the transfer has phases the file
     * export does not - talking to the database, comparing manifests - and needs to say which one
     * it is in. It still doubles as the {@link MapFile.ExportStatus} the export itself reports to.
     */
    public static class Progress extends Window implements MapFile.ExportStatus, MapMerge.Reporter {
        private Thread th;
        private volatile String text = "";

        public Progress(String title) {
            super(UI.scale(new Coord(360, 65)), title, true);
            adda(new Button(UI.scale(100), L10n.get("common.cancel"), false, this::cancel),
                 csz().x / 2, UI.scale(40), 0.5, 0.0);
        }

        public void run(Thread th) {
            (this.th = th).start();
        }

        public void set(String text) {
            this.text = text;
        }

        public void cdraw(GOut g) {
            g.text(text, UI.scale(new Coord(10, 10)));
        }

        /** Cancelling interrupts the worker; every long loop in it checks for that. */
        public void cancel() {
            if (th != null)
                th.interrupt();
        }

        public void tick(double dt) {
            super.tick(dt);
            if ((th != null) && !th.isAlive())
                destroy();
        }

        public void grid(int cs, int ns, int cg, int ng) {
            this.text = String.format("Reading map cut %,d/%,d in segment %,d/%,d", cg, ng, cs, ns);
        }

        public void mark(int cm, int nm) {
            this.text = String.format("Reading marker %,d/%,d", cm, nm);
        }

        public void phase(String what) {
            set(what);
        }

        public void merging(String uploader) {
            set(L10n.get("mapdb.import_merging", uploader));
        }

        public void fetching(String uploader, int done, int total) {
            set(L10n.get("mapdb.import_fetching", uploader, done, total));
        }
    }

    /**
     * Asks before an import.
     *
     * <p>An import rewrites the local mapfile - new segments, merged segments, and the anchor grids
     * the merge needs - and there is no undo for any of it. The stock Import... at least makes the
     * player pick a file; a bare button deserves the same moment of thought.
     */
    private static class Confirm extends Window {
        Confirm(Runnable go) {
            super(UI.scale(new Coord(370, 105)), L10n.get("mapdb.confirm_title"), true);
            add(new Label(L10n.get("mapdb.confirm_line1")), UI.scale(new Coord(10, 5)));
            add(new Label(L10n.get("mapdb.confirm_line2")), UI.scale(new Coord(10, 27)));
            adda(new Button(UI.scale(110), L10n.get("mapdb.confirm_go"), false, () -> {
                destroy();
                go.run();
            }), UI.scale(new Coord(100, 62)), 0.5, 0.0);
            adda(new Button(UI.scale(110), L10n.get("common.cancel"), false, this::destroy),
                 UI.scale(new Coord(240, 62)), 0.5, 0.0);
        }

        public void wdgmsg(String msg, Object... args) {
            if (msg.equals("close"))
                destroy();
            else
                super.wdgmsg(msg, args);
        }
    }

    /** Take the single-transfer lock, reporting to the player if one is already running. */
    private static boolean claim(GameUI gui) {
        if (!busy.compareAndSet(false, true)) {
            gui.msg(L10n.get("mapdb.err_busy"), Color.ORANGE);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ export

    /** Upload everything this client has explored. */
    public static void export(GameUI gui, MapFile file) {
        MapDbService svc = begin(gui);
        if (svc == null)
            return;
        if (!claim(gui))
            return;
        String profile = profile(gui);
        String me = uploader(gui);
        boolean marks = shareMarkers();

        Progress prog = new Progress(L10n.get("mapdb.export_title"));
        Thread th = new HackThread(() -> {
            try {
                runExport(gui, file, svc, profile, me, marks, prog);
            } catch (InterruptedException e) {
                /* The player pressed Cancel. Batches already committed stay committed, which is
                 * harmless: grids are keyed globally and merged by mtime, so a cancelled upload is
                 * simply a smaller upload. The layout is written last and in one transaction, so it
                 * is never left half replaced. */
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                System.err.println("[MapDbTransfer] export failed: " + e.getMessage());
                gui.error(L10n.get("mapdb.export_failed", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                System.err.println("[MapDbTransfer] export failed: " + e);
                e.printStackTrace();
                gui.error(L10n.get("mapdb.export_failed", String.valueOf(e.getMessage())));
            } finally {
                busy.set(false);
            }
        }, "Map database exporter");
        prog.run(th);
        gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    private static void runExport(GameUI gui, MapFile file, MapDbService svc, String profile,
                                  String me, boolean marks, Progress prog)
            throws SQLException, InterruptedException {
        long started = System.currentTimeMillis();
        prog.set(L10n.get("mapdb.export_reading"));
        MessageBuf out = new MessageBuf();
        file.export(out, MapFile.ExportFilter.all, prog);
        byte[] raw = out.fin();

        prog.set(L10n.get("mapdb.export_uploading", 0));

        /* Placements are what the layout transaction needs and they are tiny; payloads go out in
         * batches and are dropped as they go, so the map is never all in memory at once. A grid can
         * appear in more than one of the player's segments - MapFile emits a chunk per pair - and
         * those repeats are placements, not further copies of the same ~21 KB of terrain, so the
         * payload is uploaded once. */
        List<MapDataDao.Placement> layout = new ArrayList<>();
        List<MapStreamCodec.MarkChunk> markChunks = new ArrayList<>();
        Set<Long> sent = new HashSet<>();
        List<MapStreamCodec.GridChunk> batch = new ArrayList<>(PAGE);
        int[] uploaded = {0};

        MapStreamCodec.split(raw, new MapStreamCodec.Sink<SQLException>() {
            public void grid(MapStreamCodec.GridChunk g) throws SQLException, InterruptedException {
                layout.add(new MapDataDao.Placement(g.gid, g.segid, g.sc));
                if (!sent.add(g.gid))
                    return;
                batch.add(g);
                if (batch.size() >= PAGE) {
                    Utils.checkirq();
                    svc.publishGridBatch(profile, me, batch);
                    uploaded[0] += batch.size();
                    batch.clear();
                    prog.set(L10n.get("mapdb.export_uploading", uploaded[0]));
                }
            }

            public void mark(MapStreamCodec.MarkChunk m) {
                markChunks.add(m);
            }
        });
        if (!batch.isEmpty()) {
            Utils.checkirq();
            svc.publishGridBatch(profile, me, batch);
            uploaded[0] += batch.size();
            batch.clear();
        }

        Utils.checkirq();
        prog.set(L10n.get("mapdb.export_layout"));
        List<MapStreamCodec.MarkChunk> sendmarks = marks ? markChunks : List.of();
        svc.publishLayout(profile, me, layout, sendmarks);

        /* Enough to tell a slow database from a slow map read when someone reports "the export
         * takes forever", and the numbers the scale test in docs/map-db-sync-testing.md records. */
        System.out.printf("[MapDbTransfer] export: %d grids, %d placements, %d markers, "
                          + "%d KB of stream, in %.1fs%n",
                          uploaded[0], layout.size(), sendmarks.size(), raw.length / 1024,
                          (System.currentTimeMillis() - started) / 1000.0);
        gui.msg(L10n.get("mapdb.export_done", uploaded[0], sendmarks.size()), Color.WHITE);
    }

    // ------------------------------------------------------------------ import

    /** Pull in everything every other player has uploaded for this world. */
    public static void importFrom(GameUI gui, MapFile file) {
        if (begin(gui) == null)
            return;
        gui.adda(new Confirm(() -> startImport(gui, file)), gui.sz.div(2), 0.5, 0.5);
    }

    private static void startImport(GameUI gui, MapFile file) {
        MapDbService svc = begin(gui);
        if (svc == null)
            return;
        if (!claim(gui))
            return;
        String profile = profile(gui);
        String me = uploader(gui);
        boolean marks = shareMarkers();

        Progress prog = new Progress(L10n.get("mapdb.import_title"));
        Thread th = new HackThread(() -> {
            try {
                long started = System.currentTimeMillis();
                prog.set(L10n.get("mapdb.import_manifest"));
                MapMerge.Report rep = MapMerge.run(file, source(svc, profile, me), marks, prog);
                System.out.printf("[MapDbTransfer] import: %s, %d grids, %d markers, "
                                  + "%d players, %d skipped, in %.1fs%n",
                                  rep.status, rep.grids, rep.markers, rep.players, rep.notes.size(),
                                  (System.currentTimeMillis() - started) / 1000.0);
                report(gui, rep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                System.err.println("[MapDbTransfer] import failed: " + e.getMessage());
                gui.error(L10n.get("mapdb.import_failed", String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                System.err.println("[MapDbTransfer] import failed: " + e);
                e.printStackTrace();
                gui.error(L10n.get("mapdb.import_failed", String.valueOf(e.getMessage())));
            } finally {
                busy.set(false);
            }
        }, "Map database importer");
        prog.run(th);
        gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    private static void report(GameUI gui, MapMerge.Report rep) {
        for (String n : rep.notes)
            System.out.println("[MapDbTransfer] " + n);
        switch (rep.status) {
        case NOTHING_SHARED:
            gui.msg(L10n.get("mapdb.import_nothing"), Color.ORANGE);
            return;
        case NO_OTHERS:
            gui.msg(L10n.get("mapdb.import_no_others"), Color.ORANGE);
            return;
        default:
            break;
        }
        if (rep.changedNothing()) {
            gui.msg(L10n.get("mapdb.import_uptodate"), Color.WHITE);
        } else {
            gui.msg(L10n.get("mapdb.import_done", rep.grids, rep.markers, rep.players), Color.WHITE);
        }
        if (!rep.notes.isEmpty())
            gui.msg(L10n.get("mapdb.import_partial", rep.notes.size()), Color.ORANGE);
    }

    /** Binds one world and one asking player to the service, for the merge to read through. */
    private static MapMerge.Source source(MapDbService svc, String profile, String me) {
        return new MapMerge.Source() {
            public Map<Long, Long> manifest() throws SQLException {
                return svc.manifest(profile);
            }

            public List<String> uploaders() throws SQLException {
                return svc.uploaders(profile, me);
            }

            public List<MapDataDao.Placement> placements(String uploader) throws SQLException {
                return svc.placements(profile, uploader);
            }

            public List<MapDataDao.MarkerRow> markers(String uploader) throws SQLException {
                return svc.markers(profile, uploader);
            }

            public Map<Long, byte[]> payloads(List<Long> gids) throws SQLException {
                return svc.payloads(profile, gids);
            }
        };
    }
}
