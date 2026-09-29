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
        NMiniMap nmap = (NMiniMap) map;

        // Get the display grid array and extent
        MiniMap.DisplayGrid[] display = nmap.getDisplay();
        Area dgext = nmap.getDgext();

        if (display == null || dgext == null) {
            return;
        }

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
