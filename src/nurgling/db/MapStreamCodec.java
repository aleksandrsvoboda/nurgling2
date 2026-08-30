package nurgling.db;

import haven.Coord;
import haven.Message;
import haven.MessageBuf;
import haven.Resource;
import haven.Utils;
import haven.ZMessage;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
 *
 * <h2>Sizes, and why grids are stored packed</h2>
 *
 * <p>A grid chunk is <em>not</em> the ~2 KB its file on disk takes. The outer stream is what carries
 * the compression, so an individual chunk is raw: 10 000 tile bytes, a height map that is usually
 * another 10 000, plus a tileset table and any overlays - about 21 KB, and up to 50 KB when the
 * terrain defeats the height-map quantiser. Twenty thousand of those held at once is most of a
 * gigabyte, which is why {@link GridChunk#packed} is deflated the moment a chunk is split out, and
 * is inflated again only one at a time, while it is being written back into a stream.
 */
public class MapStreamCodec {

    /** Same signature MapFile writes; a stream without it is not an exported map. */
    private static final byte[] SIG = "Haven Mapfile 1".getBytes(Utils.ascii);

    /** Chunk version that {@code MapFile.export} emits, and the only one indexed here. */
    private static final int CHUNK_VER = 4;

    /** Leading byte of a packed payload, to tell it apart from a chunk stored raw. */
    private static final int PACK_DEFLATE = 1;

    /**
     * One grid chunk: its identity plus the opaque payload, deflated.
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
        /** The chunk as it is stored: {@link #pack}ed, not the raw chunk MapFile wrote. */
        public final byte[] packed;

        public GridChunk(long gid, long segid, long mtime, Coord sc, byte[] packed) {
            this.gid = gid;
            this.segid = segid;
            this.mtime = mtime;
            this.sc = sc;
            this.packed = packed;
        }
    }

    /**
     * One marker chunk, decoded far enough to build a stable dedup key. Markers stay raw: a few
     * hundred bytes each, and there are thousands of them at most, not tens of thousands.
     */
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

    /**
     * Where {@link #split} hands its output. A sink rather than a returned collection because the
     * caller uploads as it goes: holding every payload until the split finished is exactly what the
     * packing above exists to avoid, and collecting them all would undo it.
     *
     * <p>{@code E} is whatever the consumer needs to throw - {@code SQLException} for the uploader,
     * {@code RuntimeException} for a sink that only collects. Naming it keeps {@link #split} from
     * declaring a bare {@code Exception}, which would force its callers into a catch broad enough to
     * swallow an interrupt.
     */
    public interface Sink<E extends Exception> {
        void grid(GridChunk chunk) throws E, InterruptedException;

        void mark(MarkChunk chunk) throws E, InterruptedException;
    }

    /**
     * Take an exported map apart, chunk by chunk.
     *
     * <p>Chunks whose header this client cannot read are dropped rather than aborting the split.
     * The map the player already has is not at risk either way, and refusing to upload anything
     * because one marker was odd would be the worse failure.
     */
    public static <E extends Exception> void split(byte[] raw, Sink<E> sink)
            throws E, InterruptedException {
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
                if (g != null) sink.grid(g);
            } else if ("mark".equals(type)) {
                MarkChunk m = readMark(payload);
                if (m != null) sink.mark(m);
            }
            Utils.checkirq();
        }
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
            return new GridChunk(gid, segid, mtime, sc, pack(payload));
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

    // ------------------------------------------------------------------ packing

    /** Deflate a raw chunk for storage, tagged so {@link #unpack} can recognise it. */
    public static byte[] pack(byte[] raw) {
        Deflater z = new Deflater(Deflater.BEST_SPEED);
        try {
            z.setInput(raw);
            z.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 8));
            out.write(PACK_DEFLATE);
            byte[] buf = new byte[8192];
            while (!z.finished()) {
                int n = z.deflate(buf);
                if (n > 0) out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            z.end();
        }
    }

    /**
     * Inflate a stored chunk. A row written before packing existed starts with the chunk version
     * byte rather than the pack tag and is returned untouched, so no migration is needed.
     */
    public static byte[] unpack(byte[] stored) {
        if ((stored == null) || (stored.length == 0))
            return null;
        if ((stored[0] & 0xff) != PACK_DEFLATE)
            return stored;
        Inflater z = new Inflater();
        try {
            z.setInput(stored, 1, stored.length - 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream(stored.length * 8);
            byte[] buf = new byte[8192];
            while (!z.finished()) {
                int n = z.inflate(buf);
                if (n == 0) {
                    if (z.needsInput() || z.needsDictionary())
                        break;
                } else {
                    out.write(buf, 0, n);
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            System.err.println("[MapStreamCodec] corrupt stored grid chunk: " + e.getMessage());
            return null;
        } finally {
            z.end();
        }
    }

    // ------------------------------------------------------------------ assembly

    /**
     * Fixed width of a grid chunk header: {@code uint8 ver, int64 gid, int64 segid, int64 mtime,
     * coord sc}. A coord is two int32s.
     */
    public static final int GRID_HEADER = 1 + 8 + 8 + 8 + 8;

    /** A stored grid, together with the place it is to take in the receiving player's layout. */
    public static class GridEmit {
        public final byte[] packed;
        public final long segid;
        public final Coord sc;

        public GridEmit(byte[] packed, long segid, Coord sc) {
            this.packed = packed;
            this.segid = segid;
            this.sc = sc;
        }
    }

    /**
     * Restamp a raw chunk with a different player's segment and grid coordinate.
     *
     * <p>A payload is uploaded by whoever happened to have the newest copy, and carries that
     * player's segment layout in its header. Replaying it as part of a different player's map means
     * presenting it the way that player sees it - same tiles, their coordinates - which is what the
     * separate placement rows exist to supply. Only the fixed-width header changes; the tile,
     * height and overlay data is copied through untouched.
     *
     * @return the restamped chunk, or null if the header could not be read
     */
    public static byte[] rekey(byte[] raw, long segid, Coord sc) {
        if ((raw == null) || (raw.length < GRID_HEADER)) return null;
        try {
            Message h = new MessageBuf(raw);
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
            out.addbytes(raw, GRID_HEADER, raw.length - GRID_HEADER);
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
     * the same stream, and silently drops any marker whose segment it has not seen yet. The order
     * of the grids among themselves is the caller's business and it matters - see
     * {@link MapImportPlanner}.
     *
     * <p>Each grid is inflated only for as long as it takes to write it out, so the peak cost here
     * is one chunk rather than the whole map.
     */
    public static byte[] assemble(Collection<GridEmit> grids, Collection<byte[]> markPayloads)
            throws InterruptedException {
        MessageBuf out = new MessageBuf();
        out.addbytes(SIG);
        ZMessage z = new ZMessage(out);
        if (grids != null) {
            for (GridEmit g : grids) {
                byte[] raw = unpack(g.packed);
                if (raw == null)
                    continue;
                byte[] chunk = rekey(raw, g.segid, g.sc);
                if (chunk == null)
                    continue;
                z.addstring("grid");
                z.addint32(chunk.length);
                z.addbytes(chunk);
                Utils.checkirq();
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
