package nurgling.db.service;

import nurgling.areas.AreaFieldGroup;
import nurgling.areas.AreaSnapshot;
import nurgling.areas.NArea;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.Set;

/**
 * Three-way merge of an area against its server baseline and a fresh server
 * value. Each field group is treated independently:
 *
 *   - If local and remote both equal baseline -> nothing to do.
 *   - If only one side diverged from baseline -> take that side.
 *   - If both diverged in the same group -> server-timestamp wins.
 *
 * The merger never asks the user a question; the outcome is reported via
 * AreaSyncEvent so the user can see what happened.
 */
public final class AreaMerger {

    public static final class Result {
        public final JSONObject mergedJson;             // ready to construct a new NArea
        public final Set<AreaFieldGroup> takeRemote;    // groups taken from remote
        public final Set<AreaFieldGroup> remoteOverrode; // groups where remote replaced a local edit
        public final boolean hasRemoteWork;             // anything came from the remote side at all
        public final boolean hasLocalWork;              // local still contributes something
        public final int newVersion;                    // version we expect the row to have after save

        Result(JSONObject mergedJson, Set<AreaFieldGroup> takeRemote,
               Set<AreaFieldGroup> remoteOverrode,
               boolean hasRemoteWork, boolean hasLocalWork, int newVersion) {
            this.mergedJson = mergedJson;
            this.takeRemote = takeRemote;
            this.remoteOverrode = remoteOverrode;
            this.hasRemoteWork = hasRemoteWork;
            this.hasLocalWork = hasLocalWork;
            this.newVersion = newVersion;
        }
    }

    private AreaMerger() {}

    /**
     * Three-way merge for the pull path: incoming server change vs our local
     * copy, using the area's stored baselineSnapshot as the common ancestor.
     */
    public static Result merge(NArea local, AreaSnapshot remote, int id, String uuid) {
        AreaSnapshot localSnap = AreaSnapshot.of(local);
        AreaSnapshot baseline = local.baselineSnapshot;

        Set<AreaFieldGroup> localDirty = AreaSnapshot.diff(baseline, localSnap);
        Set<AreaFieldGroup> remoteDirty = AreaSnapshot.diff(baseline, remote);

        EnumSet<AreaFieldGroup> takeRemote = EnumSet.noneOf(AreaFieldGroup.class);
        EnumSet<AreaFieldGroup> remoteOverrode = EnumSet.noneOf(AreaFieldGroup.class);

        for (AreaFieldGroup g : AreaFieldGroup.values()) {
            boolean l = localDirty.contains(g);
            boolean r = remoteDirty.contains(g);
            if (r && !l) {
                takeRemote.add(g);
            } else if (r && l) {
                // Conflict in this group - server timestamp wins. Since we
                // got "remote" by reading the latest row, remote wins.
                takeRemote.add(g);
                remoteOverrode.add(g);
            }
            // else: !r && !l (nothing), or !r && l (keep local)
        }

        int newVersion = remote.version; // pull path: remote's version is authoritative for this round
        JSONObject merged = AreaSnapshot.buildMergedJson(id, uuid, localSnap, remote, takeRemote, newVersion);
        boolean hasRemoteWork = !takeRemote.isEmpty();
        boolean hasLocalWork = takeRemote.size() < 4; // some group still came from local
        return new Result(merged, takeRemote, remoteOverrode, hasRemoteWork, hasLocalWork, newVersion);
    }

    /**
     * Quick diff used by the push path to decide whether to even attempt a
     * save. Returns the set of groups locally changed since baseline.
     */
    public static Set<AreaFieldGroup> localDirtyGroups(NArea local) {
        return AreaSnapshot.diff(local.baselineSnapshot, AreaSnapshot.of(local));
    }
}
