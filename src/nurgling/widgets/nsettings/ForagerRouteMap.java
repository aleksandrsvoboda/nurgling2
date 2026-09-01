package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.overlays.NWaypointOverlay;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerWaypoint;
import nurgling.tools.MilestoneRegistry;
import nurgling.widgets.MilestoneDestinationChooser;
import nurgling.widgets.NMiniMap;
import nurgling.widgets.WaypointStepsWindow;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small, dedicated terrain-only map for creating/editing a {@link ForagerPath} directly, rather
 * than overlaying a route on the real map (which always shows the player, other gobs, and grid
 * lines - none of which help while confirming a route "sees everything" it should). Reuses
 * {@link NMiniMap}'s real terrain rendering ({@link #drawmap}) but skips every other layer
 * (icons/markers/player/grid/view-radius) via a custom {@link #drawparts} override, and adds its
 * own waypoint/view-zone/exclusion overlays and mouse handling on top.
 * <p>
 * Interaction: plain left-click on empty space adds a waypoint at the end of the route;
 * left-click-and-drag an existing waypoint's node moves it; plain right-click one deletes it.
 * Shift+left-click-and-drag paints tiles into the route's Exclusion set (a brush, sized to match
 * the viewable-zone box - not a fixed rectangle, tiles accumulate as the button stays held);
 * Shift+right-click-and-drag erases them instead. A grey square tracks the mouse at all times
 * (only while actually over this widget) to show the brush footprint. Free pan (drag empty
 * space) and zoom (scroll) both work normally, same as any other minimap.
 */
public class ForagerRouteMap extends NMiniMap {

    private ForagerPath route;

    /** Fired after any edit (add/move/delete waypoint, paint/erase exclusion tiles) so the
     *  owning panel can mark its state dirty and know to persist on save. */
    public Runnable onChange = null;

    /** Set by the owning panel - invoked when the on-map "discard unsaved changes" button (top
     *  right corner, only shown while dirty) is clicked. */
    public Runnable onResetRequested = null;

    // Whether route has live edits (waypoint/exclusion/cliff-toggle) not yet reflected in the
    // last load()/setRoute() baseline - drives the on-map reset button's visibility. Deliberately
    // scoped to just this widget's own edits, not the caps text fields elsewhere in the panel
    // (those already follow the panel-wide Save/Cancel convention).
    private boolean dirty = false;

    private int draggingWaypointIndex = -1;
    private UI.Grab dragGrab = null;
    private boolean painting = false;
    private boolean erasing = false;
    private Coord hoverC = null;

    public static final int DEFAULT_BRUSH_SIZE_TILES = 10;
    // Independent of viewZoneTileSize() - user-adjustable, only used while the Exclusion brush
    // is active (Shift held). The un-shifted cursor preview still uses viewZoneTileSize().
    private int brushSizeTiles = DEFAULT_BRUSH_SIZE_TILES;

    public ForagerRouteMap(Coord sz, MapFile file) {
        super(sz, file);
        // Without an initial location, base MiniMap's dloc (what drawmap() actually renders)
        // stays null forever - tick() only ever resolves it via center()/follow(), neither of
        // which anything else here calls (unlike NCornerMiniMap, which does this same call in
        // its own constructor). follow() just gives an initial player-centered view; the base
        // class's own drag handling already flips follow=false the moment the user pans away
        // (MiniMap.mousemove), so this doesn't fight free pan/zoom afterward.
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && gui.map != null) {
            follow(new MapLocator(gui.map));
        }
    }

    /** For edits the panel makes directly to the route model outside this widget (currently just
     *  the Avoid cliffs checkbox) - same unsaved-changes bookkeeping as an in-map edit. */
    public void markDirty() {
        dirty = true;
    }

    public void setRoute(ForagerPath route) {
        this.route = route;
        cancelDrags();
        invalidateExclusionCache();
        dirty = false;
    }

    public ForagerPath getRoute() {
        return route;
    }

    /** Same add-a-waypoint behavior as a plain left-click on this widget (see {@link #clickloc}),
     *  reachable from outside it - lets NMapView's Alt+Left-click hook add a waypoint here from a
     *  real-world click while this route is the one active in Forager Settings' Routes editor. */
    public void addWaypointFromWorld(MiniMap.Location loc) {
        if (route == null) return;
        route.addWaypoint(new ForagerWaypoint(loc));
        notifyChanged();
    }

    /** Called by the owning panel right after a successful save - clears the unsaved-changes
     *  indicator without touching route/waypoints/exclusion state (unlike setRoute(), this isn't
     *  a reload, currentRoute is still the same object, it's just no longer ahead of disk). */
    public void markClean() {
        dirty = false;
    }

    // paintOrEraseAt() does an O(size^2) tile loop on every mousemove while dragging - unbounded,
    // a user-typed value here could stall the UI thread for as long as the brush is held (e.g.
    // 1000 -> 1,000,000 Coord/HashSet ops per mousemove). 200 caps the worst case at 40,000
    // tiles/call, the same per-frame budget this codebase already treats as acceptable elsewhere.
    private static final int MAX_BRUSH_SIZE_TILES = 200;

    public void setBrushSizeTiles(int tiles) {
        this.brushSizeTiles = Utils.clip(tiles, 1, MAX_BRUSH_SIZE_TILES);
    }

    private void cancelDrags() {
        if (dragGrab != null) {
            dragGrab.remove();
            dragGrab = null;
        }
        draggingWaypointIndex = -1;
        painting = false;
        erasing = false;
    }

    private void notifyChanged() {
        dirty = true;
        if (onChange != null) {
            onChange.run();
        }
    }

    // Terrain only, plus this widget's own overlays - deliberately skips drawmarkers/drawicons
    // (gob icons), drawparty (player), drawgrid(GOut) (grid-line overlay), and drawview (the
    // "explored view radius" box) so this widget's look never depends on the user's other
    // minimap settings (NConfig.Key.showGrid/showView are global toggles shared by every minimap
    // instance - just never calling the methods that read them is simpler and always-off here).
    @Override
    public void drawparts(GOut g) {
        drawmap(g);
        drawWaypointViewZones(g);
        drawExclusion(g);
        drawMilestones(g);
        drawRouteWaypoints(g);
        drawBrushCursor(g);
        drawResetButton(g);
    }

    private static final int TILE_SQUARE_ALPHA = 110;

    // Marks a waypoint that has one or more attached steps (Ctrl+right-click to edit) - takes
    // priority over the usual active/queued colors so a route's "special" stops are obvious at a
    // glance, but still yields to the drag-in-progress color.
    private static final Color STEPS_COLOR = new Color(40, 200, 90);

    // Deliberately the exact same rendering NMiniMap.drawQueuedWaypoints already uses for the
    // real map's alt+left-click movement-queue waypoints (dashed crawling legs, circular numbered
    // plates, a pulsing ring on the route's start point) - reused via NMiniMap's own dashLine/
    // ringOutline/getWaypointLabel helpers (made protected for this) rather than reinvented, per
    // direct instruction. Fixed pixel-size nodes (not tied to tile size) so they stay comfortably
    // clickable regardless of zoom level.
    private void drawRouteWaypoints(GOut g) {
        if (route == null || dloc == null || route.waypoints.isEmpty()) return;
        Coord hsz = sz.div(2);
        int margin = UI.scale(12);

        double phase = Utils.rtime() * UI.scale(16);
        Coord prevC = null;
        ForagerWaypoint prevWp = null;
        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) {
                prevC = null;
                prevWp = null;
                continue;
            }
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            // dashLine/fellipse (unlike image()-based draws) don't respect the ancestor GOut
            // clip chain, so - same as the view-zone/exclusion/cliff boxes - they need an
            // explicit on-screen check derived from g's actual visible window, not just this
            // widget's full declared size (which can be bigger than what's actually visible
            // when this widget is partially scrolled out of the settings panel's Scrollport).
            if (prevC != null && (onScreen(g, prevC, margin) || onScreen(g, c, margin))) {
                boolean milestoneLeg = wp.milestoneHash != null && wp.milestoneHash.equals(prevWp.milestoneHash);
                Color lc = milestoneLeg ? MILESTONE_ACTIVE_LINK_COLOR
                        : (i == 1) ? NWaypointOverlay.activeColor() : NWaypointOverlay.queuedColor();
                g.chcolor(lc.getRed(), lc.getGreen(), lc.getBlue(), 200);
                dashLine(g, prevC, c, phase, 2);
            }
            prevC = c;
            prevWp = wp;
        }

        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) continue;
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (!onScreen(g, c, margin)) continue;

            boolean first = (i == 0);
            boolean dragging = (i == draggingWaypointIndex);
            boolean hasSteps = wp.steps != null && !wp.steps.isEmpty();
            Color col = dragging ? NWaypointOverlay.dragColor()
                    : hasSteps ? STEPS_COLOR
                    : (first ? NWaypointOverlay.activeColor() : NWaypointOverlay.queuedColor());

            if (first) {
                double t = (Utils.rtime() % 1.3) / 1.3;
                int a = (int) (150 * (1 - t));
                if (a > 8) {
                    g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
                    ringOutline(g, c, (int) (UI.scale(6) + t * UI.scale(10)), 2);
                }
            }

            if (wp.milestoneHash != null) {
                drawMilestoneIcon(g, c, dragging ? NWaypointOverlay.dragColor() : MILESTONE_ACTIVE_LINK_COLOR);
                continue;
            }

            int radius = UI.scale((first || dragging) ? 7 : 5);
            g.chcolor(0, 0, 0, 210);
            g.fellipse(c, new Coord(radius + 1, radius + 1));
            g.chcolor(col);
            g.fellipse(c, new Coord(radius, radius));
            g.chcolor(10, 14, 16, 255);
            g.aimage(getWaypointLabel(i + 1).tex(), c, 0.5, 0.5);
        }
        g.chcolor();
    }

    private static final Color MILESTONE_COLOR = new Color(230, 200, 40);
    private static final Color MILESTONE_LINK_COLOR = new Color(70, 130, 230);   // unspliced preview line
    private static final Color MILESTONE_ACTIVE_LINK_COLOR = new Color(230, 200, 40); // spliced into the route
    private static final int MILESTONE_ICON_RADIUS = 6;

    private void drawMilestoneIcon(GOut g, Coord c, Color col) {
        int r = UI.scale(MILESTONE_ICON_RADIUS);
        g.chcolor(0, 0, 0, 210);
        g.frect(c.sub(r + 1, r + 1), new Coord((r + 1) * 2, (r + 1) * 2));
        g.chcolor(col);
        g.frect(c.sub(r, r), new Coord(r * 2, r * 2));
    }

    /** Recorded milestones (MilestoneRegistry) not yet spliced into the current route: a static
     *  marker at the milestone's own location, one at each recorded destination (whichever are
     *  currently on screen), and a blue dashed line between a source/destination pair when both
     *  are visible at once - purely informational until the source marker is left-clicked (see
     *  unsplicedMilestoneSourceAt()/spliceMilestone()), at which point it becomes two real,
     *  linked waypoints in the route and drawRouteWaypoints() takes over rendering it instead
     *  (in the active yellow color) - so a spliced milestone's original entry is skipped here
     *  entirely to avoid drawing it twice. */
    private void drawMilestones(GOut g) {
        if (dloc == null) return;
        Coord hsz = sz.div(2);
        int margin = UI.scale(12);

        for (Map.Entry<String, Object> e : MilestoneRegistry.allMilestones().entrySet()) {
            String hash = e.getKey();
            if (isMilestoneSpliced(hash)) continue;
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            MilestoneRegistry.Location srcLoc = MilestoneRegistry.getMilestoneLocation(entry);
            if (srcLoc == null) continue;

            Coord srcC = (srcLoc.seg == dloc.seg.id) ? srcLoc.tc.sub(dloc.tc).div(scalef()).add(hsz) : null;
            boolean srcOnScreen = srcC != null && onScreen(g, srcC, margin);

            for (Map<String, Object> dest : MilestoneRegistry.getDestinations(entry)) {
                MilestoneRegistry.Location destLoc = MilestoneRegistry.getDestinationLocation(dest);
                if (destLoc == null || destLoc.seg != dloc.seg.id) continue;
                Coord destC = destLoc.tc.sub(dloc.tc).div(scalef()).add(hsz);
                boolean destOnScreen = onScreen(g, destC, margin);

                // Used to require BOTH ends on screen at once to draw the link at all, so it only
                // ever appeared zoomed out (or panned) far enough to fit the whole thing in view.
                // Same OR (not AND) leniency drawRouteWaypoints() already uses for route legs
                // just below - dashLine() clips to this widget's own declared size, not to g's
                // actual visible window within the settings panel's Scrollport (see that method's
                // comment), so this still needs an onScreen check on at least one end rather than
                // dropping it outright; it just no longer needs both.
                if (srcC != null && (srcOnScreen || destOnScreen)) {
                    g.chcolor(MILESTONE_LINK_COLOR.getRed(), MILESTONE_LINK_COLOR.getGreen(),
                            MILESTONE_LINK_COLOR.getBlue(), 200);
                    dashLine(g, srcC, destC, 0, 2);
                }
                if (destOnScreen) {
                    drawMilestoneIcon(g, destC, MILESTONE_COLOR);
                }
            }

            if (srcOnScreen) {
                drawMilestoneIcon(g, srcC, MILESTONE_COLOR);
            }
        }
        g.chcolor();
    }

    /** True once a milestone has been spliced into the current route (see spliceMilestone()) -
     *  both its anchor waypoints carry this hash. */
    private boolean isMilestoneSpliced(String hash) {
        if (route == null) return false;
        for (ForagerWaypoint wp : route.waypoints) {
            if (hash.equals(wp.milestoneHash)) return true;
        }
        return false;
    }

    /** Hit-test against an unspliced milestone's own (source) marker only - destinations aren't
     *  independently clickable, and a milestone that's already spliced into the route is no
     *  longer drawn by drawMilestones() at all (drawRouteWaypoints/waypointIndexAt own it then). */
    private String unsplicedMilestoneSourceAt(Coord c) {
        if (dloc == null) return null;
        Coord hsz = sz.div(2);
        double bestDist = UI.scale(MILESTONE_ICON_RADIUS + 3);
        String best = null;
        for (Map.Entry<String, Object> e : MilestoneRegistry.allMilestones().entrySet()) {
            String hash = e.getKey();
            if (isMilestoneSpliced(hash) || !(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            MilestoneRegistry.Location loc = MilestoneRegistry.getMilestoneLocation(entry);
            if (loc == null || loc.seg != dloc.seg.id) continue;
            Coord sc = loc.tc.sub(dloc.tc).div(scalef()).add(hsz);
            double d = sc.dist(c);
            if (d <= bestDist) {
                bestDist = d;
                best = hash;
            }
        }
        return best;
    }

    /** Left-click on an unspliced milestone's source marker: splices it into the route as two
     *  linked waypoints (the milestone's own location, then its destination) appended after
     *  whatever the route's current last waypoint is - so any further waypoint the user adds
     *  naturally continues from the destination side, matching normal append-at-end semantics.
     *  A single-destination milestone splices immediately; a multi-destination one asks first. */
    private void spliceMilestone(String hash) {
        Map<String, Object> entry = MilestoneRegistry.getMilestone(hash);
        if (entry == null || route == null) return;
        List<Map<String, Object>> destinations = MilestoneRegistry.getDestinations(entry);
        if (destinations.isEmpty()) return;

        if (destinations.size() == 1) {
            doSplice(hash, entry, destinations.get(0));
            return;
        }

        MilestoneDestinationChooser chooser = new MilestoneDestinationChooser(hash, destinations,
                (idx, dest) -> doSplice(hash, entry, dest));
        NUtils.getGameUI().add(chooser, UI.scale(200, 200));
        chooser.show();
    }

    private void doSplice(String hash, Map<String, Object> milestoneEntry, Map<String, Object> dest) {
        MilestoneRegistry.Location srcLoc = MilestoneRegistry.getMilestoneLocation(milestoneEntry);
        MilestoneRegistry.Location destLoc = MilestoneRegistry.getDestinationLocation(dest);
        if (srcLoc == null || destLoc == null) return;

        ForagerWaypoint srcWp = new ForagerWaypoint(srcLoc.seg, srcLoc.tc);
        srcWp.milestoneHash = hash;
        ForagerWaypoint destWp = new ForagerWaypoint(destLoc.seg, destLoc.tc);
        destWp.milestoneHash = hash;

        route.addWaypoint(srcWp);
        route.addWaypoint(destWp);
        notifyChanged();
    }

    /** Right-click on either anchor of a spliced milestone: removes *both* linked waypoints
     *  (not just the one clicked) and reverts the milestone to its unspliced preview - the blue
     *  dashed "not part of the route" rendering drawMilestones() already gives any milestone with
     *  no matching waypoints in the route. Plain right-click-delete on a normal waypoint still
     *  only removes that one waypoint, unchanged. */
    private void unspliceMilestone(String hash) {
        if (route == null) return;
        route.waypoints.removeIf(wp -> hash.equals(wp.milestoneHash));
    }

    // Same box the real map draws around the player to show explored/render distance
    // (NMiniMap.drawview) - same geometry (grid-aligned, 9 small-grids wide/tall), just recomputed
    // per waypoint instead of the player, and in a distinct dark-blue tint. Recomputed fresh every
    // frame from each waypoint's current position, so dragging a waypoint moves its zone with it.
    private static final Color VIEWZONE_BG = new Color(25, 60, 170, 70);
    private static final Color VIEWZONE_BORDER = new Color(25, 60, 170, 180);

    /** Tile footprint of the same "explored/render distance" box the real map draws around the
     *  player (NMiniMap.drawview) - 9 small-grids square. Shared by the view-zone rendering below
     *  and the Exclusion brush, which the user asked to be sized the same as the visible area. */
    private Coord viewZoneTileSize() {
        return _sgridsz.mul(9).div(MCache.tilesz.floor());
    }

    /** Grid-snapped view-zone box (ul, br - in dloc-relative segment-tile space, br already
     *  covers the far edge) for an arbitrary segment-tile point - same formula NMiniMap.drawview()
     *  uses for the player, just parameterized so both a real waypoint (drawWaypointViewZones)
     *  and the hover cursor preview (drawBrushCursor) snap to the exact same grid the real
     *  view-zone box would land on, rather than the cursor following the mouse smoothly while
     *  the real zone jumps in whole grid-cell steps - lining up several waypoints' zones edge to
     *  edge is much easier when the preview already shows where they'll actually land. Returns
     *  null if tc's segment doesn't match the live sessloc (nothing to resolve world coords
     *  against). */
    private Coord[] viewZoneBoxTiles(long seg, Coord tc) {
        if (sessloc == null || seg != sessloc.seg.id) return null;
        Coord2d worldC = tc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz);
        Coord2d gridsz2d = new Coord2d(_sgridsz);
        Coord ul = worldC.floor(gridsz2d).sub(4, 4).mul(gridsz2d).floor(MCache.tilesz).add(sessloc.tc);
        Coord br = ul.add(viewZoneTileSize()).add(1, 1);
        return new Coord[]{ul, br};
    }

    private void drawWaypointViewZones(GOut g) {
        if (route == null || dloc == null || sessloc == null) return;
        Coord hsz = sz.div(2);

        for (ForagerWaypoint wp : route.waypoints) {
            if (wp.seg != dloc.seg.id) continue;

            // Only draw a waypoint's zone if the waypoint itself is on screen - same check
            // drawRouteWaypoints uses for the waypoint marker. Without this, a waypoint sitting
            // just off-screen still had its zone square (much bigger than the marker) poking
            // into view, visible even though "you can't see that waypoint on the map".
            Coord wpC = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (!onScreen(g, wpC, UI.scale(12))) continue;

            Coord[] box = viewZoneBoxTiles(wp.seg, wp.tc);
            if (box == null) continue;
            Coord screenUL = box[0].sub(dloc.tc).div(scalef()).add(hsz);
            Coord screenBR = box[1].sub(dloc.tc).div(scalef()).add(hsz);

            Coord[] clipped = clampRect(g, screenUL, screenBR);
            if (clipped == null) continue;
            g.chcolor(VIEWZONE_BG);
            g.frect(clipped[0], clipped[1].sub(clipped[0]));
            g.chcolor(VIEWZONE_BORDER);
            g.rect(clipped[0], clipped[1].sub(clipped[0]));
        }
        g.chcolor();
    }

    /** Intersects [ul, br) with g's own current clip window (translated into this widget's local
     *  coordinate space), or null if there's no overlap. Needed because fill/outline primitives
     *  (frect/rect) don't get clipped by the ancestor GOut chain the way image() draws do -
     *  without this, a rect far bigger than this widget (the viewable-zone/brush boxes can be,
     *  at ~81 tiles) visibly bleeds into whatever's drawn around it. Deriving the window from g
     *  (g.ul/g.br minus g.tx) rather than hardcoding [0,0]-[sz.x,sz.y] matters because this
     *  widget itself can be partially scrolled out of the settings panel's own Scrollport - the
     *  visible portion can be smaller than this widget's full declared size, especially at the
     *  bottom edge. */
    private Coord[] clampRect(GOut g, Coord ul, Coord br) {
        Coord winUl = g.ul.sub(g.tx);
        Coord winBr = g.br.sub(g.tx);
        Coord cul = new Coord(Utils.clip(ul.x, winUl.x, winBr.x), Utils.clip(ul.y, winUl.y, winBr.y));
        Coord cbr = new Coord(Utils.clip(br.x, winUl.x, winBr.x), Utils.clip(br.y, winUl.y, winBr.y));
        if (cbr.x <= cul.x || cbr.y <= cul.y) return null;
        return new Coord[]{cul, cbr};
    }

    /** Whether point c (plus margin) falls within g's actual visible window - the same "derive
     *  from g.ul/g.br/g.tx, not this widget's full declared sz" reasoning as clampRect, for
     *  draw calls (fellipse/line-based dashLine, unlike image()) that skip entirely rather than
     *  partially-clip, so route waypoint markers/legs don't bleed past a scrolled-off bottom
     *  edge the same way the view-zone/exclusion/cliff boxes did before clampRect existed. */
    private boolean onScreen(GOut g, Coord c, int margin) {
        Coord winUl = g.ul.sub(g.tx);
        Coord winBr = g.br.sub(g.tx);
        return c.x >= winUl.x - margin && c.x <= winBr.x + margin
                && c.y >= winUl.y - margin && c.y <= winBr.y + margin;
    }

    // Exclusion tiles - freeform brush-painted, not a fixed rectangle (see paintOrEraseAt).
    //
    // A single brush application covers ~viewZoneTileSize() tiles (thousands), and a drag stroke
    // re-applies it on every mousemove - drawing one frect() per individual tile every frame
    // (as this used to) meant tens of thousands of draw calls per frame after even a short
    // stroke, which is what was tanking performance. Instead, cache the tile set merged into
    // horizontal runs (rebuilt only when the set actually changes - see invalidateExclusionCache,
    // called from paintOrEraseAt - not on every frame), and draw one frect() per run: a solid
    // painted blob becomes one rect per row instead of one rect per tile.
    private long exclusionCacheSeg = Long.MIN_VALUE;
    private boolean exclusionDirty = true;
    private final List<int[]> exclusionRuns = new ArrayList<>(); // {y, x1, x2} inclusive

    private void invalidateExclusionCache() {
        exclusionDirty = true;
    }

    private void rebuildExclusionRunsIfNeeded() {
        if (dloc != null && !exclusionDirty && exclusionCacheSeg == dloc.seg.id) return;
        exclusionRuns.clear();
        if (route != null && dloc != null) {
            Set<Coord> tiles = route.exclusionTiles.get(dloc.seg.id);
            if (tiles != null) {
                buildRuns(tiles, exclusionRuns);
            }
            exclusionCacheSeg = dloc.seg.id;
        }
        exclusionDirty = false;
    }

    private void drawExclusion(GOut g) {
        if (route == null || dloc == null) return;
        rebuildExclusionRunsIfNeeded();
        if (exclusionRuns.isEmpty()) return;
        g.chcolor(220, 30, 30, TILE_SQUARE_ALPHA);
        drawRuns(g, exclusionRuns);
        g.chcolor();
    }

    /** Merges a tile set into horizontal runs ({y, x1, x2}, x2 inclusive) for cheap batched
     *  drawing - one frect() per contiguous row-run instead of one per tile. Shared by the
     *  Exclusion overlay and the cliff/cliff-safe overlays below; expensive to build (a full sort)
     *  for a large set, so callers must only call this when the underlying tile set has actually
     *  changed, not every frame. */
    private static void buildRuns(Set<Coord> tiles, List<int[]> out) {
        out.clear();
        if (tiles.isEmpty()) return;
        List<Coord> sorted = new ArrayList<>(tiles);
        sorted.sort(Comparator.<Coord>comparingInt((Coord c) -> c.y).thenComparingInt(c -> c.x));
        int i = 0;
        while (i < sorted.size()) {
            Coord start = sorted.get(i);
            int endX = start.x;
            int j = i + 1;
            while (j < sorted.size() && sorted.get(j).y == start.y && sorted.get(j).x == endX + 1) {
                endX = sorted.get(j).x;
                j++;
            }
            out.add(new int[]{start.y, start.x, endX});
            i = j;
        }
    }

    /** Draws a set of runs built by buildRuns(), in the caller's already-set draw color. */
    private void drawRuns(GOut g, List<int[]> runs) {
        Coord hsz = sz.div(2);
        for (int[] run : runs) {
            Coord ul = new Coord(run[1], run[0]).sub(dloc.tc).div(scalef()).add(hsz);
            Coord br = new Coord(run[2] + 1, run[0] + 1).sub(dloc.tc).div(scalef()).add(hsz);
            Coord[] clipped = clampRect(g, ul, br);
            if (clipped == null) continue;
            g.frect(clipped[0], clipped[1].sub(clipped[0]));
        }
    }

    /** Paints (or, with erase=true, removes) every tile in the brush footprint (centered on the
     *  tile under screen point c) in the route's exclusion set for whichever segment that tile
     *  resolves to. Sized to the user-adjustable brush size (setBrushSizeTiles), not the
     *  viewable-zone box - those are independent now. */
    private void paintOrEraseAt(Coord c, boolean erase) {
        Location loc = xlate(c);
        if (loc == null || route == null) return;
        Set<Coord> tiles = erase ? route.exclusionTiles.get(loc.seg.id) : null;
        if (erase && tiles == null) return; // nothing painted here yet - skip the footprint loop entirely
        int half = brushSizeTiles / 2;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                Coord tc = loc.tc.add(dx, dy);
                if (erase) {
                    tiles.remove(tc); // non-null: guarded above
                } else {
                    route.paintExclusion(loc.seg.id, tc);
                }
            }
        }
        invalidateExclusionCache();
    }

    /** Square tracking the mouse - shown at all times while hovering the map, so the brush's
     *  reach is always legible before clicking. While Shift isn't held (i.e. clicking here would
     *  act on waypoints, not the Exclusion brush) it previews the viewable-zone box a waypoint
     *  placed here would get - grid-snapped exactly like the real thing (viewZoneBoxTiles), not
     *  smoothly following the cursor, so lining up several waypoints' zones edge to edge is a
     *  matter of watching the preview snap into place rather than guessing. While Shift is held
     *  it switches to the actual, user-adjustable brush size (unsnapped - a brush paints wherever
     *  you point it), tinted grey (about to paint) or red (about to erase, also holding right-click). */
    private void drawBrushCursor(GOut g) {
        if (hoverC == null || dloc == null || route == null) return;
        Location loc = xlate(hoverC);
        if (loc == null || loc.seg.id != dloc.seg.id) return;

        boolean shiftHeld = ui != null && ui.modshift;
        Coord hsz = sz.div(2);
        Coord screenUL, screenBR;
        if (shiftHeld) {
            Coord size = new Coord(brushSizeTiles, brushSizeTiles);
            Coord ul = loc.tc.sub(size.div(2));
            Coord br = ul.add(size);
            screenUL = ul.sub(dloc.tc).div(scalef()).add(hsz);
            screenBR = br.sub(dloc.tc).div(scalef()).add(hsz);
        } else {
            Coord[] box = viewZoneBoxTiles(loc.seg.id, loc.tc);
            if (box == null) return;
            screenUL = box[0].sub(dloc.tc).div(scalef()).add(hsz);
            screenBR = box[1].sub(dloc.tc).div(scalef()).add(hsz);
        }

        Coord[] clipped = clampRect(g, screenUL, screenBR);
        if (clipped == null) return;

        if (!shiftHeld) {
            g.chcolor(VIEWZONE_BORDER);
        } else if (erasing) {
            g.chcolor(220, 120, 120, 190);
        } else {
            g.chcolor(210, 210, 210, 170);
        }
        g.rect(clipped[0], clipped[1].sub(clipped[0]));
        g.chcolor();
    }

    // On-map "discard unsaved changes" button, top-right corner, only shown while dirty (per
    // direct feedback - the previous version of this lived as a permanent IButton up in the
    // panel's route row, which was both an odd place for it and always visible whether or not
    // there was anything to discard). Manually drawn/hit-tested rather than a real child Widget
    // because MiniMap.draw() deliberately never runs the normal child-draw traversal (only
    // drawparts()) - same reason every other overlay in this widget (waypoints, brush cursor,
    // exclusion tiles) is hand-drawn instead of being a child Widget.
    private static final int RESET_BTN_MARGIN = 6;

    private Coord resetButtonUL() {
        Tex icon = NStyle.canceli[0];
        return new Coord(sz.x - icon.sz().x - UI.scale(RESET_BTN_MARGIN), UI.scale(RESET_BTN_MARGIN));
    }

    private boolean resetButtonHit(Coord c) {
        if (!dirty) return false;
        Coord ul = resetButtonUL();
        Coord br = ul.add(NStyle.canceli[0].sz());
        return c.x >= ul.x && c.x < br.x && c.y >= ul.y && c.y < br.y;
    }

    private void drawResetButton(GOut g) {
        if (!dirty) return;
        boolean hovering = hoverC != null && resetButtonHit(hoverC);
        g.image(NStyle.canceli[hovering ? 2 : 0], resetButtonUL());
    }

    @Override
    public Object tooltip(Coord c, Widget prev) {
        if (resetButtonHit(c)) {
            return L10n.get("forager.settings.reset_route_tip");
        }
        return super.tooltip(c, prev);
    }

    // Without this, hoverC keeps whatever value it last had from mousemove - once the mouse
    // leaves this widget (moves elsewhere in the settings panel) mousemove simply stops firing,
    // so the brush cursor stayed drawn at that stale last-known position forever instead of
    // disappearing.
    @Override
    public boolean mousehover(MouseHoverEvent ev, boolean hovering) {
        if (!hovering) {
            hoverC = null;
        }
        return super.mousehover(ev, hovering);
    }

    private int waypointIndexAt(Coord c) {
        if (route == null || dloc == null) return -1;
        double bestDist = UI.scale(9);
        int best = -1;
        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) continue;
            Coord sc = wp.tc.sub(dloc.tc).div(scalef()).add(sz.div(2));
            double d = sc.dist(c);
            if (d <= bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Ctrl+right-click on a waypoint - opens its attached-steps popout (WaypointStepsWindow),
     *  editing the waypoint's steps list directly. Plain right-click (no Ctrl) still deletes,
     *  handled separately below. */
    private void openWaypointSteps(int idx) {
        ForagerWaypoint wp = route.waypoints.get(idx);
        WaypointStepsWindow win = new WaypointStepsWindow(wp, this::notifyChanged);
        NUtils.getGameUI().add(win, UI.scale(200, 200));
        win.show();
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (ev.b == 1 && resetButtonHit(ev.c)) {
            if (onResetRequested != null) {
                onResetRequested.run();
            }
            return true;
        }
        if (route != null) {
            boolean shift = ui.modshift;
            if (shift && (ev.b == 1 || ev.b == 3)) {
                erasing = (ev.b == 3);
                painting = (ev.b == 1);
                dragGrab = ui.grabmouse(this);
                paintOrEraseAt(ev.c, erasing);
                notifyChanged();
                return true;
            }
            if (ev.b == 3 && ui.modctrl) {
                int idx = waypointIndexAt(ev.c);
                if (idx >= 0) {
                    openWaypointSteps(idx);
                    return true;
                }
            }
            if (ev.b == 1) {
                String milestoneHash = unsplicedMilestoneSourceAt(ev.c);
                if (milestoneHash != null) {
                    spliceMilestone(milestoneHash);
                    return true;
                }
                int idx = waypointIndexAt(ev.c);
                // Milestone anchor waypoints are static, recorded locations - not user-repositionable.
                if (idx >= 0 && route.waypoints.get(idx).milestoneHash == null) {
                    draggingWaypointIndex = idx;
                    dragGrab = ui.grabmouse(this);
                    return true;
                }
            } else if (ev.b == 3) {
                int idx = waypointIndexAt(ev.c);
                if (idx >= 0) {
                    String hash = route.waypoints.get(idx).milestoneHash;
                    if (hash != null) {
                        unspliceMilestone(hash);
                    } else {
                        route.removeWaypointAt(idx);
                    }
                    notifyChanged();
                    return true;
                }
            }
        }
        return super.mousedown(ev);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        hoverC = ev.c;
        if (draggingWaypointIndex >= 0) {
            Location loc = xlate(ev.c);
            if (loc != null && route != null) {
                // Preserve the waypoint's attached steps/fail-action across the move - it's the
                // same logical waypoint, just repositioned, not a fresh one.
                ForagerWaypoint old = route.waypoints.get(draggingWaypointIndex);
                ForagerWaypoint moved = new ForagerWaypoint(loc);
                moved.steps = old.steps;
                moved.onStepsFailAction = old.onStepsFailAction;
                moved.milestoneHash = old.milestoneHash;
                route.waypoints.set(draggingWaypointIndex, moved);
            }
            return;
        }
        if (painting || erasing) {
            paintOrEraseAt(ev.c, erasing);
            return;
        }
        super.mousemove(ev);
    }

    @Override
    public boolean mouseup(MouseUpEvent ev) {
        if (draggingWaypointIndex >= 0) {
            if (dragGrab != null) {
                dragGrab.remove();
                dragGrab = null;
            }
            draggingWaypointIndex = -1;
            notifyChanged();
            return true;
        }
        if (painting || erasing) {
            if (dragGrab != null) {
                dragGrab.remove();
                dragGrab = null;
            }
            painting = false;
            erasing = false;
            notifyChanged();
            return true;
        }
        return super.mouseup(ev);
    }

    // Fires only on a genuine click (base MiniMap already filters out anything that moved more
    // than its own drag threshold before calling this) - the click-vs-pan distinction plain
    // left-click-to-add needs comes for free from that, no separate threshold logic needed here.
    @Override
    public boolean clickloc(Location loc, int button, boolean press) {
        if (!press && button == 1 && !ui.modshift && route != null) {
            route.addWaypoint(new ForagerWaypoint(loc));
            notifyChanged();
            return true;
        }
        return false;
    }
}
