package nurgling.db.service;

import nurgling.areas.AreaFieldGroup;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A user-visible record of a sync action involving one area. Surfaced via
 * an in-game toast and stored in the recent-changes history panel.
 *
 * Five kinds matter to users:
 *   AUTO_MERGED      - we merged remote changes into our copy without losing local edits
 *   REMOTE_OVERRODE  - a conflicting in-group remote edit replaced our local change (server-LWW)
 *   LOCAL_WON        - our save took effect on a row we held; nothing was lost
 *   ADDED            - a brand-new area arrived from the server
 *   DELETED          - the server reports an area removed (tombstone)
 */
public final class AreaSyncEvent {
    public enum Kind { AUTO_MERGED, REMOTE_OVERRODE, LOCAL_WON, ADDED, DELETED }

    public final long timestamp;
    public final Kind kind;
    public final int areaId;
    public final String areaName;
    public final Set<AreaFieldGroup> groups;     // groups that were affected by this event
    public final Set<AreaFieldGroup> overridden; // groups where remote replaced local (subset of groups)
    public final String byPlayer;                // remote editor's name (may be null)

    public AreaSyncEvent(Kind kind, int areaId, String areaName,
                         Set<AreaFieldGroup> groups,
                         Set<AreaFieldGroup> overridden,
                         String byPlayer) {
        this.timestamp = System.currentTimeMillis();
        this.kind = kind;
        this.areaId = areaId;
        this.areaName = areaName == null ? "" : areaName;
        this.groups = groups == null ? Collections.emptySet() : EnumSet.copyOf(groups);
        this.overridden = overridden == null ? Collections.emptySet() : EnumSet.copyOf(overridden);
        this.byPlayer = byPlayer;
    }

    /** Build a short, user-facing toast string. */
    public String toToast() {
        String who = (byPlayer != null && !byPlayer.isEmpty()) ? byPlayer : "another player";
        switch (kind) {
            case AUTO_MERGED:
                if (overridden.isEmpty()) {
                    return "Area '" + areaName + "': merged " + describeGroups(groups) + " from " + who + ".";
                }
                return "Area '" + areaName + "': merged " + describeGroups(groups) + " from " + who
                    + "; your " + describeGroups(overridden) + " was replaced.";
            case REMOTE_OVERRODE:
                return "Area '" + areaName + "': " + who + " replaced your " + describeGroups(overridden) + ".";
            case LOCAL_WON:
                return "Area '" + areaName + "': your changes saved (" + describeGroups(groups) + ").";
            case ADDED:
                return "New area '" + areaName + "' from " + who + ".";
            case DELETED:
                return "Area '" + areaName + "' deleted by " + who + ".";
            default:
                return "Area '" + areaName + "' updated.";
        }
    }

    private static String describeGroups(Set<AreaFieldGroup> g) {
        if (g == null || g.isEmpty()) return "changes";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (AreaFieldGroup fg : g) {
            if (!first) sb.append("+");
            first = false;
            sb.append(fg.name().toLowerCase());
        }
        return sb.toString();
    }
}
