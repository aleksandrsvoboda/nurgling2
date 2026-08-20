package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NMapView;
import nurgling.tools.FlatWorld;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

/**
 * Shared machinery for overlays that lay a path on the ground.
 *
 * A path is drawn as a ribbon sampled at ground height every tile, so it follows the
 * terrain instead of floating across it, plus rings at the points worth calling out.
 * The geometry is real 3D in the render tree and goes in twice: once depth-tested, so
 * hills and buildings hide it, and once faintly with no depth test, so a path hidden
 * behind a wall is still findable. With flat world enabled the terrain has no relief to
 * trace, so the path lies flat as well - MCache.getcz() still reports true heights
 * there, so the flag has to be honoured explicitly.
 *
 * Subclasses supply the points and decide when to rebuild; everything below the point
 * list is here. {@link NWaypointOverlay} draws the alt+click movement queue on top of
 * it, {@link NStorageTrailOverlay} the route to a searched-for container.
 */
public abstract class NGroundPathOverlay implements RenderTree.Node {
    /* --- world-space geometry constants (game units; one tile is 11) --- */
    protected static final double SAMPLE = 11.0;      // ribbon sample spacing
    protected static final int MAX_SAMPLES = 96;      // cap for very long legs
    protected static final double RIB_CORE = 0.60;    // half-width of the coloured core
    protected static final double RIB_CASE = 1.25;    // half-width of the dark casing
    protected static final double Z_CASE = 0.35, Z_CORE = 0.45;
    protected static final double RING_IN = 4.4, RING_OUT = 6.0, Z_RING = 0.45;
    protected static final int RING_SEG = 24;

    protected static final float[] CASING = {0.02f, 0.05f, 0.05f, 0.85f};

    private static final VertexArray.Layout LAYOUT = new VertexArray.Layout(
            new VertexArray.Layout.Input(Homo3D.vertex, new VectorFormat(3, NumberFormat.FLOAT32), 0, 0, 28),
            new VertexArray.Layout.Input(VertexColor.color, new VectorFormat(4, NumberFormat.FLOAT32), 0, 12, 28));

    private static final Pipe.Op BASE = Pipe.Op.compose(
            new States.Facecull(States.Facecull.Mode.NONE),
            Clickable.No,
            VertexColor.instance);
    private static final Pipe.Op MAT_SOLID = Pipe.Op.compose(
            Rendered.postpfx,
            new BaseColor(Color.WHITE));
    private static final Pipe.Op MAT_GHOST = Pipe.Op.compose(
            Rendered.last,
            States.Depthtest.none,
            States.maskdepth,
            new BaseColor(new Color(255, 255, 255, 60)));

    protected final NMapView mv;
    private final Part solid = new Part();
    private final Part ghost = new Part();
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);

    /** Flat-world state of the frame/rebuild currently being processed. */
    protected boolean flat = false;

    protected NGroundPathOverlay(NMapView mv) {
        this.mv = mv;
    }

    /* ------------------------------------------------------------------ *
     *  Render tree plumbing
     * ------------------------------------------------------------------ */

    private static class Part implements RenderTree.Node, Rendered {
        private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
        private volatile Model model = null;

        void set(Model m) {
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
        slot.ostate(BASE);
        slot.add(solid, MAT_SOLID);
        slot.add(ghost, MAT_GHOST);
        synchronized(slots) {
            slots.add(slot);
        }
    }

    public void removed(RenderTree.Slot slot) {
        synchronized(slots) {
            slots.remove(slot);
        }
    }

    /** Hand a finished vertex buffer to both passes, or clear them when it is empty. */
    protected void setGeometry(Buf buf) {
        if(buf == null || buf.n == 0) {
            clearGeometry();
            return;
        }
        float[] data = buf.fit();
        VertexArray va = new VertexArray(LAYOUT,
                new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data)));
        Model model = new Model(Model.Mode.TRIANGLES, va, null);
        solid.set(model);
        ghost.set(model);
    }

    protected void clearGeometry() {
        solid.set(null);
        ghost.set(null);
    }

    /* ------------------------------------------------------------------ *
     *  Geometry
     * ------------------------------------------------------------------ */

    /** Growable interleaved position+colour vertex buffer. */
    protected static class Buf {
        float[] d = new float[8192];
        int n = 0;

        void v(double x, double y, double z, float[] col) {
            if(n + 7 > d.length)
                d = Arrays.copyOf(d, d.length * 2);
            // Model space negates y, matching the rest of the client's world geometry.
            d[n++] = (float)x;
            d[n++] = (float)-y;
            d[n++] = (float)z;
            d[n++] = col[0];
            d[n++] = col[1];
            d[n++] = col[2];
            d[n++] = col[3];
        }

        void tri(double[] a, double[] b, double[] c, float[] col) {
            v(a[0], a[1], a[2], col);
            v(b[0], b[1], b[2], col);
            v(c[0], c[1], c[2], col);
        }

        void quad(double[] a, double[] b, double[] c, double[] d, float[] col) {
            tri(a, b, c, col);
            tri(a, c, d, col);
        }

        float[] fit() {
            return(Arrays.copyOf(d, n));
        }
    }

    /** Ground height at a world point - always zero while the world is drawn flat. */
    protected double cz(double x, double y, double fallback) {
        if(flat)
            return(0);
        try {
            return(mv.glob.map.getcz(x, y));
        } catch(Loading l) {
            return(fallback);
        }
    }

    /** Height to fall back on where the terrain has not been paged in yet. */
    protected double baseZ() {
        if(flat)
            return(0);
        return(mv.getcc().z);
    }

    protected void updateFlat() {
        flat = FlatWorld.isEnabled();
    }

    protected static double[] p(double x, double y, double z) {
        return(new double[]{x, y, z});
    }

    protected static float[] rgba(Color c, double alpha) {
        return(new float[]{c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, (float)alpha});
    }

    protected Coord2d playerPos() {
        Gob pl = mv.player();
        if(pl == null)
            return(null);
        return(pl.rc);
    }

    /**
     * One leg of the path, straight in X/Y, sampled at ground height so it lies on the
     * terrain.
     */
    protected void ribbon(Buf buf, Coord2d a, Coord2d b, float[] core, double baseZ) {
        double len = a.dist(b);
        if(len < 0.5)
            return;
        // Flat world has no relief to trace, so one quad spans the whole leg.
        int steps = flat ? 1 : Math.min(MAX_SAMPLES, Math.max(1, (int)Math.ceil(len / SAMPLE)));
        Coord2d dir = b.sub(a).div(len);
        Coord2d perp = new Coord2d(-dir.y, dir.x);

        double[] pcl = null, pcr = null, pkl = null, pkr = null;
        for(int i = 0; i <= steps; i++) {
            double t = (double)i / steps;
            Coord2d pt = a.add(b.sub(a).mul(t));
            double z = cz(pt.x, pt.y, baseZ);
            // Sample at the ribbon's own edges: on a slope the centreline height would
            // leave the downhill edge buried in the ground.
            double lx = pt.x + perp.x * RIB_CASE, ly = pt.y + perp.y * RIB_CASE;
            double rx = pt.x - perp.x * RIB_CASE, ry = pt.y - perp.y * RIB_CASE;
            double zl = cz(lx, ly, z), zr = cz(rx, ry, z);

            double[] kl = p(lx, ly, zl + Z_CASE);
            double[] kr = p(rx, ry, zr + Z_CASE);
            double[] cl = p(pt.x + perp.x * RIB_CORE, pt.y + perp.y * RIB_CORE, zl + Z_CORE);
            double[] cr = p(pt.x - perp.x * RIB_CORE, pt.y - perp.y * RIB_CORE, zr + Z_CORE);

            if(pkl != null) {
                buf.quad(pkl, kl, kr, pkr, CASING);
                buf.quad(pcl, cl, cr, pcr, core);
            }
            pkl = kl; pkr = kr; pcl = cl; pcr = cr;
        }
    }

    /** Ground ring with a translucent fill. */
    protected void ring(Buf buf, Coord2d c, float[] edge, float[] fill, double baseZ) {
        ring(buf, c, edge, fill, baseZ, RING_IN, RING_OUT);
    }

    protected void ring(Buf buf, Coord2d c, float[] edge, float[] fill, double baseZ, double rin, double rout) {
        double[][] in = new double[RING_SEG][];
        double[][] out = new double[RING_SEG][];
        double cztr = cz(c.x, c.y, baseZ);
        for(int i = 0; i < RING_SEG; i++) {
            double ang = (2 * Math.PI * i) / RING_SEG;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            double ix = c.x + dx * rin, iy = c.y + dy * rin;
            double ox = c.x + dx * rout, oy = c.y + dy * rout;
            in[i] = p(ix, iy, cz(ix, iy, cztr) + Z_RING);
            out[i] = p(ox, oy, cz(ox, oy, cztr) + Z_RING);
        }
        double[] mid = p(c.x, c.y, cztr + Z_RING);
        for(int i = 0; i < RING_SEG; i++) {
            int j = (i + 1) % RING_SEG;
            buf.quad(in[i], out[i], out[j], in[j], edge);
            buf.tri(mid, in[i], in[j], fill);
        }
    }

    /* ------------------------------------------------------------------ *
     *  2D pass helpers
     * ------------------------------------------------------------------ */

    protected static Coord proj(Pipe state, Area va, Coord2d wc, double z) {
        HomoCoord4f hc = Homo3D.obj2clip(new Coord3f((float)wc.x, (float)-wc.y, (float)z), state);
        if(hc.w <= 0)
            return(null);
        return(hc.toview(va).round2());
    }

    /**
     * Draw a world-space circle as a projected polyline. step=1 gives a solid ring,
     * step=2 a dashed one.
     */
    protected void circle(GOut g, Pipe state, Area va, Coord2d c, double r, double z, double w, int step) {
        final int n = 24;
        Coord[] pts = new Coord[n + 1];
        for(int i = 0; i <= n; i++) {
            double ang = (2 * Math.PI * i) / n;
            pts[i] = proj(state, va, new Coord2d(c.x + Math.cos(ang) * r, c.y + Math.sin(ang) * r), z);
        }
        for(int i = 0; i < n; i += step) {
            if(pts[i] != null && pts[i + 1] != null)
                g.line(pts[i], pts[i + 1], w);
        }
    }

    /**
     * Arrow pinned to the screen edge pointing at an off-view world point. Returns the
     * screen position of the arrow head, so callers can hang a label off it, or null if
     * the angle could not be resolved this frame.
     */
    protected Coord edgeArrow(GOut g, Coord2d wc, Color col) {
        double a;
        try {
            a = mv.screenangle(wc, true);
        } catch(Loading l) {
            return(null);
        }
        if(Double.isNaN(a))
            return(null);
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

        Coord bc = ac.add(Coord.sc(a, -UI.scale(18)));
        g.chcolor(0, 0, 0, 180);
        g.line(bc, bc.add(Coord.sc(a, -UI.scale(22))), 5);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale(9))), 5);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale(9))), 5);
        g.chcolor(col);
        g.line(bc, bc.add(Coord.sc(a, -UI.scale(22))), 2);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale(9))), 2);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale(9))), 2);
        g.chcolor();
        return(bc.add(Coord.sc(a, -UI.scale(34))));
    }
}
