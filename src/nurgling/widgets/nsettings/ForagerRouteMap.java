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

/** Dedicated terrain-only map for editing a {@link ForagerPath} - skips the real map's gob/player/grid clutter and adds its own waypoint/view-zone/exclusion overlays and mouse handling on top of {@link NMiniMap}'s terrain rendering. */
public class ForagerRouteMap extends NMiniMap {

    private ForagerPath route;

    /** Fired after any edit so the owning panel can mark its state dirty and persist on save. */
    public Runnable onChange = null;

    /** Invoked when the on-map "discard unsaved changes" button is clicked. */
    public Runnable onResetRequested = null;

    // Whether route has unsaved edits not yet reflected in the last load()/setRoute() baseline.
    private boolean dirty = false;

    private int draggingWaypointIndex = -1;
    private UI.Grab dragGrab = null;
    private boolean painting = false;
    private boolean erasing = false;
    private Coord hoverC = null;

    public static final int DEFAULT_BRUSH_SIZE_TILES = 10;
    // User-adjustable brush size, independent of viewZoneTileSize().
    private int brushSizeTiles = DEFAULT_BRUSH_SIZE_TILES;

    public ForagerRouteMap(Coord sz, MapFile file) {
        super(sz, file);
        // Without an initial location, dloc stays null forever until tick() resolves it via follow().
        NGameUI gui = NUtils.getGameUI();
        if (gui != null && gui.map != null) {
            follow(new MapLocator(gui.map));
        }
    }

    /** For edits the panel makes directly to the route model outside this widget (e.g. Avoid cliffs). */
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

    /** Adds a waypoint from a real-world click (NMapView's Alt+Left-click hook). */
    public void addWaypointFromWorld(MiniMap.Location loc) {
        if (route == null) return;
        route.addWaypoint(new ForagerWaypoint(loc));
        notifyChanged();
    }

    /** Splices a milestone from a real-world gob click; returns false if hash isn't a recorded, unspliced milestone. */
    public boolean spliceMilestoneFromWorld(String hash) {
        if (hash == null || route == null || isMilestoneSpliced(hash)) return false;
        if (MilestoneRegistry.getMilestone(hash) == null) return false;
        spliceMilestone(hash);
        return true;
    }

    /** Moves a waypoint from a real-world drag in NMapView; milestone anchors aren't repositionable. */
    public void moveWaypointFromWorld(int index, MiniMap.Location loc, boolean commit) {
        if (route == null || index < 0 || index >= route.waypoints.size()) return;
        ForagerWaypoint old = route.waypoints.get(index);
        if (old.milestoneHash != null) return;
        ForagerWaypoint moved = new ForagerWaypoint(loc);
        moved.steps = old.steps;
        moved.onStepsFailAction = old.onStepsFailAction;
        route.waypoints.set(index, moved);
        if (commit) notifyChanged();
    }

    /** Clears the unsaved-changes indicator after a successful save. */
    public void markClean() {
        dirty = false;
    }

    // Caps paintOrEraseAt's O(size^2) tile loop so a huge brush can't stall the UI thread.
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

    // Terrain + this widget's own overlays only - skips gob icons/player/grid/view-radius layers.
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

    // Marks a waypoint with attached steps; takes priority over the usual active/queued colors.
    private static final Color STEPS_COLOR = new Color(40, 200, 90);

    // Reuses NMiniMap's queued-waypoint rendering (dashLine/ringOutline/getWaypointLabel).
    private void drawRouteWaypoints(GOut g) {
        if (route == null || dloc == null || route.waypoints.isEmpty()) return;
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
            Coord c = toScreen(wp.tc);
            // dashLine/fellipse don't respect the ancestor GOut clip chain, so this needs an explicit onScreen check.
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
            Coord c = toScreen(wp.tc);
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

    /** Draws unspliced recorded milestones; a spliced one is rendered by drawRouteWaypoints() instead. */
    private void drawMilestones(GOut g) {
        if (dloc == null) return;
        int margin = UI.scale(12);

        for (Map.Entry<String, Object> e : MilestoneRegistry.allMilestones().entrySet()) {
            String hash = e.getKey();
            if (isMilestoneSpliced(hash)) continue;
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            MilestoneRegistry.Location srcLoc = MilestoneRegistry.getMilestoneLocation(entry);
            if (srcLoc == null) continue;

            Coord srcC = (srcLoc.seg == dloc.seg.id) ? toScreen(srcLoc.tc) : null;
            boolean srcOnScreen = srcC != null && onScreen(g, srcC, margin);

            for (Map<String, Object> dest : MilestoneRegistry.getDestinations(entry)) {
                MilestoneRegistry.Location destLoc = MilestoneRegistry.getDestinationLocation(dest);
                if (destLoc == null || destLoc.seg != dloc.seg.id) continue;
                Coord destC = toScreen(destLoc.tc);
                boolean destOnScreen = onScreen(g, destC, margin);

                // OR (not AND) so the link still draws once either end is on screen, not only when both are.
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

    /** True once a milestone's anchor waypoints carry this hash. */
    private boolean isMilestoneSpliced(String hash) {
        if (route == null) return false;
        for (ForagerWaypoint wp : route.waypoints) {
            if (hash.equals(wp.milestoneHash)) return true;
        }
        return false;
    }

    /** Hit-test against an unspliced milestone's source marker only. */
    private String unsplicedMilestoneSourceAt(Coord c) {
        if (dloc == null) return null;
        double bestDist = UI.scale(MILESTONE_ICON_RADIUS + 3);
        String best = null;
        for (Map.Entry<String, Object> e : MilestoneRegistry.allMilestones().entrySet()) {
            String hash = e.getKey();
            if (isMilestoneSpliced(hash) || !(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            MilestoneRegistry.Location loc = MilestoneRegistry.getMilestoneLocation(entry);
            if (loc == null || loc.seg != dloc.seg.id) continue;
            Coord sc = toScreen(loc.tc);
            double d = sc.dist(c);
            if (d <= bestDist) {
                bestDist = d;
                best = hash;
            }
        }
        return best;
    }

    /** Splices a milestone into the route as two linked waypoints appended at the end. */
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

    /** Right-click on either spliced anchor removes both linked waypoints, not just the one clicked. */
    private void unspliceMilestone(String hash) {
        if (route == null) return;
        route.waypoints.removeIf(wp -> hash.equals(wp.milestoneHash));
    }

    // Same explored/render-distance box NMiniMap.drawview draws for the player, recomputed per waypoint.
    private static final Color VIEWZONE_BG = new Color(25, 60, 170, 70);
    private static final Color VIEWZONE_BORDER = new Color(25, 60, 170, 180);

    /** Tile footprint of the real map's explored/render-distance box - shared by the view-zone rendering and the Exclusion brush. */
    private Coord viewZoneTileSize() {
        return _sgridsz.mul(9).div(MCache.tilesz.floor());
    }

    /** Grid-snapped view-zone box for an arbitrary tile - same formula NMiniMap.drawview() uses for the player. */
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

        for (ForagerWaypoint wp : route.waypoints) {
            if (wp.seg != dloc.seg.id) continue;

            // Only draw if the waypoint itself is on screen, not just its (much bigger) zone box.
            Coord wpC = toScreen(wp.tc);
            if (!onScreen(g, wpC, UI.scale(12))) continue;

            Coord[] box = viewZoneBoxTiles(wp.seg, wp.tc);
            if (box == null) continue;
            Coord screenUL = toScreen(box[0]);
            Coord screenBR = toScreen(box[1]);

            Coord[] clipped = clampRect(g, screenUL, screenBR);
            if (clipped == null) continue;
            g.chcolor(VIEWZONE_BG);
            g.frect(clipped[0], clipped[1].sub(clipped[0]));
            g.chcolor(VIEWZONE_BORDER);
            g.rect(clipped[0], clipped[1].sub(clipped[0]));
        }
        g.chcolor();
    }

    /** Intersects [ul, br) with g's actual clip window - frect/rect don't respect the GOut clip chain like image() does. */
    private Coord[] clampRect(GOut g, Coord ul, Coord br) {
        Coord winUl = g.ul.sub(g.tx);
        Coord winBr = g.br.sub(g.tx);
        Coord cul = new Coord(Utils.clip(ul.x, winUl.x, winBr.x), Utils.clip(ul.y, winUl.y, winBr.y));
        Coord cbr = new Coord(Utils.clip(br.x, winUl.x, winBr.x), Utils.clip(br.y, winUl.y, winBr.y));
        if (cbr.x <= cul.x || cbr.y <= cul.y) return null;
        return new Coord[]{cul, cbr};
    }

    /** Whether point c (plus margin) falls within g's actual visible window - same reasoning as clampRect. */
    private boolean onScreen(GOut g, Coord c, int margin) {
        Coord winUl = g.ul.sub(g.tx);
        Coord winBr = g.br.sub(g.tx);
        return c.x >= winUl.x - margin && c.x <= winBr.x + margin
                && c.y >= winUl.y - margin && c.y <= winBr.y + margin;
    }

    /** World segment-tile coordinate -&gt; on-screen pixel position, this widget's zoom/pan transform applied. */
    private Coord toScreen(Coord tc) {
        return tc.sub(dloc.tc).div(scalef()).add(sz.div(2));
    }

    // Exclusion tiles cached as merged horizontal runs (see invalidateExclusionCache) instead of one frect() per tile per frame.
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

    /** Merges a tile set into horizontal runs for cheap batched drawing - callers must only rebuild on actual change, not every frame. */
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
        for (int[] run : runs) {
            Coord ul = toScreen(new Coord(run[1], run[0]));
            Coord br = toScreen(new Coord(run[2] + 1, run[0] + 1));
            Coord[] clipped = clampRect(g, ul, br);
            if (clipped == null) continue;
            g.frect(clipped[0], clipped[1].sub(clipped[0]));
        }
    }

    /** Paints or erases the brush footprint centered on the tile under screen point c. */
    private void paintOrEraseAt(Coord c, boolean erase) {
        Location loc = xlate(c);
        if (loc == null || route == null) return;
        Set<Coord> tiles = erase ? route.exclusionTiles.get(loc.seg.id) : null;
        if (erase && tiles == null) return; // nothing painted here yet
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

    /** Square tracking the mouse - previews the waypoint view-zone box, or (Shift held) the Exclusion brush footprint. */
    private void drawBrushCursor(GOut g) {
        if (hoverC == null || dloc == null || route == null) return;
        Location loc = xlate(hoverC);
        if (loc == null || loc.seg.id != dloc.seg.id) return;

        boolean shiftHeld = ui != null && ui.modshift;
        Coord screenUL, screenBR;
        if (shiftHeld) {
            Coord size = new Coord(brushSizeTiles, brushSizeTiles);
            Coord ul = loc.tc.sub(size.div(2));
            Coord br = ul.add(size);
            screenUL = toScreen(ul);
            screenBR = toScreen(br);
        } else {
            Coord[] box = viewZoneBoxTiles(loc.seg.id, loc.tc);
            if (box == null) return;
            screenUL = toScreen(box[0]);
            screenBR = toScreen(box[1]);
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

    // On-map "discard unsaved changes" button, top-right corner, only shown while dirty.
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

    // Clears hoverC once the mouse leaves this widget, so the brush cursor stops drawing at a stale position.
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
            Coord sc = toScreen(wp.tc);
            double d = sc.dist(c);
            if (d <= bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** Ctrl+right-click on a waypoint opens its attached-steps popout (WaypointStepsWindow). */
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
                // Preserve the waypoint's attached steps/fail-action across the move.
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

    // Fires only on a genuine click - base MiniMap already filters out drags before calling this.
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
