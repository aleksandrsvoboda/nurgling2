package nurgling.actions;

import haven.*;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.db.dao.ContainerDao;
import nurgling.navigation.ChunkNavManager;
import nurgling.tasks.NTask;
import nurgling.tools.Finder;

import java.sql.SQLException;

import static haven.OCache.posres;

/**
 * Walks to a container known only from the Storage Items index (storageitems/containers rows),
 * until its gob is loaded. Moved out of FetchStorageItemBot so autocraft can share it.
 */
public final class StorageNav {
    /** How long to wait for the container's gob to stream in after arriving. */
    private static final int GOB_WAIT_TICKS = 300;

    private StorageNav() {
    }

    /**
     * Find the stored container's gob, walking to where the containers row says it stands if it
     * isn't loaded. Does not step up to it.
     * @return the gob, or null if its row is missing, the spot can't be reached or nothing is there
     */
    public static Gob reach(NGameUI gui, String containerHash) throws InterruptedException {
        Gob gob = Finder.findStorageGob(containerHash);
        if (gob != null)
            return gob;
        ContainerDao.ContainerData data = loadContainerData(containerHash);
        if (data == null) {
            System.out.println("[StorageNav] Could not load container data from DB for hash: " + containerHash);
            return null;
        }
        return reach(gui, containerHash, data.getGridId(), data.getCoord());
    }

    /**
     * As {@link #reach(NGameUI, String)}, with the container's location already known.
     * @param coord the containers row's coord, "(x, y)" in posres units inside the grid
     */
    public static Gob reach(NGameUI gui, String containerHash, long gridId, String coord) throws InterruptedException {
        Gob gob = Finder.findStorageGob(containerHash);
        if (gob != null)
            return gob;
        Coord localCoord = parseLocalCoordinates(coord);
        if (localCoord == null) {
            System.out.println("[StorageNav] Could not parse coordinates: " + coord);
            return null;
        }
        System.out.println("[StorageNav] Navigating to container at grid=" + gridId + " coord=" + localCoord);
        if (!navigateToContainer(gui, gridId, localCoord)) {
            System.out.println("[StorageNav] Navigation failed to grid=" + gridId);
            return null;
        }
        // Gobs may take a moment to load after arriving
        final Gob[] found = {null};
        NUtils.addTask(new NTask() {
            int ticks = 0;

            @Override
            public boolean check() {
                found[0] = Finder.findStorageGob(containerHash);
                return found[0] != null || ticks++ > GOB_WAIT_TICKS;
            }
        });
        if (found[0] == null)
            System.out.println("[StorageNav] Container not found after navigation, hash=" + containerHash);
        return found[0];
    }

    private static ContainerDao.ContainerData loadContainerData(String containerHash) {
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()) {
            return null;
        }
        try {
            return NCore.databaseManager.executeOperation(adapter -> new ContainerDao().loadContainer(adapter, containerHash));
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Parse local coordinates string "(x, y)" to Coord (in posres units, as stored by ContainerWatcher)
     */
    private static Coord parseLocalCoordinates(String coords) {
        if (coords == null) return null;
        String[] parts = coords.replace("(", "").replace(")", "").replace(" ", "").split(",");
        if (parts.length != 2)
            return null;
        try {
            return new Coord((int) Double.parseDouble(parts[0]), (int) Double.parseDouble(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Navigate to a container using gridId + local coordinates: local pathfinding when the grid is
     * loaded, chunk navigation (which plans across layers) otherwise.
     */
    private static boolean navigateToContainer(NGameUI gui, long gridId, Coord localCoord) throws InterruptedException {
        ChunkNavManager chunkNav = ((NMapView) gui.map).getChunkNavManager();
        if (chunkNav == null || !chunkNav.isInitialized()) {
            System.out.println("[StorageNav] ChunkNavManager not available");
            return false;
        }

        MCache mcache = gui.map.glob.map;
        MCache.Grid grid = null;
        synchronized (mcache.grids) {
            for (MCache.Grid g : mcache.grids.values()) {
                if (g.id == gridId) {
                    grid = g;
                    break;
                }
            }
        }
        if (grid != null) {
            // worldPos = gridOrigin(world) + localCoord * posres
            Coord2d gridOrigin = new Coord2d(grid.ul.x * MCache.tilesz.x, grid.ul.y * MCache.tilesz.y);
            Coord2d worldPos = gridOrigin.add(new Coord2d(localCoord.x * posres.x, localCoord.y * posres.y));
            return new PathFinder(worldPos).run(gui).IsSuccess();
        }

        // Grid not loaded: chunk nav plans in tile coordinates
        Coord2d offsetWorld = new Coord2d(localCoord.x * posres.x, localCoord.y * posres.y);
        Coord tileCoord = offsetWorld.floor(MCache.tilesz);
        nurgling.navigation.ChunkPath path = chunkNav.planToGridCoord(gridId, tileCoord);
        if (path != null) {
            return chunkNav.navigateWithPath(path, null, gui).IsSuccess();
        }
        System.out.println("[StorageNav] Cannot plan path to grid=" + gridId + " tile=" + tileCoord);
        return false;
    }
}
