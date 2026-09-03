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
     *  or hoverable/pulsed like a ROUTE node. DETOUR_TARGET is the single node at the end of that
     *  same chain representing the actionable gob currently being walked to/interacted with
     *  (gui.activeBotDetourTarget) - rendered in activeColor() like a ROUTE node's active
     *  waypoint, with its own live leg from the player, so "what is Forager actually grabbing
     *  right now" reads the same way "what waypoint is it heading to" does. */
    public enum Kind { ROUTE, DETOUR, DETOUR_TARGET }

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
    // resolve() itself isn't free even when nothing has changed - the activeRouteEditor/
    // activeBotPath branches allocate and walk every waypoint - and update()/draw2d() each call
    // it once per frame independently (tick pass then render pass), so without this it ran twice
    // per frame unconditionally, before the signature check below ever gets a chance to skip the
    // *expensive* geometry rebuild. update() runs first each frame (NMapView's tick hook, ahead
    // of the render pass draw2d() is called from), so draw2d() just reuses what update() already
    // resolved instead of resolving a second time.
    private List<WNode> lastResolvedNodes = Collections.emptyList();
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

    // Indices (within the current node list) Forager marked as unreachable this run
    // (gui.activeBotFailedWaypoints) - checked by nodeColor()/nodeAlphaMult() ahead of the
    // normal active/stale/queued logic, so a skipped waypoint reads as "failed" (red, dimmed)
    // regardless of where it falls relative to the bot's current progress. Empty for every
    // source except a running bot (gui.activeBotPath), which is the only one that ever marks a
    // waypoint unreachable.
    private volatile java.util.Set<Integer> failedIdx = Collections.emptySet();
    private static final double FAILED_ALPHA_MULT = 0.4;

    public static Color failedColor() {
        return(new Color(224, 64, 64));
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
     *  recolored. A node Forager marked unreachable this run is the one exception that does get
     *  its own hue (red), ahead of every other rule including drag/hover. */
    private Color nodeColor(int idx, long id) {
        if(failedIdx.contains(idx))
            return(failedColor());
        if(id == mv.wpDragId())
            return(dragColor());
        if(id == mv.wpHoverId())
            return(hoverColor());
        return((idx == activeIdx) ? activeColor() : queuedColor());
    }

    /** Alpha multiplier for a ROUTE node/leg at this index - full brightness at or ahead of
     *  activeIdx, dimmed behind it; a failed node gets its own (slightly heavier) dimming
     *  regardless of activeIdx, so a skipped waypoint reads as "skipped" whether it's already
     *  behind the bot or still ahead of it. */
    private double nodeAlphaMult(int idx) {
        if(failedIdx.contains(idx))
            return FAILED_ALPHA_MULT;
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

    /** The path loaded/being recorded in an open bot window (e.g. TrufflePigHunter) - checked
     *  only once gui.activeBotPath itself is already known null (see resolve(), which handles
     *  that case separately/unconditionally rather than through this method). */
    private nurgling.routes.ForagerPath resolveBotOrRecordingPath(NGameUI gui) {
        for(Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if(wdg instanceof nurgling.widgets.bots.PathRecordable)
                return ((nurgling.widgets.bots.PathRecordable) wdg).getCurrentLoadedPath();
        }
        return null;
    }

    /** Forager's off-path gob-collection detour trail (gui.activeBotDetourTrail - world Coord2d,
     *  most-recent-last, live-mutated by the bot thread) as DETOUR nodes, plus - if set -
     *  gui.activeBotDetourTarget (the actionable gob currently being walked to/interacted with)
     *  as one final DETOUR_TARGET node. The player's own live position is deliberately not
     *  included here - update()'s player-leg (same mechanism the main route's active waypoint
     *  uses) supplies that separately, so it's not baked into this list's own signature/chain.
     *  Only called for gui.activeBotPath specifically (a running bot), never for a merely-open/
     *  loaded PathRecordable window, which has no live detour of its own. Ids are negative so
     *  they can never collide with a ROUTE node's list-index id, though it's moot in practice -
     *  this overlay is never draggable while any DETOUR/DETOUR_TARGET nodes are present (see
     *  resolve()). */
    private List<WNode> resolveDetourNodes(NGameUI gui) {
        List<Coord2d> trail = gui.activeBotDetourTrail;
        boolean hasTrail = trail != null && !trail.isEmpty();
        Coord2d target = gui.activeBotDetourTarget;
        if(!hasTrail && target == null)
            return Collections.emptyList();
        List<WNode> ret = new ArrayList<>();
        int id = -1;
        if(hasTrail) {
            int n = trail.size();
            // If the target is literally the most recent breadcrumb (e.g. returnToPathViaBreadcrumbs
            // walking back to it), don't render that position twice - the DETOUR_TARGET node below
            // already covers it, in its own distinct color.
            if(target != null && trail.get(n - 1).equals(target))
                n--;
            for(int i = 0; i < n; i++)
                ret.add(new WNode(id--, 0, trail.get(i), Kind.DETOUR));
        }
        if(target != null)
            ret.add(new WNode(id, 0, target, Kind.DETOUR_TARGET));
        return ret;
    }

    /** Current queue in world coordinates, or an empty list when there is nothing to draw.
     *  Tries, in order: (1) Forager Settings' Routes editor, if it's showing a route
     *  (gui.activeRouteEditor) - unconditionally, bypassing showWaypointsInWorld below, since
     *  Routes editing is a deliberate, temporary context, not the general "always show my queue"
     *  preference that toggle controls; draggable. (2) a bot actually running (gui.activeBotPath)
     *  - also unconditional: unlike case (3) below, Forager's own bot-launch window has no UI
     *  exposing showBotPathOnGround at all (it doesn't extend PathBotWindow, the only place that
     *  checkbox lives), so gating this the same way left it silently never rendering while the
     *  bot ran; read-only, with progress tracking (activeIdx/failedIdx) and its own detour trail.
     *  (3) a merely open/loaded PathRecordable window's path (e.g. TrufflePigHunter mid-record) -
     *  gated by showBotPathOnGround, which is that window's own explicit checkbox, still
     *  respected here; read-only, no progress tracking (no live "current position" for it).
     *  (4) WaypointMovementService's alt-click queue, gated by showWaypointsInWorld; draggable.
     *  A Forager route waypoint's own list index stands in for WaypointMovementService.Waypoint's
     *  stable id in cases (1)/(2)/(3) - safe since nothing mutates any of those lists concurrently
     *  with a drag gesture (all are only ever touched from the UI thread, and (2)/(3) aren't
     *  draggable anyway). */
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
            failedIdx = Collections.emptySet();
            sourceKey = gui.activeRouteEditor;
            return resolveForagerPath(gui.activeRouteEditor.getRoute(), sessloc);
        }

        if(gui.activeBotPath != null && !gui.activeBotPath.waypoints.isEmpty()) {
            draggable = false;
            activeIdx = Math.max(0, gui.activeBotWaypointIndex);
            failedIdx = (gui.activeBotFailedWaypoints != null) ? gui.activeBotFailedWaypoints : Collections.emptySet();
            sourceKey = gui.activeBotPath;
            List<WNode> ret = new ArrayList<>(resolveForagerPath(gui.activeBotPath, sessloc));
            ret.addAll(resolveDetourNodes(gui));
            return ret;
        }

        if((Boolean)NConfig.get(NConfig.Key.showBotPathOnGround)) {
            nurgling.routes.ForagerPath recPath = resolveBotOrRecordingPath(gui);
            if(recPath != null && !recPath.waypoints.isEmpty()) {
                draggable = false;
                activeIdx = 0;
                failedIdx = Collections.emptySet();
                sourceKey = recPath;
                return resolveForagerPath(recPath, sessloc);
            }
        }

        draggable = true;
        activeIdx = 0;
        failedIdx = Collections.emptySet();
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
        // Newly marking a waypoint unreachable doesn't change its id/position either, but does
        // change its color - sum rather than order-sensitive-hash since failedIdx is an
        // unordered Set.
        int failedSum = 0;
        for(int i : failedIdx)
            failedSum += i;
        h = h * 31 + failedSum;
        h = h * 31 + failedIdx.size();
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
        lastResolvedNodes = nodes;
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

        // While a detour is in progress, the player isn't actually walking toward the main
        // route's active waypoint at all - that leg (drawn below) would otherwise stretch a
        // long, jarring line from wherever the player currently is (off chasing a gob) back to
        // a route waypoint they're not really heading to right now. The DETOUR_TARGET node gets
        // its own, more accurate version of this same leg instead (see below).
        boolean detouring = false;
        for(WNode n : nodes) {
            if(n.kind == Kind.DETOUR_TARGET) {
                detouring = true;
                break;
            }
        }

        Buf buf = new Buf();
        // Starts null, not pl - the route's own chain (drawn below, always queuedColor()) already
        // covers node 0's leg-in-from-nothing case correctly by simply not drawing one, same as
        // any other node with nothing before it. The player's own leg into the active node is a
        // second, separate line (see inside the loop) - not a substitute for this chain, which
        // stays intact end to end (dimmed where stale) regardless of where the player actually is.
        Coord2d prev = null;
        boolean prevIsDetourFamily = false;
        for(WNode n : nodes) {
            boolean isDetourFamily = (n.kind == Kind.DETOUR || n.kind == Kind.DETOUR_TARGET);
            if(isDetourFamily != prevIsDetourFamily) {
                // Entering (or, in principle, leaving) the DETOUR/DETOUR_TARGET chain - it's
                // separate and disconnected from the route/queue's own legs, but DETOUR and
                // DETOUR_TARGET are one continuous chain with each other (breadcrumbs leading up
                // to the gob currently being grabbed), so this only resets between family
                // boundaries, not on every DETOUR->DETOUR_TARGET transition within it.
                prev = null;
                prevIsDetourFamily = isDetourFamily;
            }
            if(n.kind == Kind.DETOUR) {
                Color dcol = detourColor();
                if(prev != null)
                    ribbon(buf, prev, n.wc, rgba(dcol, 0.95), baseZ);
                ring(buf, n.wc, rgba(dcol, 0.95), rgba(dcol, 0.18), baseZ);
                prev = n.wc;
                continue;
            }
            if(n.kind == Kind.DETOUR_TARGET) {
                // Ring is blue, mirroring the main route's own active-node treatment - "what is
                // Forager actually grabbing right now". The chain leg from the last breadcrumb
                // (prev), if there is one, stays detourColor() (green) instead of blue - it's
                // part of the breadcrumb trail's own chain, same as how the main route's chain
                // never turns blue for the leg leading into its active node either (see below);
                // only the live leg from the player is blue, "this is fine and good" per direct
                // feedback on this exact split.
                Color acol = activeColor();
                if(prev != null)
                    ribbon(buf, prev, n.wc, rgba(detourColor(), 0.95), baseZ);
                if(pl != null)
                    ribbon(buf, pl, n.wc, rgba(acol, 0.95), baseZ);
                ring(buf, n.wc, rgba(acol, 0.95), rgba(acol, 0.18), baseZ);
                prev = n.wc;
                continue;
            }
            int idx = n.num - 1;
            Color col = nodeColor(idx, n.id);
            double mult = nodeAlphaMult(idx);
            if(prev != null) {
                // Always the route's own colour (dimmed when stale) - this chain represents the
                // route itself, not "where the player currently is", so it never turns active/blue
                // even for the leg leading into the active node; that's the separate player leg
                // just below instead. Keeps the route fully connected end to end regardless of
                // where the player actually happens to be relative to it. The one exception is a
                // failed node - its incoming leg shares its own red, not the route's yellow, so
                // the whole stop reads as "skipped" rather than just the ring.
                Color legc = failedIdx.contains(idx) ? failedColor() : queuedColor();
                ribbon(buf, prev, n.wc, rgba(legc, 0.95 * mult), baseZ);
            }
            if(idx == activeIdx && pl != null && !detouring) {
                // Second, additional leg: live, always full-brightness, tracks the player's
                // actual current position to wherever they're really heading - coexists with the
                // route-chain leg above rather than replacing it (reported live: replacing it left
                // a visible gap in the route chain right where the player currently is). Skipped
                // entirely while detouring - see the `detouring` comment above.
                ribbon(buf, pl, n.wc, rgba(activeColor(), 0.95), baseZ);
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
        List<WNode> nodes = lastResolvedNodes;
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

            if(n.kind == Kind.DETOUR_TARGET) {
                // Pulses like the route's own active node - draws the eye to "what is Forager
                // actually grabbing right now" - but no numbered plate/ETA, it isn't a numbered
                // route stop.
                pulse(g, state, va, n, z, activeColor());
                continue;
            }

            int idx = n.num - 1;
            Color col = nodeColor(idx, n.id);
            boolean isActive = (idx == activeIdx);
            if(isActive)
                pulse(g, state, va, n, z, col);

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
    private void pulse(GOut g, Pipe state, Area va, WNode n, double z, Color col) {
        // While the active waypoint is being dragged the character is re-routing to it,
        // so the ping speeds up and brightens - the visible answer to the drag.
        boolean rerouting = (n.id == mv.wpDragId());
        double period = rerouting ? 0.5 : 1.3;
        double t = (Utils.rtime() % period) / period;
        double r = RING_OUT + (t * 12.0);
        int a = (int)((rerouting ? 220 : 140) * (1 - t));
        if(a < 8)
            return;
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
