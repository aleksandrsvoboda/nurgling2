package nurgling.widgets.nsettings;

import haven.*;
import haven.resutil.Ridges;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerWaypoint;
import nurgling.widgets.NMiniMap;

/**
 * Small, dedicated terrain-only map for creating/editing a {@link ForagerPath} directly, rather
 * than overlaying a route on the real map (which always shows the player, other gobs, and grid
 * lines - none of which help while confirming a route "sees everything" it should). Reuses
 * {@link NMiniMap}'s real terrain rendering ({@link #drawmap}) but skips every other layer
 * (icons/markers/player/grid/view-radius) via a custom {@link #drawparts} override, and adds its
 * own waypoint/cliff/no-forage-zone overlays and mouse handling on top.
 * <p>
 * Interaction (confirmed with the user - deliberately simpler than the real map's own waypoint
 * tool, which is Alt+Left-click for an unrelated, ephemeral system, {@code WaypointMovementService}):
 * plain left-click on empty space adds a waypoint at the end of the route; left-click-and-drag an
 * existing waypoint's box moves it; right-click an existing waypoint's box deletes it; free
 * pan (drag empty space) and zoom (scroll) both work normally, same as any other minimap.
 */
public class ForagerRouteMap extends NMiniMap {

    public enum Mode { EDIT_ROUTE, DONT_FORAGE }

    private ForagerPath route;
    private Mode mode = Mode.EDIT_ROUTE;

    /** Fired after any edit (add/move/delete waypoint, add a no-forage zone) so the owning panel
     *  can mark its state dirty and know to persist on save. */
    public Runnable onChange = null;

    private int draggingWaypointIndex = -1;
    private UI.Grab dragGrab = null;

    private Location zoneDragStart = null;
    private Location zoneDragCurrent = null;

    public ForagerRouteMap(Coord sz, MapFile file) {
        super(sz, file);
    }

    public void setRoute(ForagerPath route) {
        this.route = route;
        cancelDrags();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        cancelDrags();
    }

    public Mode getMode() {
        return mode;
    }

    private void cancelDrags() {
        if (dragGrab != null) {
            dragGrab.remove();
            dragGrab = null;
        }
        draggingWaypointIndex = -1;
        zoneDragStart = null;
        zoneDragCurrent = null;
    }

    private void notifyChanged() {
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
        drawCliffs(g);
        drawNoForageZones(g);
        if (route != null && mode == Mode.EDIT_ROUTE) {
            drawRouteWaypoints(g);
        }
        drawZoneDragPreview(g);
    }

    private static final int TILE_SQUARE_ALPHA = 110;

    private int tileScreenSize() {
        float sf = scalef();
        if (sf <= 0) return UI.scale(4);
        return Math.max(UI.scale(2), Math.round(1f / sf));
    }

    private void drawRouteWaypoints(GOut g) {
        if (dloc == null) return;
        Coord hsz = sz.div(2);
        int boxSz = tileScreenSize();
        Coord boxHalf = new Coord(boxSz / 2, boxSz / 2);

        g.chcolor(0, 0, 0, 160);
        Coord prevC = null;
        for (ForagerWaypoint wp : route.waypoints) {
            if (wp.seg != dloc.seg.id) {
                prevC = null;
                continue;
            }
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (prevC != null) {
                g.line(prevC, c, 1);
            }
            prevC = c;
        }

        g.chcolor(60, 120, 255, TILE_SQUARE_ALPHA);
        for (ForagerWaypoint wp : route.waypoints) {
            if (wp.seg != dloc.seg.id) continue;
            Coord c = wp.tc.sub(dloc.tc).div(scalef()).add(hsz);
            if (c.x < -boxSz || c.x > sz.x + boxSz || c.y < -boxSz || c.y > sz.y + boxSz) continue;
            g.frect(c.sub(boxHalf), new Coord(boxSz, boxSz));
        }
        g.chcolor();
    }

    private void drawNoForageZones(GOut g) {
        if (route == null || dloc == null) return;
        Coord hsz = sz.div(2);
        g.chcolor(200, 40, 40, 90);
        for (ForagerPath.NoForageZone zone : route.noForageZones) {
            if (zone.seg != dloc.seg.id) continue;
            Coord ul = zone.ul.sub(dloc.tc).div(scalef()).add(hsz);
            Coord br = zone.br.add(1, 1).sub(dloc.tc).div(scalef()).add(hsz);
            g.frect(ul, br.sub(ul));
        }
        g.chcolor();
    }

    private void drawZoneDragPreview(GOut g) {
        if (zoneDragStart == null || zoneDragCurrent == null || dloc == null) return;
        if (zoneDragStart.seg.id != dloc.seg.id) return;
        Coord hsz = sz.div(2);
        Coord a = zoneDragStart.tc.sub(dloc.tc).div(scalef()).add(hsz);
        Coord b = zoneDragCurrent.tc.sub(dloc.tc).div(scalef()).add(hsz);
        Coord ul = new Coord(Math.min(a.x, b.x), Math.min(a.y, b.y));
        Coord br = new Coord(Math.max(a.x, b.x), Math.max(a.y, b.y));
        g.chcolor(200, 40, 40, 140);
        g.frect(ul, br.sub(ul));
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

        g.chcolor(220, 30, 30, TILE_SQUARE_ALPHA);
        // Collect broken tiles first, then expand to their 8 neighbors too (per the spec: "the
        // cliffs and the tiles around it should be red") before drawing, so a neighbor of one
        // broken tile that's also broken itself doesn't get double-processed/doesn't matter -
        // it's a set, not a list.
        java.util.Set<Coord> broken = new java.util.HashSet<>();
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

        java.util.Set<Coord> highlight = new java.util.HashSet<>(broken);
        for (Coord tc : broken) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    highlight.add(tc.add(dx, dy));
                }
            }
        }

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
        if (route != null && mode == Mode.EDIT_ROUTE) {
            int idx = waypointIndexAt(ev.c);
            if (idx >= 0) {
                if (ev.b == 1) {
                    draggingWaypointIndex = idx;
                    dragGrab = ui.grabmouse(this);
                    return true;
                } else if (ev.b == 3) {
                    route.removeWaypointAt(idx);
                    notifyChanged();
                    return true;
                }
            }
        } else if (route != null && mode == Mode.DONT_FORAGE && ev.b == 1) {
            Location loc = xlate(ev.c);
            if (loc != null) {
                zoneDragStart = loc;
                zoneDragCurrent = loc;
                dragGrab = ui.grabmouse(this);
                return true;
            }
        }
        return super.mousedown(ev);
    }

    @Override
    public void mousemove(MouseMoveEvent ev) {
        if (draggingWaypointIndex >= 0) {
            Location loc = xlate(ev.c);
            if (loc != null && route != null) {
                route.waypoints.set(draggingWaypointIndex, new ForagerWaypoint(loc));
            }
            return;
        }
        if (zoneDragStart != null) {
            Location loc = xlate(ev.c);
            if (loc != null) {
                zoneDragCurrent = loc;
            }
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
        if (zoneDragStart != null) {
            if (dragGrab != null) {
                dragGrab.remove();
                dragGrab = null;
            }
            if (zoneDragCurrent != null && route != null && zoneDragStart.seg.id == zoneDragCurrent.seg.id) {
                route.noForageZones.add(new ForagerPath.NoForageZone(zoneDragStart.seg.id, zoneDragStart.tc, zoneDragCurrent.tc));
                notifyChanged();
            }
            zoneDragStart = null;
            zoneDragCurrent = null;
            return true;
        }
        return super.mouseup(ev);
    }

    // Fires only on a genuine click (base MiniMap already filters out anything that moved more
    // than its own drag threshold before calling this) - the click-vs-pan distinction plain
    // left-click-to-add needs comes for free from that, no separate threshold logic needed here.
    @Override
    public boolean clickloc(Location loc, int button, boolean press) {
        if (!press && button == 1 && route != null && mode == Mode.EDIT_ROUTE) {
            route.addWaypoint(new ForagerWaypoint(loc));
            notifyChanged();
            return true;
        }
        return false;
    }
}
