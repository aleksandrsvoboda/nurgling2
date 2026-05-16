package nurgling.planning;

import java.util.EnumSet;
import java.util.Set;

/**
 * Frozen view of a {@link PlanningNode}'s synced field-group values. Used as
 * the common-ancestor baseline in three-way merge.
 *
 * NOTE: {@code visible} is intentionally absent — visibility is a local-only
 * per-user preference, never synced.
 */
public final class PlanningSnapshot {
    public final String name;
    public final String parentId;   // nullable
    public final int orderIndex;
    public final int version;

    public PlanningSnapshot(String name, String parentId, int orderIndex, int version) {
        this.name = name == null ? "" : name;
        this.parentId = parentId;
        this.orderIndex = orderIndex;
        this.version = version;
    }

    public static PlanningSnapshot of(PlanningNode n) {
        return new PlanningSnapshot(n.name, n.parentId, n.orderIndex, n.version);
    }

    public static Set<PlanningFieldGroup> diff(PlanningSnapshot a, PlanningSnapshot b) {
        EnumSet<PlanningFieldGroup> out = EnumSet.noneOf(PlanningFieldGroup.class);
        if (a == null || b == null) {
            out.add(PlanningFieldGroup.IDENTITY);
            out.add(PlanningFieldGroup.STRUCTURE);
            return out;
        }
        if (!a.name.equals(b.name)) out.add(PlanningFieldGroup.IDENTITY);
        if (!eqNullable(a.parentId, b.parentId) || a.orderIndex != b.orderIndex) {
            out.add(PlanningFieldGroup.STRUCTURE);
        }
        return out;
    }

    private static boolean eqNullable(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
