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

    /** ROUTE nodes are the normal queue/Forager-route waypoints, colored by index vs. the active
     *  one (see nodeColor()). DETOUR nodes are Forager's off-path gob-collection breadcrumb trail
     *  - always a fixed color (see detourColor()), rendered as their own disconnected chain (no
     *  leg drawn between the last ROUTE node and the first DETOUR one), and never drag-addressable
     *  or hoverable/pulsed like a ROUTE node. */
    public enum Kind { ROUTE, DETOUR }

    /** One queued waypoint, resolved to world coordinates. */
    public static class WNode {
        public final long id;
        public final int num;
        public final Coord2d wc;
        public final Kind kind;
        public Coord sc;        // screen position of the ground point, last frame (null if not visible)

        WNode(long id, int num, Coord2d wc) {
            this(id, num, wc, Kind.ROUTE);
        }

        WNode(long id, int num, Coord2d wc, Kind kind) {
            this.id = id;
            this.num = num;
            this.wc = wc;
            this.kind = kind;
        }
    }

    private long lastSig = Long.MIN_VALUE;
    private Coord2d lastPlayer = null;
    private double lastBuild = 0;
    private volatile List<WNode> screen = Collections.emptyList();
    // Whether the nodes resolve() most recently built may be picked up for a 3D-view drag - true
    // for Forager route editing and the WaypointMovementService queue, false while merely
    // displaying a running/recording bot's path (read-only; there's no sensible write-back target
    // for repositioning a waypoint of a route the bot is actively walking). See resolve()/draggable().
    private volatile boolean draggable = true;

    // Identity of whatever resolve() most recently drew from - the ForagerRouteMap instance, the
    // running/recording bot's own ForagerPath instance, or QUEUE_SOURCE for the movement queue -
    // set alongside draggable in resolve(), compared in update() against lastSourceKey (the
    // source the currently-cached geometry was actually built from). A real source switch (e.g.
    // Forager Settings' Routes editor turning on) always forces a rebuild this way, even in the
    // extremely unlikely event the freshly-computed signature collides with the previous one -
    // belt-and-suspenders alongside the signature check itself, not a replacement for it.
    private static final Object QUEUE_SOURCE = new Object();
    private volatile Object sourceKey = null;
    private volatile Object lastSourceKey = null;

    // Index (within the current node list) of the "current position" node - 0 for Forager
    // Settings' Routes editor and WaypointMovementService's queue (neither has a moving "current
    // position" concept, so the first node is always treated as active, unchanged from before),
    // or gui.activeBotWaypointIndex for a running bot. Set in resolve(); read by nodeColor()/
    // nodeAlphaMult() so already-passed nodes render dimmed and the current target stays bright,
    // instead of only ever the very first node being "active" regardless of progress.
    private volatile int activeIdx = 0;
    // Dims an already-passed ROUTE node's ring/leg alpha rather than changing its hue - "stale",
    // not hidden; the route's own history stays visible, just de-emphasized against the current
    // target and what's still ahead.
    private static final double STALE_ALPHA_MULT = 0.35;

    // Forager's off-path gob-collection detour trail (gui.activeBotDetourTrail), resolved
    // separately from the main route/queue nodes above - see resolveDetourNodes(). Rendered as
    // its own disconnected chain, always this fixed color, never draggable/hoverable/pulsed.
    public static Color detourColor() {
        return(new Color(80, 220, 120));
    }

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

    /** Colour of a ROUTE node given its position relative to activeIdx and the current pointer
     *  state - an already-passed node keeps queuedColor()'s hue (dimmed separately, see
     *  nodeAlphaMult()), not a distinct "stale" hue, matching "make it transparent" rather than
     *  recolored. */
    private Color nodeColor(int idx, long id) {
        if(id == mv.wpDragId())
            return(dragColor());
        if(id == mv.wpHoverId())
            return(hoverColor());
        return((idx == activeIdx) ? activeColor() : queuedColor());
    }

    /** Alpha multiplier for a ROUTE node/leg at this index - full brightness at or ahead of
     *  activeIdx, dimmed behind it. */
    private double nodeAlphaMult(int idx) {
        return (idx < activeIdx) ? STALE_ALPHA_MULT : 1.0;
    }

    /* ------------------------------------------------------------------ *
     *  Queue resolution
     * ------------------------------------------------------------------ */

    /** True if the nodes resolve() most recently built came from a source that supports being
     *  dragged in the 3D view - see the {@link #draggable} field. */
    public boolean draggable() {
        return draggable;
    }

    /** Forager route/waypoint list -&gt; WNodes, shared by the route-editing and running/recording-
     *  bot branches of resolve() below - both are just "some ForagerPath," differing only in
     *  whether dragging one of its waypoints means anything (set by the caller via draggable). */
    private List<WNode> resolveForagerPath(nurgling.routes.ForagerPath path, MiniMap.Location sessloc) {
        if(path == null || path.waypoints.isEmpty())
            return(Collections.emptyList());
        List<WNode> ret = new ArrayList<>(path.waypoints.size());
        int num = 1;
        for(int i = 0; i < path.waypoints.size(); i++) {
            nurgling.routes.ForagerWaypoint wp = path.waypoints.get(i);
            if(wp.seg == sessloc.seg.id)
                ret.add(new WNode(i, num, wp.tc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz)));
            num++;
        }
        return(ret);
    }

    /** The path a running bot is currently executing, or one loaded/being recorded in an open
     *  bot window - same two sources NMapView's old drawBotPathOnGround screen-space overlay
     *  used to read before this class took over rendering both cases, in the same priority order. */
    private nurgling.routes.ForagerPath resolveBotOrRecordingPath(NGameUI gui) {
        nurgling.routes.ForagerPath path = gui.activeBotPath;
        if(path != null)
            return path;
        for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if(wdg instanceof nurgling.widgets.bots.PathRecordable)
                return ((nurgling.widgets.bots.PathRecordable) wdg).getCurrentLoadedPath();
        }
        return null;
    }

    /** Forager's off-path gob-collection detour trail (gui.activeBotDetourTrail - world Coord2d,
     *  most-recent-last, live-mutated by the bot thread), plus the player's own current position
     *  as its leading edge - mirrors what NMapView's old drawBotDetourTrailOnGround screen-space
     *  overlay used to draw (in yellow; this renders it in detourColor() instead). Only called
     *  for gui.activeBotPath specifically (a running bot), never for a merely-open/loaded
     *  PathRecordable window, which has no live detour of its own. Ids are negative so they can
     *  never collide with a ROUTE node's list-index id, though it's moot in practice - this
     *  overlay is never draggable while any DETOUR nodes are present (see resolve()). */
    private List<WNode> resolveDetourNodes(NGameUI gui) {
        List<Coord2d> trail = gui.activeBotDetourTrail;
        if(trail == null || trail.isEmpty())
            return Collections.emptyList();
        List<Coord2d> pts = new ArrayList<>(trail);
        Gob player = mv.player();
        if(player != null)
            pts.add(player.rc);
        if(pts.size() < 2)
            return Collections.emptyList();
        List<WNode> ret = new ArrayList<>(pts.size());
        for(int i = 0; i < pts.size(); i++)
            ret.add(new WNode(-1 - i, 0, pts.get(i), Kind.DETOUR));
        return ret;
    }

    /** Current queue in world coordinates, or an empty list when there is nothing to draw.
     *  Tries, in order: (1) Forager Settings' Routes editor, if it's showing a route
     *  (gui.activeRouteEditor) - unconditionally, bypassing showWaypointsInWorld below, since
     *  Routes editing is a deliberate, temporary context, not the general "always show my queue"
     *  preference that toggle controls; draggable. (2) a running or actively-recording bot's own
     *  path, gated by showBotPathOnGround (its own pre-existing toggle, unchanged); read-only -
     *  there's nothing sensible to write a drag back into while the bot is using this path itself.
     *  (3) WaypointMovementService's alt-click queue, gated by showWaypointsInWorld; draggable.
     *  A Forager route waypoint's own list index stands in for WaypointMovementService.Waypoint's
     *  stable id in cases (1)/(2) - safe since nothing mutates either list concurrently with a
     *  drag gesture (both are only ever touched from the UI thread, and (2)'s isn't draggable
     *  anyway). */
    private List<WNode> resolve() {
        NGameUI gui = NUtils.getGameUI();
        if(gui == null || gui.mmap == null) {
            sourceKey = null;
            return(Collections.emptyList());
        }
        MiniMap.Location sessloc = gui.mmap.sessloc;
        if(sessloc == null) {
            sourceKey = null;
            return(Collections.emptyList());
        }

        if(gui.activeRouteEditor != null) {
            draggable = true;
            activeIdx = 0;
            sourceKey = gui.activeRouteEditor;
            return resolveForagerPath(gui.activeRouteEditor.getRoute(), sessloc);
        }

        if((Boolean)NConfig.get(NConfig.Key.showBotPathOnGround)) {
            nurgling.routes.ForagerPath botPath = resolveBotOrRecordingPath(gui);
            if(botPath != null && !botPath.waypoints.isEmpty()) {
                draggable = false;
                // gui.activeBotWaypointIndex only tracks progress while THIS is the same path
                // Forager is actually running (gui.activeBotPath) - a merely open/loaded
                // PathRecordable window's path (the other resolveBotOrRecordingPath() source)
                // has no such live progress, so it falls back to the same "index 0" convention
                // used everywhere else.
                activeIdx = (botPath == gui.activeBotPath)
                        ? Math.max(0, gui.activeBotWaypointIndex) : 0;
                sourceKey = botPath;
                List<WNode> ret = new ArrayList<>(resolveForagerPath(botPath, sessloc));
                if(botPath == gui.activeBotPath)
                    ret.addAll(resolveDetourNodes(gui));
                return ret;
            }
        }

        draggable = true;
        activeIdx = 0;
        if(!(Boolean)NConfig.get(NConfig.Key.showWaypointsInWorld) || (gui.waypointMovementService == null)) {
            sourceKey = null;
            return(Collections.emptyList());
        }
        List<WaypointMovementService.Waypoint> wps = gui.waypointMovementService.snapshot();
        if(wps.isEmpty()) {
            sourceKey = null;
            return(Collections.emptyList());
        }
        sourceKey = QUEUE_SOURCE;
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
        // A progress advance (arriving at a waypoint) doesn't change any node's id/position, but
        // does change which one should render as active/stale/queued.
        h = h * 31 + activeIdx;
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
            lastSourceKey = sourceKey;
            return;
        }

        Coord2d pl = playerPos();
        long sig = signature(nodes);
        double now = Utils.rtime();
        boolean moved = (pl != null) && ((lastPlayer == null) || (lastPlayer.dist(pl) > 3.0));
        boolean sourceChanged = sourceKey != lastSourceKey;
        if(!sourceChanged && (sig == lastSig) && !(moved && (now - lastBuild > 0.2)))
            return;

        double baseZ;
        try {
            baseZ = baseZ();
        } catch(Loading l) {
            return;
        }

        Buf buf = new Buf();
        // Starts null, not pl - the player's own leg must connect to whichever node is
        // *currently* active, not unconditionally to the first node in the list. Once progress
        // moves the active node past index 0, node 0 (and any other already-passed node) is
        // stale history the player is no longer physically connected to; drawing a leg straight
        // from the player back to it produced exactly that stray line (reported live: it stayed
        // connected to the first waypoint, faded, even long after moving past it).
        Coord2d prev = null;
        Kind prevKind = Kind.ROUTE;
        for(WNode n : nodes) {
            if(n.kind != prevKind) {
                // Entering the DETOUR trail (or, in principle, leaving it) - it's a separate,
                // disconnected chain, not a continuation of the route/queue's own legs.
                prev = null;
                prevKind = n.kind;
            }
            if(n.kind == Kind.DETOUR) {
                Color dcol = detourColor();
                if(prev != null)
                    ribbon(buf, prev, n.wc, rgba(dcol, 0.95), baseZ);
                ring(buf, n.wc, rgba(dcol, 0.95), rgba(dcol, 0.18), baseZ);
                prev = n.wc;
                continue;
            }
            int idx = n.num - 1;
            Color col = nodeColor(idx, n.id);
            double mult = nodeAlphaMult(idx);
            // The chain from the previous ROUTE node still draws normally (so the stale history
            // between already-passed waypoints stays visible, dimmed) - but the leg leading into
            // the active node specifically always originates from the player's actual current
            // position instead, overriding whatever that chain would otherwise have supplied.
            Coord2d legFrom = (idx == activeIdx && pl != null) ? pl : prev;
            if(legFrom != null) {
                // The leg keeps the queue colour even when its node is grabbed, so the
                // path stays readable while a waypoint is being dragged.
                Color legc = (idx == activeIdx) ? activeColor() : queuedColor();
                ribbon(buf, legFrom, n.wc, rgba(legc, 0.95 * mult), baseZ);
            }
            ring(buf, n.wc, rgba(col, 0.95 * mult), rgba(col, 0.18 * mult), baseZ);
            prev = n.wc;
        }

        setGeometry(buf);
        lastSig = sig;
        lastPlayer = pl;
        lastBuild = now;
        lastSourceKey = sourceKey;
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
                // Out of view: not clickable, and only the first few ROUTE nodes get an edge
                // arrow - see drawEdgeArrows. Nodes come in queue order, so keeping the head of
                // the list keeps the lowest-numbered ones. DETOUR nodes never get one - they're
                // ephemeral breadcrumbs, not route stops worth pointing at off-screen.
                n.sc = null;
                if(n.kind == Kind.ROUTE) {
                    if(offscreen == null)
                        offscreen = new ArrayList<>(MAX_EDGE_ARROWS);
                    if(offscreen.size() < MAX_EDGE_ARROWS)
                        offscreen.add(n);
                }
                continue;
            }

            if(n.kind == Kind.DETOUR) {
                // The 3D ring (already drawn in update()) is enough - no numbered label, pulse,
                // or ETA readout for an ephemeral breadcrumb.
                continue;
            }

            int idx = n.num - 1;
            Color col = nodeColor(idx, n.id);
            boolean isActive = (idx == activeIdx);
            if(isActive)
                pulse(g, state, va, n, z);

            // stem from the ground point up to the plate
            g.chcolor(0, 0, 0, 160);
            g.line(n.sc, top, 3);
            g.chcolor(col);
            g.line(n.sc, top, 1);

            plate(g, top, n.num, col);

            if(isActive)
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
        Color col = nodeColor(n.num - 1, n.id);
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
