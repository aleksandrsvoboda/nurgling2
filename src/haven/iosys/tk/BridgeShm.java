package haven.iosys.tk;

import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.lwjgl.system.*;

/*
 * nurgling: the memory the frame bridge's two processes share (see
 * BridgeToolkit and BridgePresenter): frame slots, an input event ring
 * and a few control fields. On Windows it is a named mapping backed by
 * the paging file, so the frames rewritten every frame never go to
 * disk; elsewhere, a mapped file.
 *
 * Each field has a single writer. The writer fills data before it
 * publishes the field announcing it, with a full fence between, and
 * the reader fences between reading the announcement and the data,
 * so neither side sees half-written frames, events or commands.
 */
public class BridgeShm {
    public static final int MAGIC = 0x4e425247, VERSION = 1;
    /* Frame slots: the game fills one the presenter is not reading,
     * that is neither the latest nor the one before (the presenter may
     * be about to take that), and publishes it as the latest. */
    public static final int NSLOT = 6;
    /* Slots whose pixels go through this memory (the copy path); with
     * shared GPU textures, slots only carry their descriptions. */
    public static final int NDATA = 4;
    /* The largest window the slots hold, plus the scene's margins. */
    public static final int MAXW = 3840 + 128, MAXH = 2160 + 128;
    public static final int IMGCAP = MAXW * MAXH * 4;

    /* Header, written by the game unless noted. */
    static final int H_MAGIC = 0, H_VERSION = 4, H_GAMEPID = 8, H_CLOSED = 16;
    /* Written by the presenter. */
    static final int H_WNDW = 24, H_WNDH = 28, H_FOCUSED = 32, H_VISIBLE = 36, H_READING = 40, H_READPREV = 44, H_BEAT = 48;
    static final int H_LATEST = 56;
    /* Window control, written by the game; each block has a sequence
     * number the presenter compares with the last one it applied. */
    static final int C_TITLESEQ = 64, C_TITLELEN = 68, C_TITLE = 72, TITLECAP = 512;
    static final int C_SIZESEQ = 584, C_FIXW = 588, C_FIXH = 592, C_NORMW = 596, C_NORMH = 600, C_MINW = 604, C_MINH = 608, C_STATE = 612;
    static final int C_SHOWSEQ = 616, C_SHOW = 620;
    static final int C_CURSSEQ = 624, C_CURSKIND = 628, C_CURSVAL = 632;
    /* The client's frame limit, as the shortest time between frames
     * in nanoseconds (0 for none); the presenter shows no more often. */
    static final int H_FRAMEDUR = 640;
    /* When the presenter showed its frames (System.nanoTime()), for
     * the client's frame statistics: a count, then a ring of times. */
    static final int H_SHOWN = 1024, H_SHOWNT = 1032, NSHOWN = 128;
    /* And by how much the scene's step on screen changed in each
     * (see BridgePresenter.hop). */
    static final int H_SHOWNH = H_SHOWNT + (NSHOWN * 8);
    static final int HEADER = 4096;

    /* Custom cursor images, registered by the game. */
    public static final int NCURS = 64, CURSDIM = 128;
    static final int CU_W = 0, CU_H = 4, CU_HX = 8, CU_HY = 12, CU_DATA = 16, CURSREC = 16 + (CURSDIM * CURSDIM * 4);
    static final int CURS = HEADER;

    /* Frame slot headers. */
    static final int S_SEQ = 0, S_UIW = 8, S_UIH = 12, S_SCW = 16, S_SCH = 20, S_SCX = 24, S_SCY = 28;
    static final int S_TIME = 32, S_HASSCENE = 40, S_UIGEN = 44, S_SCGEN = 48, S_ZC = 52;
    /* How far the client moved its view to land on whole pixels. */
    static final int S_SNAPX = 56, S_SNAPY = 60;
    /* How far the camera has slid on screen in all, unrounded, and a
     * count of the times it turned, zoomed or jumped instead, across
     * which the slide does not continue. */
    static final int S_OGEN = 64, S_OX = 72, S_OY = 80, SLOTREC = 96;
    static final int SLOTS = CURS + (NCURS * CURSREC);

    /* Input ring, written by the presenter. */
    public static final int NREC = 4096, REC = 64;
    static final int R_HEAD = 0, R_TAIL = 8, R_DATA = 64;
    static final int RING = SLOTS + (NSLOT * SLOTREC);
    public static final int I_KIND = 0, I_ID = 4, I_WHEN = 8, I_MODS = 16, I_CODE = 20, I_CHAR = 24, I_LOC = 28, I_EXT = 32;
    public static final int I_X = 36, I_Y = 40, I_BTN = 44, I_CLICKS = 48, I_ROT = 52, I_PRECISE = 56;
    public static final int K_KEY = 1, K_MOUSE = 2, K_WHEEL = 3, K_CLOSE = 4;

    static final int DATA = RING + R_DATA + (NREC * REC);
    public static final long SIZE = DATA + ((long)NDATA * 2 * IMGCAP);

    public final String name;
    private final Storage st;
    final ByteBuffer hdr;
    private final ByteBuffer[] data = new ByteBuffer[NDATA];

    private interface Storage {
	ByteBuffer map(long off, int len) throws IOException;
	void close();
    }

    private static boolean windows() {
	return(System.getProperty("os.name", "").startsWith("Windows"));
    }

    /* A paging-file mapping, through kernel32 directly. */
    private static class WinMapping implements Storage {
	static final int PAGE_READWRITE = 0x04, FILE_MAP_ALL_ACCESS = 0x000f001f;
	static final SharedLibrary k32 = Library.loadNative(BridgeShm.class, "org.lwjgl", "kernel32");
	static final long fcreate = k32.getFunctionAddress("CreateFileMappingW"), fopen = k32.getFunctionAddress("OpenFileMappingW");
	static final long fmap = k32.getFunctionAddress("MapViewOfFile"), funmap = k32.getFunctionAddress("UnmapViewOfFile");
	static final long fclose = k32.getFunctionAddress("CloseHandle");
	final long handle, base;

	WinMapping(String name, boolean create) throws IOException {
	    ByteBuffer wname = MemoryUtil.memUTF16("Local\\" + name, true);
	    try {
		if(create)
		    handle = JNI.invokePPPP(-1L, 0L, PAGE_READWRITE, (int)(SIZE >>> 32), (int)SIZE, MemoryUtil.memAddress(wname), fcreate);
		else
		    handle = JNI.invokePP(FILE_MAP_ALL_ACCESS, 0, MemoryUtil.memAddress(wname), fopen);
	    } finally {
		MemoryUtil.memFree(wname);
	    }
	    if(handle == 0)
		throw(new IOException("could not " + (create ? "create" : "open") + " shared memory " + name));
	    base = JNI.invokePPP(handle, FILE_MAP_ALL_ACCESS, 0, 0, SIZE, fmap);
	    if(base == 0) {
		JNI.invokePI(handle, fclose);
		throw(new IOException("could not map shared memory " + name));
	    }
	}

	public ByteBuffer map(long off, int len) {
	    return(MemoryUtil.memByteBuffer(base + off, len));
	}

	public void close() {
	    JNI.invokePI(base, funmap);
	    JNI.invokePI(handle, fclose);
	}
    }

    private static class FileMapping implements Storage {
	final FileChannel ch;

	FileMapping(String name, boolean create) throws IOException {
	    Path path = Paths.get(System.getProperty("java.io.tmpdir"), name);
	    if(create)
		ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
	    else
		ch = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
	}

	public ByteBuffer map(long off, int len) throws IOException {
	    return(ch.map(FileChannel.MapMode.READ_WRITE, off, len));
	}

	public void close() {
	    try {
		ch.close();
	    } catch(IOException e) {
	    }
	}
    }

    private BridgeShm(String name, boolean create) throws IOException {
	this.name = name;
	this.st = windows() ? new WinMapping(name, create) : new FileMapping(name, create);
	hdr = st.map(0, DATA).order(ByteOrder.LITTLE_ENDIAN);
	for(int i = 0; i < NDATA; i++)
	    data[i] = st.map(DATA + ((long)i * 2 * IMGCAP), 2 * IMGCAP).order(ByteOrder.nativeOrder());
    }

    /* Game side: fresh memory. */
    public static BridgeShm create(String name) throws IOException {
	BridgeShm ret = new BridgeShm(name, true);
	ret.hdr.putInt(H_VERSION, VERSION);
	ret.hdr.putLong(H_GAMEPID, ProcessHandle.current().pid());
	ret.hdr.putInt(H_READING, -1);
	ret.hdr.putInt(H_READPREV, -1);
	ret.hdr.putInt(H_LATEST, -1);
	for(int i = 0; i < NSLOT; i++)
	    ret.hdr.putLong(SLOTS + (i * SLOTREC) + S_SEQ, -1);
	VarHandle.fullFence();
	ret.hdr.putInt(H_MAGIC, MAGIC);
	return(ret);
    }

    /* Presenter side. */
    public static BridgeShm open(String name) throws IOException {
	BridgeShm ret = new BridgeShm(name, false);
	if((ret.hdr.getInt(H_MAGIC) != MAGIC) || (ret.hdr.getInt(H_VERSION) != VERSION))
	    throw(new IOException("not frame bridge memory: " + name));
	return(ret);
    }

    /* Plain accessors. Callers place fences as the protocol needs. */
    int geti(int off) {return(hdr.getInt(off));}
    long getl(int off) {return(hdr.getLong(off));}
    float getf(int off) {return(hdr.getFloat(off));}
    void puti(int off, int v) {hdr.putInt(off, v);}
    void putl(int off, long v) {hdr.putLong(off, v);}
    void putf(int off, float v) {hdr.putFloat(off, v);}

    static int slot(int i) {return(SLOTS + (i * SLOTREC));}
    static int curs(int i) {return(CURS + (i * CURSREC));}
    static int rec(long n) {return(RING + R_DATA + (int)((n % NREC) * REC));}

    /* The name a slot's shared texture is exported under. */
    static String texname(long gamepid, int slot, char what, int gen) {
	return(String.format("nurgling-bridge-%d-s%d-%c%d", gamepid, slot, what, gen));
    }

    /* A slot's UI layer pixels, then its scene pixels. */
    ByteBuffer uibuf(int slot, int bytes) {
	return(data[slot].slice(0, bytes).order(ByteOrder.nativeOrder()));
    }

    ByteBuffer scenebuf(int slot, int bytes) {
	return(data[slot].slice(IMGCAP, bytes).order(ByteOrder.nativeOrder()));
    }

    public void close() {
	st.close();
    }
}
