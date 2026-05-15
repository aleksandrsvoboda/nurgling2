package nurgling.planning;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A layer of planning ghosts. Either a root-level item or a child of a
 * {@link PlanningFolder}. The order of {@code ghosts} is the insertion
 * order; the right-panel ghost list displays newest-first by reversing.
 */
public class PlanningLayer extends PlanningNode {
    public final List<PlanningGhost> ghosts = new ArrayList<>();

    public PlanningLayer(String id, String name, boolean visible, String parentId) {
        super(id, name, visible, parentId);
    }

    @Override
    public String type() { return "layer"; }

    @Override
    public JSONObject toJson() {
        JSONObject o = super.toJson();
        JSONArray arr = new JSONArray();
        for (PlanningGhost g : ghosts) arr.put(g.toJson());
        o.put("ghosts", arr);
        return o;
    }

    public static PlanningLayer fromJson(JSONObject o) {
        PlanningLayer layer = new PlanningLayer(
                o.getString("id"),
                o.optString("name", "Layer"),
                o.optBoolean("visible", true),
                null);
        if (o.has("ghosts")) {
            JSONArray arr = o.getJSONArray("ghosts");
            for (int i = 0; i < arr.length(); i++) {
                try {
                    layer.ghosts.add(PlanningGhost.fromJson(arr.getJSONObject(i)));
                } catch (JSONException ignore) {
                    // Skip malformed individual entries; the rest of the layer survives.
                }
            }
        }
        return layer;
    }
}
