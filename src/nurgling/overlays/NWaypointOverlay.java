package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.WaypointMovementService;
import nurgling.tools.FlatWorld;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Draws the alt+click movement queue in the world.
 *
 * The character walks to a waypoint by straight-line click-walk - the server does no
 * routing - so the path drawn here is deliberately straight in X/Y. The only bend it
 * ever shows comes from elevation: the same straight segment is sampled at ground
 * height every tile, so it lies on the terrain instead of floating across it. With flat
 * world enabled the terrain has no relief to follow, so the path lies flat as well -
 * MCache.getcz() still reports true heights there, so the flag has to be honoured here
 * the same way NPathVisualizer honours it.
 *
 * Geometry (ribbon + waypoint rings) is real 3D in the render tree, drawn twice: once
 * depth-tested, so hills and buildings hide it, and once faintly with no depth test, so
 * a hidden path is still findable. Labels, the active-node pulse and the drag ghost are
 * drawn in the 2D pass on top (PView.Render2D), the same way area labels work.
 */
public class NWaypointOverlay implements RenderTree.Node, PView.Render2D {
    /* --- world-space geometry constants (game units; one tile is 11) --- */
    private static final double SAMPLE = 11.0;      // ribbon sample spacing
    private static final int MAX_SAMPLES = 96;      // cap for very long legs
    private static final double RIB_CORE = 0.60;    // half-width of the coloured core
    private static final double RIB_CASE = 1.25;    // half-width of the dark casing
    private static final double Z_CASE = 0.35, Z_CORE = 0.45;
    private static final double RING_IN = 4.4, RING_OUT = 6.0, Z_RING = 0.45;
    private static final int RING_SEG = 24;
    private static final double STEM_H = 7.0;       // world height of the label stem

    private static final float[] CASING = {0.02f, 0.05f, 0.05f, 0.85f};

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

    private final NMapView mv;
    private final Part solid = new Part();
    private final Part ghost = new Part();
    private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);

    /** One queued waypoint, resolved to world coordinates. */
    public static class WNode {
        public final long id;
        public final int num;
        public final Coord2d wc;
        public Coord sc;        // screen position of the ground point, last frame (null if not visible)

        WNode(long id, int num, Coord2d wc) {
            this.id = id;
            this.num = num;
            this.wc = wc;
        }
    }

    /** Flat-world state of the frame/rebuild currently being processed. */
    private boolean flat = false;
    private long lastSig = Long.MIN_VALUE;
    private Coord2d lastPlayer = null;
    private double lastBuild = 0;
    private volatile List<WNode> screen = Collections.emptyList();

    // Cached ETA text so it is not re-rendered every frame.
    private static final Text.Foundry etaf = new Text.Foundry(Text.dfont, 11).aa(true);
    private String etaStr = null;
    private Text etaTex = null;
    private String dragStr = null;
    private Text dragTex = null;

    public NWaypointOverlay(NMapView mv) {
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

    /* ------------------------------------------------------------------ *
     *  Colours
     * ------------------------------------------------------------------ */

    public static Color activeColor() {
        return(NConfig.getColor(NConfig.Key.waypointColorActive, new Color(0, 224, 224)));
    }

    public static Color queuedColor() {
        return(NConfig.getColor(NConfig.Key.waypointColorQueued, new Color(255, 212, 0)));
    }

    public static Color hoverColor() {
        return(new Color(180, 220, 255));
    }

    public static Color dragColor() {
        return(Color.WHITE);
    }

    private static float[] rgba(Color c, double alpha) {
        return(new float[]{c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, (float)alpha});
    }

    /** Colour of a node given its position in the queue and the current pointer state. */
    private Color nodeColor(int idx, long id) {
        if(id == mv.wpDragId())
            return(dragColor());
        if(id == mv.wpHoverId())
            return(hoverColor());
        return((idx == 0) ? activeColor() : queuedColor());
    }

    /* ------------------------------------------------------------------ *
     *  Queue resolution
     * ------------------------------------------------------------------ */

    /** Current queue in world coordinates, or an empty list when there is nothing to draw. */
    private List<WNode> resolve() {
        if(!(Boolean)NConfig.get(NConfig.Key.showWaypointsInWorld))
            return(Collections.emptyList());
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.waypointMovementService == null || gui.mmap == null)
            return(Collections.emptyList());
        MiniMap.Location sessloc = gui.mmap.sessloc;
        if(sessloc == null)
            return(Collections.emptyList());
        List<WaypointMovementService.Waypoint> wps = gui.waypointMovementService.snapshot();
        if(wps.isEmpty())
            return(Collections.emptyList());
        List<WNode> ret = new ArrayList<>(wps.size());
        int num = 1;
        for(WaypointMovementService.Waypoint wp : wps) {
            if(wp.loc.seg.id == sessloc.seg.id)
                ret.add(new WNode(wp.id, num, wp.loc.tc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz)));
            num++;
        }
        return(ret);
    }

    private Coord2d playerPos() {
        Gob pl = mv.player();
        if(pl == null)
            return(null);
        return(pl.rc);
    }

    /* ------------------------------------------------------------------ *
     *  Geometry
     * ------------------------------------------------------------------ */

    /** Growable interleaved position+colour vertex buffer. */
    private static class Buf {
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
    private double cz(double x, double y, double fallback) {
        if(flat)
            return(0);
        try {
            return(mv.glob.map.getcz(x, y));
        } catch(Loading l) {
            return(fallback);
        }
    }

    /** Height to fall back on where the terrain has not been paged in yet. */
    private double baseZ() {
        if(flat)
            return(0);
        return(mv.getcc().z);
    }

    private static double[] p(double x, double y, double z) {
        return(new double[]{x, y, z});
    }

    /**
     * One leg of the walk. Straight in X/Y - exactly the line the character runs -
     * sampled at ground height so it lies on the terrain.
     */
    private void ribbon(Buf buf, Coord2d a, Coord2d b, float[] core, double baseZ) {
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

    /** Ground ring with a translucent fill at a waypoint. */
    private void ring(Buf buf, Coord2d c, float[] edge, float[] fill, double baseZ) {
        double[][] in = new double[RING_SEG][];
        double[][] out = new double[RING_SEG][];
        double cztr = cz(c.x, c.y, baseZ);
        for(int i = 0; i < RING_SEG; i++) {
            double ang = (2 * Math.PI * i) / RING_SEG;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            double ix = c.x + dx * RING_IN, iy = c.y + dy * RING_IN;
            double ox = c.x + dx * RING_OUT, oy = c.y + dy * RING_OUT;
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

    private long signature(List<WNode> nodes) {
        long h = 1125899906842597L;
        for(WNode n : nodes) {
            h = h * 31 + n.id;
            h = h * 31 + (long)n.wc.x;
            h = h * 31 + (long)n.wc.y;
        }
        h = h * 31 + mv.wpHoverId();
        h = h * 31 + mv.wpDragId();
        h = h * 31 + activeColor().getRGB();
        h = h * 31 + queuedColor().getRGB();
        // Toggling flat world changes every vertex, so it has to force a rebuild.
        h = h * 31 + (flat ? 1 : 0);
        return(h);
    }

    /**
     * Rebuild the 3D geometry when it actually changed. The waypoints themselves only
     * move when the user drags one, so the expensive terrain sampling is driven by the
     * queue signature; the player's own leg is refreshed on a short throttle instead.
     */
    public void update() {
        flat = FlatWorld.isEnabled();
        List<WNode> nodes = resolve();
        if(nodes.isEmpty()) {
            if(lastSig != Long.MIN_VALUE) {
                solid.set(null);
                ghost.set(null);
                lastSig = Long.MIN_VALUE;
                lastPlayer = null;
                screen = Collections.emptyList();
            }
            return;
        }

        Coord2d pl = playerPos();
        long sig = signature(nodes);
        double now = Utils.rtime();
        boolean moved = (pl != null) && ((lastPlayer == null) || (lastPlayer.dist(pl) > 3.0));
        if((sig == lastSig) && !(moved && (now - lastBuild > 0.2)))
            return;

        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        Buf buf = new Buf();
        Coord2d prev = pl;
        for(WNode n : nodes) {
            Color col = nodeColor(n.num - 1, n.id);
            if(prev != null) {
                // The leg keeps the queue colour even when its node is grabbed, so the
                // path stays readable while a waypoint is being dragged.
                Color legc = (n.num == 1) ? activeColor() : queuedColor();
                ribbon(buf, prev, n.wc, rgba(legc, 0.95), baseZ);
            }
            ring(buf, n.wc, rgba(col, 0.95), rgba(col, 0.18), baseZ);
            prev = n.wc;
        }

        if(buf.n == 0) {
            solid.set(null);
            ghost.set(null);
        } else {
            float[] data = buf.fit();
            VertexArray va = new VertexArray(LAYOUT,
                    new VertexArray.Buffer(data.length * 4, DataBuffer.Usage.STATIC, DataBuffer.Filler.of(data)));
            Model model = new Model(Model.Mode.TRIANGLES, va, null);
            solid.set(model);
            ghost.set(model);
        }
        lastSig = sig;
        lastPlayer = pl;
        lastBuild = now;
    }

    /* ------------------------------------------------------------------ *
     *  2D pass: labels, pulse, drag ghost, off-screen arrows
     * ------------------------------------------------------------------ */

    /** Screen positions of the waypoint ground points as of the last frame. */
    public List<WNode> screenNodes() {
        return(screen);
    }

    private static Coord proj(Pipe state, Area va, Coord2d wc, double z) {
        HomoCoord4f hc = Homo3D.obj2clip(new Coord3f((float)wc.x, (float)-wc.y, (float)z), state);
        if(hc.w <= 0)
            return(null);
        return(hc.toview(va).round2());
    }

    public void draw(GOut g, Pipe state) {
        // The 2D pass runs on the UI thread; a Loading escaping here would take the
        // whole frame down, so anything not yet paged in just skips a frame.
        try {
            draw2d(g, state);
        } catch(Loading l) {
            screen = Collections.emptyList();
        }
    }

    private void draw2d(GOut g, Pipe state) {
        flat = FlatWorld.isEnabled();
        List<WNode> nodes = resolve();
        if(nodes.isEmpty()) {
            screen = Collections.emptyList();
            return;
        }
        Area va = Area.sized(g.sz());
        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            screen = Collections.emptyList();
            return;
        }

        for(WNode n : nodes) {
            double z = cz(n.wc.x, n.wc.y, baseZ);
            n.sc = proj(state, va, n.wc, z + Z_RING);
            Coord top = proj(state, va, n.wc, z + STEM_H);

            if(n.sc == null || top == null) {
                drawOffscreen(g, n);
                continue;
            }
            if(!n.sc.isect(Coord.z, g.sz()) && !top.isect(Coord.z, g.sz())) {
                drawOffscreen(g, n);
                continue;
            }

            Color col = nodeColor(n.num - 1, n.id);
            if(n.num == 1)
                pulse(g, state, va, n, z);

            // stem from the ground point up to the plate
            g.chcolor(0, 0, 0, 160);
            g.line(n.sc, top, 3);
            g.chcolor(col);
            g.line(n.sc, top, 1);

            plate(g, top, n.num, col);

            if(n.num == 1)
                eta(g, top, n);
        }

        dragGhost(g, state, va, nodes, baseZ);
        screen = nodes;
        g.chcolor();
    }

    /** Numbered plate on top of the stem. */
    private void plate(GOut g, Coord c, int num, Color col) {
        Tex num_t = NMiniMap.getWaypointLabel(num).tex();
        Coord psz = num_t.sz().add(UI.scale(10), UI.scale(4));
        Coord ul = c.sub(psz.div(2));
        g.chcolor(12, 16, 18, 215);
        g.frect(ul, psz);
        g.chcolor(col);
        g.rect(ul, psz);
        g.aimage(num_t, c, 0.5, 0.5);
        g.chcolor();
    }

    /** Distance and, while moving, arrival estimate under the active waypoint. */
    private void eta(GOut g, Coord c, WNode n) {
        Gob pl = mv.player();
        if(pl == null)
            return;
        double dist;
        try {
            dist = pl.rc.dist(n.wc);
        } catch(Loading l) {
            return;
        }
        int tiles = (int)Math.round(dist / MCache.tilesz.x);
        String s = tiles + " tiles";
        Moving m = pl.getattr(Moving.class);
        if(m != null) {
            double v = m.getv();
            if(v > 0.1)
                s = s + " · " + (int)Math.ceil(dist / v) + "s";
        }
        if(!s.equals(etaStr)) {
            if(etaTex != null)
                etaTex.dispose();
            etaTex = etaf.render(s, new Color(215, 235, 240));
            etaStr = s;
        }
        Coord ul = c.add(0, UI.scale(11)).sub(etaTex.sz().x / 2, 0);
        g.chcolor(12, 16, 18, 190);
        g.frect(ul.sub(UI.scale(3), UI.scale(1)), etaTex.sz().add(UI.scale(6), UI.scale(2)));
        g.chcolor();
        g.image(etaTex.tex(), ul);
    }

    /** Expanding ring on the waypoint the character is running to. */
    private void pulse(GOut g, Pipe state, Area va, WNode n, double z) {
        // While the active waypoint is being dragged the character is re-routing to it,
        // so the ping speeds up and brightens - the visible answer to the drag.
        boolean rerouting = (n.id == mv.wpDragId());
        double period = rerouting ? 0.5 : 1.3;
        double t = (Utils.rtime() % period) / period;
        double r = RING_OUT + (t * 12.0);
        int a = (int)((rerouting ? 220 : 140) * (1 - t));
        if(a < 8)
            return;
        Color col = nodeColor(0, n.id);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
        circle(g, state, va, n.wc, r, z + Z_RING, 2, 1);
        g.chcolor();
    }

    /**
     * Draw a world-space circle as a projected polyline. step=1 gives a solid ring,
     * step=2 a dashed one.
     */
    private void circle(GOut g, Pipe state, Area va, Coord2d c, double r, double z, double w, int step) {
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

    /** Where the waypoint was picked up from, while it is being dragged. */
    private void dragGhost(GOut g, Pipe state, Area va, List<WNode> nodes, double baseZ) {
        long id = mv.wpDragId();
        Coord2d org = mv.wpDragOrigin();
        if(id < 0 || org == null)
            return;
        WNode cur = null;
        for(WNode n : nodes) {
            if(n.id == id)
                cur = n;
        }
        if(cur == null)
            return;
        double oz = cz(org.x, org.y, baseZ);
        Coord osc = proj(state, va, org, oz + Z_RING);

        g.chcolor(255, 255, 255, 110);
        circle(g, state, va, org, RING_OUT, oz + Z_RING, 2, 2);
        if(osc != null && cur.sc != null) {
            // dashed tether from the original spot to the dragged one
            int seg = 12;
            for(int i = 0; i < seg; i += 2) {
                Coord a = osc.add(cur.sc.sub(osc).mul(i).div(seg));
                Coord b = osc.add(cur.sc.sub(osc).mul(i + 1).div(seg));
                g.line(a, b, 2);
            }
            String s = (int)Math.round(org.dist(cur.wc) / MCache.tilesz.x) + " tiles";
            if(!s.equals(dragStr)) {
                if(dragTex != null)
                    dragTex.dispose();
                dragTex = etaf.render(s, Color.WHITE);
                dragStr = s;
            }
            Tex t = dragTex.tex();
            Coord mid = osc.add(cur.sc).div(2);
            g.chcolor(12, 16, 18, 190);
            g.frect(mid.sub(t.sz().x / 2 + UI.scale(3), t.sz().y / 2), t.sz().add(UI.scale(6), 0));
            g.chcolor();
            g.aimage(t, mid, 0.5, 0.5);
        }
        g.chcolor();
    }

    /** Numbered arrow at the screen edge for a waypoint that is out of view. */
    private void drawOffscreen(GOut g, WNode n) {
        n.sc = null;
        double a;
        try {
            a = mv.screenangle(n.wc, true);
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

        Coord bc = ac.add(Coord.sc(a, -UI.scale(18)));
        Color col = nodeColor(n.num - 1, n.id);
        g.chcolor(0, 0, 0, 180);
        g.line(bc, bc.add(Coord.sc(a, -UI.scale(22))), 5);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale(9))), 5);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale(9))), 5);
        g.chcolor(col);
        g.line(bc, bc.add(Coord.sc(a, -UI.scale(22))), 2);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale(9))), 2);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale(9))), 2);
        plate(g, bc.add(Coord.sc(a, -UI.scale(34))), n.num, col);
        g.chcolor();
    }
}
