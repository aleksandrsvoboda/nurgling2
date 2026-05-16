package nurgling.planning;

import org.json.JSONObject;

import java.util.EnumSet;

/**
 * Common base for items in the Base planner tree. A node is either a
 * {@link PlanningFolder} (containing layers) or a {@link PlanningLayer}
 * (containing ghosts). v1 limits the tree to one level of nesting:
 * folders may contain layers but not other folders.
 *
 * Carries sync metadata (version, baseline, dirty groups, presence) so
 * three-way merges and OCC saves can run when {@code ndbenable} is on.
 */
public abstract class PlanningNode {
    public final String id;
    public String name;
    public boolean visible;
    public String parentId; // null = root
    public int orderIndex;  // ordering within parent

    // Sync state ------------------------------------------------------------
    public int version;                       // server's current version (or 0 for never-saved)
    public int baselineVersion;               // version we last synced
    public PlanningSnapshot baselineSnapshot; // common-ancestor view for merge
    public final EnumSet<PlanningFieldGroup> dirtyGroups = EnumSet.noneOf(PlanningFieldGroup.class);
    public String lastTouchedBy;
    public long lastTouchedAt;

    protected PlanningNode(String id, String name, boolean visible, String parentId) {
        this.id = id;
        this.name = name;
        this.visible = visible;
        this.parentId = parentId;
    }

    public abstract String type();

    public void markDirty(PlanningFieldGroup g) {
        dirtyGroups.add(g);
    }

    public void captureBaseline() {
        baselineSnapshot = PlanningSnapshot.of(this);
        baselineVersion = version;
        dirtyGroups.clear();
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("type", type());
        o.put("id", id);
        o.put("name", name);
        o.put("visible", visible);
        o.put("orderIndex", orderIndex);
        return o;
    }
}
