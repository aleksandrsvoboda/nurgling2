package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.PingService;
import nurgling.tools.FlatWorld;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Draws map pings in the world - the glow, rings and beacon for a spot someone pinged
 * over chat.
 *
 * <p>The effect is split across the two render passes on purpose, because the two halves
 * want opposite things:
 *
 * <ul>
 *   <li><b>The ground glow</b> is real geometry in the render tree. It is a small grid of
 *       quads sampled at terrain height, so it lies on the ground and creeps up a slope
 *       instead of slicing through it, textured with a soft radial falloff and tinted per
 *       sender. This is what stops the ping reading as HUD lines drawn over the world.</li>
 *   <li><b>The rings, beacon and edge arrow</b> are drawn in the 2D pass. They change every
 *       frame - radius, width and alpha all animate - and rebuilding vertex buffers for
 *       that would cost far more than projecting a few dozen points and stroking them.</li>
 * </ul>
 *
 * <p>Every phase is keyed off {@link PingService.Ping#since()} rather than the wall clock,
 * so a ping always begins with its rings at the centre and gets a single arrival burst.
 * Driving them off a free-running clock, as the first version did, meant a ping born at an
 * arbitrary phase had no moment of arrival at all.
 */
public class NPointPingOverlay implements RenderTree.Node, PView.Render2D {
    /* --- world-space geometry constants (game units; one tile is 11) --- */
    private static final double R_START = 2.5;      // radius a ring is born at
    private static final double R_GROW = 26.0;      // how far it expands before dying
    private static final double Z_RING = 0.45;
    private static final double BEAM_H = 10.0;      // world height of the beacon
    private static final int RING_SEG = 32;
    /** Rings in flight at once, evenly staggered through the cycle. */
    private static final int RINGS = 3;
    /** Seconds for one ring to go from R_START to R_START + R_GROW. */
    private static final double PERIOD = 1.5;
    /** Duration of the one-shot arrival burst, seconds. */
    private static final double BURST = 0.55;

    /* --- ground glow --- */
    private static final double GLOW_R = 13.0;      // half-width of the decal, world units
    private static final int GLOW_CELLS = 6;        // grid resolution; more = better on slopes
    private static final double GLOW_Z = 0.35;      // lift off the ground to avoid z-fighting
    private static final double GLOW_ALPHA = 0.55;
    /** Alpha quantisation steps; see {@link #update}. */
    private static final double ALPHA_STEPS = 24.0;

    private static final VertexArray.Layout GLOW_LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 36),
            new VertexArray.Layout.Input(Tex2D.texc, new VectorFormat(2, NumberFormat.FLOAT32), 0, 12, 36),
            new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.FLOAT32), 0, 20, 36));

    /** Soft radial falloff, generated rather than shipped. White, so the vertex colour tints it. */
    private static final ColorTex GLOW_TEX = new TexI(glowImage(128)).st();

    private static final Pipe.Op GLOW_MAT = Pipe.Op.compose(
            new States.Facecull(States.Facecull.Mode.NONE),
            Clickable.No,
            VertexColor.instance,
            new BaseColor(Color.WHITE),
            GLOW_TEX,
            new Rendered.Order.Default(-100),
            new States.Depthtest(States.Depthtest.Test.LE),
            States.maskdepth,
            FragColor.blend(new BlendMode(
                    BlendMode.Function.ADD, BlendMode.Factor.SRC_ALPHA, BlendMode.Factor.INV_SRC_ALPHA,
                    BlendMode.Function.ADD, BlendMode.Factor.ONE, BlendMode.Factor.INV_SRC_ALPHA)),
            Rendered.postpfx);

    private final NMapView mv;
    private final Part glow = new Part();
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    /** Flat-world state of the frame being processed. */
    private boolean flat = false;
    private long lastSig = Long.MIN_VALUE;

    public NPointPingOverlay(NMapView mv) {
        this.mv = mv;
    }

    /* ------------------------------------------------------------------ *
     *  Render tree plumbing
     * ------------------------------------------------------------------ */

    /** A node whose model can be swapped out from under its slots between frames. */
    private static class Part implements RenderTree.Node, Rendered {
        private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
        private volatile Model model = null;

        void set(Model m) {
            if(this.model == m)
                return;
            this.model = m;
            Collection<RenderTree.Slot> cur;
            synchronized(slots) {
                cur = new ArrayList<>(slots);
            }
            for(RenderTree.Slot s : cur) {
                try {
                    s.update();
                } catch(RenderTree.SlotRemoved ignored) {
                }
            }
        }

        public void added(RenderTree.Slot slot) {
            synchronized(slots) {
                slots.add(slot);
            }
        }

        public void removed(RenderTree.Slot slot) {
            synchronized(slots) {
                slots.remove(slot);
            }
        }

        public void draw(Pipe context, Render out) {
            Model m = this.model;
            if(m != null)
                out.draw(context, m);
        }
    }

    public void added(RenderTree.Slot slot) {
        slot.add(glow, GLOW_MAT);
        synchronized(slots) {
            slots.add(slot);
        }
    }

    public void removed(RenderTree.Slot slot) {
        synchronized(slots) {
            slots.remove(slot);
        }
    }

    /* ------------------------------------------------------------------ *
     *  Ground glow geometry
     * ------------------------------------------------------------------ */

    /**
     * Rebuild the ground glow for the live pings. Called once per frame, but the geometry
     * is only regenerated when it would actually differ.
     *
     * <p>That caching matters more here than it looks. Uploading a fresh vertex buffer
     * every frame for the whole life of every ping would churn GPU memory for no visible
     * gain, so the glow is deliberately a <em>still</em> element: fixed radius, and an
     * alpha quantised into steps. A ping therefore builds its mesh once and rebuilds a
     * couple of dozen times during the final fade, instead of twelve hundred times over
     * its life. The motion in the effect comes from the rings and the beacon in the 2D
     * pass, which cost nothing to animate.
     */
    public void update() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.pingService == null) {
            clearGlow();
            return;
        }
        List<PingService.Ping> pings = gui.pingService.snapshot();
        if(pings.isEmpty()) {
            clearGlow();
            return;
        }
        flat = FlatWorld.isEnabled();
        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        long sig = signature(pings, baseZ);
        if(sig == lastSig)
            return;
        lastSig = sig;

        Buf buf = new Buf();
        for(PingService.Ping p : pings) {
            Coord2d wc = p.wc();
            if(wc == null)
                continue;
            double a = quant(p.alpha());
            if(a <= 0)
                continue;
            glowPatch(buf, wc, GLOW_R, p.col, a * GLOW_ALPHA, baseZ);
        }

        if(buf.n == 0) {
            glow.set(null);
            return;
        }
        float[] data = buf.fit();
        VertexArray va = new VertexArray(GLOW_LAYOUT,
                new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data)));
        glow.set(new Model(Model.Mode.TRIANGLES, va, null));
    }

    private void clearGlow() {
        if(lastSig != Long.MIN_VALUE) {
            glow.set(null);
            lastSig = Long.MIN_VALUE;
        }
    }

    /** Alpha snapped to fixed steps, so a slow fade does not mean a rebuild every frame. */
    private static double quant(double a) {
        return(Math.floor(a * ALPHA_STEPS) / ALPHA_STEPS);
    }

    /**
     * Everything the glow geometry depends on. The ground height at each ping is folded in
     * because terrain pages in after the ping lands: without it the mesh would keep the
     * fallback height it was first built at and float or sink once the real ground arrives.
     */
    private long signature(List<PingService.Ping> pings, double baseZ) {
        long h = 1125899906842597L;
        for(PingService.Ping p : pings) {
            Coord2d wc = p.wc();
            if(wc == null)
                continue;
            h = (h * 31) + (long)wc.x;
            h = (h * 31) + (long)wc.y;
            h = (h * 31) + (long)(quant(p.alpha()) * ALPHA_STEPS);
            h = (h * 31) + p.col.getRGB();
            h = (h * 31) + (long)(cz(wc.x, wc.y, baseZ) * 4);
        }
        h = (h * 31) + (flat ? 1 : 0);
        return(h);
    }

    /** One ping's glow: a terrain-sampled grid of quads carrying the radial texture. */
    private void glowPatch(Buf buf, Coord2d c, double r, Color col, double alpha, double baseZ) {
        int n = GLOW_CELLS + 1;
        double[][] px = new double[n][n], py = new double[n][n], pz = new double[n][n];
        float[][] tu = new float[n][n], tv = new float[n][n];
        double czc = cz(c.x, c.y, baseZ);
        for(int i = 0; i < n; i++) {
            for(int j = 0; j < n; j++) {
                double u = (double)i / GLOW_CELLS, v = (double)j / GLOW_CELLS;
                double x = c.x + ((u * 2) - 1) * r, y = c.y + ((v * 2) - 1) * r;
                px[i][j] = x;
                py[i][j] = y;
                pz[i][j] = cz(x, y, czc) + GLOW_Z;
                tu[i][j] = (float)u;
                tv[i][j] = (float)v;
            }
        }
        float[] fc = {col.getRed() / 255f, col.getGreen() / 255f, col.getBlue() / 255f, (float)alpha};
        for(int i = 0; i < GLOW_CELLS; i++) {
            for(int j = 0; j < GLOW_CELLS; j++) {
                int i2 = i + 1, j2 = j + 1;
                buf.v(px[i][j],   py[i][j],   pz[i][j],   tu[i][j],   tv[i][j],   fc);
                buf.v(px[i2][j],  py[i2][j],  pz[i2][j],  tu[i2][j],  tv[i2][j],  fc);
                buf.v(px[i2][j2], py[i2][j2], pz[i2][j2], tu[i2][j2], tv[i2][j2], fc);
                buf.v(px[i][j],   py[i][j],   pz[i][j],   tu[i][j],   tv[i][j],   fc);
                buf.v(px[i2][j2], py[i2][j2], pz[i2][j2], tu[i2][j2], tv[i2][j2], fc);
                buf.v(px[i][j2],  py[i][j2],  pz[i][j2],  tu[i][j2],  tv[i][j2],  fc);
            }
        }
    }

    /** Growable interleaved position + texcoord + colour vertex buffer. */
    private static class Buf {
        float[] d = new float[4096];
        int n = 0;

        void v(double x, double y, double z, float u, float v, float[] col) {
            if(n + 9 > d.length)
                d = java.util.Arrays.copyOf(d, d.length * 2);
            // Model space negates y, matching the rest of the client's world geometry.
            d[n++] = (float)x;
            d[n++] = (float)-y;
            d[n++] = (float)z;
            d[n++] = u;
            d[n++] = v;
            d[n++] = col[0];
            d[n++] = col[1];
            d[n++] = col[2];
            d[n++] = col[3];
        }

        float[] fit() {
            return(java.util.Arrays.copyOf(d, n));
        }
    }

    /**
     * White disc with a soft edge. The falloff is deliberately not linear - a linear ramp
     * reads as a flat washer, while this keeps a bright middle and lets the rim disappear
     * into the ground.
     */
    private static BufferedImage glowImage(int sz) {
        BufferedImage img = TexI.mkbuf(new Coord(sz, sz));
        double h = sz / 2.0;
        for(int y = 0; y < sz; y++) {
            for(int x = 0; x < sz; x++) {
                double dx = (x + 0.5 - h) / h, dy = (y + 0.5 - h) / h;
                double d = Math.sqrt((dx * dx) + (dy * dy));
                double a = (d >= 1) ? 0 : Math.pow(1 - d, 2.2);
                // A brighter shoulder just inside the rim gives the glow an edge to read
                // against, so it does not dissolve into an amorphous smudge.
                if((d > 0.62) && (d < 0.90))
                    a = Math.min(1.0, a + (0.30 * Math.sin(Math.PI * (d - 0.62) / 0.28)));
                img.setRGB(x, y, (((int)(a * 255)) << 24) | 0xffffff);
            }
        }
        return(img);
    }

    /* ------------------------------------------------------------------ *
     *  2D pass: rings, beacon, edge arrows
     * ------------------------------------------------------------------ */

    public void draw(GOut g, Pipe state) {
        // The 2D pass runs on the UI thread; a Loading escaping here would take the whole
        // frame down, so anything not paged in yet just skips a frame.
        try {
            draw2d(g, state);
        } catch(Loading ignored) {
        }
    }

    private void draw2d(GOut g, Pipe state) {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.pingService == null)
            return;
        List<PingService.Ping> pings = gui.pingService.snapshot();
        if(pings.isEmpty())
            return;

        flat = FlatWorld.isEnabled();
        Area va = Area.sized(g.sz());
        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        for(PingService.Ping p : pings) {
            Coord2d wc = p.wc();
            if(wc == null)
                continue;
            double alpha = p.alpha();
            if(alpha <= 0)
                continue;
            double z = cz(wc.x, wc.y, baseZ);
            Coord foot = proj(state, va, wc, z + Z_RING);
            Coord head = proj(state, va, wc, z + BEAM_H);

            boolean onscreen = (foot != null) && (head != null) &&
                    (foot.isect(Coord.z, g.sz()) || head.isect(Coord.z, g.sz()));
            if(!onscreen) {
                drawOffscreen(g, p, alpha);
                continue;
            }

            rings(g, state, va, p, wc, z, alpha);
            beacon(g, p, foot, head, alpha);
        }
        g.chcolor();
    }

    /**
     * The expanding ground rings.
     *
     * <p>Two things do the work here. The radius eases out, so a ring leaves the centre
     * fast and then drifts, which is what makes it read as a pulse rather than as a
     * circle being animated; and each ring is stroked three times - dark casing, colour
     * band, hot core - which is what makes a 2px polyline read as a solid glowing band.
     */
    private void rings(GOut g, Pipe state, Area va, PingService.Ping p, Coord2d wc, double z, double alpha) {
        double life = p.since();
        Color col = p.col;
        Color core = Utils.blendcol(col, Color.WHITE, 0.65);

        for(int i = 0; i < RINGS; i++) {
            // Staggering by delay rather than by phase offset means every ring is born at
            // the centre; offsetting the phase would start rings 2 and 3 mid-flight.
            double t = (life - (i * (PERIOD / RINGS))) / PERIOD;
            if(t < 0)
                continue;
            t = t % 1.0;
            int a = (int)(215 * alpha * Math.pow(1 - t, 1.7));
            if(a < 8)
                continue;
            double w = 1.0 + ((1 - t) * 3.0);
            Coord[] pts = ringPoints(state, va, wc, R_START + (R_GROW * easeOut(t)), z);
            band(g, pts, w + 3.5, 6, 18, 12, (int)(a * 0.55));
            band(g, pts, w, col.getRed(), col.getGreen(), col.getBlue(), a);
            if(w > 2.2)
                band(g, pts, Math.max(1, w - 2), core.getRed(), core.getGreen(), core.getBlue(), (int)(a * 0.8));
        }

        // One-shot arrival burst: a fast, wide, near-white ring that says "this just
        // landed". Without it a ping looks the same whether it arrived now or ten seconds
        // ago, which is most of why the effect felt inert.
        if(life < BURST) {
            double t = life / BURST;
            int a = (int)(230 * Math.pow(1 - t, 1.4));
            if(a >= 8) {
                Coord[] pts = ringPoints(state, va, wc, R_START + ((R_GROW * 1.45) * easeOut(t)), z);
                band(g, pts, 5.5, 8, 20, 14, (int)(a * 0.5));
                band(g, pts, 3.0, 255, 255, 255, a);
            }
        }
    }

    /** Fast out of the gate, then drifting - the shape that reads as a pulse. */
    private static double easeOut(double t) {
        double u = 1 - t;
        return(1 - (u * u * u));
    }

    /** Vertical shaft over the pinged tile, so it reads even against busy terrain. */
    private void beacon(GOut g, PingService.Ping p, Coord foot, Coord head, double alpha) {
        Color col = p.col;
        // Segment the shaft and drop the alpha with height: a gradient instead of a stick.
        // Drawn as tapered quads rather than lines - see band() for why that matters.
        final int seg = 4;
        for(int i = 0; i < seg; i++) {
            Coord a = lerp(foot, head, (double)i / seg);
            Coord b = lerp(foot, head, (double)(i + 1) / seg);
            double f0 = 1.0 - ((double)i / seg), f1 = 1.0 - ((double)(i + 1) / seg);
            quad(g, a, b, 4 * (0.6 + (0.4 * f0)), 4 * (0.6 + (0.4 * f1)),
                 6, 18, 12, (int)(140 * alpha * f0));
            quad(g, a, b, 2 * (0.6 + (0.4 * f0)), 2 * (0.6 + (0.4 * f1)),
                 col.getRed(), col.getGreen(), col.getBlue(), (int)(235 * alpha * f0 * f0));
        }

        // Head: a plate that pulses on the ring clock, with a hot centre.
        double pulse = 1.0 + (0.22 * Math.sin(2 * Math.PI * p.since() / PERIOD));
        int r = (int)(UI.scale(6) * pulse);
        g.chcolor(6, 18, 12, (int)(210 * alpha));
        g.fellipse(head, new Coord(r + UI.scale(2), r + UI.scale(2)));
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
        g.fellipse(head, new Coord(r, r));
        g.chcolor(255, 255, 255, (int)(220 * alpha));
        g.fellipse(head, new Coord(Math.max(1, r - UI.scale(3)), Math.max(1, r - UI.scale(3))));

        // Chevron bobbing above the head - a downward "here" that survives a busy screen.
        double bob = UI.scale(4) * (0.5 + (0.5 * Math.sin(2 * Math.PI * p.since() / PERIOD)));
        Coord tip = head.sub(0, (int)(UI.scale(13) + bob));
        int wing = UI.scale(7), hgt = UI.scale(7);
        g.chcolor(6, 18, 12, (int)(190 * alpha));
        chevron(g, tip, wing, hgt, 5);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
        chevron(g, tip, wing, hgt, 2);
    }

    private void chevron(GOut g, Coord tip, int wing, int hgt, double w) {
        g.line(tip.add(-wing, -hgt), tip, w);
        g.line(tip, tip.add(wing, -hgt), w);
    }

    private static Coord lerp(Coord a, Coord b, double t) {
        return(new Coord((int)Math.round(a.x + ((b.x - a.x) * t)),
                         (int)Math.round(a.y + ((b.y - a.y) * t))));
    }

    /** Arrow at the viewport edge pointing at a ping that is out of view. */
    private void drawOffscreen(GOut g, PingService.Ping p, double alpha) {
        Coord2d wc = p.wc();
        double a;
        try {
            a = mv.screenangle(wc, true);
        } catch(Loading l) {
            return;
        }
        if(Double.isNaN(a))
            return;
        Coord sz = g.sz();
        Coord hsz = sz.div(2);
        double ca = -Coord.z.angle(hsz);
        Coord ac;
        if((a > ca) && (a < -ca))
            ac = new Coord(sz.x, hsz.y - (int)(Math.tan(a) * hsz.x));
        else if((a > -ca) && (a < Math.PI + ca))
            ac = new Coord(hsz.x - (int)(Math.tan(a - Math.PI / 2) * hsz.y), 0);
        else if((a > -Math.PI - ca) && (a < ca))
            ac = new Coord(hsz.x + (int)(Math.tan(a + Math.PI / 2) * hsz.y), sz.y);
        else
            ac = new Coord(0, hsz.y + (int)(Math.tan(a) * hsz.x));

        Coord bc = ac.add(Coord.sc(a, -UI.scale(20)));
        // The arrow pulses on the ring clock, so an off-screen ping still reads as the
        // same live thing rather than as a static marker.
        double t = (p.since() % PERIOD) / PERIOD;
        double sc = 1.0 + (0.28 * Math.sin(2 * Math.PI * t));
        Color col = p.col;
        g.chcolor(6, 18, 12, (int)(190 * alpha));
        arrow(g, bc, a, sc, 6);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * alpha));
        arrow(g, bc, a, sc, 2.5);
        g.chcolor();
    }

    private void arrow(GOut g, Coord bc, double a, double scale, double w) {
        g.line(bc, bc.add(Coord.sc(a, -UI.scale((int)(24 * scale)))), w);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale((int)(10 * scale)))), w);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale((int)(10 * scale)))), w);
    }

    /* ------------------------------------------------------------------ *
     *  Projection helpers
     * ------------------------------------------------------------------ */

    /** Ground height at a world point - always zero while the world is drawn flat. */
    private double cz(double x, double y, double fallback) {
        if(flat)
            return(0);
        try {
            return(mv.glob.map.getcz(x, y));
        } catch(Loading l) {
            return(fallback);
        }
    }

    private double baseZ() {
        if(flat)
            return(0);
        return(mv.getcc().z);
    }

    private static Coord proj(Pipe state, Area va, Coord2d wc, double z) {
        HomoCoord4f hc = Homo3D.obj2clip(new Coord3f((float)wc.x, (float)-wc.y, (float)z), state);
        if(hc.w <= 0)
            return(null);
        return(hc.toview(va).round2());
    }

    /**
     * Screen positions of a world-space circle, each point sampled at its own ground
     * height so a ring crossing a slope hugs the slope instead of half burying itself.
     * Entries are null where the point is behind the camera. Projected once and then
     * stroked several times, because reprojecting per stroke would triple the cost of the
     * layered look for nothing.
     */
    private Coord[] ringPoints(Pipe state, Area va, Coord2d c, double r, double z) {
        // Ground height is sampled every HSTEP vertices and interpolated between, not
        // sampled per vertex. getcz() is a bilinear blend of four tile heights, each
        // needing a grid lookup, so per-vertex sampling meant well over a hundred of them
        // per ring per frame for detail no one can see - terrain varies over whole tiles,
        // while the ring has two vertices per tile at this radius.
        final int HSTEP = 4;
        double[] hs = new double[RING_SEG + 1];
        for(int i = 0; i <= RING_SEG; i += HSTEP) {
            double ang = (2 * Math.PI * i) / RING_SEG;
            hs[i] = cz(c.x + (Math.cos(ang) * r), c.y + (Math.sin(ang) * r), z);
        }
        Coord[] pts = new Coord[RING_SEG + 1];
        for(int i = 0; i <= RING_SEG; i++) {
            int lo = (i / HSTEP) * HSTEP;
            int hi = Math.min(RING_SEG, lo + HSTEP);
            double f = (hi == lo) ? 0 : ((double)(i - lo) / (hi - lo));
            double h = hs[lo] + ((hs[hi] - hs[lo]) * f);
            double ang = (2 * Math.PI * i) / RING_SEG;
            pts[i] = proj(state, va, new Coord2d(c.x + (Math.cos(ang) * r), c.y + (Math.sin(ang) * r)), h + Z_RING);
        }
        return(pts);
    }

    /**
     * Stroke a projected polyline as a screen-space band - <em>one</em> draw call per run
     * of visible points.
     *
     * <p>This exists because {@link GOut#line} is far more expensive than it looks: every
     * call allocates a {@code States.LineWidth}, a VertexArray, a buffer and a Model, and
     * issues its own draw with a pipeline state change. Stroking a 32-segment ring three
     * times over meant ~96 draw calls per ring, ~300 per ping per frame, which is enough
     * to be felt as a frame-rate drop with a few pings up. Building the band as a triangle
     * strip collapses each layer to a single draw, and as a bonus gives real geometric
     * thickness instead of relying on GL line width, which drivers honour inconsistently.
     */
    private void band(GOut g, Coord[] pts, double w, int r, int gr, int b, int a) {
        if(a < 4)
            return;
        g.chcolor(r, gr, b, a);
        // Points behind the camera come back null, so the loop emits one strip per
        // contiguous visible run rather than stitching across the gap.
        int i = 0;
        while(i < pts.length) {
            int s = i;
            while((s < pts.length) && (pts[s] == null))
                s++;
            int e = s;
            while((e < pts.length) && (pts[e] != null))
                e++;
            if(e - s >= 2)
                emitBand(g, pts, s, e, (float)(w / 2));
            i = Math.max(e, s + 1);
        }
    }

    private void emitBand(GOut g, Coord[] pts, int s, int e, float h) {
        float[] data = new float[(e - s) * 4];
        int p = 0;
        for(int i = s; i < e; i++) {
            Coord a = pts[Math.max(s, i - 1)], b = pts[Math.min(e - 1, i + 1)];
            float tx = b.x - a.x, ty = b.y - a.y;
            float len = (float)Math.sqrt((tx * tx) + (ty * ty));
            float nx = 0, ny = 0;
            if(len > 1e-4f) {
                nx = -ty / len;
                ny = tx / len;
            }
            float px = pts[i].x + g.tx.x, py = pts[i].y + g.tx.y;
            data[p++] = px - (nx * h); data[p++] = py - (ny * h);
            data[p++] = px + (nx * h); data[p++] = py + (ny * h);
        }
        g.drawp(Model.Mode.TRIANGLE_STRIP, data);
    }

    /** Tapered quad between two screen points; one draw call, no line-width state change. */
    private void quad(GOut g, Coord a, Coord b, double wa, double wb, int r, int gr, int bl, int al) {
        if(al < 4)
            return;
        float dx = b.x - a.x, dy = b.y - a.y;
        float len = (float)Math.sqrt((dx * dx) + (dy * dy));
        if(len < 1e-4f)
            return;
        float nx = -dy / len, ny = dx / len;
        float ax = a.x + g.tx.x, ay = a.y + g.tx.y;
        float bx = b.x + g.tx.x, by = b.y + g.tx.y;
        float ha = (float)(wa / 2), hb = (float)(wb / 2);
        g.chcolor(r, gr, bl, al);
        g.drawp(Model.Mode.TRIANGLE_STRIP, new float[]{
                ax - (nx * ha), ay - (ny * ha),
                ax + (nx * ha), ay + (ny * ha),
                bx - (nx * hb), by - (ny * hb),
                bx + (nx * hb), by + (ny * hb)});
    }
}
