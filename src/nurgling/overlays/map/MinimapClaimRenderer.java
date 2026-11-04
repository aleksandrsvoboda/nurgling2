package nurgling.overlays.map;

import haven.*;
import haven.render.BaseColor;
import haven.render.BufPipe;
import nurgling.NConfig;
import nurgling.widgets.NMiniMap;

import java.awt.Color;

/**
 * Renders personal claims, village claims, and realm overlays on the minimap.
 * Uses persistent MapFile data instead of runtime MCache, showing all discovered claims.
 * Synchronizes with the 3D map overlay visibility toggles.
 */
public class MinimapClaimRenderer {
    // Claim colors (semi-transparent to avoid obscuring terrain)
    private static final Color CPLOT_COLOR = new Color(0, 255, 0, 60);    // Personal - Green
    private static final Color VLG_COLOR = new Color(255, 128, 255, 60);   // Village - Pink
    private static final Color PROV_COLOR = new Color(255, 0, 255, 60);    // Realm - Magenta

    /**
     * Main rendering method called from MiniMap's drawparts()
     * Queries persistent MapFile data from DisplayGrids to show all discovered claims.
     *
     * @param map The minimap instance
     * @param g   Graphics context
     */
    public static void renderClaims(MiniMap map, GOut g) {
        if (map.ui == null || map.ui.gui == null || map.ui.gui.map == null) {
            return;
        }

        MapView mv = map.ui.gui.map;
        if (mv == null || map.dloc == null) {
            return;
        }

        // Must be NMiniMap to access display grids
        if (!(map instanceof NMiniMap)) {
            return;
        }

        /* The map window's claims button hides every kind at once without touching which kinds
         * are picked in QoL. A missing key (a config saved before it existed) means shown. */
        if (Boolean.FALSE.equals(NConfig.get(NConfig.Key.mapClaimsShown))) {
            return;
        }
        NMiniMap nmap = (NMiniMap) map;

        // Get the display grid array and extent
        MiniMap.DisplayGrid[] display = nmap.getDisplay();
        Area dgext = nmap.getDgext();

        if (display == null || dgext == null) {
            return;
        }

        /* Outlines are drawn after every grid's fill, all halos before all bright lines; drawn grid
         * by grid, one grid's halo would cut a notch into its neighbour's line where they meet. */
        java.util.Map<Color, Segs> outlines = new java.util.LinkedHashMap<>();
        try {
            Coord hsz = map.sz.div(2);

            // Iterate through all display grids
            for (Coord gc : dgext) {
                MiniMap.DisplayGrid disp = display[dgext.ri(gc)];
                if (disp == null) {
                    continue;
                }

                // Get the underlying DataGrid from MapFile
                MapFile.DataGrid grid;
                try {
                    grid = disp.gref.get();
                } catch (Loading e) {
                    // Grid data not loaded yet
                    continue;
                }

                if (grid == null || grid.ols.isEmpty()) {
                    continue;
                }

                // Process each overlay in this grid
                for (MapFile.Overlay overlay : grid.ols) {
                    try {
                        // Get the overlay resource and its tags
                        Resource res = overlay.olid.get();
                        MCache.ResOverlay olinfo = res.flayer(MCache.ResOverlay.class);

                        if (olinfo == null) {
                            continue;
                        }

                        // Check each tag to see if it matches a claim type we want to render
                        for (String tag : olinfo.tags()) {
                            // Check if this overlay type is enabled in QoL minimap settings
                            if (!isMinimapOverlayEnabled(tag)) {
                                continue;
                            }

                            // Extract color from the resource's Material (supports enemy/friendly differentiation)
                            Color fillColor = extractColorFromMaterial(olinfo, tag);

                            if (fillColor != null) {
                                renderOverlay(map, g, overlay, disp, hsz, fillColor);
                                if (bordered(tag))
                                    outline(nmap, overlay, gc, display, dgext, hsz,
                                            outlines.computeIfAbsent(bright(fillColor), k -> new Segs()));
                                break; // Only render once per overlay, even if it has multiple tags
                            }
                        }
                    } catch (Loading e) {
                        // Resource not loaded yet, skip this overlay
                    } catch (Exception e) {
                        // Silently handle other errors
                    }
                }
            }
        } catch (Exception e) {
            // Silently handle errors
        }
        drawOutlines(g, outlines);
    }

    /* A grid's claim mask never changes once loaded, so its merged
     * rectangles (x, y, w, h per rect) are computed once per mask array
     * instead of rescanning 100x100 tiles every frame. Weak keys drop them
     * with the map data. Draw-thread only. */
    private static final java.util.Map<boolean[], int[]> RECTS = new java.util.WeakHashMap<>();

    private static int[] rects(boolean[] mask, int width, int height) {
        int[] cached = RECTS.get(mask);
        if (cached != null)
            return cached;
        boolean[] rendered = new boolean[mask.length];
        int[] out = new int[64];
        int n = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                if (!mask[idx] || rendered[idx])
                    continue;
                int rectWidth = 0;
                while (x + rectWidth < width &&
                       mask[y * width + (x + rectWidth)] &&
                       !rendered[y * width + (x + rectWidth)]) {
                    rectWidth++;
                }
                int rectHeight = 1;
                boolean canExtend = true;
                while (canExtend && y + rectHeight < height) {
                    for (int dx = 0; dx < rectWidth; dx++) {
                        int checkIdx = (y + rectHeight) * width + (x + dx);
                        if (!mask[checkIdx] || rendered[checkIdx]) {
                            canExtend = false;
                            break;
                        }
                    }
                    if (canExtend)
                        rectHeight++;
                }
                for (int dy = 0; dy < rectHeight; dy++) {
                    for (int dx = 0; dx < rectWidth; dx++)
                        rendered[(y + dy) * width + (x + dx)] = true;
                }
                if (n + 4 > out.length)
                    out = java.util.Arrays.copyOf(out, out.length * 2);
                out[n++] = x; out[n++] = y; out[n++] = rectWidth; out[n++] = rectHeight;
            }
        }
        int[] ret = java.util.Arrays.copyOf(out, n);
        RECTS.put(mask, ret);
        return ret;
    }

    /**
     * Renders a single overlay on the minimap from its cached merged rectangles.
     * The overlay data comes from the persistent MapFile storage.
     */
    private static void renderOverlay(MiniMap map, GOut g,
                                      MapFile.Overlay overlay,
                                      MiniMap.DisplayGrid disp,
                                      Coord hsz,
                                      Color fillColor) {
        try {
            // The overlay boolean array is indexed as: x + (y * cmaps.x)
            // where cmaps is the grid size (100x100 tiles typically)
            int width = MCache.cmaps.x;
            int height = MCache.cmaps.y;
            boolean[] mask = overlay.ol;

            if (mask.length != width * height) {
                return; // Invalid mask size
            }

            int[] rects = rects(mask, width, height);
            g.chcolor(fillColor);
            for (int i = 0; i < rects.length; i += 4) {
                int x = rects[i], y = rects[i + 1], rectWidth = rects[i + 2], rectHeight = rects[i + 3];

                // For NMiniMap, we need to use the same coordinate transformation as drawmap()
                if (!(map instanceof NMiniMap)) {
                    // Fallback for base MiniMap (shouldn't happen)
                    Coord tileUL = disp.sc.mul(MCache.cmaps).add(x, y);
                    Coord tileBR = disp.sc.mul(MCache.cmaps).add(x + rectWidth, y + rectHeight);
                    Coord screenUL = UI.scale(tileUL).sub(map.dloc.tc.div(map.scalef())).add(hsz);
                    Coord screenBR = UI.scale(tileBR).sub(map.dloc.tc.div(map.scalef())).add(hsz);
                    g.frect2(screenUL, screenBR);
                    continue;
                }

                NMiniMap nmap = (NMiniMap) map;

                // Get current scale for coordinate transformation
                float currentScale = nmap.getCurrentScale();

                // Grid tiles are at data level, so need to account for that
                int dataLevel = nmap.getDataLevelPublic();
                int gridTileSize = MCache.cmaps.x * (1 << dataLevel);

                // Tile coordinates in segment (at base level)
                Coord tileUL = new Coord(disp.sc.x * gridTileSize + x * (1 << dataLevel),
                                         disp.sc.y * gridTileSize + y * (1 << dataLevel));
                Coord tileBR = new Coord(disp.sc.x * gridTileSize + (x + rectWidth) * (1 << dataLevel),
                                         disp.sc.y * gridTileSize + (y + rectHeight) * (1 << dataLevel));

                // Convert to screen coordinates using current scale
                // Same formula as NMiniMap.drawmap(): UI.scale(tile).mul(currentScale).sub(dloc.tc.div(scalef())).add(hsz)
                // Use round for consistent alignment without gaps or overlaps
                Coord2d screenULDouble = new Coord2d(UI.scale(tileUL)).mul(currentScale).sub(new Coord2d(map.dloc.tc.div(map.scalef()))).add(new Coord2d(hsz));
                Coord2d screenBRDouble = new Coord2d(UI.scale(tileBR)).mul(currentScale).sub(new Coord2d(map.dloc.tc.div(map.scalef()))).add(new Coord2d(hsz));
                Coord screenUL = new Coord((int)Math.round(screenULDouble.x), (int)Math.round(screenULDouble.y));
                Coord screenBR = new Coord((int)Math.round(screenBRDouble.x), (int)Math.round(screenBRDouble.y));

                g.frect2(screenUL, screenBR);
            }

            g.chcolor(); // Reset color
        } catch (Exception e) {
            // Silently handle errors
        }
    }

    /* ------------------------------------------------------------------ claim borders */

    /** Personal and village claims get an outline; realms are big enough to see already. */
    private static boolean bordered(String tag) {
        return "cplot".equals(tag) || "vlg".equals(tag);
    }

    /** A neighbouring grid whose data is not to hand yet: leave that edge alone rather than flicker. */
    private static final boolean[] UNKNOWN = new boolean[0];

    /** Growable list of edge segments, four ints each: x1, y1, x2, y2 (grid tiles, or screen pixels). */
    private static final class Segs {
        int[] a = new int[64];
        int n = 0;

        void add(int x1, int y1, int x2, int y2) {
            if (n + 4 > a.length)
                a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x1; a[n++] = y1; a[n++] = x2; a[n++] = y2;
        }
    }

    /* Edges inside one grid depend on its mask alone, so they are found once per mask, like the
     * fill rectangles. Draw-thread only. */
    private static final java.util.Map<boolean[], int[]> EDGES = new java.util.WeakHashMap<>();

    /** Every boundary between a claimed and an unclaimed tile inside the grid, merged into runs. */
    private static int[] innerEdges(boolean[] m, int w, int h) {
        int[] cached = EDGES.get(m);
        if (cached != null)
            return cached;
        Segs out = new Segs();
        for (int y = 1; y < h; y++) {
            for (int x = 0; x < w; ) {
                if (m[(y - 1) * w + x] == m[y * w + x]) { x++; continue; }
                int x0 = x;
                while (x < w && m[(y - 1) * w + x] != m[y * w + x]) x++;
                out.add(x0, y, x, y);
            }
        }
        for (int x = 1; x < w; x++) {
            for (int y = 0; y < h; ) {
                if (m[y * w + x - 1] == m[y * w + x]) { y++; continue; }
                int y0 = y;
                while (y < h && m[y * w + x - 1] != m[y * w + x]) y++;
                out.add(x, y0, x, y);
            }
        }
        int[] ret = java.util.Arrays.copyOf(out.a, out.n);
        EDGES.put(m, ret);
        return ret;
    }

    /**
     * The same overlay's mask in a neighbouring grid: null when nothing of it is there (so the
     * claim ends at this edge), UNKNOWN when that cannot be told yet.
     */
    private static boolean[] neighbour(MiniMap.DisplayGrid[] display, Area dgext, Coord gc, String olname) {
        if (!dgext.contains(gc))
            return UNKNOWN; // Beyond what is displayed, so off screen anyway
        MiniMap.DisplayGrid d = display[dgext.ri(gc)];
        if (d == null)
            return null;    // Never mapped: the claim visibly ends here
        MapFile.DataGrid grid;
        try {
            grid = d.gref.get();
        } catch (Loading e) {
            return UNKNOWN;
        }
        if (grid == null)
            return null;
        for (MapFile.Overlay o : grid.ols) {
            if (o.olid.name.equals(olname))
                return o.ol;
        }
        return null;
    }

    private static boolean on(boolean[] nb, int idx) {
        return (nb != null) && (nb.length > idx) && nb[idx];
    }

    /**
     * Outlines a claim: a line wherever a claimed tile meets an unclaimed one, added to {@code out}
     * as screen coordinates. A claim that spans several grids is one shape - along a grid's edge
     * the line goes in only where the neighbouring grid's same overlay does not carry on, so no
     * seams show between grids.
     */
    private static void outline(NMiniMap nmap, MapFile.Overlay overlay, Coord gc,
                                MiniMap.DisplayGrid[] display, Area dgext, Coord hsz, Segs out) {
        int w = MCache.cmaps.x, h = MCache.cmaps.y;
        boolean[] m = overlay.ol;
        if (m.length != w * h)
            return;
        MiniMap.DisplayGrid disp = display[dgext.ri(gc)];
        String name = overlay.olid.name;
        Segs segs = new Segs();

        /* The four sides: a tile on the edge is outlined unless the neighbour's tile across it is
         * claimed by the same overlay. */
        boolean[] up = neighbour(display, dgext, gc.add(0, -1), name);
        boolean[] dn = neighbour(display, dgext, gc.add(0, 1), name);
        boolean[] lf = neighbour(display, dgext, gc.add(-1, 0), name);
        boolean[] rt = neighbour(display, dgext, gc.add(1, 0), name);
        for (int x = 0; x < w; x++) {
            if (up != UNKNOWN && m[x] && !on(up, (h - 1) * w + x))
                segs.add(x, 0, x + 1, 0);
            if (dn != UNKNOWN && m[(h - 1) * w + x] && !on(dn, x))
                segs.add(x, h, x + 1, h);
        }
        for (int y = 0; y < h; y++) {
            if (lf != UNKNOWN && m[y * w] && !on(lf, y * w + (w - 1)))
                segs.add(0, y, 0, y + 1);
            if (rt != UNKNOWN && m[y * w + (w - 1)] && !on(rt, y * w))
                segs.add(w, y, w, y + 1);
        }

        int lvl = nmap.getDataLevelPublic();
        float scale = nmap.getCurrentScale();
        int[] inner = innerEdges(m, w, h);
        for (int[] src : new int[][] {inner, java.util.Arrays.copyOf(segs.a, segs.n)}) {
            for (int i = 0; i < src.length; i += 4) {
                Coord a = screen(nmap, disp, src[i], src[i + 1], hsz, lvl, scale);
                Coord b = screen(nmap, disp, src[i + 2], src[i + 3], hsz, lvl, scale);
                out.add(a.x, a.y, b.x, b.y);
            }
        }
    }

    /** A dark halo under a bright line, so the outline reads on grass, water and snow alike. */
    private static void drawOutlines(GOut g, java.util.Map<Color, Segs> outlines) {
        if (outlines.isEmpty())
            return;
        int t = Math.max(1, UI.scale(1));
        g.chcolor(0, 0, 0, 170);
        for (Segs s : outlines.values())
            drawSegs(g, s, t * 2);
        for (java.util.Map.Entry<Color, Segs> e : outlines.entrySet()) {
            g.chcolor(e.getKey());
            drawSegs(g, e.getValue(), t);
        }
        g.chcolor();
    }

    /** Axis-aligned screen segments as filled bars, t either side of the line. */
    private static void drawSegs(GOut g, Segs s, int t) {
        for (int i = 0; i < s.n; i += 4) {
            /* Running each bar t past its ends closes the corners where two edges meet. */
            g.frect2(Coord.of(Math.min(s.a[i], s.a[i + 2]) - t, Math.min(s.a[i + 1], s.a[i + 3]) - t),
                     Coord.of(Math.max(s.a[i], s.a[i + 2]) + t, Math.max(s.a[i + 1], s.a[i + 3]) + t));
        }
    }

    /** A grid-local tile corner on screen, by the same sum renderOverlay uses for the fill. */
    private static Coord screen(NMiniMap nmap, MiniMap.DisplayGrid disp, int x, int y, Coord hsz, int lvl, float scale) {
        int step = 1 << lvl;
        Coord tile = new Coord(disp.sc.x * MCache.cmaps.x * step + x * step,
                               disp.sc.y * MCache.cmaps.y * step + y * step);
        Coord2d sc = new Coord2d(UI.scale(tile)).mul(scale)
            .sub(new Coord2d(nmap.dloc.tc.div(nmap.scalef()))).add(new Coord2d(hsz));
        return new Coord((int)Math.round(sc.x), (int)Math.round(sc.y));
    }

    private static final java.util.Map<Color, Color> BRIGHT = new java.util.HashMap<>();

    /** The claim's own hue, fully saturated and lit, at full opacity. */
    private static Color bright(Color fill) {
        Color c = BRIGHT.get(fill);
        if (c == null) {
            float[] hsb = Color.RGBtoHSB(fill.getRed(), fill.getGreen(), fill.getBlue(), null);
            c = Color.getHSBColor(hsb[0], Math.max(hsb[1], 0.75f), 1f);
            BRIGHT.put(fill, c);
        }
        return c;
    }

    /**
     * Extract color from the overlay's Material (supports different colors for enemy/friendly claims).
     * Falls back to hardcoded tag-based colors if Material extraction fails.
     */
    /* Material colors per overlay type; the material never changes once
     * loaded. Draw-thread only. */
    private static final java.util.Map<MCache.ResOverlay, Color> MATCOLORS = new java.util.WeakHashMap<>();

    private static Color extractColorFromMaterial(MCache.ResOverlay olinfo, String tag) {
        Color cached = MATCOLORS.get(olinfo);
        if (cached != null)
            return cached;
        try {
            Material mat = olinfo.mat();
            if (mat != null) {
                BufPipe st = new BufPipe();
                mat.states.apply(st);
                if (st.get(BaseColor.slot) != null) {
                    FColor bc = st.get(BaseColor.slot).color;
                    // Convert to semi-transparent for minimap (alpha=60)
                    Color c = new Color(
                        Math.round(bc.r * 255),
                        Math.round(bc.g * 255),
                        Math.round(bc.b * 255),
                        60  // Semi-transparent to avoid obscuring terrain
                    );
                    MATCOLORS.put(olinfo, c);
                    return c;
                }
            }
        } catch (Loading e) {
            // Material not loaded yet, will retry next frame
            throw e;
        } catch (Exception e) {
            // Other errors - fall through to tag-based fallback
        }

        // Fallback to hardcoded tag-based colors if Material extraction fails
        return getColorForTag(tag);
    }

    /**
     * Get fallback fill color for a claim tag (used when Material extraction fails)
     */
    private static Color getColorForTag(String tag) {
        switch (tag) {
            case "cplot":
                return CPLOT_COLOR;
            case "vlg":
                return VLG_COLOR;
            case "prov":
            case "realm":
                return PROV_COLOR;
            default:
                return null;
        }
    }

    /**
     * Check if a minimap overlay is enabled in QoL settings
     */
    private static boolean isMinimapOverlayEnabled(String tag) {
        Object val;
        switch (tag) {
            case "cplot":
                val = NConfig.get(NConfig.Key.minimapClaimol);
                return val instanceof Boolean ? (Boolean) val : false;
            case "vlg":
                val = NConfig.get(NConfig.Key.minimapVilol);
                return val instanceof Boolean ? (Boolean) val : false;
            case "prov":
            case "realm":
                val = NConfig.get(NConfig.Key.minimapRealmol);
                return val instanceof Boolean ? (Boolean) val : false;
            default:
                return false;
        }
    }
}
