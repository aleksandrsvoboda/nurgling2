package nurgling.contextmenu;

import haven.Coord2d;
import haven.MCache;
import monitoring.ItemWatcher;
import monitoring.NGlobalSearchItems;
import nurgling.NCore;
import nurgling.NGameUI;
import nurgling.NMapView;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.navigation.StorageTrailService;

import java.awt.Color;
import java.sql.SQLException;

/**
 * Removes a container the item database still believes in but the world no longer has.
 *
 * Container rows are written when a container is opened and nothing ever deletes them, so
 * a cupboard that has since been torn down keeps sending storage trails to an empty patch
 * of ground. Ctrl+RMB on that patch offers this, which drops the row and its items.
 *
 * The offer only appears over a container a trail is currently pointing at - see
 * {@link StorageTrailService#containerAt} for why.
 */
public class DeleteStorageContainerAction implements TileContextAction {

    /** How near the recorded spot the click has to land. Containers are a couple of tiles wide. */
    private static final double RADIUS = MCache.tilesz.x * 2.5;

    @Override
    public boolean appliesTo(Coord2d mapPos) {
        return containerAt(mapPos) != null;
    }

    @Override
    public String label() {
        return nurgling.i18n.L10n.get("context.delete_storage_container");
    }

    @Override
    public Action create(Coord2d mapPos) {
        // Resolve at menu-build time, not at run time: by the time the action runs the
        // player may have taken a step and the trail may have re-ranked, and deleting a
        // different container than the one that was clicked would be unrecoverable.
        final String hash = containerAt(mapPos);
        return gui -> deleteContainer(gui, hash);
    }

    private static Results deleteContainer(NGameUI gui, String hash) {
        if (hash == null)
            return Results.FAIL();
        if (NCore.databaseManager == null || !NCore.databaseManager.isReady()) {
            gui.error(nurgling.i18n.L10n.get("context.delete_storage_container_nodb"));
            return Results.FAIL();
        }

        try {
            // The SQLite schema declares no foreign key, so the items have to go explicitly
            // rather than relying on a cascade that only exists on some backends.
            NCore.databaseManager.getStorageItemService().deleteStorageItemsByContainer(hash);
            NCore.databaseManager.getContainerService().deleteContainer(hash);
        } catch (SQLException e) {
            gui.error(nurgling.i18n.L10n.get("context.delete_storage_container_failed"));
            e.printStackTrace();
            return Results.FAIL();
        }

        ItemWatcher.invalidateContainerCache(hash);

        StorageTrailService trail = trailService();
        if (trail != null)
            trail.forget(hash);

        // The query cache would otherwise serve the pre-delete result set for another couple
        // of seconds, leaving the trail pointing at a row that is already gone.
        NGlobalSearchItems.clearQueryCache();
        if (gui.itemsForSearch != null)
            gui.itemsForSearch.refreshSearch();

        gui.msg(nurgling.i18n.L10n.get("context.delete_storage_container_done"), Color.GREEN);
        return Results.SUCCESS();
    }

    private static String containerAt(Coord2d mapPos) {
        StorageTrailService trail = trailService();
        if (trail == null)
            return null;
        return trail.containerAt(mapPos, RADIUS);
    }

    private static StorageTrailService trailService() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || !(gui.map instanceof NMapView))
            return null;
        return ((NMapView) gui.map).getStorageTrailService();
    }
}
