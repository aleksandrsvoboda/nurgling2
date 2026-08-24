package nurgling.db;

import haven.Coord;
import haven.Message;
import haven.MessageBuf;
import haven.Resource;
import haven.Utils;
import haven.ZMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static haven.PType.COORD;
import static haven.PType.STR;
import static haven.PType.UNIQID;

/**
 * Splits and reassembles the byte stream produced by {@link haven.MapFile#export}.
 *
 * <p>The exported map format is a signature followed by a zlib stream of
 * {@code (string type, int32 length, byte[] payload)} records - one per grid, one per marker - and
 * {@code MapFile.ImportedGrid} parses exactly one such payload. That makes the format a chunk store
 * already: a chunk can be pulled out, stored in a database row verbatim, and later replayed into a
 * freshly built stream that {@link haven.MapFile#reimport} accepts without knowing where it came
 * from.
 *
 * <p>Working at this level is deliberate. The hard part of sharing maps - deciding how two players'
 * segments line up and merging them - stays inside MapFile, which already does it correctly for
 * file import. Nothing here interprets tile data; the payloads are opaque, and only the small
 * fixed header of each chunk is decoded, to get the keys the database needs to index by.
 */
public class MapStreamCodec {

    /** Same signature MapFile writes; a stream without it is not an exported map. */
    public static final byte[] SIG = "Haven Mapfile 1".getBytes(Utils.ascii);

    /** Chunk version that {@code MapFile.export} emits, and the only one indexed here. */
    private static final int CHUNK_VER = 4;

    /**
     * One grid chunk: its identity plus the opaque payload.
     *
     * <p>{@link #gid} is assigned by the game server and is therefore the same value on every
     * player's client for the same physical piece of world - which is what lets the database key
     * grids globally and deduplicate them across a whole village. {@link #segid} and {@link #sc}
     * are the exporting player's own layout and mean nothing on anyone else's map except as input
     * to MapFile's merge.
     */
    public static class GridChunk {
        public final long gid;
        public final long segid;
        public final long mtime;
        public final Coord sc;
        public final byte[] payload;

        public GridChunk(long gid, long segid, long mtime, Coord sc, byte[] payload) {
            this.gid = gid;
            this.segid = segid;
            this.mtime = mtime;
            this.sc = sc;
            this.payload = payload;
        }
    }

    /** One marker chunk, decoded far enough to build a stable dedup key. */
    public static class MarkChunk {
        public final long segid;
        public final Coord tc;
        public final String name;
        /** Resource name for a natural (S) marker; null for a placed (P) marker. */
        public final String res;
        public final byte[] payload;

        public MarkChunk(long segid, Coord tc, String name, String res, byte[] payload) {
            this.segid = segid;
            this.tc = tc;
            this.name = name;
            this.res = res;
            this.payload = payload;
        }
    }

    /** Everything an exported stream contained. */
    public static class Split {
        public final List<GridChunk> grids = new ArrayList<>();
        public final List<MarkChunk> marks = new ArrayList<>();
    }

    /**
     * Take an exported map apart into indexable chunks.
     *
     * <p>Chunks whose header this client cannot read are dropped rather than aborting the split.
     * The map the player already has is not at risk either way, and refusing to upload anything
     * because one marker was odd would be the worse failure.
     */
    public static Split split(byte[] raw) throws InterruptedException {
        Split ret = new Split();
        Message in = new MessageBuf(raw);
        if (!Arrays.equals(SIG, in.bytes(SIG.length)))
            throw new Message.FormatError("not an exported map stream");
        Message z = new ZMessage(in);
        while (!z.eom()) {
            String type = z.string();
            int len = z.int32();
            byte[] payload = z.bytes(len);
            if ("grid".equals(type)) {
                GridChunk g = readGrid(payload);
                if (g != null) ret.grids.add(g);
            } else if ("mark".equals(type)) {
                MarkChunk m = readMark(payload);
                if (m != null) ret.marks.add(m);
            }
            Utils.checkirq();
        }
        return ret;
    }

    /**
     * Header of a grid payload: {@code uint8 ver, int64 gid, int64 segid, int64 mtime, coord sc}.
     * The rest - tiles, height map, overlays - is never touched here.
     */
    public static GridChunk readGrid(byte[] payload) {
        try {
            Message h = new MessageBuf(payload);
            int ver = h.uint8();
            if (ver != CHUNK_VER) return null;
            long gid = h.int64();
            long segid = h.int64();
            long mtime = h.int64();
            Coord sc = h.coord();
            return new GridChunk(gid, segid, mtime, sc, payload);
        } catch (RuntimeException e) {
            System.err.println("[MapStreamCodec] unreadable grid chunk: " + e.getMessage());
            return null;
        }
    }

    /** Header of a marker payload: {@code uint8 ver} then a tagged-object map. */
    public static MarkChunk readMark(byte[] payload) {
        try {
            Message h = new MessageBuf(payload);
            int ver = h.uint8();
            if (ver != CHUNK_VER) return null;
            @SuppressWarnings("unchecked")
            Map<Object, Object> enc = (Map<Object, Object>) h.tto();
            long segid = UNIQID.of(enc.get("seg")).bits;
            Coord tc = COORD.of(enc.get("c"));
            String nm = STR.of(enc.get("nm"));
            String res = null;
            if (enc.containsKey("res")) {
                Object r = enc.get("res");
                if (r instanceof Resource.Named)
                    res = ((Resource.Named) r).name;
            }
            return new MarkChunk(segid, tc, nm, res, payload);
        } catch (RuntimeException e) {
            System.err.println("[MapStreamCodec] unreadable marker chunk: " + e.getMessage());
            return null;
        }
    }

    /**
     * Fixed width of a grid chunk header: {@code uint8 ver, int64 gid, int64 segid, int64 mtime,
     * coord sc}. A coord is two int32s.
     */
    public static final int GRID_HEADER = 1 + 8 + 8 + 8 + 8;

    /**
     * Restamp a stored payload with a different player's segment and grid coordinate.
     *
     * <p>A payload is uploaded by whoever happened to have the newest copy, and carries that
     * player's segment layout in its header. Replaying it as part of a different player's map means
     * presenting it the way that player sees it - same tiles, their coordinates - which is what the
     * separate placement rows exist to supply. Only the fixed-width header changes; the tile,
     * height and overlay data is copied through untouched.
     *
     * @return the restamped payload, or null if the header could not be read
     */
    public static byte[] rekey(byte[] payload, long segid, Coord sc) {
        if (payload == null || payload.length < GRID_HEADER) return null;
        try {
            Message h = new MessageBuf(payload);
            if (h.uint8() != CHUNK_VER) return null;
            long gid = h.int64();
            h.int64();
            long mtime = h.int64();
            MessageBuf out = new MessageBuf();
            out.adduint8(CHUNK_VER);
            out.addint64(gid);
            out.addint64(segid);
            out.addint64(mtime);
            out.addcoord(sc);
            out.addbytes(payload, GRID_HEADER, payload.length - GRID_HEADER);
            return out.fin();
        } catch (RuntimeException e) {
            System.err.println("[MapStreamCodec] could not restamp grid chunk: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a stream {@link haven.MapFile#reimport} accepts out of stored chunks.
     *
     * <p>Grids must precede markers, exactly as {@code export} writes them: the importer resolves a
     * marker's position through the segment offset that its segment's grids established earlier in
     * the same stream, and silently drops any marker whose segment it has not seen yet.
     */
    public static byte[] assemble(Collection<byte[]> gridPayloads, Collection<byte[]> markPayloads) {
        MessageBuf out = new MessageBuf();
        out.addbytes(SIG);
        ZMessage z = new ZMessage(out);
        if (gridPayloads != null) {
            for (byte[] p : gridPayloads) {
                z.addstring("grid");
                z.addint32(p.length);
                z.addbytes(p);
            }
        }
        if (markPayloads != null) {
            for (byte[] p : markPayloads) {
                z.addstring("mark");
                z.addint32(p.length);
                z.addbytes(p);
            }
        }
        z.finish();
        return out.fin();
    }
}
