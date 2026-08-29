package nurgling.widgets.nsettings;

import haven.*;
import haven.resutil.Ridges;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.i18n.L10n;
import nurgling.overlays.NWaypointOverlay;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerWaypoint;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.util.HashSet;
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
 * left-click-and-drag an existing waypoint's node moves it; Shift+left-click one deletes it.
 * Right-click-and-drag paints tiles into the route's Exclusion set (a brush, not a fixed
 * rectangle - painted tiles accumulate as the button stays held). A grey square tracks the mouse
 * at all times to show the brush footprint. Free pan (drag empty space) and zoom (scroll) both
 * work normally, same as any other minimap. Three small buttons overlaid in the map's own
 * top-left corner (added as real child widgets, drawn via an explicit child-draw pass in
 * {@link #draw} since {@link MiniMap#draw} deliberately never calls it) independently toggle
 * whether the Viewable-zone/Route/Exclusion layers are drawn - they don't gate interaction.
 */
public class ForagerRouteMap extends NMiniMap {

    private ForagerPath route;

    /** Fired after any edit (add/move/delete waypoint, paint exclusion tiles) so the owning
     *  panel can mark its state dirty and know to persist on save. */
    public Runnable onChange = null;

    private boolean showViewZones = true;
    private boolean showRoute = true;
    private boolean showExclusion = true;

    private int draggingWaypointIndex = -1;
    private UI.Grab dragGrab = null;
    private boolean painting = false;
    private Coord hoverC = null;

    // Tiles either side of the cursor tile a single brush application paints (3x3 total).
    private static final int BRUSH_RADIUS = 1;

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

        int bw = UI.scale(78), gap = UI.scale(4);
        add(new LayerButton(bw, L10n.get("forager.routemap.layer_zone"), () -> showViewZones, a -> showViewZones = a), new Coord(UI.scale(5), UI.scale(5)));
        add(new LayerButton(bw, L10n.get("forager.routemap.layer_route"), () -> showRoute, a -> showRoute = a), new Coord(UI.scale(5) + bw + gap, UI.scale(5)));
        add(new LayerButton(bw, L10n.get("forager.routemap.layer_exclude"), () -> showExclusion, a -> showExclusion = a), new Coord(UI.scale(5) + 2 * (bw + gap), UI.scale(5)));
    }

    /** A small toggle button overlaid directly on the map, matching the real map's own small
     *  on-map buttons in spirit (position, not exact icon styling - no icon assets exist for
     *  these three new toggles, so this is a plain labeled Button dimmed when off). */
    private static class LayerButton extends Button {
        private final java.util.function.Supplier<Boolean> get;
        private final java.util.function.Consumer<Boolean> set;

        LayerButton(int w, String label, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
            super(w, label);
            this.get = get;
            this.set = set;
        }

        @Override
        public void click() {
            super.click();
            set.accept(!get.get());
        }

        @Override
        public void draw(GOut g) {
            if (!get.get()) {
                g.chcolor(255, 255, 255, 110);
            }
            super.draw(g);
            g.chcolor();
        }
    }

    public void setRoute(ForagerPath route) {
        this.route = route;
        cancelDrags();
    }

    private void cancelDrags() {
        if (dragGrab != null) {
            dragGrab.remove();
            dragGrab = null;
        }
        draggingWaypointIndex = -1;
        painting = false;
    }

    private void notifyChanged() {
        if (onChange != null) {
            onChange.run();
        }
    }

    // MiniMap.draw() deliberately never calls the normal Widget child-draw traversal (it only
    // ever calls drawparts()), so the three LayerButtons added above would otherwise never
    // render - draw(g, true) below is Widget's own child-traversal method, invoked explicitly.
    @Override
    public void draw(GOut g) {
        super.draw(g);
        draw(g, true);
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
        if (showViewZones) drawWaypointViewZones(g);
        if (showExclusion) drawExclusion(g);
        if (showRoute) drawRouteWaypoints(g);
        drawBrushCursor(g);
    }

    private static final int TILE_SQUARE_ALPHA = 110;

    private int tileScreenSize() {
        float sf = scalef();
        if (sf <= 0) return UI.scale(4);
        return Math.max(UI.scale(2), Math.round(1f / sf));
    }

    // Deliberately the exact same rendering NMiniMap.drawQueuedWaypoints already uses for the
    // real map's alt+left-click movement-queue waypoints (dashed crawling legs, circular numbered
    // plates, a pulsing ring on the route's start point) - reused via NMiniMap's own dashLine/
    // ringOutline/getWaypointLabel helpers (made protected for this) rather than reinvented, per
    // direct instruction. Fixed pixel-size nodes (not tied to tile size) so they stay comfortably
    // clickable regardless of zoom level.
    private void drawRouteWaypoints(GOut g) {
        if (route == null || dloc == null || route.waypoints.isEmpty()) return;
        Coord hsz = sz.div(2);

        double phase = Utils.rtime() * UI.scale(16);
        Coord prevC = null;
        for (int i = 0; i < route.waypoints.size(); i++) {
            ForagerWaypoint wp = route.waypoints.get(i);
            if (wp.seg != dloc.seg.id) {
                prevC = null;
                continue;
            }
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (prevC != null) {
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
            if (c.x < -UI.scale(12) || c.y < -UI.scale(12) || c.x > sz.x + UI.scale(12) || c.y > sz.y + UI.scale(12))
                continue;

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

    private void drawWaypointViewZones(GOut g) {
        if (route == null || dloc == null || sessloc == null) return;
        Coord2d gridsz2d = new Coord2d(_sgridsz);
        Coord unscaledViewSize = _sgridsz.mul(9).div(MCache.tilesz.floor());
        Coord hsz = sz.div(2);

        for (ForagerWaypoint wp : route.waypoints) {
            if (wp.seg != dloc.seg.id) continue;
            Coord2d worldC = wp.toWorldCoord(sessloc);
            if (worldC == null) continue;

            Coord ul = worldC.floor(gridsz2d).sub(4, 4).mul(gridsz2d).floor(MCache.tilesz).add(sessloc.tc);
            Coord br = ul.add(unscaledViewSize).add(1, 1);
            Coord screenUL = ul.sub(dloc.tc).div(scalef()).add(hsz);
            Coord screenBR = br.sub(dloc.tc).div(scalef()).add(hsz);
            Coord screenSize = screenBR.sub(screenUL);

            g.chcolor(VIEWZONE_BG);
            g.frect(screenUL, screenSize);
            g.chcolor(VIEWZONE_BORDER);
            g.rect(screenUL, screenSize);
        }
        g.chcolor();
    }

    // Exclusion tiles - freeform brush-painted, not a fixed rectangle (see paintExclusionAt).
    private void drawExclusion(GOut g) {
        if (route == null || dloc == null) return;
        Set<Coord> tiles = route.exclusionTiles.get(dloc.seg.id);
        if (tiles == null || tiles.isEmpty()) return;

        Coord hsz = sz.div(2);
        int boxSz = tileScreenSize();
        Coord boxHalf = new Coord(boxSz / 2, boxSz / 2);

        g.chcolor(220, 30, 30, TILE_SQUARE_ALPHA);
        for (Coord tc : tiles) {
            Coord c = tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (c.x < -boxSz || c.x > sz.x + boxSz || c.y < -boxSz || c.y > sz.y + boxSz) continue;
            g.frect(c.sub(boxHalf), new Coord(boxSz, boxSz));
        }
        g.chcolor();
    }

    /** Paints every tile in the brush footprint (centered on the tile under screen point c) into
     *  the route's exclusion set for whichever segment that tile resolves to. */
    private void paintExclusionAt(Coord c) {
        Location loc = xlate(c);
        if (loc == null || route == null) return;
        for (int dy = -BRUSH_RADIUS; dy <= BRUSH_RADIUS; dy++) {
            for (int dx = -BRUSH_RADIUS; dx <= BRUSH_RADIUS; dx++) {
                route.paintExclusion(loc.seg.id, loc.tc.add(dx, dy));
            }
        }
    }

    /** Grey square tracking the mouse, sized to the brush footprint - shown regardless of which
     *  layers are toggled on, so the brush's reach is always legible before painting. */
    private void drawBrushCursor(GOut g) {
        if (hoverC == null || dloc == null || route == null) return;
        Location loc = xlate(hoverC);
        if (loc == null || loc.seg.id != dloc.seg.id) return;

        Coord hsz = sz.div(2);
        int tsz = tileScreenSize();
        int side = tsz * (2 * BRUSH_RADIUS + 1);
        Coord center = loc.tc.sub(dloc.tc).div(scalef()).add(hsz);

        g.chcolor(210, 210, 210, 170);
        g.rect(center.sub(side / 2, side / 2), new Coord(side, side));
        g.chcolor();
    }

    // Cliff highlighting (visual only for now - not yet consumed by pathfinding, see the
    // deferred cliff-pathing plan). Ridges.brokenp needs the LIVE MCache, which is a different
    // coordinate space/data source than this widget's persisted MapFile segment rendering - only
    // resolvable while displaying the same segment the player is physically standing in right
    // now (same constraint ForagerWaypoint.toWorldCoord already has). Outside that, this simply
    // draws nothing rather than erroring - a graceful degrade, not a bug.
    private void drawCliffs(GOut g) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.map == null || dloc == null || sessloc == null) return;
        if (dloc.seg.id != sessloc.seg.id) return;

        Coord hsz = sz.div(2);
        float sf = scalef();
        if (sf <= 0) return;

        Coord ul = dloc.tc.sub(hsz.mul((double) sf));
        Coord br = dloc.tc.add(hsz.mul((double) sf));

        // Cap how many tiles get checked per frame - brokenp does neighbor/corner lookups per
        // tile with no caching, and this widget can show a lot of tiles at once when zoomed out.
        long tileCount = (long) (br.x - ul.x + 1) * (br.y - ul.y + 1);
        if (tileCount > 40000) return;

        MCache mcache = gui.map.glob.map;
        int boxSz = tileScreenSize();
        Coord boxHalf = new Coord(boxSz / 2, boxSz / 2);

        // Collect broken tiles first, then expand to their 8 neighbors too (per the spec: "the
        // cliffs and the tiles around it should be red") before drawing, so a neighbor of one
        // broken tile that's also broken itself doesn't get double-processed/doesn't matter -
        // it's a set, not a list.
        Set<Coord> broken = new HashSet<>();
        for (int y = ul.y; y <= br.y; y++) {
            for (int x = ul.x; x <= br.x; x++) {
                Coord locTc = new Coord(x, y);
                Coord absTile = locToAbsoluteTile(locTc);
                if (absTile == null) continue;
                try {
                    if (Ridges.brokenp(mcache, absTile)) {
                        broken.add(locTc);
                    }
                } catch (Loading e) {
                    // Tile itself, or a neighbor/corner brokenp reads, isn't loaded yet - skip.
                }
            }
        }

        Set<Coord> highlight = new HashSet<>(broken);
        for (Coord tc : broken) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    highlight.add(tc.add(dx, dy));
                }
            }
        }

        g.chcolor(220, 30, 30, TILE_SQUARE_ALPHA);
        for (Coord locTc : highlight) {
            Coord c = locTc.sub(dloc.tc).div(sf).add(hsz);
            g.frect(c.sub(boxHalf), new Coord(boxSz, boxSz));
        }
        g.chcolor();
    }

    /** Converts a segment-relative minimap tile coord to the absolute world-tile coord
     *  MCache/Ridges.brokenp expect - only valid while dloc.seg matches the live sessloc.seg
     *  (checked by the caller). Same session-relative-world-coord formula as
     *  {@link ForagerWaypoint#toWorldCoord}, generalized to an arbitrary tile coord. */
    private Coord locToAbsoluteTile(Coord locTc) {
        Coord2d wc = locTc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz);
        return wc.floor(MCache.tilesz);
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
        if (route != null) {
            if (ev.b == 1) {
                int idx = waypointIndexAt(ev.c);
                if (idx >= 0) {
                    if (ui.modshift) {
                        route.removeWaypointAt(idx);
                        notifyChanged();
                    } else {
                        draggingWaypointIndex = idx;
                        dragGrab = ui.grabmouse(this);
                    }
                    return true;
                }
            } else if (ev.b == 3) {
                painting = true;
                dragGrab = ui.grabmouse(this);
                paintExclusionAt(ev.c);
                notifyChanged();
                return true;
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
        if (painting) {
            paintExclusionAt(ev.c);
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
        if (painting) {
            if (dragGrab != null) {
                dragGrab.remove();
                dragGrab = null;
            }
            painting = false;
            return true;
        }
        return super.mouseup(ev);
    }

    // Fires only on a genuine click (base MiniMap already filters out anything that moved more
    // than its own drag threshold before calling this) - the click-vs-pan distinction plain
    // left-click-to-add needs comes for free from that, no separate threshold logic needed here.
    @Override
    public boolean clickloc(Location loc, int button, boolean press) {
        if (!press && button == 1 && route != null) {
            route.addWaypoint(new ForagerWaypoint(loc));
            notifyChanged();
            return true;
        }
        return false;
    }
}
