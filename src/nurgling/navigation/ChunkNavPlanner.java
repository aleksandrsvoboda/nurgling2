package nurgling.navigation;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.areas.NArea;

import java.util.*;

import static nurgling.navigation.ChunkNavConfig.*;

/**
 * A* pathfinding on the chunk graph.
 * Plans global paths from player position to target areas.
 */
public class ChunkNavPlanner {
    private final ChunkNavGraph graph;
    private final UnifiedTilePathfinder unifiedPathfinder;

    public ChunkNavPlanner(ChunkNavGraph graph) {
        this.graph = graph;
        this.unifiedPathfinder = new UnifiedTilePathfinder(graph);
    }

    /**
     * Set chunks where portal traversal has failed.
     * The pathfinder will avoid using portals on these chunks.
     */
    public void setExcludedPortalChunks(Set<Long> excludedChunks) {
        if (excludedChunks != null && !excludedChunks.isEmpty()) {
            this.unifiedPathfinder.setExcludedPortalChunks(excludedChunks);
        }
    }

    /**
     * The area's per-grid pieces (local tile bounds, br exclusive), as stored in {@link NArea#space}.
     */
    public static Map<Long, haven.Area> pieces(NArea area) {
        Map<Long, haven.Area> res = new HashMap<>();
        if (area == null || area.space == null || area.space.space == null) return res;
        for (Map.Entry<Long, NArea.VArea> e : area.space.space.entrySet()) {
            if (e.getValue() != null && e.getValue().area != null)
                res.put(e.getKey(), e.getValue().area);
        }
        return res;
    }

    /**
     * Plan a path from the player to the nearest way into an area: the first walkable tile inside it or in the
     * one-tile ring around it, from whichever side is cheapest. Null if the area cannot be reached.
     */
    public ChunkPath planToArea(NArea area) {
        if (area == null) return null;
        PlayerLocation playerLoc = getPlayerLocation();
        if (playerLoc == null) return null;
        return planToArea(pieces(area), playerLoc.gridId, playerLoc.localCoord);
    }

    /** {@link #planToArea(NArea)} from an explicit start tile. Needs no game UI. */
    public ChunkPath planToArea(Map<Long, haven.Area> pieces, long startChunkId, Coord startLocal) {
        AreaGoalSet goal = AreaGoalSet.ofArea(graph, pieces);
        UnifiedTilePathfinder.UnifiedPath up = unifiedPathfinder.findPathToAny(startChunkId, startLocal, goal);
        return toChunkPath(up);
    }

    /** Result of {@link #nearestOf}: which candidate was nearest, and the path to it. */
    public static final class Nearest {
        public final int index;
        public final ChunkPath path;

        Nearest(int index, ChunkPath path) {
            this.index = index;
            this.path = path;
        }
    }

    /**
     * The candidate area the player can reach most cheaply, found with one search over all of them. Null if none
     * is reachable.
     */
    public Nearest nearestOf(List<NArea> areas) {
        if (areas == null || areas.isEmpty()) return null;
        PlayerLocation playerLoc = getPlayerLocation();
        if (playerLoc == null) return null;
        List<Map<Long, haven.Area>> all = new ArrayList<>(areas.size());
        for (NArea a : areas) all.add(pieces(a));
        return nearestOf(all, playerLoc.gridId, playerLoc.localCoord);
    }

    /** {@link #nearestOf(List)} from an explicit start tile. Needs no game UI. */
    public Nearest nearestOf(List<Map<Long, haven.Area>> areas, long startChunkId, Coord startLocal) {
        List<AreaGoalSet> goals = new ArrayList<>(areas.size());
        for (Map<Long, haven.Area> p : areas) goals.add(AreaGoalSet.ofArea(graph, p));
        UnifiedTilePathfinder.UnifiedPath up = unifiedPathfinder.findNearest(startChunkId, startLocal, goals);
        ChunkPath path = toChunkPath(up);
        return path == null ? null : new Nearest(up.goalIndex, path);
    }

    private ChunkPath toChunkPath(UnifiedTilePathfinder.UnifiedPath up) {
        if (up == null || !up.reachable) return null;
        ChunkPath path = new ChunkPath();
        up.populateChunkPath(path, graph);
        return path;
    }

    /** The tile search behind this planner (its lastPops / lastNanos describe the last plan). */
    public UnifiedTilePathfinder getTilePathfinder() {
        return unifiedPathfinder;
    }

    /**
     * Plan a path from player's current position to a specific world coordinate.
     * Used for planning paths to specific corners of an area.
     * NOTE: This only works reliably within the same layer/area!
     */
    public ChunkPath planToCoord(Coord2d worldCoord) {
        if (worldCoord == null) return null;

        // Get player's current chunk and local position
        PlayerLocation playerLoc = getPlayerLocation();
        if (playerLoc == null) {
            return null;
        }

        long startChunkId = playerLoc.gridId;
        Coord playerLocal = playerLoc.localCoord;

        // Convert world coord to tile coord
        Coord targetTile = worldCoord.floor(MCache.tilesz);
        
        // Find which chunk contains this tile
        TargetLocation target = findTargetLocationForTile(targetTile);
        if (target == null) {
            return null;
        }

        // Use unified pathfinder to get complete tile-level path
        UnifiedTilePathfinder.UnifiedPath unifiedPath = unifiedPathfinder.findPath(
            startChunkId, playerLocal,
            target.chunkId, target.localCoord
        );

        if (unifiedPath == null || !unifiedPath.reachable) {
            return null;
        }

        // Convert to ChunkPath with segments
        ChunkPath path = new ChunkPath();
        unifiedPath.populateChunkPath(path, graph);

        return path;
    }
    
    /**
     * Plan a path from player's current position to a specific grid + local coordinate.
     * Works correctly across different layers because it uses gridId directly.
     * @param gridId The target grid ID
     * @param localCoord Local tile coordinate within the grid
     */
    public ChunkPath planToGridCoord(long gridId, Coord localCoord) {
        if (localCoord == null) {
            return null;
        }

        // Get player's current chunk and local position
        PlayerLocation playerLoc = getPlayerLocation();
        if (playerLoc == null) {
            return null;
        }

        long startChunkId = playerLoc.gridId;
        Coord playerLocal = playerLoc.localCoord;

        // Check if target grid is in our graph
        ChunkNavData chunk = graph.getChunk(gridId);
        if (chunk == null) {
            return null;
        }

        // Find walkable tile near the target coordinate
        Coord walkable = findWalkableTileNear(chunk, localCoord);
        if (walkable == null) {
            walkable = localCoord; // fallback
        }

        // Use unified pathfinder
        UnifiedTilePathfinder.UnifiedPath unifiedPath = unifiedPathfinder.findPath(
            startChunkId, playerLocal,
            gridId, walkable
        );

        if (unifiedPath == null || !unifiedPath.reachable) {
            return null;
        }

        // Convert to ChunkPath with segments
        ChunkPath path = new ChunkPath();
        unifiedPath.populateChunkPath(path, graph);

        return path;
    }

    /**
     * Find the target chunk and local coordinate for a specific world tile.
     */
    private TargetLocation findTargetLocationForTile(Coord targetTile) {
        try {
            // Try loaded grid first
            NGameUI gui = NUtils.getGameUI();
            if (gui != null && gui.map != null && gui.map.glob != null) {
                MCache mcache = gui.map.glob.map;
                MCache.Grid grid = mcache.getgridt(targetTile);
                if (grid != null) {
                    Coord localCoord = targetTile.sub(grid.ul);
                    if (localCoord.x >= 0 && localCoord.x < CHUNK_SIZE &&
                        localCoord.y >= 0 && localCoord.y < CHUNK_SIZE) {
                        // Check if walkable
                        ChunkNavData chunk = graph.getChunk(grid.id);
                        if (chunk != null) {
                            Coord walkable = findWalkableTileNear(chunk, localCoord);
                            if (walkable != null) {
                                return new TargetLocation(grid.id, walkable);
                            }
                        }
                        return new TargetLocation(grid.id, localCoord);
                    }
                }
            }
            
            // Search recorded chunks by worldTileOrigin
            for (ChunkNavData chunk : graph.getAllChunks()) {
                if (chunk.worldTileOrigin == null) continue;

                Coord localCoord = targetTile.sub(chunk.worldTileOrigin);
                if (localCoord.x >= 0 && localCoord.x < CHUNK_SIZE &&
                    localCoord.y >= 0 && localCoord.y < CHUNK_SIZE) {
                    Coord walkable = findWalkableTileNear(chunk, localCoord);
                    if (walkable != null) {
                        return new TargetLocation(chunk.gridId, walkable);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Check if a specific cell is walkable.
     */
    private boolean isCellWalkable(ChunkNavData chunk, int cellX, int cellY) {
        if (cellX >= 0 && cellX < CELLS_PER_EDGE && cellY >= 0 && cellY < CELLS_PER_EDGE) {
            return chunk.walkability[cellX][cellY] == 0;
        }
        return false;
    }
    
    /**
     * Find a walkable tile near the given coordinate.
     * Searches in cell grid (half-tile resolution, ~5.5 world units per step)
     * with max radius of 4 cells from the target.
     */
    private Coord findWalkableTileNear(ChunkNavData chunk, Coord target) {
        // Convert target tile to cell coordinates (center of the tile)
        int targetCellX = target.x * CELLS_PER_TILE;
        int targetCellY = target.y * CELLS_PER_TILE;
        
        // Check the target cell itself first
        if (isCellWalkable(chunk, targetCellX, targetCellY)) {
            return target;
        }

        // Search in expanding rings around the target in CELL coordinates
        // Max radius = 4 cells = 2 tiles = ~22 world units
        final int MAX_CELL_RADIUS = 4;
        
        for (int radius = 1; radius <= MAX_CELL_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius) continue; // Only perimeter

                    int cx = targetCellX + dx;
                    int cy = targetCellY + dy;
                    
                    if (isCellWalkable(chunk, cx, cy)) {
                        // Convert cell back to tile
                        int tileX = cx / CELLS_PER_TILE;
                        int tileY = cy / CELLS_PER_TILE;
                        if (tileX >= 0 && tileX < CHUNK_SIZE && tileY >= 0 && tileY < CHUNK_SIZE) {
                            return new Coord(tileX, tileY);
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Target location result.
     */
    private static class TargetLocation {
        final long chunkId;
        final Coord localCoord;

        TargetLocation(long chunkId, Coord localCoord) {
            this.chunkId = chunkId;
            this.localCoord = localCoord;
        }
    }

    /**
     * Player location result.
     */
    private static class PlayerLocation {
        final long gridId;
        final Coord localCoord;

        PlayerLocation(long gridId, Coord localCoord) {
            this.gridId = gridId;
            this.localCoord = localCoord;
        }
    }

    /**
     * Get player's current grid ID and local coordinate using direct MCache lookup.
     * This is more reliable than ngob.grid_id which can be stale after teleports.
     */
    private PlayerLocation getPlayerLocation() {
        try {
            NGameUI gui = NUtils.getGameUI();
            if (gui == null || gui.map == null || gui.map.glob == null) {
                return null;
            }

            Gob player = NUtils.player();
            if (player == null) return null;

            MCache mcache = gui.map.glob.map;
            Coord playerTile = player.rc.floor(MCache.tilesz);

            // Direct lookup - most reliable
            MCache.Grid grid = mcache.getgridt(playerTile);
            if (grid != null) {
                Coord localCoord = playerTile.sub(grid.ul);
                // Validate
                if (localCoord.x >= 0 && localCoord.x < CHUNK_SIZE &&
                    localCoord.y >= 0 && localCoord.y < CHUNK_SIZE) {
                    return new PlayerLocation(grid.id, localCoord);
                }
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
