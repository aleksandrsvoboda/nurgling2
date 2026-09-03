package nurgling.routes;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.scenarios.BotStep;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ForagerWaypoint {

    // Segment-based coordinates (same as MapFile.Marker)
    public long seg;  // Segment ID
    public Coord tc;  // Tile coordinates within segment

    // Best-effort ChunkNav target (MCache.Grid.id space, not seg/tc's segment space); -1/null if unresolved.
    public long gridId = -1;
    public Coord localTile = null;

    // Optional scheduler-style steps run in full on arrival, before continuing the route.
    public List<BotStep> steps = new ArrayList<>();
    // "nothing"/"logout"/"travel hearth", dispatched via Forager.performSafetyAction() on step failure.
    public String onStepsFailAction = "nothing";

    // Non-null only on a waypoint anchoring one end of a spliced-in milestone link - rendering hint only.
    public String milestoneHash = null;

    public ForagerWaypoint(long seg, Coord tc) {
        this.seg = seg;
        this.tc = tc;
        resolveGridId();
    }

    // Create from MiniMap.Location (when clicking on minimap)
    public ForagerWaypoint(MiniMap.Location loc) {
        this.seg = loc.seg.id;
        this.tc = loc.tc;
        resolveGridId();
    }

    /** Resolves gridId/localTile; leaves them unresolved if this waypoint's segment isn't the live session's. */
    private void resolveGridId() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.mmap == null) return;
        MiniMap.Location sessloc = gui.mmap.sessloc;
        if (sessloc == null || sessloc.seg.id != this.seg) return;
        Coord2d wc = tc.sub(sessloc.tc).mul(MCache.tilesz).add(MCache.tilehsz);
        nurgling.areas.NGlobalCoord gc = new nurgling.areas.NGlobalCoord(wc);
        Coord lt = gc.getLocalTile();
        if (lt != null) {
            this.gridId = gc.getGridId();
            this.localTile = lt;
        }
    }

    public ForagerWaypoint(JSONObject json) {
        this.seg = json.getLong("seg");
        JSONObject coordJson = json.getJSONObject("tc");
        this.tc = new Coord(coordJson.getInt("x"), coordJson.getInt("y"));
        if (json.has("gridId")) {
            this.gridId = json.getLong("gridId");
            JSONObject gtJson = json.getJSONObject("localTile");
            this.localTile = new Coord(gtJson.getInt("x"), gtJson.getInt("y"));
        }
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

        if (gridId != -1 && localTile != null) {
            json.put("gridId", gridId);
            JSONObject gtJson = new JSONObject();
            gtJson.put("x", localTile.x);
            gtJson.put("y", localTile.y);
            json.put("localTile", gtJson);
        }

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
