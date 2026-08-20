package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.PingService;
import nurgling.tools.FlatWorld;

import java.awt.Color;
import java.util.List;

/**
 * Draws map pings in the world - the rings and beacon for a spot someone pinged over chat.
 *
 * <p>Unlike {@link NWaypointOverlay} this builds no geometry in the render tree. A ping is
 * a handful of rings that change radius every single frame and live for twelve seconds;
 * rebuilding a vertex buffer for that would cost more than projecting two dozen points and
 * drawing lines. So the overlay joins the render tree purely to get a
 * {@link PView.Render2D} slot, and everything is drawn in the 2D pass on top of the frame.
 *
 * <p>The rings are still world-space: their points are projected from ground height at the
 * pinged tile, so they sit on the terrain and follow the camera the way the waypoint rings
 * do, rather than floating as a flat screen circle. A ping that is off-screen gets an arrow
 * at the viewport edge instead, because the whole point of pinging is to send someone
 * somewhere they are not looking.
 */
public class NPointPingOverlay implements RenderTree.Node, PView.Render2D {
    /* --- world-space geometry constants (game units; one tile is 11) --- */
    private static final double R_START = 2.5;      // radius a ring is born at
    private static final double R_GROW = 22.0;      // how far it expands before dying
    private static final double Z_RING = 0.45;
    private static final double BEAM_H = 9.0;       // world height of the beacon
    private static final int RING_SEG = 28;
    /** Rings in flight at once, evenly staggered through the cycle. */
    private static final int RINGS = 3;
    /** Seconds for one ring to go from R_START to R_START + R_GROW. */
    private static final double PERIOD = 1.4;

    private final NMapView mv;
    /** Flat-world state of the frame being drawn. */
    private boolean flat = false;

    public NPointPingOverlay(NMapView mv) {
        this.mv = mv;
    }

    public void added(RenderTree.Slot slot) {}

    public void removed(RenderTree.Slot slot) {}

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
            double fade = 1.0 - p.age();
            if(fade <= 0)
                continue;
            double z = cz(wc.x, wc.y, baseZ);
            Coord foot = proj(state, va, wc, z + Z_RING);
            Coord head = proj(state, va, wc, z + BEAM_H);

            boolean onscreen = (foot != null) && (head != null) &&
                    (foot.isect(Coord.z, g.sz()) || head.isect(Coord.z, g.sz()));
            if(!onscreen) {
                drawOffscreen(g, p, fade);
                continue;
            }

            rings(g, state, va, wc, z, p.col, fade);
            beacon(g, foot, head, p.col, fade);
        }
        g.chcolor();
    }

    /** The expanding ground rings, several in flight and staggered through the cycle. */
    private void rings(GOut g, Pipe state, Area va, Coord2d wc, double z, Color col, double fade) {
        double phase = (Utils.rtime() % PERIOD) / PERIOD;
        for(int i = 0; i < RINGS; i++) {
            double t = (phase + ((double)i / RINGS)) % 1.0;
            int a = (int)(200 * fade * (1 - t));
            if(a < 8)
                continue;
            g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
            circle(g, state, va, wc, R_START + (t * R_GROW), z);
        }
    }

    /** Vertical shaft over the pinged tile, so it reads even against busy terrain. */
    private void beacon(GOut g, Coord foot, Coord head, Color col, double fade) {
        g.chcolor(0, 0, 0, (int)(150 * fade));
        g.line(foot, head, 4);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(230 * fade));
        g.line(foot, head, 2);
        g.chcolor(0, 0, 0, (int)(200 * fade));
        g.fellipse(head, new Coord(UI.scale(5), UI.scale(5)));
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * fade));
        g.fellipse(head, new Coord(UI.scale(4), UI.scale(4)));
    }

    /** Arrow at the viewport edge pointing at a ping that is out of view. */
    private void drawOffscreen(GOut g, PingService.Ping p, double fade) {
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

        Coord bc = ac.add(Coord.sc(a, -UI.scale(18)));
        // The arrow pulses on the same clock as the rings, so an off-screen ping still
        // reads as the same live thing rather than a static marker.
        double t = (Utils.rtime() % PERIOD) / PERIOD;
        double sc = 1.0 + (0.25 * Math.sin(2 * Math.PI * t));
        Color col = p.col;
        g.chcolor(0, 0, 0, (int)(180 * fade));
        arrow(g, bc, a, sc, 5);
        g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), (int)(255 * fade));
        arrow(g, bc, a, sc, 2);
        g.chcolor();
    }

    private void arrow(GOut g, Coord bc, double a, double scale, double w) {
        g.line(bc, bc.add(Coord.sc(a, -UI.scale((int)(22 * scale)))), w);
        g.line(bc, bc.add(Coord.sc(a + Math.PI / 4, -UI.scale((int)(9 * scale)))), w);
        g.line(bc, bc.add(Coord.sc(a - Math.PI / 4, -UI.scale((int)(9 * scale)))), w);
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
     * World-space circle drawn as a projected polyline. Each point is sampled at its own
     * ground height rather than the centre's, so a ring crossing a slope hugs the slope
     * instead of half burying itself in it. {@code z} is the fallback height for ground
     * that has not paged in.
     */
    private void circle(GOut g, Pipe state, Area va, Coord2d c, double r, double z) {
        Coord prev = null;
        for(int i = 0; i <= RING_SEG; i++) {
            double ang = (2 * Math.PI * i) / RING_SEG;
            double x = c.x + (Math.cos(ang) * r), y = c.y + (Math.sin(ang) * r);
            Coord cur = proj(state, va, new Coord2d(x, y), cz(x, y, z) + Z_RING);
            if((prev != null) && (cur != null))
                g.line(prev, cur, 2);
            prev = cur;
        }
    }
}
