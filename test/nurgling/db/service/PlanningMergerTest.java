package nurgling.db.service;

import nurgling.planning.PlanningFieldGroup;
import nurgling.planning.PlanningLayer;
import nurgling.planning.PlanningSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningMergerTest {
    @Test
    void unchanged_snapshots_need_no_merge() {
        PlanningLayer local = local_layer();

        PlanningMerger.Result result = PlanningMerger.merge(local, remote("Original", "folder-a", 0));

        assertEquals("Original", result.merged.name);
        assertEquals("folder-a", result.merged.parentId);
        assertEquals(0, result.merged.orderIndex);
        assertTrue(result.takeRemote.isEmpty());
        assertTrue(result.remoteOverrode.isEmpty());
    }

    @Test
    void takes_remote_change_when_local_is_unchanged() {
        PlanningLayer local = local_layer();

        PlanningMerger.Result result = PlanningMerger.merge(local, remote("Remote", "folder-a", 0));

        assertEquals("Remote", result.merged.name);
        assertEquals(EnumSet.of(PlanningFieldGroup.IDENTITY), result.takeRemote);
        assertTrue(result.remoteOverrode.isEmpty());
    }

    @Test
    void combines_changes_to_different_field_groups() {
        PlanningLayer local = local_layer();
        local.name = "Local";

        PlanningMerger.Result result = PlanningMerger.merge(local, remote("Original", "folder-b", 3));

        assertEquals("Local", result.merged.name);
        assertEquals("folder-b", result.merged.parentId);
        assertEquals(3, result.merged.orderIndex);
        assertEquals(EnumSet.of(PlanningFieldGroup.STRUCTURE), result.takeRemote);
        assertTrue(result.remoteOverrode.isEmpty());
    }

    @Test
    void remote_wins_when_both_change_the_same_field_group() {
        PlanningLayer local = local_layer();
        local.name = "Local";

        PlanningMerger.Result result = PlanningMerger.merge(local, remote("Remote", "folder-a", 0));

        assertEquals("Remote", result.merged.name);
        assertEquals(EnumSet.of(PlanningFieldGroup.IDENTITY), result.takeRemote);
        assertEquals(EnumSet.of(PlanningFieldGroup.IDENTITY), result.remoteOverrode);
    }

    private static PlanningLayer local_layer() {
        PlanningLayer layer = new PlanningLayer("layer", "Original", true, "folder-a");
        layer.version = 1;
        layer.captureBaseline();
        return layer;
    }

    private static PlanningSnapshot remote(String name, String parentId, int orderIndex) {
        return new PlanningSnapshot(name, parentId, orderIndex, 2);
    }
}
