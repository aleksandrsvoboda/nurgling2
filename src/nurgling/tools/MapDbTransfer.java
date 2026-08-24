package nurgling.tools;

import haven.Button;
import haven.Coord;
import haven.GOut;
import haven.GameUI;
import haven.HackThread;
import haven.MapFile;
import haven.MessageBuf;
import haven.UI;
import haven.Utils;
import haven.Window;
import nurgling.NConfig;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.db.DatabaseManager;
import nurgling.db.MapStreamCodec;
import nurgling.db.dao.MapDataDao;
import nurgling.db.service.MapDbService;

import java.awt.Color;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>Merging is left entirely to {@link MapFile#reimport}: the payloads stored in the database are
 * the same opaque chunks {@link MapFile#export} produces, so all the difficult work of lining up
 * two players' segments happens in the code that already does it correctly for file import.
 */
public class MapDbTransfer {

    /** Grid ids fetched per database round trip; matches the DAO's batching. */
    private static final int PAGE = MapDataDao.BATCH;

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
            gui.msg("Map sharing: database sync is switched off in settings.", Color.ORANGE);
            return null;
        }
        Object pg = NConfig.get(NConfig.Key.postgres);
        if (!(pg instanceof Boolean) || !(Boolean) pg) {
            /* A local SQLite file has nobody to share with, so offering the buttons there would be
             * a promise the storage cannot keep. */
            gui.msg("Map sharing needs a shared PostgreSQL database.", Color.ORANGE);
            return null;
        }
        MapDbService svc = service();
        if (svc == null) {
            DatabaseManager dbm = NCore.databaseManager;
            gui.msg(((dbm != null) && dbm.isReady())
                    ? "Map sharing unavailable: the map tables are missing or not readable by this user."
                    : "Map sharing unavailable: not connected to the database.", Color.ORANGE);
            return null;
        }
        if (profile(gui) == null) {
            gui.msg("Map sharing: no world identity yet, try again once logged in.", Color.ORANGE);
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

    /** Who an upload is attributed to, and whose rows an import skips. */
    private static String uploader(GameUI gui) {
        try {
            if ((NUtils.getUI() != null) && (NUtils.getUI().sess != null)
                && (NUtils.getUI().sess.user != null)) {
                String name = NUtils.getUI().sess.user.name;
                if ((name != null) && !name.isEmpty())
                    return name;
            }
        } catch (RuntimeException ignore) {
        }
        if ((gui != null) && (gui.chrid != null) && !gui.chrid.isEmpty())
            return gui.chrid;
        return "unknown";
    }

    // ------------------------------------------------------------------ progress window

    /**
     * Progress window for both directions.
     *
     * <p>Its own class rather than MapWnd's ExportWindow because the transfer has phases the file
     * export does not - talking to the database, comparing manifests - and needs to say which one
     * it is in. It still doubles as the {@link MapFile.ExportStatus} the export itself reports to.
     */
    public static class Progress extends Window implements MapFile.ExportStatus {
        private Thread th;
        private volatile String text = "Starting";

        public Progress(String title) {
            super(UI.scale(new Coord(360, 65)), title, true);
            adda(new Button(UI.scale(100), "Cancel", false, this::cancel), csz().x / 2, UI.scale(40), 0.5, 0.0);
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
    }

    // ------------------------------------------------------------------ export

    /** Upload everything this client has explored. */
    public static void export(GameUI gui, MapFile file) {
        MapDbService svc = begin(gui);
        if (svc == null)
            return;
        String profile = profile(gui);
        String me = uploader(gui);
        boolean marks = shareMarkers();

        Progress prog = new Progress("Exporting map to database");
        Thread th = new HackThread(() -> {
            try {
                prog.set("Reading local map...");
                MessageBuf buf = new MessageBuf();
                file.export(buf, MapFile.ExportFilter.all, prog);

                prog.set("Indexing map data...");
                MapStreamCodec.Split split = MapStreamCodec.split(buf.fin());

                Utils.checkirq();
                prog.set(String.format("Uploading %,d grids...", split.grids.size()));
                svc.publishGrids(profile, me, split.grids);

                Utils.checkirq();
                prog.set("Uploading map layout...");
                List<MapStreamCodec.MarkChunk> sendmarks = marks ? split.marks : List.of();
                svc.publishLayout(profile, me, split.grids, sendmarks);

                gui.msg(String.format("Map exported: %,d grids, %,d markers.",
                                      split.grids.size(), sendmarks.size()), Color.WHITE);
            } catch (InterruptedException e) {
                /* The player pressed Cancel. Grids already written stay written, which is harmless:
                 * they are keyed globally and merged by mtime, so a partial upload is just a
                 * smaller upload. */
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                System.err.println("[MapDbTransfer] export failed: " + e.getMessage());
                gui.error("Map export failed: " + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("[MapDbTransfer] export failed: " + e);
                e.printStackTrace();
                gui.error("Map export failed: " + e.getMessage());
            }
        }, "Map database exporter");
        prog.run(th);
        gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    // ------------------------------------------------------------------ import

    /** Pull in everything every other player has uploaded for this world. */
    public static void importFrom(GameUI gui, MapFile file) {
        MapDbService svc = begin(gui);
        if (svc == null)
            return;
        String profile = profile(gui);
        String me = uploader(gui);
        boolean marks = shareMarkers();

        Progress prog = new Progress("Importing map from database");
        Thread th = new HackThread(() -> {
            try {
                runImport(gui, file, svc, profile, me, marks, prog);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                System.err.println("[MapDbTransfer] import failed: " + e.getMessage());
                gui.error("Map import failed: " + e.getMessage());
            } catch (RuntimeException e) {
                System.err.println("[MapDbTransfer] import failed: " + e);
                e.printStackTrace();
                gui.error("Map import failed: " + e.getMessage());
            }
        }, "Map database importer");
        prog.run(th);
        gui.adda(prog, gui.sz.div(2), 0.5, 1.0);
    }

    private static void runImport(GameUI gui, MapFile file, MapDbService svc, String profile,
                                  String me, boolean marks, Progress prog)
            throws SQLException, InterruptedException {
        prog.set("Reading database manifest...");
        Map<Long, Long> manifest = svc.manifest(profile);
        if (manifest.isEmpty()) {
            gui.msg("Map import: nothing shared for this world yet.", Color.ORANGE);
            return;
        }

        /* What this client already knows, as gid -> mtime. Built once and then kept current as
         * grids are accepted, so the same grid is not reconsidered for every uploader whose map
         * also contains it. */
        Map<Long, Long> localMtimes = new HashMap<>();
        List<Long> want = new ArrayList<>();
        int seen = 0;
        for (Map.Entry<Long, Long> e : manifest.entrySet()) {
            Utils.checkirq();
            long gid = e.getKey();
            Long local = localMtime(file, gid);
            if (local == null) {
                want.add(gid);
            } else {
                localMtimes.put(gid, local);
                if (e.getValue() > local)
                    want.add(gid);
            }
            if ((++seen % 250) == 0)
                prog.set(String.format("Comparing with local map: %,d/%,d", seen, manifest.size()));
        }

        /* Deliberately no early exit when no grid needs fetching: another player may have added
         * markers to land this client already has, and those still have to come across. Their
         * segments arrive through the anchor mechanism in addAnchors, one grid apiece. */

        /* Only the grids that survived the comparison are worth their ~2 KB of transfer. */
        Map<Long, byte[]> blobs = new HashMap<>();
        for (int off = 0; off < want.size(); off += PAGE) {
            Utils.checkirq();
            int end = Math.min(off + PAGE, want.size());
            blobs.putAll(svc.payloads(profile, want.subList(off, end)));
            prog.set(String.format("Fetching grids: %,d/%,d", end, want.size()));
        }

        List<String> uploaders = svc.uploaders(profile, me);
        if (uploaders.isEmpty()) {
            gui.msg("Map import: no other players have exported to this world.", Color.ORANGE);
            return;
        }

        /* [0] grids accepted, [1] markers accepted - counted by the filter, so these are what
         * actually landed rather than what was offered. */
        int[] counts = {0, 0};
        int players = 0;
        for (String up : uploaders) {
            Utils.checkirq();
            prog.set("Merging map from " + up + "...");
            mergeOne(file, svc, profile, up, marks, manifest, blobs, localMtimes, counts, prog);
            players++;
        }

        if ((counts[0] == 0) && (counts[1] == 0)) {
            gui.msg("Map import: your map is already up to date.", Color.WHITE);
            return;
        }
        gui.msg(String.format("Map imported: %,d new grids and %,d markers from %,d player%s.",
                              counts[0], counts[1], players, (players == 1) ? "" : "s"), Color.WHITE);
    }

    /** Replay one player's map into this one. */
    private static void mergeOne(MapFile file, MapDbService svc, String profile, String uploader,
                                boolean marks, Map<Long, Long> manifest, Map<Long, byte[]> blobs,
                                Map<Long, Long> localMtimes, int[] counts, Progress prog)
            throws SQLException, InterruptedException {
        List<MapDataDao.Placement> placements = svc.placements(profile, uploader);
        if (placements.isEmpty())
            return;

        /* Their grids, restamped into their own coordinates. A payload uploaded by a third player
         * carries that player's segment in its header, which would mean nothing here. */
        List<byte[]> gridPayloads = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        Map<Long, List<MapDataDao.Placement>> bySeg = new HashMap<>();
        for (MapDataDao.Placement p : placements) {
            bySeg.computeIfAbsent(p.segid, k -> new ArrayList<>()).add(p);
            byte[] blob = blobs.get(p.gid);
            if (blob == null)
                continue;
            byte[] chunk = MapStreamCodec.rekey(blob, p.segid, p.sc);
            if (chunk == null)
                continue;
            gridPayloads.add(chunk);
            /* A segment counts as anchored only if one of its grids will actually be accepted.
             * Emitting a chunk is not enough: the importer sets the offset a marker is placed by
             * inside the branch the filter guards, so a segment whose grids are all rejected as
             * stale - which is the normal case once an earlier player in this same import already
             * supplied them - still needs an anchor forced through. */
            if (accepts(manifest, localMtimes, p.gid))
                covered.add(p.segid);
        }

        List<MapDataDao.MarkerRow> markerRows = marks ? svc.markers(profile, uploader) : List.of();

        /* A marker is dropped unless a grid of its segment was accepted earlier in the same stream:
         * that is what establishes the offset the importer places it by. When the comparison above
         * decided this client already has every grid of a segment, the segment needs an anchor - one
         * grid replayed purely to register the offset. */
        Set<Long> forced = new HashSet<>();
        Set<Long> anchorSegs = new HashSet<>();
        for (MapDataDao.MarkerRow m : markerRows) {
            if (!covered.contains(m.segid))
                anchorSegs.add(m.segid);
        }
        if (!anchorSegs.isEmpty())
            addAnchors(svc, profile, anchorSegs, bySeg, blobs, localMtimes, gridPayloads, covered, forced);

        List<byte[]> markPayloads = new ArrayList<>();
        for (MapDataDao.MarkerRow m : markerRows) {
            if (covered.contains(m.segid))
                markPayloads.add(m.payload);
        }

        if (gridPayloads.isEmpty() && markPayloads.isEmpty())
            return;

        byte[] stream = MapStreamCodec.assemble(gridPayloads, markPayloads);
        file.reimport(new MessageBuf(stream), filter(localMtimes, forced, counts, uploader));
    }

    /** The filter's decision, predicted at assembly time. Must stay in step with {@link #filter}. */
    private static boolean accepts(Map<Long, Long> manifest, Map<Long, Long> localMtimes, long gid) {
        Long db = manifest.get(gid);
        if (db == null)
            return false;
        Long local = localMtimes.get(gid);
        return (local == null) || (db > local);
    }

    /**
     * Give every segment that only contributes markers one grid to anchor it.
     *
     * <p>An anchor is preferred among grids this client does not have, because those are accepted
     * on their own merits and overwrite nothing. Only when a segment is entirely known locally does
     * one grid have to be forced through, and then the newest available copy is chosen so that the
     * forced write is the least likely to be a step backwards.
     */
    private static void addAnchors(MapDbService svc, String profile, Set<Long> segs,
                                   Map<Long, List<MapDataDao.Placement>> bySeg,
                                   Map<Long, byte[]> blobs, Map<Long, Long> localMtimes,
                                   List<byte[]> gridPayloads, Set<Long> covered, Set<Long> forced)
            throws SQLException, InterruptedException {
        List<MapDataDao.Placement> chosen = new ArrayList<>();
        for (long seg : segs) {
            List<MapDataDao.Placement> cands = bySeg.get(seg);
            if ((cands == null) || cands.isEmpty())
                continue;
            MapDataDao.Placement pick = null;
            for (MapDataDao.Placement p : cands) {
                if (!localMtimes.containsKey(p.gid)) {
                    pick = p;
                    break;
                }
            }
            if (pick == null)
                pick = cands.get(0);
            chosen.add(pick);
        }

        List<Long> missing = new ArrayList<>();
        for (MapDataDao.Placement p : chosen) {
            if (!blobs.containsKey(p.gid))
                missing.add(p.gid);
        }
        for (int off = 0; off < missing.size(); off += PAGE) {
            Utils.checkirq();
            blobs.putAll(svc.payloads(profile, missing.subList(off, Math.min(off + PAGE, missing.size()))));
        }

        for (MapDataDao.Placement p : chosen) {
            byte[] blob = blobs.get(p.gid);
            if (blob == null)
                continue;
            byte[] chunk = MapStreamCodec.rekey(blob, p.segid, p.sc);
            if (chunk == null)
                continue;
            gridPayloads.add(chunk);
            covered.add(p.segid);
            if (localMtimes.containsKey(p.gid))
                forced.add(p.gid);
        }
    }

    /**
     * Accept a grid only when it is genuinely newer than the local copy.
     *
     * <p>This matters more than it looks. {@code Importer.importgrid} saves whatever it is given
     * without comparing timestamps, so replaying an older snapshot of a grid - a villager who
     * mapped an area before it was built on - would quietly undo newer terrain. Filtering here is
     * safe because the importer records the segment offset before ever consulting the filter, which
     * is exactly how the stock validation pass works.
     */
    private static MapFile.ImportFilter filter(Map<Long, Long> localMtimes, Set<Long> forced,
                                               int[] counts, String uploader) {
        return new MapFile.ImportFilter() {
            public boolean includegrid(MapFile.ImportedGrid grid, boolean hasprev) {
                if (forced.contains(grid.gid)) {
                    /* Forced anchors are written whatever their age, so record the mtime that is
                     * now genuinely on disk rather than leaving a stale higher one behind. */
                    localMtimes.put(grid.gid, grid.mtime);
                    return true;
                }
                Long local = localMtimes.get(grid.gid);
                if ((local != null) && (grid.mtime <= local))
                    return false;
                /* Remember it as ours now, so the next player's map does not offer it again. */
                localMtimes.put(grid.gid, grid.mtime);
                counts[0]++;
                return true;
            }

            public boolean includemark(MapFile.Marker mark, MapFile.Marker prev) {
                if (prev != null)
                    return false;
                counts[1]++;
                return true;
            }

            /**
             * One bad chunk must not abandon the rest of the merge. The likeliest cause is
             * "Inconsistent grid locations detected", which means one player's stored layout
             * disagrees with this map about where a segment sits; skipping that chunk and carrying
             * on leaves both maps intact.
             */
            public void handleerror(RuntimeException exc, String ctx) {
                System.err.println("[MapDbTransfer] skipped a " + ctx + " from " + uploader
                    + ": " + exc.getMessage());
            }
        };
    }

    /** Local mtime for a grid, or null when this client does not have it. */
    private static Long localMtime(MapFile file, long gid) {
        MapFile.GridInfo info;
        file.lock.readLock().lock();
        try {
            info = file.gridinfo.get(gid);
        } catch (RuntimeException e) {
            return null;
        } finally {
            file.lock.readLock().unlock();
        }
        if (info == null)
            return null;
        MapFile.Grid local = MapFile.Grid.load(file, gid);
        return (local == null) ? null : local.mtime;
    }
}
