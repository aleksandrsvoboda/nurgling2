package haven.iosys.tk;

import java.awt.Component;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.invoke.VarHandle;
import java.util.*;
import haven.*;
import haven.render.*;

/*
 * nurgling: the frame bridge, presenter side (see BridgeToolkit): a
 * small separate JVM that owns the client's window. Each refresh it
 * shows the newest scene the client published, slid to where the
 * camera was a moment ago (between frames already here), with the
 * interface layer on top, and forwards the window's raw input back. It draws the
 * same two textures over and over and allocates next to nothing, so
 * its own collector seldom runs, and briefly when it does.
 */
public class BridgePresenter {
    /* Never carry the scene further than this past its frame. */
    public static final double AHEAD = 0.1;
    private static final VertexArray.Layout vf = new VertexArray.Layout(new VertexArray.Layout.Input(Ortho2D.pos, new VectorFormat(2, NumberFormat.FLOAT32), 0, 0, 16),
									new VertexArray.Layout.Input(ColorTex.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 8, 16));
    private static final Pipe.Op premult = FragColor.blend(new BlendMode(BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA,
									   BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA));
    private final BridgeShm shm;
    private final AWTToolkit tk;
    private final AWTToolkit.AWTWindow wnd;
    private final Optional<ProcessHandle> game;

    private Texture2D uitex, sctex;
    private Texture2D.Sampler2D uismp, scsmp;
    private Coord uisz, scsz;
    private boolean hasscene = false;
    private int scx, scy;
    private long ftime;
    private float snapx, snapy;
    private long lastseq = -1;
    private long nshown = 0;
    private final long gamepid;
    /* Zero-copy: each slot's imported textures, by generation. */
    private final SharedTexture2D[] zui = new SharedTexture2D[BridgeShm.NSLOT], zsc = new SharedTexture2D[BridgeShm.NSLOT];
    private final Texture2D.Sampler2D[] zuismp = new Texture2D.Sampler2D[BridgeShm.NSLOT], zscsmp = new Texture2D.Sampler2D[BridgeShm.NSLOT];
    private final int[] zuigen = new int[BridgeShm.NSLOT], zscgen = new int[BridgeShm.NSLOT];
    private int cur = -1;
    private boolean zc = false;
    /* What is drawn: the current frame's interface and scene. */
    private Texture2D.Sampler2D dui, dsc;
    private int titleseq = -1, sizeseq = -1, showseq = -1, cursseq = -1;
    private final Map<Integer, Cursor> cursors = new HashMap<>();

    BridgePresenter(BridgeShm shm) {
	this.shm = shm;
	this.gamepid = shm.getl(BridgeShm.H_GAMEPID);
	this.game = ProcessHandle.of(gamepid);
	Arrays.fill(zuigen, -1);
	Arrays.fill(zscgen, -1);
	Toolkit tk = Toolkit.instance();
	if(!(tk instanceof AWTToolkit))
	    throw(new RuntimeException("the frame bridge presenter needs an AWT-based toolkit, not " + tk.description()));
	this.tk = (AWTToolkit)tk;
	this.wnd = (AWTToolkit.AWTWindow)tk.window();
	try(java.io.InputStream icon = BridgePresenter.class.getResourceAsStream("/haven/icon.png")) {
	    if(icon != null)
		wnd.icon(javax.imageio.ImageIO.read(icon));
	} catch(java.io.IOException e) {
	}
	wnd.title("Haven & Hearth (Nurgling II)");
	/* A size to start with; the client sends its saved one once it
	 * sees the window. */
	wnd.sizing(new Windeye.Sizing().normsize(Coord.of(1024, 768)));
	listen();
	shm.puti(BridgeShm.H_VISIBLE, 1);
	wnd.show(true);
    }

    /* Input: the window's raw AWT events, in order, into the ring. */
    private long head = 0;
    private synchronized void put(int kind, AWTEvent ev) {
	long tail = shm.getl(BridgeShm.RING + BridgeShm.R_TAIL);
	/* Full: the client is not reading. Dropping beats blocking AWT. */
	if(head - tail >= BridgeShm.NREC)
	    return;
	int off = BridgeShm.rec(head);
	java.nio.ByteBuffer h = shm.hdr;
	h.putInt(off + BridgeShm.I_KIND, kind);
	if(ev != null) {
	    h.putInt(off + BridgeShm.I_ID, ev.id);
	    h.putLong(off + BridgeShm.I_WHEN, ev.when);
	    h.putInt(off + BridgeShm.I_MODS, ev.mods);
	    h.putInt(off + BridgeShm.I_CODE, ev.code);
	    h.putInt(off + BridgeShm.I_CHAR, ev.chr);
	    h.putInt(off + BridgeShm.I_LOC, ev.loc);
	    h.putInt(off + BridgeShm.I_EXT, ev.ext);
	    h.putInt(off + BridgeShm.I_X, ev.x);
	    h.putInt(off + BridgeShm.I_Y, ev.y);
	    h.putInt(off + BridgeShm.I_BTN, ev.btn);
	    h.putInt(off + BridgeShm.I_CLICKS, ev.clicks);
	    h.putInt(off + BridgeShm.I_ROT, ev.rot);
	    h.putDouble(off + BridgeShm.I_PRECISE, ev.precise);
	}
	VarHandle.fullFence();
	shm.putl(BridgeShm.RING + BridgeShm.R_HEAD, ++head);
    }

    /* The fields of one AWT event, as recorded. */
    private static class AWTEvent {
	int id, mods, code, chr, loc, ext, x, y, btn, clicks, rot;
	long when;
	double precise;

	AWTEvent(java.awt.event.InputEvent e) {
	    id = e.getID();
	    when = e.getWhen();
	    mods = e.getModifiersEx();
	}

	static AWTEvent of(java.awt.event.KeyEvent e) {
	    AWTEvent r = new AWTEvent(e);
	    r.code = e.getKeyCode();
	    r.chr = e.getKeyChar();
	    r.loc = e.getKeyLocation();
	    r.ext = e.getExtendedKeyCode();
	    return(r);
	}

	static AWTEvent of(java.awt.event.MouseEvent e) {
	    AWTEvent r = new AWTEvent(e);
	    r.x = e.getX();
	    r.y = e.getY();
	    r.btn = e.getButton();
	    r.clicks = e.getClickCount();
	    if(e instanceof java.awt.event.MouseWheelEvent) {
		java.awt.event.MouseWheelEvent w = (java.awt.event.MouseWheelEvent)e;
		r.rot = w.getWheelRotation();
		r.precise = w.getPreciseWheelRotation();
	    }
	    return(r);
	}
    }

    private void listen() {
	Component panel = wnd.panel();
	KeyListener keys = new KeyListener() {
		public void keyPressed(KeyEvent e) {put(BridgeShm.K_KEY, AWTEvent.of(e));}
		public void keyReleased(KeyEvent e) {put(BridgeShm.K_KEY, AWTEvent.of(e));}
		public void keyTyped(KeyEvent e) {put(BridgeShm.K_KEY, AWTEvent.of(e));}
	    };
	MouseAdapter mouse = new MouseAdapter() {
		public void mousePressed(MouseEvent e) {put(BridgeShm.K_MOUSE, AWTEvent.of(e));}
		public void mouseReleased(MouseEvent e) {put(BridgeShm.K_MOUSE, AWTEvent.of(e));}
		public void mouseMoved(MouseEvent e) {put(BridgeShm.K_MOUSE, AWTEvent.of(e));}
		public void mouseDragged(MouseEvent e) {put(BridgeShm.K_MOUSE, AWTEvent.of(e));}
		public void mouseWheelMoved(MouseWheelEvent e) {put(BridgeShm.K_WHEEL, AWTEvent.of(e));}
	    };
	panel.addKeyListener(keys);
	panel.addMouseListener(mouse);
	panel.addMouseMotionListener(mouse);
	panel.addMouseWheelListener(mouse);
	wnd.frame.addWindowListener(new WindowAdapter() {
		public void windowClosing(WindowEvent e) {put(BridgeShm.K_CLOSE, null);}
		public void windowActivated(WindowEvent e) {shm.puti(BridgeShm.H_FOCUSED, 1);}
		public void windowDeactivated(WindowEvent e) {shm.puti(BridgeShm.H_FOCUSED, 0);}
		public void windowIconified(WindowEvent e) {shm.puti(BridgeShm.H_VISIBLE, 0);}
		public void windowDeiconified(WindowEvent e) {shm.puti(BridgeShm.H_VISIBLE, 1);}
	    });
    }

    /* Window control the client asked for since last time. */
    private void control() {
	int seq = shm.geti(BridgeShm.C_TITLESEQ);
	if(seq != titleseq) {
	    titleseq = seq;
	    VarHandle.fullFence();
	    int n = Math.min(shm.geti(BridgeShm.C_TITLELEN), BridgeShm.TITLECAP);
	    byte[] b = new byte[n];
	    for(int i = 0; i < n; i++)
		b[i] = shm.hdr.get(BridgeShm.C_TITLE + i);
	    if(n > 0)
		wnd.title(new String(b, Utils.utf8));
	}
	seq = shm.geti(BridgeShm.C_SIZESEQ);
	if(seq != sizeseq) {
	    sizeseq = seq;
	    VarHandle.fullFence();
	    Windeye.Sizing sz = new Windeye.Sizing();
	    int fw = shm.geti(BridgeShm.C_FIXW), fh = shm.geti(BridgeShm.C_FIXH);
	    int nw = shm.geti(BridgeShm.C_NORMW), nh = shm.geti(BridgeShm.C_NORMH);
	    int mw = shm.geti(BridgeShm.C_MINW), mh = shm.geti(BridgeShm.C_MINH);
	    if((fw > 0) && (fh > 0))
		sz.fixsize(Coord.of(fw, fh));
	    if((nw > 0) && (nh > 0))
		sz.normsize(Coord.of(nw, nh));
	    if((mw > 0) && (mh > 0))
		sz.minsize(Coord.of(mw, mh));
	    if((fw > 0) || (nw > 0) || (mw > 0))
		wnd.sizing(sz);
	    int st = shm.geti(BridgeShm.C_STATE);
	    if((st >= 0) && (st < Windeye.State.values().length) && (Windeye.State.values()[st] != Windeye.State.NORMAL))
		wnd.state(Windeye.State.values()[st]);
	}
	seq = shm.geti(BridgeShm.C_SHOWSEQ);
	if(seq != showseq) {
	    showseq = seq;
	    VarHandle.fullFence();
	    wnd.show(shm.geti(BridgeShm.C_SHOW) != 0);
	}
	seq = shm.geti(BridgeShm.C_CURSSEQ);
	if(seq != cursseq) {
	    cursseq = seq;
	    VarHandle.fullFence();
	    int kind = shm.geti(BridgeShm.C_CURSKIND), val = shm.geti(BridgeShm.C_CURSVAL);
	    if(kind == 1)
		wnd.cursor(Cursor.Std.values()[val]);
	    else if(kind == 2)
		wnd.cursor(cursor(val));
	    else
		wnd.cursor(null);
	}
    }

    private Cursor cursor(int id) {
	Cursor ret = cursors.get(id);
	if(ret == null) {
	    int off = BridgeShm.curs(id);
	    int w = shm.geti(off + BridgeShm.CU_W), h = shm.geti(off + BridgeShm.CU_H);
	    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
	    for(int y = 0, o = off + BridgeShm.CU_DATA; y < h; y++) {
		for(int x = 0; x < w; x++, o += 4)
		    img.setRGB(x, y, shm.geti(o));
	    }
	    ret = tk.makecursor(img, Coord.of(shm.geti(off + BridgeShm.CU_HX), shm.geti(off + BridgeShm.CU_HY)));
	    cursors.put(id, ret);
	}
	return(ret);
    }

    private static Texture2D mktex(Coord sz) {
	return(new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null));
    }

    private static Texture2D.Sampler2D mksmp(Texture2D tex) {
	Texture2D.Sampler2D ret = new Texture2D.Sampler2D(tex);
	ret.minfilter(Texture.Filter.NEAREST).magfilter(Texture.Filter.NEAREST);
	return(ret);
    }

    /* Takes the newest published frame, if there is one not taken yet.
     * Zero-copy frames are sampled where the client rendered them: the
     * slot stays marked as read while it is shown, and the one before
     * until the first frame showing the new one has finished. Copied
     * frames are uploaded, and their slot is free once that has run. */
    private void take(Render out) {
	int slot = shm.geti(BridgeShm.H_LATEST);
	if(slot < 0)
	    return;
	int off = BridgeShm.slot(slot);
	VarHandle.fullFence();
	long seq = shm.getl(off + BridgeShm.S_SEQ);
	if(seq == lastseq)
	    return;
	int prevcur = cur;
	if((prevcur >= 0) && (prevcur != slot))
	    shm.puti(BridgeShm.H_READPREV, prevcur);
	shm.puti(BridgeShm.H_READING, slot);
	VarHandle.fullFence();
	/* Re-read under the reading mark; the client skips this slot
	 * from now on. */
	if(shm.getl(off + BridgeShm.S_SEQ) != seq)
	    return;
	boolean zc = shm.geti(off + BridgeShm.S_ZC) != 0;
	Coord us = Coord.of(shm.geti(off + BridgeShm.S_UIW), shm.geti(off + BridgeShm.S_UIH));
	boolean sc = shm.geti(off + BridgeShm.S_HASSCENE) != 0;
	Coord ss = sc ? Coord.of(shm.geti(off + BridgeShm.S_SCW), shm.geti(off + BridgeShm.S_SCH)) : null;
	if(zc) {
	    int ug = shm.geti(off + BridgeShm.S_UIGEN);
	    if((zui[slot] == null) || (zuigen[slot] != ug)) {
		if(zui[slot] != null)
		    zui[slot].dispose();
		zui[slot] = new SharedTexture2D(us, BridgeShm.texname(gamepid, slot, 'u', ug), false);
		zuismp[slot] = mksmp(zui[slot]);
		zuigen[slot] = ug;
	    }
	    dui = zuismp[slot];
	    if(sc) {
		int cg = shm.geti(off + BridgeShm.S_SCGEN);
		if((zsc[slot] == null) || (zscgen[slot] != cg)) {
		    if(zsc[slot] != null)
			zsc[slot].dispose();
		    zsc[slot] = new SharedTexture2D(ss, BridgeShm.texname(gamepid, slot, 'c', cg), false);
		    zscsmp[slot] = mksmp(zsc[slot]);
		    zscgen[slot] = cg;
		}
		dsc = zscsmp[slot];
	    }
	    cur = slot;
	    out.fence(() -> {
		    if(shm.geti(BridgeShm.H_READPREV) == prevcur)
			shm.puti(BridgeShm.H_READPREV, -1);
		});
	} else {
	    if(!Utils.eq(us, uisz)) {
		if(uitex != null)
		    uitex.dispose();
		uitex = mktex(us);
		uismp = mksmp(uitex);
	    }
	    out.update(uitex.image(0), DataBuffer.Filler.of(shm.uibuf(slot, us.x * us.y * 4)));
	    dui = uismp;
	    if(sc) {
		if(!Utils.eq(ss, scsz)) {
		    if(sctex != null)
			sctex.dispose();
		    sctex = mktex(ss);
		    scsmp = mksmp(sctex);
		}
		out.update(sctex.image(0), DataBuffer.Filler.of(shm.scenebuf(slot, ss.x * ss.y * 4)));
		dsc = scsmp;
	    }
	    cur = -1;
	    out.fence(() -> {
		    if(shm.geti(BridgeShm.H_READING) == slot)
			shm.puti(BridgeShm.H_READING, -1);
		    if(shm.geti(BridgeShm.H_READPREV) == prevcur)
			shm.puti(BridgeShm.H_READPREV, -1);
		});
	}
	uisz = us;
	if(sc) {
	    scsz = ss;
	    scx = shm.geti(off + BridgeShm.S_SCX);
	    scy = shm.geti(off + BridgeShm.S_SCY);
	}
	hasscene = sc;
	this.zc = zc;
	ftime = shm.getl(off + BridgeShm.S_TIME);
	snapx = shm.getf(off + BridgeShm.S_SNAPX);
	snapy = shm.getf(off + BridgeShm.S_SNAPY);
	if(sc) {
	    int ogen = shm.geti(off + BridgeShm.S_OGEN);
	    /* Where the scene is about to be shown, before and after
	     * learning this frame: they differ when the scene was being
	     * carried on past the newest frame, and guessed wrong. */
	    boolean cont = (hn > 0) && (hgen[(int)((hn - 1) % NHIST)] == ogen);
	    double prex = 0, prey = 0;
	    long st = vtime - delay;
	    if(cont) {
		slidepos(st);
		prex = slx;
		prey = sly;
	    }
	    int h = (int)(hn++ % NHIST);
	    hft[h] = ftime;
	    hox[h] = shm.hdr.getDouble(off + BridgeShm.S_OX);
	    hoy[h] = shm.hdr.getDouble(off + BridgeShm.S_OY);
	    hgen[h] = ogen;
	    if(cont) {
		slidepos(st);
		corx += prex - slx;
		cory += prey - sly;
	    } else {
		corx = cory = 0;
	    }
	}
	lastseq = seq;
    }

    private void quad(Render out, Pipe base, Texture2D.Sampler2D smp, int x, int y, Coord sz, Pipe.Op blend) {
	Pipe p = base.copy();
	p.prep(new ColorTex(smp));
	if(blend != null)
	    p.prep(blend);
	float x1 = x + sz.x, y1 = y + sz.y;
	/* Read-back images are stored bottom row first. */
	float[] data = {
	    x,  y,  0, 1,
	    x1, y,  1, 1,
	    x,  y1, 0, 0,
	    x1, y1, 1, 0,
	};
	Model mod = new Model(Model.Mode.TRIANGLE_STRIP, new VertexArray(vf, new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.EPHEMERAL, DataBuffer.Filler.of(data))), null, 0, 4);
	out.draw(p, mod);
	mod.dispose();
    }

    /* The camera's slide over the last frames taken. */
    private static final int NHIST = 32;
    private final long[] hft = new long[NHIST];
    private final double[] hox = new double[NHIST], hoy = new double[NHIST];
    private final int[] hgen = new int[NHIST];
    private long hn = 0;
    private double slx, sly;
    /* The part of a wrong guess not yet worked off: eased out over a
     * few frames instead of jumping the scene into place. */
    private static final double CORTC = 0.05;
    private double corx, cory;
    private long corlast = 0;

    /* Where the camera had slid to at time t, in the newest frame's
     * run of slides: between the frames around t, carried on along
     * the last two when t is past the newest, and held at the oldest
     * when t is before it. */
    private void slidepos(long t) {
	int l = (int)((hn - 1) % NHIST);
	if(t >= hft[l]) {
	    double vx = 0, vy = 0;
	    if(hn > 1) {
		int p = (int)((hn - 2) % NHIST);
		long sdt = hft[l] - hft[p];
		if((hgen[p] == hgen[l]) && (sdt > 0) && (sdt < 100_000_000L)) {
		    vx = (hox[l] - hox[p]) / (sdt / 1e9);
		    vy = (hoy[l] - hoy[p]) / (sdt / 1e9);
		}
	    }
	    double dt = Math.min((t - hft[l]) / 1e9, AHEAD);
	    slx = hox[l] + (vx * dt);
	    sly = hoy[l] + (vy * dt);
	    return;
	}
	int b = l, nb = -1;
	for(long i = hn - 2; (i >= 0) && (i > hn - NHIST); i--) {
	    int a = (int)(i % NHIST);
	    if(hgen[a] != hgen[l])
		break;
	    if(hft[a] <= t) {
		double u = (hft[b] == hft[a]) ? 1 : ((double)(t - hft[a]) / (hft[b] - hft[a]));
		slx = hox[a] + ((hox[b] - hox[a]) * u);
		sly = hoy[a] + ((hoy[b] - hoy[a]) * u);
		return;
	    }
	    nb = b;
	    b = a;
	}
	/* Before the run's first frame: carry its first slide back, so
	 * that the scene keeps moving instead of waiting for the run to
	 * be old enough. */
	if((nb >= 0) && (hft[nb] > hft[b])) {
	    double s = (hft[nb] - hft[b]) / 1e9, dt = Math.max((t - hft[b]) / 1e9, -AHEAD);
	    slx = hox[b] + (((hox[nb] - hox[b]) / s) * dt);
	    sly = hoy[b] + (((hoy[nb] - hoy[b]) / s) * dt);
	    return;
	}
	slx = hox[b];
	sly = hoy[b];
    }

    /* How far behind to show the scene: about as old as the newest
     * frame usually is when shown, so that a newer one is nearly
     * always here already. */
    private static final int NAGE = 288;
    private final long[] ages = new long[NAGE], agesort = new long[NAGE];
    private long nage = 0;
    private long delay = 0, tdelay = 0;
    private void pace(long now) {
	/* Changed gradually: a step in the delay would move the scene
	 * by as much as the camera goes in that time. */
	delay += Math.max(-50_000L, Math.min(tdelay - delay, 50_000L));
	ages[(int)(nage++ % NAGE)] = now - hft[(int)((hn - 1) % NHIST)];
	if((nage >= NAGE) && ((nage % 72) == 0)) {
	    System.arraycopy(ages, 0, agesort, 0, NAGE);
	    Arrays.sort(agesort);
	    tdelay = Math.max(0, Math.min(agesort[(NAGE * 95) / 100], 40_000_000L));
	}
    }

    /* The times frames are shown at come every refresh, evenly, but
     * this loop wakes up a millisecond or so early or late; positions
     * are taken at an even clock locked to the refreshes instead, or
     * the scene would wobble by however far it moves in that time. */
    private long vtime = 0, vlast = 0, vn = 0;
    private double vper = 0;
    private final long[] vint = new long[64], vsort = new long[64];
    private long vclock(long now) {
	if(vlast != 0) {
	    vint[(int)(vn++ % vint.length)] = now - vlast;
	    if((vn >= vint.length) && ((vn % 16) == 0)) {
		System.arraycopy(vint, 0, vsort, 0, vint.length);
		Arrays.sort(vsort);
		/* The mean, not the median: wake-ups come in steps, so
		 * the median is one of the steps, not the period. */
		long sum = 0;
		int lo = vint.length / 8, hi = vint.length - lo;
		for(int i = lo; i < hi; i++)
		    sum += vsort[i];
		vper = (double)sum / (hi - lo);
	    }
	}
	vlast = now;
	if((vper <= 0) || (vtime == 0)) {
	    vtime = now;
	} else {
	    vtime += (long)vper;
	    long err = now - vtime;
	    if(Math.abs(err) > vper * 0.6)
		vtime = now;
	    else
		vtime += (long)(err * 0.05);
	}
	return(vtime);
    }

    /* How much the scene's step on screen changed from the last frame
     * to this one, in whole pixels along the worse axis: what the eye
     * sees as a hop. Rounding alone changes it by at most one. */
    private int hop = 0, hopgen = -1, hopn = 0;
    private double hopx, hopy, hopsx, hopsy;
    private void hop(int gen, double x, double y) {
	hop = 0;
	if(gen != hopgen) {
	    hopgen = gen;
	    hopn = 0;
	} else {
	    double sx = x - hopx, sy = y - hopy;
	    if((hopn > 0) && ((Math.abs(sx) + Math.abs(sy) >= 1) || (Math.abs(hopsx) + Math.abs(hopsy) >= 1)))
		hop = (int)Math.max(Math.abs(Math.round(sx - hopsx)), Math.abs(Math.round(sy - hopsy)));
	    hopsx = sx;
	    hopsy = sy;
	    hopn++;
	}
	hopx = x;
	hopy = y;
    }

    private void draw(Render out, Coord wsz) {
	Area area = Area.sized(wsz);
	Pipe base = new BufPipe();
	base.prep(wnd.fbstate());
	base.prep(new States.Viewport(area)).prep(new Ortho2D(area));
	/* Shared textures were written by the client's device. */
	if(zc)
	    out.extsync();
	out.clear(base, FragColor.fragcol, FColor.BLACK);
	hop = 0;
	if(hasscene && (dsc != null) && (hn > 0)) {
	    long now = vclock(System.nanoTime());
	    pace(now);
	    /* Show the newest scene where the camera was a moment ago,
	     * which lies between frames already here, rather than
	     * guessing where it is going. */
	    slidepos(now - delay);
	    if(corlast != 0) {
		double f = Math.exp(-((now - corlast) / 1e9) / CORTC);
		corx *= f;
		cory *= f;
	    }
	    corlast = now;
	    slx += corx;
	    sly += cory;
	    int l = (int)((hn - 1) % NHIST);
	    int lim = BridgeToolkit.MARGIN - 1;
	    /* From where the scene was before the client moved it onto
	     * whole pixels, so that it is rounded only once. */
	    int dx = (int)Math.round(Utils.clip((slx - hox[l]) - snapx, -lim, lim));
	    int dy = (int)Math.round(Utils.clip((sly - hoy[l]) - snapy, -lim, lim));
	    quad(out, base, dsc, scx + dx, scy + dy, scsz, null);
	    hop(hgen[l], hox[l] + snapx + dx, hoy[l] + snapy + dy);
	}
	if(dui != null)
	    quad(out, base, dui, 0, 0, uisz, premult);
    }

    private boolean gamealive() {
	return(game.isPresent() && game.get().isAlive() && (shm.geti(BridgeShm.H_CLOSED) == 0));
    }

    void run() throws InterruptedException {
	UILoop.Fence prev = null;
	long nextcheck = 0;
	while(true) {
	    long now = System.nanoTime();
	    if(now > nextcheck) {
		if(!gamealive())
		    break;
		nextcheck = now + 250_000_000L;
	    }
	    control();
	    Coord wsz = wnd.size();
	    shm.putl(BridgeShm.H_BEAT, now);
	    /* Nothing to draw into until the window is laid out. */
	    if((wsz.x <= 0) || (wsz.y <= 0)) {
		Thread.sleep(10);
		continue;
	    }
	    shm.puti(BridgeShm.H_WNDW, wsz.x);
	    shm.puti(BridgeShm.H_WNDH, wsz.y);
	    Environment env = wnd.env();
	    Render out = env.render();
	    UILoop.Fence done = new UILoop.Fence();
	    try {
		take(out);
		draw(out, wsz);
		wnd.swapbuffers(out, Boolean.TRUE);
		out.fence(done);
		env.submit(out);
		out = null;
	    } finally {
		if(out != null) {
		    out.dispose();
		    done.abort();
		}
	    }
	    if(prev != null)
		prev.waitfor();
	    prev = done;
	    long t2 = System.nanoTime();
	    shm.putl(BridgeShm.H_SHOWNT + (int)((nshown % BridgeShm.NSHOWN) * 8), t2);
	    shm.puti(BridgeShm.H_SHOWNH + (int)((nshown % BridgeShm.NSHOWN) * 4), hop);
	    VarHandle.fullFence();
	    shm.putl(BridgeShm.H_SHOWN, ++nshown);
	}
	shm.puti(BridgeShm.H_READING, -1);
	wnd.dispose();
	tk.dispose();
    }

    /* The presenter must never miss a vsync, and the client's busy
     * threads easily outnumber the cores; it sleeps most of each frame,
     * so running it above them costs them next to nothing. */
    private static void highpriority() {
	if(!System.getProperty("os.name", "").startsWith("Windows"))
	    return;
	final int HIGH_PRIORITY_CLASS = 0x80;
	org.lwjgl.system.SharedLibrary k32 = org.lwjgl.system.Library.loadNative(BridgePresenter.class, "org.lwjgl", "kernel32");
	long proc = org.lwjgl.system.JNI.invokeP(k32.getFunctionAddress("GetCurrentProcess"));
	if(org.lwjgl.system.JNI.invokePI(proc, HIGH_PRIORITY_CLASS, k32.getFunctionAddress("SetPriorityClass")) == 0)
	    System.err.println("frame bridge: could not raise the presenter's priority");
    }

    public static void main(String[] args) throws Exception {
	int status = 1;
	try {
	    Utils.initlocale();
	    highpriority();
	    MainFrame.initawt();
	    BridgeShm shm = BridgeShm.open(args[0]);
	    try {
		new BridgePresenter(shm).run();
		status = 0;
	    } finally {
		shm.close();
	    }
	} finally {
	    /* Also on errors: the AWT thread would keep a broken presenter
	     * alive, and the client would wait on it forever. */
	    System.out.flush();
	    System.exit(status);
	}
    }
}
