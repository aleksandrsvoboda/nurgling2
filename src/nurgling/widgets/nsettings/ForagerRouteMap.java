package nurgling.widgets.nsettings;

import haven.*;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.overlays.NWaypointOverlay;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerWaypoint;
import nurgling.tools.CliffTileCache;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Small, dedicated terrain-only map for creating/editing a {@link ForagerPath} directly, rather
 * than overlaying a route on the real map (which always shows the player, other gobs, and grid
 * lines - none of which help while confirming a route "sees everything" it should). Reuses
 * {@link NMiniMap}'s real terrain rendering ({@link #drawmap}) but skips every other layer
 * (icons/markers/player/grid/view-radius) via a custom {@link #drawparts} override, and adds its
 * own waypoint/cliff/view-zone/exclusion overlays and mouse handling on top.
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

    // Throttle for CliffTileCache.scanNewGrids()/applyCliffExclusionFromCache() - see tick().
    // Not tied to draw() at all (unlike the old per-frame viewport-limited cliff scan this
    // replaced) so the cache keeps growing, and an enabled Cliff exclusion toggle keeps applying,
    // regardless of whether this widget is currently visible/on-screen or panned to a particular
    // spot.
    private static final double CLIFF_SCAN_INTERVAL = 0.5;
    private double cliffScanTimer = 0;

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
     *  the Cliff exclusion checkbox) - same unsaved-changes bookkeeping as an in-map edit. */
    public void markDirty() {
        dirty = true;
    }

    public void setRoute(ForagerPath route) {
        this.route = route;
        cancelDrags();
        invalidateExclusionCache();
        dirty = false;
    }

    /** Called by the owning panel right after a successful save - clears the unsaved-changes
     *  indicator without touching route/waypoints/exclusion state (unlike setRoute(), this isn't
     *  a reload, currentRoute is still the same object, it's just no longer ahead of disk). */
    public void markClean() {
        dirty = false;
    }

    public void setBrushSizeTiles(int tiles) {
        this.brushSizeTiles = Math.max(1, tiles);
    }

    /** Whether the currently-displayed segment still has a cliff-scan backlog in progress - used
     *  by the panel to show/hide a spinner next to the Cliff exclusion checkbox. Requires the
     *  checkbox itself to be on, matching tick()'s own gate on actually running the scan - so the
     *  spinner can't appear to be "still working" from a stale backlog count left over from
     *  earlier, if the checkbox has since been unchecked. */
    public boolean isScanningCliffs() {
        return dloc != null && route != null && route.cliffExclusionEnabled && CliffTileCache.isScanning(dloc.seg.id);
    }

    /** Stops further background cliff scanning for the currently-displayed segment (whatever's
     *  already been found is kept) - wired to the spinner's cancel button. */
    public void cancelCliffScan() {
        if (dloc != null) {
            CliffTileCache.cancelScan(dloc.seg.id);
        }
    }

    /** Lets a previously-cancelled scan resume - called when the Cliff exclusion checkbox is
     *  turned back on. */
    public void resumeCliffScan() {
        if (dloc != null) {
            CliffTileCache.resumeScan(dloc.seg.id);
        }
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

    // Not tied to draw() - runs whether or not this widget is currently visible/attached to a
    // shown panel (Widget.TickEvent reaches every attached widget regardless of the visible flag,
    // only draw() is gated on it). CliffTileCache reads straight from the persisted MapFile (see
    // its class javadoc), so this only needs to know which segment is currently displayed here -
    // no live MCache/player-proximity dependency at all, which is what lets it cover a segment's
    // entire explored history rather than requiring the player to physically revisit every spot.
    //
    // super.tick(dt) is NMiniMap's tick(), which (beyond the zoom-smoothing this widget does
    // want) also unconditionally forwards to WaypointMovementService.processMovementQueue() -
    // logic written for the real corner minimap's alt+left-click movement queue, not a secondary
    // standalone editor widget, and which touches MapFile.gridinfo without holding file's lock.
    // If that throws while a movement command happens to be active, it does so INSIDE
    // super.tick(dt), silently skipping everything below every single tick with no visible
    // crash (nothing else here depends on tick() succeeding - mouse-driven edits are handled by
    // separate event dispatch). Isolating it so a failure there can never block our own cliff
    // scan trigger.
    @Override
    public void tick(double dt) {
        try {
            super.tick(dt);
        } catch (Exception e) {
            System.err.println("ForagerRouteMap: NMiniMap.tick() threw, cliff scan trigger below would otherwise never run:");
            e.printStackTrace();
        }
        cliffScanTimer += dt;
        if (cliffScanTimer < CLIFF_SCAN_INTERVAL) return;
        cliffScanTimer = 0;
        // Only scan while the route actually wants cliff data - not continuously in the
        // background regardless of the checkbox. Grids already scanned stay cached in
        // CliffTileCache regardless (scannedGridIds is permanent for the session), so toggling
        // this on and off never re-does already-finished work, it only pauses/resumes new work.
        if (dloc == null || route == null || !route.cliffExclusionEnabled) return;
        try {
            CliffTileCache.scanSegment(file, dloc.seg.id);
        } catch (Exception e) {
            System.err.println("ForagerRouteMap: CliffTileCache.scanSegment() threw:");
            e.printStackTrace();
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
        drawCliffs(g);
        drawWaypointViewZones(g);
        drawExclusion(g);
        drawRouteWaypoints(g);
        drawBrushCursor(g);
        drawResetButton(g);
    }

    private static final int TILE_SQUARE_ALPHA = 110;

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
        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) {
                prevC = null;
                continue;
            }
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            // dashLine/fellipse (unlike image()-based draws) don't respect the ancestor GOut
            // clip chain, so - same as the view-zone/exclusion/cliff boxes - they need an
            // explicit on-screen check derived from g's actual visible window, not just this
            // widget's full declared size (which can be bigger than what's actually visible
            // when this widget is partially scrolled out of the settings panel's Scrollport).
            if (prevC != null && (onScreen(g, prevC, margin) || onScreen(g, c, margin))) {
                Color lc = (i == 1) ? NWaypointOverlay.activeColor() : NWaypointOverlay.queuedColor();
                g.chcolor(lc.getRed(), lc.getGreen(), lc.getBlue(), 200);
                dashLine(g, prevC, c, phase, 2);
            }
            prevC = c;
        }

        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) continue;
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (!onScreen(g, c, margin)) continue;

            boolean first = (i == 0);
            boolean dragging = (i == draggingWaypointIndex);
            Color col = dragging ? NWaypointOverlay.dragColor() : (first ? NWaypointOverlay.activeColor() : NWaypointOverlay.queuedColor());

            if (first) {
                double t = (Utils.rtime() % 1.3) / 1.3;
                int a = (int) (150 * (1 - t));
                if (a > 8) {
                    g.chcolor(col.getRed(), col.getGreen(), col.getBlue(), a);
                    ringOutline(g, c, (int) (UI.scale(6) + t * UI.scale(10)), 2);
                }
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
        int half = brushSizeTiles / 2;
        Set<Coord> tiles = erase ? route.exclusionTiles.get(loc.seg.id) : null;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                Coord tc = loc.tc.add(dx, dy);
                if (erase) {
                    if (tiles != null) tiles.remove(tc);
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

    // Cliff (red) and cliff-confirmed-safe (green, only while route.cliffExclusionEnabled)
    // overlays - both sourced from CliffTileCache, a session-wide cache kept up to date by
    // tick() below, rather than a live per-frame Ridges.brokenp scan restricted to whatever
    // happens to be on screen/loaded right now. This is what lets both show up for a whole
    // segment at once (even zoomed out, and for any segment ever explored this session - not
    // just wherever the player is physically standing). Anywhere neither color appears is simply
    // unexplored territory the cache has no data for - deliberately not painted as anything in
    // particular (there's no bounded way to paint "everywhere we haven't looked"), but meant to
    // be read the same as excluded once Forager's own bot logic consumes this: see the Cliff
    // exclusion checkbox's tooltip.
    private static final Color CLIFF_COLOR = new Color(220, 30, 30, TILE_SQUARE_ALPHA);
    private static final Color CLIFF_SAFE_COLOR = new Color(60, 190, 110, 55);

    // Rebuilt into run-length-merged rects (see buildRuns) only when CliffTileCache's data for
    // the displayed segment has actually changed since last built (tracked via
    // CliffTileCache.getVersion()) - a well-explored segment's safe-tile set can be tens of
    // thousands of tiles, far too many to sort/iterate fresh every single frame.
    private long cliffCacheSeg = Long.MIN_VALUE;
    private int cliffCacheVersion = -1;
    private final List<int[]> cliffRuns = new ArrayList<>();
    private final List<int[]> cliffSafeRuns = new ArrayList<>();

    private void rebuildCliffRunsIfNeeded() {
        if (dloc == null) return;
        long seg = dloc.seg.id;
        int ver = CliffTileCache.getVersion(seg);
        if (seg == cliffCacheSeg && ver == cliffCacheVersion) return;
        cliffCacheSeg = seg;
        cliffCacheVersion = ver;

        // "The cliffs and the tiles around it should be red" - expand raw cliff tiles to their 8
        // neighbors too, same as the old live-scan version did, before run-encoding.
        Set<Coord> rawCliffs = CliffTileCache.forSegment(seg);
        Set<Coord> highlight = new HashSet<>(rawCliffs);
        for (Coord tc : rawCliffs) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    highlight.add(tc.add(dx, dy));
                }
            }
        }
        buildRuns(highlight, cliffRuns);
        buildRuns(CliffTileCache.safeForSegment(seg), cliffSafeRuns);
    }

    private void drawCliffs(GOut g) {
        if (dloc == null) return;
        rebuildCliffRunsIfNeeded();

        if (route != null && route.cliffExclusionEnabled && !cliffSafeRuns.isEmpty()) {
            g.chcolor(CLIFF_SAFE_COLOR);
            drawRuns(g, cliffSafeRuns);
        }
        if (!cliffRuns.isEmpty()) {
            g.chcolor(CLIFF_COLOR);
            drawRuns(g, cliffRuns);
        }
        g.chcolor();
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
            if (ev.b == 1) {
                int idx = waypointIndexAt(ev.c);
                if (idx >= 0) {
                    draggingWaypointIndex = idx;
                    dragGrab = ui.grabmouse(this);
                    return true;
                }
            } else if (ev.b == 3) {
                int idx = waypointIndexAt(ev.c);
                if (idx >= 0) {
                    route.removeWaypointAt(idx);
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
                route.waypoints.set(draggingWaypointIndex, new ForagerWaypoint(loc));
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
