package nurgling;

import org.json.JSONObject;
import haven.Coord;

/**
 * Represents a discovered stone/ore location in a mine.
 * Recorded automatically by tunneling bots as they mine through tiles.
 */
public class StoneLocation {
    private final String locationId;
    private final long segmentId;
    private final Coord tileCoords;
    private final String stoneName;        // e.g., "Feldspar", "Chalcopyrite"
    private final String stoneResource;    // e.g., "gfx/tiles/rocks/feldspar"
    private final long timestamp;

    public StoneLocation(long segmentId, Coord tileCoords, String stoneName, String stoneResource) {
        this.segmentId = segmentId;
        this.tileCoords = tileCoords;
        this.stoneName = stoneName;
        this.stoneResource = stoneResource;
        this.timestamp = System.currentTimeMillis();
        this.locationId = generateLocationId(segmentId, tileCoords, stoneName);
    }

    public StoneLocation(JSONObject json) {
        this.locationId = json.getString("locationId");
        this.segmentId = json.getLong("segmentId");
        this.tileCoords = new Coord(json.getInt("tileX"), json.getInt("tileY"));
        this.stoneName = json.getString("stoneName");
        this.stoneResource = json.getString("stoneResource");
        this.timestamp = json.getLong("timestamp");
    }

    private static String generateLocationId(long segmentId, Coord tileCoords, String stoneName) {
        return String.format("stone_%d_%d_%d_%s", segmentId, tileCoords.x, tileCoords.y,
                           stoneName.replaceAll("[^a-zA-Z0-9]", "_"));
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("locationId", locationId);
        json.put("segmentId", segmentId);
        json.put("tileX", tileCoords.x);
        json.put("tileY", tileCoords.y);
        json.put("stoneName", stoneName);
        json.put("stoneResource", stoneResource);
        json.put("timestamp", timestamp);
        return json;
    }

    public String getLocationId() { return locationId; }
    public long getSegmentId() { return segmentId; }
    public Coord getTileCoords() { return tileCoords; }
    public String getStoneName() { return stoneName; }
    public String getStoneResource() { return stoneResource; }
    public long getTimestamp() { return timestamp; }
}
