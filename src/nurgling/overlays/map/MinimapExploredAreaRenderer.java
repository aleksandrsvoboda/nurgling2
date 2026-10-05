package nurgling.overlays.map;

import haven.*;
import nurgling.NConfig;
import nurgling.tools.ExploredArea;
import nurgling.widgets.NCornerMiniMap;
import nurgling.widgets.NMiniMap;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;

/**
 * Renders explored area overlay on the minimap.
 * Uses ExploredArea rectangle data to generate overlay masks for each DisplayGrid.
 * Similar to MinimapClaimRenderer but for explored (visited) areas.
 * 
 * Supports rendering both main explored area and session layer.
 */
public class MinimapExploredAreaRenderer implements Disposable {
    private static final ThreadPoolExecutor rasterizer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "exploration-overlay"); t.setDaemon(true); return t;
            });
    private ExploredArea source;
    private int updatesLeft;
    
    /**
     * Main rendering method called from MiniMap's drawparts()
     * Renders explored area overlay on top of map tiles.
     * Also renders session layer if active.
     *
     * @param map The minimap instance
     * @param g   Graphics context
     */
    public void renderExploredArea(MiniMap map, GOut g) {
        // Check if feature is enabled
        Object val = NConfig.get(NConfig.Key.exploredAreaEnable);
        if (!(val instanceof Boolean) || !(Boolean) val) {
            return;
        }
        
        if (map.ui == null || map.ui.gui == null || map.ui.gui.map == null) {
            return;
        }

        MapView mv = map.ui.gui.map;
        if (mv == null || map.dloc == null || map.sessloc == null) {
            return;
        }

        // Must be NMiniMap to access display grids and exploredArea
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
            int dataLevel = nmap.getDataLevelPublic();
            float scaleFactor = nmap.getCurrentScale();

            // Get explored area data from the main minimap (corner minimap)
            // This ensures both the corner minimap and the large map window 
            // use the same explored area data
            ExploredArea exploredArea = null;
            if (map.ui.gui.mmap instanceof NCornerMiniMap) {
                exploredArea = ((NCornerMiniMap) map.ui.gui.mmap).exploredArea;
            }
            if (exploredArea == null) {
                // Fallback to the map's own explored area if corner minimap not available
                exploredArea = nmap.exploredArea;
            }
            if (exploredArea == null) {
                return;
            }
            if(source != exploredArea) {dispose(); source = exploredArea;}
            beginFrame(); // Bound texture creation/upload work in any one frame.

            // Check if player is in current segment
            boolean playerSegment = (map.sessloc != null) && 
                                   ((map.curloc == null) || (map.sessloc.seg.id == map.curloc.seg.id));
            if (!playerSegment) {
                return; // Only show explored area in current segment
            }

            // At dataLevel N, each display grid represents (2^N) x (2^N) base grids
            int gridScale = (1 << dataLevel);
            
            // Iterate through all display grids
            for (Coord gc : dgext) {
                MiniMap.DisplayGrid disp = display[dgext.ri(gc)];
                if (disp == null) {
                    continue;
                }
                
                // Calculate which base grids this display grid covers
                Coord baseGridStart = disp.sc.mul(gridScale);
                Coord baseGridEnd = baseGridStart.add(gridScale, gridScale);
                
                // Render each base grid separately
                for (int bgy = baseGridStart.y; bgy < baseGridEnd.y; bgy++) {
                    for (int bgx = baseGridStart.x; bgx < baseGridEnd.x; bgx++) {
                        Coord baseGridCoord = new Coord(bgx, bgy);
                        
                        // Render main explored area
                        boolean[] baseMask = exploredArea.getExploredMaskForGrid(baseGridCoord, map.sessloc.seg.id, 0);
                        if (baseMask != null) {
                            renderGridOverlay(g, map, nmap, baseGridCoord, baseMask, 
                                NMiniMap.VIEW_EXPLORED_COLOR, hsz, scaleFactor, dataLevel, false);
                        }
                        
                        // Render session layer on top if active
                        boolean[] sessionMask = exploredArea.getSessionMaskForGrid(baseGridCoord, map.sessloc.seg.id);
                        if (sessionMask != null) {
                            renderGridOverlay(g, map, nmap, baseGridCoord, sessionMask,
                                NMiniMap.VIEW_SESSION_COLOR, hsz, scaleFactor, dataLevel, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently handle errors
        }
    }
    
    /**
     * Render a single grid's overlay
     */
    private void renderGridOverlay(GOut g, MiniMap map, NMiniMap nmap,
            Coord baseGridCoord, boolean[] mask, Color color,
            Coord hsz, float scaleFactor, int dataLevel, boolean isSession) {
        try {
                // Calculate screen position for this base grid
                int gridTileSize = MCache.cmaps.x; // Always 100 for base level
                int bgx = baseGridCoord.x;
                int bgy = baseGridCoord.y;
                
                // This base grid's tile coordinate in the segment
                Coord baseTileUL = new Coord(bgx * gridTileSize, bgy * gridTileSize);
                
                // Convert to screen coordinates
                Coord baseTileBR = new Coord((bgx + 1) * gridTileSize, (bgy + 1) * gridTileSize);
                Coord2d screenULDouble = new Coord2d(UI.scale(baseTileUL)).mul(scaleFactor).sub(new Coord2d(map.dloc.tc.div(map.scalef()))).add(new Coord2d(hsz));
                Coord2d screenBRDouble = new Coord2d(UI.scale(baseTileBR)).mul(scaleFactor).sub(new Coord2d(map.dloc.tc.div(map.scalef()))).add(new Coord2d(hsz));
                Coord screenUL = new Coord((int)Math.round(screenULDouble.x), (int)Math.round(screenULDouble.y));
                Coord screenBR = new Coord((int)Math.round(screenBRDouble.x), (int)Math.round(screenBRDouble.y));
                
                // Image size calculated from exact boundaries
                Coord imgsz = screenBR.sub(screenUL);
                if(imgsz.x <= 0 || imgsz.y <= 0 || screenBR.x <= 0 || screenBR.y <= 0 ||
                        screenUL.x >= map.sz.x || screenUL.y >= map.sz.y) return;
                Tex overlayImg = getOverlay(baseGridCoord, map.sessloc.seg.id, mask, color, isSession);
                if(overlayImg == null) return;
                
                // Draw overlay
                g.chcolor(color);
                g.image(overlayImg, screenUL, imgsz);
                g.chcolor();
        } catch (Exception e) {
            // Ignore rendering errors
        }
    }

    /**
     * Cache for explored area overlays with version tracking
     */
    private static class ExploredOverlayCache {
        Tex img;
        boolean[] readyMask, pendingMask;
        java.util.concurrent.Future<BufferedImage> pending;
        void dispose() {
            if(img != null) img.dispose();
            if(pending != null) pending.cancel(false);
        }
    }
    
    private static class CacheKey {
        final Coord baseGridCoord;
        final long segmentId;
        final boolean session;
        
        CacheKey(Coord baseGridCoord, long segmentId, boolean session) {
            this.baseGridCoord = new Coord(baseGridCoord);
            this.segmentId = segmentId;
            this.session = session;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return segmentId == k.segmentId && session == k.session && baseGridCoord.equals(k.baseGridCoord);
        }
        
        @Override
        public int hashCode() {
            return (baseGridCoord.hashCode() * 31 + Long.hashCode(segmentId)) * 31 + (session ? 1 : 0);
        }
    }
    
    private final Map<CacheKey, ExploredOverlayCache> overlayCache = new LinkedHashMap<>(64, .75f, true);

    Tex getOverlay(Coord grid, long segment, boolean[] mask, Color color, boolean session) {
        CacheKey key = new CacheKey(grid, segment, session);
        ExploredOverlayCache cache = overlayCache.get(key);
        if(cache == null) {
            cache = new ExploredOverlayCache();
            overlayCache.put(key, cache);
            if(overlayCache.size() > 512) {
                Iterator<ExploredOverlayCache> it = overlayCache.values().iterator();
                it.next().dispose(); it.remove();
            }
        }
        if(cache.pending != null && cache.pending.isDone() && updatesLeft > 0) {
            try {
                BufferedImage image = cache.pending.get(); // isDone: never waits on the render thread.
                if(cache.pendingMask == mask) {
                    if(cache.img != null) cache.img.dispose();
                    cache.img = new TexI(image);
                    cache.readyMask = mask;
                    updatesLeft--;
                }
            } catch(InterruptedException e) {Thread.currentThread().interrupt();}
            catch(ExecutionException | CancellationException e) { /* Retry current data below. */ }
            cache.pending = null;
            cache.pendingMask = null;
        }
        if(cache.readyMask != mask && cache.pending == null) {
            try {
                cache.pending = rasterizer.submit(() -> renderOverlayImage(mask, color));
                cache.pendingMask = mask;
            } catch(RejectedExecutionException busy) { /* Keep the old texture; retry next frame. */ }
        }
        return cache.img;
    }

    void beginFrame() {updatesLeft = 2;}

    public void dispose() {
        for(ExploredOverlayCache cache : overlayCache.values()) cache.dispose();
        overlayCache.clear();
        source = null;
    }

    static BufferedImage renderOverlayImage(boolean[] mask, Color color) {
        int width = MCache.cmaps.x, height = MCache.cmaps.y;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
        int argb = color.getRGB();
        for(int i = 0; i < pixels.length; i++) if(mask[i]) pixels[i] = argb;
        return image;
    }
}
