package nurgling.planning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Folder of layers in the planning tree. Holds only layers (v1 single-level
 * nesting). Visibility cascades: a hidden folder hides all its layers.
 */
public class PlanningFolder extends PlanningNode {
    public final List<PlanningLayer> layers = new ArrayList<>();

    public PlanningFolder(String id, String name, boolean visible, String parentId) {
        super(id, name, visible, parentId);
    }

    @Override
    public String type() { return "folder"; }

    @Override
    public JSONObject toJson() {
        JSONObject o = super.toJson();
        JSONArray arr = new JSONArray();
        for (PlanningLayer layer : layers) arr.put(layer.toJson());
        o.put("layers", arr);
        return o;
    }

    public static PlanningFolder fromJson(JSONObject o) {
        PlanningFolder f = new PlanningFolder(
                o.getString("id"),
                o.optString("name", "Folder"),
                o.optBoolean("visible", true),
                null);
        f.orderIndex = o.optInt("orderIndex", 0);
        if (o.has("layers")) {
            JSONArray arr = o.getJSONArray("layers");
            for (int i = 0; i < arr.length(); i++) {
                PlanningLayer layer = PlanningLayer.fromJson(arr.getJSONObject(i));
                layer.parentId = f.id;
                f.layers.add(layer);
            }
        }
        return f;
    }
}
