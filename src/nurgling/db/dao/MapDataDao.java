package nurgling.db.dao;

import haven.Coord;
import nurgling.db.DatabaseAdapter;
import nurgling.db.MapStreamCodec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data access for the shared map tables.
 *
 * <p>Grid content and grid placement are deliberately kept apart. The content of a grid is the same
 * for everyone who has walked it, so {@code map_grids} is keyed by the server-assigned grid id and
 * holds one ~2 KB payload per physical chunk of world however many villagers uploaded it. Where
 * that grid sits is a property of one player's segment layout, so {@code map_grid_placements} is
 * per uploader - but only a couple of dozen bytes per row.
 *
 * <p>Freshness is settled in SQL: a grid row is only overwritten by an upload with a strictly newer
 * {@code mtime}. That means an export never has to work out what the database already knows, and
 * two villagers exporting in either order converge on the same result.
 */
public class MapDataDao {

    /** Rows per batch. Payloads are ~2 KB, so a whole map in one batch would be tens of megabytes. */
    public static final int BATCH = 200;

    /**
     * How one uploader's map places a grid. A grid may have more than one placement: MapFile emits
     * a chunk per (segment, grid) pair, and those repeats are what let its importer discover that
     * two segments are actually the same land.
     */
    public static final class Placement {
        public final long gid;
        public final long segid;
        public final Coord sc;

        public Placement(long gid, long segid, Coord sc) {
            this.gid = gid;
            this.segid = segid;
            this.sc = sc;
        }
    }

    /** A stored marker chunk plus the segment it belongs to. */
    public static final class MarkerRow {
        public final long segid;
        public final byte[] payload;

        public MarkerRow(long segid, byte[] payload) {
            this.segid = segid;
            this.payload = payload;
        }
    }

    // ------------------------------------------------------------------ export

    /**
     * Store grid payloads, keeping whichever copy is newer.
     *
     * <p>The {@code WHERE} on the conflict clause is the whole freshness policy: an older snapshot
     * of a grid - a villager who mapped the area before it was built on, say - is accepted as a row
     * only if nobody has a newer one, and is otherwise discarded by the database itself.
     */
    public void upsertGrids(DatabaseAdapter adapter, String profile, String uploader,
                            List<MapStreamCodec.GridChunk> grids) throws SQLException {
        String sql = "INSERT INTO map_grids (profile, gid, mtime, payload, uploader, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (profile, gid) DO UPDATE SET "
            + "mtime = EXCLUDED.mtime, payload = EXCLUDED.payload, "
            + "uploader = EXCLUDED.uploader, updated_at = EXCLUDED.updated_at "
            + "WHERE EXCLUDED.mtime > map_grids.mtime";
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Object[]> batch = new ArrayList<>(BATCH);
        for (MapStreamCodec.GridChunk g : grids) {
            batch.add(new Object[]{profile, g.gid, g.mtime, g.payload, uploader, now});
            if (batch.size() >= BATCH) {
                adapter.executeBatch(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty())
            adapter.executeBatch(sql, batch);
    }

    /**
     * Replace this uploader's placement rows wholesale.
     *
     * <p>Deliberately not an incremental update. When a player's own segments merge, MapFile
     * re-coordinates the absorbed grids, so placements written before and after a merge describe
     * different origins; replaying a mixture of the two is exactly what makes the importer throw
     * "Inconsistent grid locations detected". Rewriting the whole set in one transaction means the
     * stored rows are always a single coherent snapshot of one moment.
     */
    public void replacePlacements(DatabaseAdapter adapter, String profile, String uploader,
                                  List<MapStreamCodec.GridChunk> grids) throws SQLException {
        adapter.executeUpdate("DELETE FROM map_grid_placements WHERE profile = ? AND uploader = ?",
                              profile, uploader);
        /* One grid can sit in two of a player's segments, so (gid, segid) is the identity here.
         * DO NOTHING guards against a map that somehow lists the same pair twice, which would
         * otherwise abort the whole replace. */
        String sql = "INSERT INTO map_grid_placements (profile, uploader, gid, segid, sc_x, sc_y) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (profile, uploader, gid, segid) DO NOTHING";
        List<Object[]> batch = new ArrayList<>(BATCH);
        for (MapStreamCodec.GridChunk g : grids) {
            batch.add(new Object[]{profile, uploader, g.gid, g.segid, g.sc.x, g.sc.y});
            if (batch.size() >= BATCH) {
                adapter.executeBatch(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty())
            adapter.executeBatch(sql, batch);
    }

    /** Replace this uploader's markers wholesale, for the same reason placements are replaced. */
    public void replaceMarkers(DatabaseAdapter adapter, String profile, String uploader,
                               List<MapStreamCodec.MarkChunk> marks) throws SQLException {
        adapter.executeUpdate("DELETE FROM map_markers WHERE profile = ? AND uploader = ?",
                              profile, uploader);
        String sql = "INSERT INTO map_markers (profile, uploader, mkey, segid, payload, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (profile, uploader, mkey) DO UPDATE SET "
            + "segid = EXCLUDED.segid, payload = EXCLUDED.payload, updated_at = EXCLUDED.updated_at";
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Object[]> batch = new ArrayList<>(BATCH);
        for (MapStreamCodec.MarkChunk m : marks) {
            batch.add(new Object[]{profile, uploader, markerKey(m), m.segid, m.payload, now});
            if (batch.size() >= BATCH) {
                adapter.executeBatch(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty())
            adapter.executeBatch(sql, batch);
    }

    /**
     * Stable identity for a marker: segment, position, name and resource.
     *
     * <p>The same four fields MapFile's own import dedup compares, hashed so that a long marker
     * name cannot overflow the key column. Two exports of an unchanged marker produce the same key
     * and therefore one row.
     */
    public static String markerKey(MapStreamCodec.MarkChunk m) {
        String raw = m.segid + "|" + m.tc.x + "|" + m.tc.y + "|"
            + (m.name == null ? "" : m.name) + "|" + (m.res == null ? "" : m.res);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            /* Every JRE ships SHA-256; if one somehow does not, a truncated literal key still
             * dedups correctly for every marker whose name fits. */
            return raw.length() > 128 ? raw.substring(0, 128) : raw;
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Every grid the database holds for a world, as gid -> mtime.
     *
     * <p>Payloads are left behind on purpose. This is the cheap half of the import: a manifest of
     * twenty thousand grids is a few hundred kilobytes, and it is what decides which of the
     * expensive rows are worth fetching at all.
     */
    public Map<Long, Long> loadGridManifest(DatabaseAdapter adapter, String profile) throws SQLException {
        Map<Long, Long> ret = new HashMap<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT gid, mtime FROM map_grids WHERE profile = ?", profile)) {
            while (rs.next())
                ret.put(rs.getLong("gid"), rs.getLong("mtime"));
        }
        return ret;
    }

    /** Players who have exported to this world, excluding the one asking. */
    public List<String> loadUploaders(DatabaseAdapter adapter, String profile, String exclude)
            throws SQLException {
        List<String> ret = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT DISTINCT uploader FROM map_grid_placements WHERE profile = ? ORDER BY uploader",
                profile)) {
            while (rs.next()) {
                String up = rs.getString("uploader");
                if (up == null || up.isEmpty()) continue;
                if (up.equals(exclude)) continue;
                ret.add(up);
            }
        }
        return ret;
    }

    /** One uploader's full placement set for a world. */
    public List<Placement> loadPlacements(DatabaseAdapter adapter, String profile, String uploader)
            throws SQLException {
        List<Placement> ret = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT gid, segid, sc_x, sc_y FROM map_grid_placements WHERE profile = ? AND uploader = ?",
                profile, uploader)) {
            while (rs.next())
                ret.add(new Placement(rs.getLong("gid"), rs.getLong("segid"),
                                      new Coord(rs.getInt("sc_x"), rs.getInt("sc_y"))));
        }
        return ret;
    }

    /** Payloads for one page of grid ids. Callers page; see {@link #BATCH}. */
    public Map<Long, byte[]> loadGridPayloads(DatabaseAdapter adapter, String profile, List<Long> gids)
            throws SQLException {
        Map<Long, byte[]> ret = new HashMap<>();
        if (gids.isEmpty()) return ret;
        StringBuilder sql = new StringBuilder(
            "SELECT gid, payload FROM map_grids WHERE profile = ? AND gid IN (");
        for (int i = 0; i < gids.size(); i++)
            sql.append(i == 0 ? "?" : ", ?");
        sql.append(")");
        Object[] params = new Object[gids.size() + 1];
        params[0] = profile;
        for (int i = 0; i < gids.size(); i++)
            params[i + 1] = gids.get(i);
        try (ResultSet rs = adapter.executeQuery(sql.toString(), params)) {
            while (rs.next())
                ret.put(rs.getLong("gid"), rs.getBytes("payload"));
        }
        return ret;
    }

    /** One uploader's markers for a world. */
    public List<MarkerRow> loadMarkers(DatabaseAdapter adapter, String profile, String uploader)
            throws SQLException {
        List<MarkerRow> ret = new ArrayList<>();
        try (ResultSet rs = adapter.executeQuery(
                "SELECT segid, payload FROM map_markers WHERE profile = ? AND uploader = ?",
                profile, uploader)) {
            while (rs.next())
                ret.add(new MarkerRow(rs.getLong("segid"), rs.getBytes("payload")));
        }
        return ret;
    }
}
