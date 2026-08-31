package nurgling.routes;

import haven.*;
import nurgling.scenarios.BotStep;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ForagerWaypoint {

    // Segment-based coordinates (same as MapFile.Marker)
    public long seg;  // Segment ID
    public Coord tc;  // Tile coordinates within segment

    // Optional scheduler-style steps Forager runs in full when it arrives at this waypoint,
    // before continuing the route as normal (Ctrl+right-click on the route map to edit). Empty
    // for the overwhelming majority of waypoints, which have no special behavior attached.
    public List<BotStep> steps = new ArrayList<>();
    // Same "nothing"/"logout"/"travel hearth" vocabulary already used for onAnimalAction/
    // afterFinishAction/onFullInventoryAction (see widgets/bots/Forager.java's AFTER_FINISH_ACTIONS),
    // dispatched via Forager.performSafetyAction() when this waypoint's steps fail.
    public String onStepsFailAction = "nothing";

    // Non-null only for a waypoint that anchors one end of a spliced-in milestone (signpost)
    // link - set on exactly two consecutive waypoints (the milestone's own location, then its
    // recorded destination) when the user left-clicks a milestone marker on the Routes map to
    // splice it into the route (see ForagerRouteMap.spliceMilestone()). Purely a rendering hint:
    // a waypoint with the same milestoneHash as its immediate predecessor draws the connecting
    // leg as a dashed line instead of the normal solid path, and both anchors draw as the
    // milestone icon instead of a plain waypoint dot. Not yet consumed by Forager's bot logic -
    // point-navigation between these two still walks a straight line like any other section
    // until a "use this milestone" bot step exists (a natural follow-up, not built yet).
    public String milestoneHash = null;

    public ForagerWaypoint(long seg, Coord tc) {
        this.seg = seg;
        this.tc = tc;
    }

    // Create from MiniMap.Location (when clicking on minimap)
    public ForagerWaypoint(MiniMap.Location loc) {
        this.seg = loc.seg.id;
        this.tc = loc.tc;
    }

    public ForagerWaypoint(JSONObject json) {
        this.seg = json.getLong("seg");
        JSONObject coordJson = json.getJSONObject("tc");
        this.tc = new Coord(coordJson.getInt("x"), coordJson.getInt("y"));
        if (json.has("steps")) {
            JSONArray stepsArray = json.getJSONArray("steps");
            for (int i = 0; i < stepsArray.length(); i++) {
                steps.add(new BotStep(stepsArray.getJSONObject(i)));
            }
        }
        if (json.has("onStepsFailAction")) {
            this.onStepsFailAction = json.getString("onStepsFailAction");
        }
        if (json.has("milestoneHash")) {
            this.milestoneHash = json.getString("milestoneHash");
        }
    }
    
    // Get tile coordinates for minimap display (always works, just returns tc)
    public Coord getTileCoord(MCache mcache) {
        return tc;
    }
    
    // Get world coordinates for pathfinding (needs sessloc for proper conversion)
    public Coord2d toWorldCoord(MiniMap.Location sessloc) {
        if(sessloc == null || sessloc.seg.id != this.seg) {
            return null; // Can't convert if not in same segment
        }
        // Convert segment tile coords to world coords relative to sessloc
        // Same formula as in MiniMap.mvclick: loc.tc.sub(sessloc.tc).mul(tilesz).add(tilesz.div(2))
        return tc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz);
    }
    
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("seg", seg);

        JSONObject coordJson = new JSONObject();
        coordJson.put("x", tc.x);
        coordJson.put("y", tc.y);
        json.put("tc", coordJson);

        if (!steps.isEmpty()) {
            JSONArray stepsArray = new JSONArray();
            for (BotStep step : steps) {
                stepsArray.put(step.toJson());
            }
            json.put("steps", stepsArray);
        }
        if (!"nothing".equals(onStepsFailAction)) {
            json.put("onStepsFailAction", onStepsFailAction);
        }
        if (milestoneHash != null) {
            json.put("milestoneHash", milestoneHash);
        }

        return json;
    }
    
    @Override
    public String toString() {
        return String.format("Waypoint[Seg=%d, TC=(%d,%d)]", seg, tc.x, tc.y);
    }
}
