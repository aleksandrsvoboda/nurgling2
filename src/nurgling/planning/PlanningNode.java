package nurgling.planning;

import org.json.JSONObject;

/**
 * Common base for items in the Base planner tree. A node is either a
 * {@link PlanningFolder} (containing layers) or a {@link PlanningLayer}
 * (containing ghosts). v1 limits the tree to one level of nesting:
 * folders may contain layers but not other folders.
 */
public abstract class PlanningNode {
    public final String id;
    public String name;
    public boolean visible;
    public String parentId; // null = root

    protected PlanningNode(String id, String name, boolean visible, String parentId) {
        this.id = id;
        this.name = name;
        this.visible = visible;
        this.parentId = parentId;
    }

    public abstract String type();

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("type", type());
        o.put("id", id);
        o.put("name", name);
        o.put("visible", visible);
        return o;
    }
}
