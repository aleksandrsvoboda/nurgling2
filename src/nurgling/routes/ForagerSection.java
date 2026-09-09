package nurgling.routes;

import haven.Coord2d;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ForagerSection {
    
    public Coord2d startPoint;
    public Coord2d endPoint;
    public List<ForagerAction> actions;
    public int sectionIndex;

    // Which waypoint gap this section belongs to: this section lies somewhere between
    // waypoints[waypointIndex] and waypoints[waypointIndex+1]. A single gap longer than
    // SECTION_LENGTH gets split into several sections (see ForagerPath.generateSections()), so
    // section index and waypoint index are NOT interchangeable - Forager's main loop must use
    // this field, never the raw section-loop counter, to look up waypoints.
    public int waypointIndex;
    // True only for the final section covering waypointIndex's gap - i.e. the one that actually
    // reaches waypoints[waypointIndex+1]. Waypoint-arrival side effects (steps, milestone splice)
    // must only fire on this section, not on every intermediate sub-section of a subdivided gap.
    public boolean isLastInGap;

    public ForagerSection(Coord2d startPoint, Coord2d endPoint, int sectionIndex, int waypointIndex, boolean isLastInGap) {
        this.startPoint = startPoint;
        this.endPoint = endPoint;
        this.sectionIndex = sectionIndex;
        this.waypointIndex = waypointIndex;
        this.isLastInGap = isLastInGap;
        this.actions = new ArrayList<>();
    }

    public ForagerSection(JSONObject json) {
        JSONObject startJson = json.getJSONObject("startPoint");
        this.startPoint = new Coord2d(startJson.getDouble("x"), startJson.getDouble("y"));

        JSONObject endJson = json.getJSONObject("endPoint");
        this.endPoint = new Coord2d(endJson.getDouble("x"), endJson.getDouble("y"));

        this.sectionIndex = json.getInt("sectionIndex");
        // Never actually loaded from JSON (ForagerPath always regenerates sections fresh - see
        // ForagerPath's "Always generate sections from waypoints" constructor comment), so these
        // don't need real values here.
        this.waypointIndex = this.sectionIndex;
        this.isLastInGap = true;

        this.actions = new ArrayList<>();
        if (json.has("actions")) {
            JSONArray actionsArray = json.getJSONArray("actions");
            for (int i = 0; i < actionsArray.length(); i++) {
                actions.add(new ForagerAction(actionsArray.getJSONObject(i)));
            }
        }
    }
    
    public void addAction(ForagerAction action) {
        actions.add(action);
    }
    
    public void removeAction(ForagerAction action) {
        actions.remove(action);
    }
    
    public Coord2d getCenterPoint() {
        return new Coord2d(
            (startPoint.x + endPoint.x) / 2.0,
            (startPoint.y + endPoint.y) / 2.0
        );
    }
    
    public double getLength() {
        return startPoint.dist(endPoint);
    }
    
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        
        JSONObject startJson = new JSONObject();
        startJson.put("x", startPoint.x);
        startJson.put("y", startPoint.y);
        json.put("startPoint", startJson);
        
        JSONObject endJson = new JSONObject();
        endJson.put("x", endPoint.x);
        endJson.put("y", endPoint.y);
        json.put("endPoint", endJson);
        
        json.put("sectionIndex", sectionIndex);
        
        JSONArray actionsArray = new JSONArray();
        for (ForagerAction action : actions) {
            actionsArray.put(action.toJson());
        }
        json.put("actions", actionsArray);
        
        return json;
    }
    
    @Override
    public String toString() {
        return String.format("Section %d: %.1f units, %d actions", 
            sectionIndex, getLength(), actions.size());
    }
}
