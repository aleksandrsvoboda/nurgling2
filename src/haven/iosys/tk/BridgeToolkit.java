package haven.iosys.tk;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import haven.*;
import haven.iosys.*;
import haven.render.*;

/*
 * nurgling: the frame bridge, game side. The client renders offscreen
 * (an Acephal) and hands its frames to a separate presenter process
 * (BridgePresenter), which owns the real window, through shared memory
 * (BridgeShm). The map is rendered into its own scene image with a
 * margin around it, the rest of the interface into a transparent
 * layer; the presenter draws the scene shifted along the camera's
 * motion for however long the newest frame is old, then the layer on
 * top. A garbage-collection pause or stall in this process therefore
 * no longer stops what is shown: the presenter is a small, separate
 * JVM that keeps its own beat.
 *
 * Input goes the other way: the presenter forwards its window's raw
 * AWT events, and they are fed into the same AWT event processing a
 * local window uses, so the client sees identical events.
 */
@Toolkit.Available(name = "bridge")
public class BridgeToolkit extends AWTToolkit {
    /* The scene's margin on every side, in pixels: how far the
     * presenter may shift it before an edge would show. */
    public static final int MARGIN = 32;
    public final Acephal backend;
    public final String frontend;
    final BridgeShm shm;
    private final Process presenter;
    private final Map<Cursor, Integer> cursids = new IdentityHashMap<>();
    private int ncurs = 0;

    public BridgeToolkit(String backend, String frontend) {
	Providers.Factory<? extends Acephal> bf = Acephal.types().get(backend);
	if(bf == null)
	    throw(new Unavailable("no such headless renderer: " + backend));
	this.backend = bf.open();
	this.frontend = frontend;
	try {
	    this.shm = BridgeShm.create("nurgling-bridge-" + ProcessHandle.current().pid());
	} catch(IOException e) {
	    throw(new Unavailable("could not create frame bridge memory", e));
	}
	this.presenter = spawn();
	waitwindow();
	Runtime.getRuntime().addShutdownHook(new Thread(() -> {
		shm.puti(BridgeShm.H_CLOSED, 1);
		presenter.destroy();
	    }, "Frame bridge shutdown"));
    }

    private Process spawn() {
	String java = ProcessHandle.current().info().command().orElse("java");
	List<String> cmd = new ArrayList<>();
	cmd.add(java);
	/* Small and quiet: its loop allocates next to nothing, so a small
	 * young generation and the serial collector keep pauses short and
	 * rare. */
	cmd.add("-Xmx512m");
	cmd.add("-XX:+UseSerialGC");
	cmd.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
	cmd.add("-Dhaven.toolkit=" + frontend);
	cmd.add("-cp");
	cmd.add(System.getProperty("java.class.path"));
	cmd.add(BridgePresenter.class.getName());
	cmd.add(shm.name);
	ProcessBuilder pb = new ProcessBuilder(cmd);
	/* Never pipes: an unread pipe fills up and freezes the child. */
	File log = new File(System.getProperty("java.io.tmpdir"), "nurgling-presenter.log");
	pb.redirectErrorStream(true);
	pb.redirectOutput(ProcessBuilder.Redirect.to(log));
	try {
	    return(pb.start());
	} catch(IOException e) {
	    throw(new Unavailable("could not start the frame bridge presenter", e));
	}
    }

    /* Until the presenter's window exists and has reported its size. */
    private void waitwindow() {
	long end = System.currentTimeMillis() + 30000;
	while(shm.geti(BridgeShm.H_WNDW) <= 0) {
	    if(!presenter.isAlive())
		throw(new Unavailable("the frame bridge presenter exited at startup; see nurgling-presenter.log"));
	    if(System.currentTimeMillis() > end)
		throw(new Unavailable("the frame bridge presenter did not open its window"));
	    try {
		Thread.sleep(10);
	    } catch(InterruptedException e) {
		Thread.currentThread().interrupt();
		throw(new Unavailable("interrupted waiting for the frame bridge presenter"));
	    }
	}
    }

    /* The bridge window of a UI, if it is drawn through one. */
    public static BridgeWindow of(UI ui) {
	if((ui == null) || (ui.loop == null))
	    return(null);
	Windeye wnd = ui.loop.wnd;
	return((wnd instanceof BridgeWindow) ? (BridgeWindow)wnd : null);
    }

    /* Cursors are drawn by the presenter's window, so they keep moving
     * whatever this process does; their images go through the shared
     * memory, once each. */
    public AWTCursor makecursor(BufferedImage img, Coord hs) {
	AWTCursor ret = super.makecursor(img, hs);
	synchronized(cursids) {
	    int w = img.getWidth(), h = img.getHeight();
	    if((ncurs < BridgeShm.NCURS) && (w <= BridgeShm.CURSDIM) && (h <= BridgeShm.CURSDIM)) {
		int id = ncurs++;
		int off = BridgeShm.curs(id);
		shm.puti(off + BridgeShm.CU_W, w);
		shm.puti(off + BridgeShm.CU_H, h);
		shm.puti(off + BridgeShm.CU_HX, hs.x);
		shm.puti(off + BridgeShm.CU_HY, hs.y);
		for(int y = 0, o = off + BridgeShm.CU_DATA; y < h; y++) {
		    for(int x = 0; x < w; x++, o += 4)
			shm.puti(o, img.getRGB(x, y));
		}
		cursids.put(ret, id);
	    }
	}
	return(ret);
    }

    public Windeye window() {
	return(new BridgeWindow());
    }

    public String description() {
	return(String.format("Frame bridge (renderer=%s, window=%s)", backend, frontend));
    }

    public void dispose() {
	shm.puti(BridgeShm.H_CLOSED, 1);
	presenter.destroy();
	backend.dispose();
    }

    public class BridgeWindow extends AWTWindow {
	private final Component dummy = new java.awt.Canvas();
	private final EventQueue eq = new EventQueue();
	private final Thread input;
	/* The interface layer: the framebuffer the UI draws into. */
	private Coord uisz = null;
	private Texture2D uicol, uidpt;
	private Pipe.Op uifb;
	/* The map's scene, drawn by the map (see scene()). */
	private Coord scsz = null;
	private Texture2D sccol, scdpt;
	private Pipe.Op scfb;
	private boolean scdrawn = false;
	private Coord scul = Coord.z;
	/* The scene's motion as of this frame; see scenemotion(). */
	private double[] slide = {0, 0, 0, 0, 0};
	private long stime;
	/* Frame slots: the latest published, the one before, and those
	 * still being rendered or read back. */
	private int latest = -1, prevlatest = -1;
	private final boolean[] inflight = new boolean[BridgeShm.NSLOT];
	private long fseq = 0;
	/* Zero-copy: on a Vulkan device that can share memory, frames are
	 * rendered straight into textures the presenter samples. Each slot
	 * has its own pair; gen counts their re-creations (resizes), and is
	 * part of the name they are shared under. */
	private Boolean zc = null;
	private final SharedTexture2D[] zui = new SharedTexture2D[BridgeShm.NSLOT], zsc = new SharedTexture2D[BridgeShm.NSLOT];
	private final int[] zuigen = new int[BridgeShm.NSLOT], zscgen = new int[BridgeShm.NSLOT];
	/* The slot this frame renders into; -1 for none free, when the
	 * frame goes to scratch textures and is dropped. */
	private int cur = -1;
	private boolean framing = false;
	private Texture2D scratchui, scratchsc;
	private final long pid = ProcessHandle.current().pid();
	private int titleseq, sizeseq, showseq, cursseq;

	public BridgeWindow() {
	    input = new HackThread(this::inputloop, "Frame bridge input");
	    input.setDaemon(true);
	    input.start();
	}

	protected Component panel() {return(dummy);}

	public Coord size() {
	    int w = shm.geti(BridgeShm.H_WNDW), h = shm.geti(BridgeShm.H_WNDH);
	    return(((w > 0) && (h > 0)) ? Coord.of(w, h) : Coord.of(1024, 768));
	}

	public boolean focused() {
	    return(shm.geti(BridgeShm.H_FOCUSED) != 0);
	}

	public Visibility visible() {
	    return((shm.geti(BridgeShm.H_VISIBLE) != 0) ? Visibility.FULL : Visibility.NONE);
	}

	public State state() {
	    return(State.NORMAL);
	}

	public Environment env() {
	    return(backend.env());
	}

	/* Window control, applied by the presenter. */
	public BridgeWindow title(String title) {
	    byte[] b = title.getBytes(Utils.utf8);
	    int n = Math.min(b.length, BridgeShm.TITLECAP);
	    synchronized(shm) {
		shm.puti(BridgeShm.C_TITLELEN, n);
		for(int i = 0; i < n; i++)
		    shm.hdr.put(BridgeShm.C_TITLE + i, b[i]);
		VarHandle.fullFence();
		shm.puti(BridgeShm.C_TITLESEQ, ++titleseq);
	    }
	    return(this);
	}

	public BridgeWindow sizing(Sizing info) {
	    synchronized(shm) {
		shm.puti(BridgeShm.C_FIXW, (info.fixsize == null) ? 0 : info.fixsize.x);
		shm.puti(BridgeShm.C_FIXH, (info.fixsize == null) ? 0 : info.fixsize.y);
		shm.puti(BridgeShm.C_NORMW, (info.normsize == null) ? 0 : info.normsize.x);
		shm.puti(BridgeShm.C_NORMH, (info.normsize == null) ? 0 : info.normsize.y);
		shm.puti(BridgeShm.C_MINW, (info.minsize == null) ? 0 : info.minsize.x);
		shm.puti(BridgeShm.C_MINH, (info.minsize == null) ? 0 : info.minsize.y);
		VarHandle.fullFence();
		shm.puti(BridgeShm.C_SIZESEQ, ++sizeseq);
	    }
	    return(this);
	}

	public BridgeWindow state(State st) {
	    synchronized(shm) {
		shm.puti(BridgeShm.C_STATE, st.ordinal());
		VarHandle.fullFence();
		shm.puti(BridgeShm.C_SIZESEQ, ++sizeseq);
	    }
	    return(this);
	}

	public BridgeWindow show(boolean show) {
	    synchronized(shm) {
		shm.puti(BridgeShm.C_SHOW, show ? 1 : 0);
		VarHandle.fullFence();
		shm.puti(BridgeShm.C_SHOWSEQ, ++showseq);
	    }
	    return(this);
	}

	public BridgeWindow icon(BufferedImage icon) {
	    /* The presenter sets the same client icon itself. */
	    return(this);
	}

	public BridgeWindow cursor(Cursor curs) {
	    int kind, val;
	    if(curs == null) {
		kind = 0; val = 0;
	    } else if(curs instanceof Cursor.Std) {
		kind = 1; val = ((Cursor.Std)curs).ordinal();
	    } else {
		Integer id;
		synchronized(cursids) {
		    id = cursids.get(curs);
		}
		if(id == null) {
		    kind = 0; val = 0;
		} else {
		    kind = 2; val = id;
		}
	    }
	    synchronized(shm) {
		shm.puti(BridgeShm.C_CURSKIND, kind);
		shm.puti(BridgeShm.C_CURSVAL, val);
		VarHandle.fullFence();
		shm.puti(BridgeShm.C_CURSSEQ, ++cursseq);
	    }
	    return(this);
	}

	private Pipe.Op mkfb(Texture2D col, Texture2D dpt) {
	    return(Pipe.Op.compose(new FragColor<>(col.image(0)), new DepthBuffer<>(dpt.image(0))));
	}

	private boolean zerocopy() {
	    if(zc == null) {
		Environment env = backend.env();
		zc = (env instanceof haven.render.vk.VkEnvironment) && ((haven.render.vk.VkEnvironment)env).extmem;
	    }
	    return(zc);
	}

	private Texture2D zctex(SharedTexture2D[] texs, int[] gens, char what, Coord sz) {
	    if(cur < 0)
		return(null);
	    SharedTexture2D t = texs[cur];
	    if((t == null) || (t.w != sz.x) || (t.h != sz.y)) {
		if(t != null)
		    t.dispose();
		texs[cur] = t = new SharedTexture2D(sz, BridgeShm.texname(pid, cur, what, ++gens[cur]), true);
	    }
	    return(t);
	}

	private Texture2D sized(Texture2D t, Coord sz, boolean depth) {
	    if((t != null) && (t.w == sz.x) && (t.h == sz.y))
		return(t);
	    if(t != null)
		t.dispose();
	    return(depth ? new Texture2D(sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null)
		   : new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null));
	}

	public Pipe.Op fbstate() {
	    Coord sz = size();
	    if(zerocopy()) {
		/* A frame starts with its interface: it takes its slot here. */
		if(!framing) {
		    cur = pickslot();
		    framing = true;
		}
		uidpt = sized(uidpt, sz, true);
		Texture2D col = zctex(zui, zuigen, 'u', sz);
		if(col == null)
		    col = scratchui = sized(scratchui, sz, false);
		uicol = col;
		uisz = sz;
		return(mkfb(col, uidpt));
	    }
	    if(!Utils.eq(uisz, sz)) {
		if(uicol != null) {
		    uicol.dispose();
		    uidpt.dispose();
		}
		uicol = new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
		uidpt = new Texture2D(sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
		uifb = mkfb(uicol, uidpt);
		uisz = sz;
	    }
	    return(uifb);
	}

	/* UI thread, from MapView.draw: where the map draws its scene
	 * instead of the interface layer. ul is the map's place in the
	 * window; the map is sized to cover the margins. */
	public GOut scene(Render out, Coord sz, Coord ul) {
	    if(zerocopy()) {
		scdpt = sized(scdpt, sz, true);
		Texture2D col = zctex(zsc, zscgen, 'c', sz);
		if(col == null)
		    col = scratchsc = sized(scratchsc, sz, false);
		sccol = col;
		scfb = mkfb(col, scdpt);
		scsz = sz;
	    } else if(!Utils.eq(scsz, sz)) {
		if(sccol != null) {
		    sccol.dispose();
		    scdpt.dispose();
		}
		sccol = new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
		scdpt = new Texture2D(sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
		scfb = mkfb(sccol, scdpt);
		scsz = sz;
	    }
	    Area area = Area.sized(sz);
	    Pipe base = new BufPipe();
	    base.prep(scfb).prep(FragColor.blend(new BlendMode()));
	    base.prep(new States.Viewport(area)).prep(new Ortho2D(area));
	    base.prep(new FrameInfo());
	    out.clear(base, FragColor.fragcol, FColor.BLACK);
	    out.clear(base, 1.0);
	    scdrawn = true;
	    scul = ul;
	    return(new GOut(out, base, sz));
	}

	/* UI thread: records the frames the presenter has shown since
	 * the last call, which are the frames the player actually saw. */
	private long shownread = 0;
	public void recordshown(nurgling.render.FrameHistory hist) {
	    long n = shm.getl(BridgeShm.H_SHOWN);
	    VarHandle.fullFence();
	    long from = Math.max(shownread, n - BridgeShm.NSHOWN);
	    long nano = System.nanoTime();
	    double rnow = Utils.rtime();
	    for(long i = from; i < n; i++) {
		long t = shm.getl(BridgeShm.H_SHOWNT + (int)((i % BridgeShm.NSHOWN) * 8));
		hist.record(rnow - ((nano - t) / 1e9), shm.geti(BridgeShm.H_SHOWNH + (int)((i % BridgeShm.NSHOWN) * 4)));
	    }
	    shownread = n;
	}

	/* UI thread: how far the camera has slid on screen in all (ox,
	 * oy, unrounded), the count of turns, zooms and jumps across which
	 * the slide does not continue (gen), how far the view was moved
	 * onto whole pixels (snapx, snapy), and the time of the world the
	 * scene shows; for the presenter to place the scene between
	 * frames. */
	public void scenemotion(double ox, double oy, int gen, float snapx, float snapy, long time) {
	    this.slide = new double[] {ox, oy, gen, snapx, snapy};
	    this.stime = time;
	}

	private int pickslot() {
	    int reading = shm.geti(BridgeShm.H_READING), readprev = shm.geti(BridgeShm.H_READPREV);
	    int n = zerocopy() ? BridgeShm.NSLOT : BridgeShm.NDATA;
	    synchronized(inflight) {
		for(int i = 0; i < n; i++) {
		    if((i != latest) && (i != prevlatest) && (i != reading) && (i != readprev) && !inflight[i]) {
			inflight[i] = true;
			return(i);
		    }
		}
	    }
	    return(-1);
	}

	public void swapbuffers(Render out, Object mode) {
	    if(zerocopy()) {
		/* The frame is in its slot's textures already; it is the
		 * latest once the GPU has finished it. */
		int slot = cur;
		framing = false;
		cur = -1;
		Coord us = uisz, ss = scdrawn ? scsz : null;
		scdrawn = false;
		if(slot < 0)
		    return;
		long time = (ss != null) ? stime : System.nanoTime();
		double[] slide = this.slide;
		Coord ul = scul;
		int uigen = zuigen[slot], scgen = zscgen[slot];
		out.fence(() -> publish(slot, us, ss, ul, time, slide, true, uigen, scgen));
		return;
	    }
	    Coord us = uisz;
	    Coord ss = scdrawn ? scsz : null;
	    scdrawn = false;
	    if((us == null) || (us.x > BridgeShm.MAXW) || (us.y > BridgeShm.MAXH))
		return;
	    if((ss != null) && ((ss.x > BridgeShm.MAXW) || (ss.y > BridgeShm.MAXH)))
		ss = null;
	    int slot = pickslot();
	    /* The presenter is behind: this frame is dropped, not queued. */
	    if(slot < 0)
		return;
	    long time = (ss != null) ? stime : System.nanoTime();
	    double[] slide = this.slide;
	    Coord ul = scul;
	    Coord fss = ss;
	    AtomicInteger left = new AtomicInteger((ss != null) ? 2 : 1);
	    Runnable done = () -> {
		if(left.decrementAndGet() == 0)
		    publish(slot, us, fss, ul, time, slide, false, 0, 0);
	    };
	    VectorFormat fmt = new VectorFormat(4, NumberFormat.UNORM8);
	    out.pget(uicol.image(0), fmt, shm.uibuf(slot, us.x * us.y * 4), buf -> done.run());
	    if(ss != null)
		out.pget(sccol.image(0), fmt, shm.scenebuf(slot, ss.x * ss.y * 4), buf -> done.run());
	}

	private void publish(int slot, Coord us, Coord ss, Coord ul, long time, double[] slide, boolean zc, int uigen, int scgen) {
	    int off = BridgeShm.slot(slot);
	    synchronized(inflight) {
		shm.puti(off + BridgeShm.S_ZC, zc ? 1 : 0);
		shm.puti(off + BridgeShm.S_UIGEN, uigen);
		shm.puti(off + BridgeShm.S_SCGEN, scgen);
		shm.puti(off + BridgeShm.S_UIW, us.x);
		shm.puti(off + BridgeShm.S_UIH, us.y);
		shm.puti(off + BridgeShm.S_HASSCENE, (ss != null) ? 1 : 0);
		if(ss != null) {
		    shm.puti(off + BridgeShm.S_SCW, ss.x);
		    shm.puti(off + BridgeShm.S_SCH, ss.y);
		    shm.puti(off + BridgeShm.S_SCX, ul.x);
		    shm.puti(off + BridgeShm.S_SCY, ul.y);
		}
		shm.putl(off + BridgeShm.S_TIME, time);
		shm.hdr.putDouble(off + BridgeShm.S_OX, slide[0]);
		shm.hdr.putDouble(off + BridgeShm.S_OY, slide[1]);
		shm.puti(off + BridgeShm.S_OGEN, (int)slide[2]);
		shm.putf(off + BridgeShm.S_SNAPX, (float)slide[3]);
		shm.putf(off + BridgeShm.S_SNAPY, (float)slide[4]);
		VarHandle.fullFence();
		shm.putl(off + BridgeShm.S_SEQ, ++fseq);
		VarHandle.fullFence();
		prevlatest = latest;
		latest = slot;
		shm.puti(BridgeShm.H_LATEST, slot);
		inflight[slot] = false;
	    }
	    if(fseq == 1)
		RendererPref.confirm();
	}

	/* Events from the presenter's window, turned back into the AWT
	 * events they were and handed to the usual AWT event processing. */
	private void inputloop() {
	    long tail = shm.getl(BridgeShm.RING + BridgeShm.R_TAIL);
	    boolean warned = false;
	    while(true) {
		long head = shm.getl(BridgeShm.RING + BridgeShm.R_HEAD);
		VarHandle.fullFence();
		boolean any = false;
		for(; tail < head; tail++) {
		    deliver(BridgeShm.rec(tail));
		    any = true;
		}
		if(any) {
		    VarHandle.fullFence();
		    shm.putl(BridgeShm.RING + BridgeShm.R_TAIL, tail);
		    eq.process();
		}
		if(!presenter.isAlive()) {
		    /* Without its window the client cannot be used; leave
		     * the way closing the window would. */
		    if(!warned) {
			warned = true;
			System.err.println("frame bridge: the presenter exited; closing");
			eq.windowClosing(null);
			eq.process();
		    }
		}
		try {
		    Thread.sleep(1);
		} catch(InterruptedException e) {
		    return;
		}
	    }
	}

	private void deliver(int off) {
	    ByteBuffer h = shm.hdr;
	    int kind = h.getInt(off + BridgeShm.I_KIND), id = h.getInt(off + BridgeShm.I_ID);
	    long when = h.getLong(off + BridgeShm.I_WHEN);
	    int mods = h.getInt(off + BridgeShm.I_MODS);
	    switch(kind) {
	    case BridgeShm.K_KEY: {
		java.awt.event.KeyEvent ev = new java.awt.event.KeyEvent(dummy, id, when, mods, h.getInt(off + BridgeShm.I_CODE),
									(char)h.getInt(off + BridgeShm.I_CHAR), h.getInt(off + BridgeShm.I_LOC));
		extcode(ev, h.getInt(off + BridgeShm.I_EXT));
		if(id == java.awt.event.KeyEvent.KEY_PRESSED)
		    eq.keyPressed(ev);
		else if(id == java.awt.event.KeyEvent.KEY_RELEASED)
		    eq.keyReleased(ev);
		else if(id == java.awt.event.KeyEvent.KEY_TYPED)
		    eq.keyTyped(ev);
		break;
	    }
	    case BridgeShm.K_MOUSE: {
		java.awt.event.MouseEvent ev = new java.awt.event.MouseEvent(dummy, id, when, mods, h.getInt(off + BridgeShm.I_X), h.getInt(off + BridgeShm.I_Y),
									    h.getInt(off + BridgeShm.I_CLICKS), false, h.getInt(off + BridgeShm.I_BTN));
		if(id == java.awt.event.MouseEvent.MOUSE_PRESSED)
		    eq.mousePressed(ev);
		else if(id == java.awt.event.MouseEvent.MOUSE_RELEASED)
		    eq.mouseReleased(ev);
		else if(id == java.awt.event.MouseEvent.MOUSE_DRAGGED)
		    eq.mouseDragged(ev);
		else if(id == java.awt.event.MouseEvent.MOUSE_MOVED)
		    eq.mouseMoved(ev);
		break;
	    }
	    case BridgeShm.K_WHEEL: {
		int x = h.getInt(off + BridgeShm.I_X), y = h.getInt(off + BridgeShm.I_Y);
		java.awt.event.MouseWheelEvent ev = new java.awt.event.MouseWheelEvent(dummy, id, when, mods, x, y, x, y, 0, false,
										       java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1,
										       h.getInt(off + BridgeShm.I_ROT), h.getDouble(off + BridgeShm.I_PRECISE));
		eq.mouseWheelMoved(ev);
		break;
	    }
	    case BridgeShm.K_CLOSE:
		eq.windowClosing(null);
		break;
	    }
	}

	public void dispose() {
	    input.interrupt();
	    super.dispose();
	}
    }

    /* The extended key code is not a constructor argument; AWT's own
     * accessor sets it (sun.awt is exported to the client). */
    private static java.lang.reflect.Method setext = null;
    private static Object keyacc = null;
    private static boolean extfailed = false;
    static void extcode(java.awt.event.KeyEvent ev, int code) {
	if(extfailed)
	    return;
	try {
	    if(setext == null) {
		Class<?> acc = Class.forName("sun.awt.AWTAccessor");
		keyacc = acc.getMethod("getKeyEventAccessor").invoke(null);
		setext = Class.forName("sun.awt.AWTAccessor$KeyEventAccessor").getMethod("setExtendedKeyCode", java.awt.event.KeyEvent.class, long.class);
	    }
	    setext.invoke(keyacc, ev, (long)code);
	} catch(ReflectiveOperationException | RuntimeException e) {
	    extfailed = true;
	    new Warning(e, "frame bridge: cannot set extended key codes").issue();
	}
    }

    private static String defrenderer() {
	return(RendererPref.VULKAN.equals(RendererPref.get()) ? "vulkan" : "jogl");
    }

    private static Providers.Factory<BridgeToolkit> factory = new Providers.Factory<BridgeToolkit>() {
	/* One per process: the toolkit is asked for from several threads
	 * at startup (UI scaling asks too), and each would start its own
	 * presenter. */
	private BridgeToolkit instance = null;

	/* "bridge" uses the renderer chosen in the options; "bridge:jogl"
	 * or "bridge:vulkan" picks one, for both processes. */
	public synchronized BridgeToolkit open(String... args) {
	    if(instance == null) {
		String rnd = ((args.length > 0) && (args[0].length() > 0)) ? args[0] : defrenderer();
		instance = new BridgeToolkit(rnd, rnd);
	    }
	    return(instance);
	}

	public boolean autouse() {return(false);}
    };
    public static Providers.Factory<BridgeToolkit> get() {
	return(factory);
    }
}
