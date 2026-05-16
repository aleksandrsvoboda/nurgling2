package nurgling.db.service;

import nurgling.planning.PlanningFieldGroup;
import nurgling.planning.PlanningNode;
import nurgling.planning.PlanningSnapshot;

import java.util.EnumSet;
import java.util.Set;

/**
 * Three-way merge for {@link PlanningNode}s (folders and layers). Mirrors
 * {@link AreaMerger}: each {@link PlanningFieldGroup} is treated independently;
 * conflicts (both sides edited the same group since baseline) fall back to
 * server-timestamp LWW (i.e. remote wins).
 *
 * Visibility is per-user and never enters the merge.
 *
 * Ghosts are handled whole-row (atomic insert/update/tombstone) and don't use
 * this merger.
 */
public final class PlanningMerger {

    public static final class Result {
        public final PlanningSnapshot merged;
        public final Set<PlanningFieldGroup> takeRemote;
        public final Set<PlanningFieldGroup> remoteOverrode;
        public final boolean hasRemoteWork;
        public final boolean hasLocalWork;
        public final int newVersion;

        Result(PlanningSnapshot merged,
               Set<PlanningFieldGroup> takeRemote,
               Set<PlanningFieldGroup> remoteOverrode,
               boolean hasRemoteWork, boolean hasLocalWork, int newVersion) {
            this.merged = merged;
            this.takeRemote = takeRemote;
            this.remoteOverrode = remoteOverrode;
            this.hasRemoteWork = hasRemoteWork;
            this.hasLocalWork = hasLocalWork;
            this.newVersion = newVersion;
        }
    }

    private PlanningMerger() {}

    public static Result merge(PlanningNode local, PlanningSnapshot remote) {
        PlanningSnapshot localSnap = PlanningSnapshot.of(local);
        PlanningSnapshot baseline = local.baselineSnapshot;

        Set<PlanningFieldGroup> localDirty = PlanningSnapshot.diff(baseline, localSnap);
        Set<PlanningFieldGroup> remoteDirty = PlanningSnapshot.diff(baseline, remote);

        EnumSet<PlanningFieldGroup> takeRemote = EnumSet.noneOf(PlanningFieldGroup.class);
        EnumSet<PlanningFieldGroup> remoteOverrode = EnumSet.noneOf(PlanningFieldGroup.class);

        for (PlanningFieldGroup g : PlanningFieldGroup.values()) {
            boolean l = localDirty.contains(g);
            boolean r = remoteDirty.contains(g);
            if (r && !l) {
                takeRemote.add(g);
            } else if (r && l) {
                takeRemote.add(g);
                remoteOverrode.add(g);
            }
        }

        String name = takeRemote.contains(PlanningFieldGroup.IDENTITY) ? remote.name : localSnap.name;
        String parentId = takeRemote.contains(PlanningFieldGroup.STRUCTURE) ? remote.parentId : localSnap.parentId;
        int orderIndex = takeRemote.contains(PlanningFieldGroup.STRUCTURE) ? remote.orderIndex : localSnap.orderIndex;

        PlanningSnapshot merged = new PlanningSnapshot(name, parentId, orderIndex, remote.version);
        boolean hasRemoteWork = !takeRemote.isEmpty();
        boolean hasLocalWork = takeRemote.size() < PlanningFieldGroup.values().length;
        return new Result(merged, takeRemote, remoteOverrode, hasRemoteWork, hasLocalWork, remote.version);
    }

    public static Set<PlanningFieldGroup> localDirtyGroups(PlanningNode local) {
        return PlanningSnapshot.diff(local.baselineSnapshot, PlanningSnapshot.of(local));
    }
}
