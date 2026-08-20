package nurgling.overlays;

import haven.*;
import haven.render.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.WaypointMovementService;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draws the alt+click movement queue in the world.
 *
 * The character walks to a waypoint by straight-line click-walk - the server does no
 * routing - so the path drawn here is deliberately straight in X/Y. The only bend it
 * ever shows comes from elevation: the same straight segment is sampled at ground
 * height every tile, so it lies on the terrain instead of floating across it.
 *
 * The ribbon and ring geometry, the terrain sampling and the screen-edge arrows all
 * live in {@link NGroundPathOverlay}; this class supplies the queue and everything
 * specific to it. Labels, the active-node pulse and the drag ghost are drawn in the 2D
 * pass on top (PView.Render2D), the same way area labels work.
 */
public class NWaypointOverlay extends NGroundPathOverlay implements PView.Render2D {
    private static final double STEM_H = 7.0;       // world height of the label stem
    /** How many out-of-view waypoints get an edge arrow; a long path would otherwise
     *  ring the whole viewport with numbers. */
    private static final int MAX_EDGE_ARROWS = 3;

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
        super(mv);
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

    /* ------------------------------------------------------------------ *
     *  Geometry
     * ------------------------------------------------------------------ */

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
        updateFlat();
        List<WNode> nodes = resolve();
        if(nodes.isEmpty()) {
            if(lastSig != Long.MIN_VALUE) {
                clearGeometry();
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

        setGeometry(buf);
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
        updateFlat();
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

        List<WNode> offscreen = null;
        for(WNode n : nodes) {
            double z = cz(n.wc.x, n.wc.y, baseZ);
            n.sc = proj(state, va, n.wc, z + Z_RING);
            Coord top = proj(state, va, n.wc, z + STEM_H);

            boolean out = (n.sc == null) || (top == null) ||
                    (!n.sc.isect(Coord.z, g.sz()) && !top.isect(Coord.z, g.sz()));
            if(out) {
                // Out of view: not clickable, and only the first few get an edge arrow
                // - see drawEdgeArrows. Nodes come in queue order, so keeping the head
                // of the list keeps the lowest-numbered ones.
                n.sc = null;
                if(offscreen == null)
                    offscreen = new ArrayList<>(MAX_EDGE_ARROWS);
                if(offscreen.size() < MAX_EDGE_ARROWS)
                    offscreen.add(n);
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

        if(offscreen != null)
            drawEdgeArrows(g, offscreen);

        dragGhost(g, state, va, nodes, baseZ);
        screen = nodes;
        g.chcolor();
    }

    /**
     * Edge arrows for the first {@link #MAX_EDGE_ARROWS} out-of-view waypoints, in queue
     * order - the next ones the character will walk to. Paths run to hundreds of
     * waypoints, and pointing at all of them turns the viewport border into a wall of
     * numbers.
     */
    private void drawEdgeArrows(GOut g, List<WNode> off) {
        for(WNode n : off) {
            Color col = nodeColor(n.num - 1, n.id);
            Coord head = edgeArrow(g, n.wc, col);
            if(head != null)
                plate(g, head, n.num, col);
        }
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
}
