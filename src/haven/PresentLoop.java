package haven;

import java.util.*;
import haven.render.*;

/*
 * nurgling: the decoupled renderer. The UI thread keeps ticking at its
 * own pace and draws the widget tree into an offscreen layer that it
 * publishes here. This thread draws the 3D pass of the presentable
 * PView (the map) beneath the latest layer and swaps, so the 3D
 * recording no longer runs in series with the UI thread's work.
 *
 * Sharing rules: the UI thread never writes a layer that a present
 * frame may still read (it waits on the layer's fence), and GPU objects
 * a presented PView replaces are retired rather than disposed (see
 * PView.retire), so this thread never records with a disposed object.
 */
public class PresentLoop {
    /* How far past the world tick's last sample movers are drawn: long
     * enough to cover a stalled tick or two, short enough that a gob
     * which stops or turns barely overshoots before the next sample. */
    public static final double AHEAD = 0.05;
    private static volatile int running = 0;
    public final UILoop loop;
    private final Thread th;
    private final Layer[] layers = {new Layer(), new Layer(), new Layer()};
    private final Set<PView> views = Collections.newSetFromMap(new WeakHashMap<>());
    private Published published = null;
    private long pubseq = 0;
    private Building building = null;
    private int fps;
    private volatile boolean failed = false;
    /* When the last layer was published, and the UI thread's usual
     * interval between layers. Guarded by this. */
    private double pubtime = 0, pubint = 1.0 / 60;
    /* How long recording a frame usually takes. Present thread only. */
    private double recint = 0.002;

    private static class Layer {
	Texture2D col, dep;
	Texture2D.Sampler2D samp;
	Pipe.Op fb;
	Coord sz;
	long pubseq = -1;
	/* The last present frame that sampled this layer. Guarded by
	 * the PresentLoop. */
	UILoop.Fence used = null;

	void ensure(Coord sz) {
	    if(Utils.eq(this.sz, sz))
		return;
	    dispose();
	    col = new Texture2D(sz, DataBuffer.Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null);
	    dep = new Texture2D(sz, DataBuffer.Usage.STATIC, Texture.DEPTH, new VectorFormat(1, NumberFormat.FLOAT32), null);
	    samp = col.sampler();
	    samp.minfilter(Texture.Filter.NEAREST).magfilter(Texture.Filter.NEAREST);
	    samp.swrap(Texture.Wrapping.CLAMP).twrap(Texture.Wrapping.CLAMP);
	    fb = Pipe.Op.compose(new FragColor<>(col.image(0)), new DepthBuffer<>(dep.image(0)));
	    this.sz = sz;
	}

	void dispose() {
	    if(col != null) {
		col.dispose();
		dep.dispose();
		col = dep = null;
		samp = null;
		sz = null;
	    }
	}
    }

    private static class Building {
	final Layer layer;
	PView view;
	Coord vul, vsz;

	Building(Layer layer) {
	    this.layer = layer;
	}
    }

    private static class Published {
	final long seq;
	final Environment env;
	final Layer layer;
	final PView view;
	final Coord vul, vsz;
	final Object vsync;
	final UI ui;

	Published(long seq, Environment env, Building b, Object vsync, UI ui) {
	    this.seq = seq;
	    this.env = env;
	    this.layer = b.layer;
	    this.view = b.view;
	    this.vul = b.vul;
	    this.vsz = b.vsz;
	    this.vsync = vsync;
	    this.ui = ui;
	}
    }

    public PresentLoop(UILoop loop) {
	this.loop = loop;
	this.th = new HackThread(this::run, "Haven present thread");
	/* It must get the CPU on time even while the world tick, loaders
	 * and GC fill every core, or frames come late however little
	 * work they take. */
	this.th.setPriority(Thread.MAX_PRIORITY);
    }

    public static boolean wanted() {
	return(Boolean.TRUE.equals(nurgling.NConfig.get(nurgling.NConfig.Key.decoupledRender)));
    }

    /* The present loop drawing ui's current frame, if the caller is
     * that frame's UI thread draw. */
    public static PresentLoop of(UI ui) {
	if((ui == null) || (ui.loop == null))
	    return(null);
	PresentLoop present = ui.loop.present();
	if((present == null) || (Thread.currentThread() != ui.loop.th) || (present.building == null))
	    return(null);
	return(present);
    }

    /* Whether any present thread is running, i.e. whether the world
     * tick should sample motion for it. */
    public static boolean active() {
	return(running > 0);
    }

    public void start() {
	synchronized(PresentLoop.class) {
	    running++;
	}
	nurgling.render.TimerResolution.raise();
	th.start();
    }

    /* Called on the UI thread. Afterwards nothing is drawn on the
     * present thread and the views go back to being drawn inline. */
    public void stop() {
	th.interrupt();
	boolean joined = false;
	try {
	    th.join(5000);
	    joined = !th.isAlive();
	} catch(InterruptedException e) {
	    Thread.currentThread().interrupt();
	}
	if(!joined) {
	    Warning.warn("present thread failed to terminate");
	    return;
	}
	synchronized(PresentLoop.class) {
	    running--;
	}
	for(PView view : views)
	    view.presenting(false);
	views.clear();
	for(Layer layer : layers)
	    layer.dispose();
    }

    /* UI thread: picks a layer no present frame can still read, and
     * returns the 2D state for drawing the UI into it. */
    public Pipe layerstate(Render out, Coord sz) throws InterruptedException {
	Layer pick = null;
	UILoop.Fence wait;
	synchronized(this) {
	    Layer cur = (published == null) ? null : published.layer;
	    for(Layer layer : layers) {
		if((layer != cur) && ((pick == null) || (layer.pubseq < pick.pubseq)))
		    pick = layer;
	    }
	    wait = pick.used;
	}
	/* A layer stops being acquired once it is no longer the
	 * published one, so this fence covers every frame reading it. */
	if(wait != null)
	    wait.waitfor();
	pick.ensure(sz);
	building = new Building(pick);
	Pipe base = new BufPipe();
	Area area = Area.sized(sz);
	base.prep(pick.fb).prep(Window.cblend);
	base.prep(new States.Viewport(area)).prep(new Ortho2D(area));
	base.prep(new FrameInfo());
	out.clear(base, FragColor.fragcol, new FColor(0, 0, 0, 0));
	out.clear(base, 1.0);
	return(base);
    }

    /* UI thread, from PView.draw: this view's 3D pass is drawn by the
     * present thread, in the area g covers. Only one view per frame;
     * false leaves any other to draw itself. */
    public boolean view(PView view, GOut g) {
	Building b = building;
	if(b.view != null)
	    return(false);
	b.view = view;
	b.vul = g.tx;
	b.vsz = g.sz();
	if(views.add(view))
	    view.presenting(true);
	return(true);
    }

    /* UI thread, once the layer's frame has been submitted. */
    public void publish(Environment env, Object vsync, UI ui) {
	Building b = building;
	building = null;
	if(b == null)
	    return;
	synchronized(this) {
	    double now = Utils.rtime();
	    if(pubtime > 0)
		pubint = Utils.clip((pubint * 0.9) + ((now - pubtime) * 0.1), 0.002, AHEAD);
	    pubtime = now;
	    b.layer.pubseq = ++pubseq;
	    published = new Published(pubseq, env, b, vsync, ui);
	    notifyAll();
	}
    }

    /* UI thread: the layer frame failed; nothing new to show. */
    public void abandon() {
	building = null;
    }

    public int fps() {
	return(fps);
    }

    private static final Pipe.Op premult = FragColor.blend(new BlendMode(BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA,
									   BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA));

    private void present(Published p, Render out) {
	Coord sz = p.layer.sz;
	Area area = Area.sized(sz);
	Pipe base = new BufPipe();
	base.prep(loop.wnd.fbstate());
	base.prep(FragColor.blend(new BlendMode()));
	base.prep(new States.Viewport(area)).prep(new Ortho2D(area));
	base.prep(new FrameInfo());
	out.clear(base, FragColor.fragcol, FColor.BLACK);
	GOut g = new GOut(out, base, sz);
	if(p.view != null) {
	    synchronized(p.view.presentlock) {
		if(!p.view.disposed()) {
		    p.view.drainretired();
		    double start = Utils.rtime();
		    if(p.view instanceof MapView)
			((MapView)p.view).presentmotion(start);
		    double moved = Utils.rtime();
		    nurgling.diagnostics.MovementTrace.present("motion", start, moved);
		    try {
			p.view.render3d(g.reclip(p.vul, p.vsz));
			nurgling.diagnostics.MovementTrace.present("scene", moved, Utils.rtime());
		    } catch(Loading l) {
			/* As MapView.draw does: show nothing of the
			 * scene until what it waits for has loaded. */
			l.boostprio(6);
		    }
		}
	    }
	}
	GOut lg = new GOut(out, base.copy().prep(premult), sz);
	lg.image(new TexRaw(p.layer.samp, true), Coord.z);
	FastText.aprintf(g, new Coord(10, sz.y - UI.scale(10)), 0, 1, "FPS: %d", fps);
    }

    private final double[] frames = new double[128];
    private long frameno = 0;
    private void updfps(double now) {
	int fi = (int)(frameno++ % frames.length);
	frames[fi] = now;
	int i = 0, ckf = fi;
	for(; i < frames.length - 1; i++) {
	    if(now - frames[ckf] > 1)
		break;
	    ckf = (ckf - 1 + frames.length) % frames.length;
	}
	if(now > frames[ckf]) {
	    fps = (int)Math.round(i / (now - frames[ckf]));
	    nurgling.NUtils.setFps(fps);
	}
    }

    private void run() {
	try {
	    UILoop.Fence prev = null;
	    long lastseq = -1;
	    double last = 0, due = 0;
	    UI lastui = null;
	    while(true) {
		Published p;
		synchronized(this) {
		    while(true) {
			if((published != null) && (published.seq != lastseq))
			    break;
			/* Without a new layer, draw only to fill in for a UI
			 * thread that is late, keeping the usual cadence with
			 * animation time, movers and the camera moved on.
			 * Drawing more than that would just compete with it,
			 * most of all on OpenGL where both share one GL
			 * thread. */
			if(published == null) {
			    wait();
			    continue;
			}
			/* With a frame rate limit, frames come on its beat
			 * (the pacing below waited for it), from a fresh
			 * layer or filled in. */
			if(loop.framedur() > 0)
			    break;
			double now = Utils.rtime();
			/* Start the fill-in so it is shown on the UI's usual
			 * beat, allowing for the time drawing it takes. */
			double fill = last + Math.max(pubint * 0.5, pubint - recint);
			if(now >= fill)
			    break;
			long ms = Math.max(1, (long)Math.ceil((fill - now) * 1000));
			wait(ms);
		    }
		    p = published;
		}
		Render out = p.env.render();
		UILoop.Fence done = new UILoop.Fence();
		try {
		    synchronized(this) {
			if(published.env == p.env)
			    p = published;
			p.layer.used = done;
		    }
		    lastseq = p.seq;
		    double start = Utils.rtime();
		    present(p, out);
		    loop.wnd.swapbuffers(out, p.vsync);
		    out.fence(done);
		    p.env.submit(out);
		    out = null;
		    double recorded = Utils.rtime();
		    recint = Utils.clip((recint * 0.9) + ((recorded - start) * 0.1), 0.0005, AHEAD);
		    nurgling.diagnostics.MovementTrace.present("record", start, recorded);
		} finally {
		    if(out != null) {
			out.dispose();
			done.abort();
		    }
		}
		if(prev != null) {
		    double start = Utils.rtime();
		    prev.waitfor();
		    nurgling.diagnostics.MovementTrace.present("gpu-wait", start, Utils.rtime());
		}
		prev = done;
		double now = Utils.rtime(), fd = loop.framedur();
		if(fd > 0) {
		    /* Frames on a fixed schedule, so one late frame does not
		     * shift every later one; after falling a whole frame
		     * behind, start the schedule over. */
		    double until = (due + fd > now) ? (due + fd) : now;
		    pace(until);
		    now = Utils.rtime();
		    due = until;
		} else {
		    due = now;
		}
		if(last > 0)
		    nurgling.diagnostics.MovementTrace.present("frame", last, now);
		if(p.ui != null) {
		    if(p.ui != lastui)
			p.ui.frameHistory.reset(now);
		    else
			p.ui.frameHistory.record(now);
		    lastui = p.ui;
		}
		last = now;
		updfps(now);
	    }
	} catch(InterruptedException e) {
	} catch(RuntimeException | AssertionError e) {
	    /* The UI thread notices and goes back to drawing inline,
	     * rather than leaving a frozen screen. */
	    new Warning(e, "present thread failed; drawing inline").issue();
	    failed = true;
	}
    }

    /* Waits until time until. Sleeping alone overshoots: on Windows a
     * sleep can last a whole 15.6 ms timer tick. So it sleeps to near
     * the deadline and spins the rest. */
    private static final double SPIN = 0.002;
    private static void pace(double until) throws InterruptedException {
	while(true) {
	    double left = until - Utils.rtime();
	    if(left <= 0)
		return;
	    if(left > SPIN)
		Thread.sleep((long)((left - SPIN) * 1000));
	    else
		Thread.onSpinWait();
	}
    }

    /* Whether this thread died of an error (see run). */
    public boolean failed() {
	return(failed);
    }
}
