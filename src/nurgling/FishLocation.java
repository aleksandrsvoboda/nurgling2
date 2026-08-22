package nurgling;

import org.json.JSONObject;
import haven.Coord;
import haven.MCache;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A saved fish location.
 *
 * <p><b>Identity is grid-relative.</b> The stored position is the server-assigned
 * {@code gridId} plus a tile offset inside that grid, exactly like {@link nurgling.planning.PlanningGhost}
 * and {@link nurgling.areas.NArea}. Map-file segment ids are deliberately NOT the identity: they come from
 * {@code new Segment(rnd.nextLong())}, so they differ per client (useless in a shared database) and are
 * reassigned whenever the map file merges segments (which silently loses locally saved spots).
 *
 * <p>Segment coordinates are therefore <i>derived</i> state, resolved lazily off the render thread by
 * {@link FishLocationService} and cached on the record.
 *
 * <p>Records written by older clients carry only {@code segmentId} + segment-relative tile coords. Those are
 * kept verbatim in {@link #legacySegId}/{@link #legacyTc} so they keep rendering exactly as before and are
 * never lost on rewrite; {@link FishLocationSeeder} converts them to a grid id when they are pushed to the
 * database.
 */
public class FishLocation {
    /** Stable local key. Also the database row id for records that have a grid id. */
    private final String locationId;

    /** Server-assigned grid id, or {@link #NO_GRID} for a legacy record that has not been converted. */
    private final long gridId;
    /** Tile offset inside the grid (0..cmaps-1), or null when {@link #gridId} is {@link #NO_GRID}. */
    private final Coord offset;

    public static final long NO_GRID = 0L;

    /** Legacy map-file segment id, or {@link Long#MIN_VALUE} when this record was born grid-relative. */
    private final long legacySegId;
    /** Legacy segment-relative tile coord, or null. */
    private final Coord legacyTc;

    private final String fishName;        // e.g. "Asp", "Salmon"
    private final String fishResource;    // e.g. "gfx/invobjs/fish-asp"
    private final String percentage;      // e.g. "7%", "95%"
    private final long timestamp;         // when it was saved (epoch ms)

    private final String gameTime;        // e.g. "12:45"
    private final String moonPhase;       // e.g. "Full Moon"

    private final String fishingRod;
    private final String hook;
    private final String line;
    private final String bait;

    /* ---- derived render state, filled in by FishLocationService.ensureResolved ---- */
    private volatile long resSeg = Long.MIN_VALUE;
    private volatile Coord resTc = null;
    /** Guards against queueing a second map-file lookup while one is in flight. */
    volatile boolean resolving = false;
    /** Last resolution attempt (epoch ms), so an unknown grid is not re-queried every frame. */
    volatile long lastResolveAttempt = 0;

    /**
     * New save. The caller already knows both the grid and the segment position, so the record is born
     * resolved and draws on the very next frame.
     */
    public FishLocation(String profile, long gridId, Coord offset, long segId, Coord segTc,
                        String fishName, String fishResource, String percentage,
                        String gameTime, String moonPhase,
                        String fishingRod, String hook, String line, String bait) {
        this.gridId = gridId;
        this.offset = offset;
        this.legacySegId = Long.MIN_VALUE;
        this.legacyTc = null;
        this.fishName = fishName;
        this.fishResource = fishResource;
        this.percentage = percentage;
        this.gameTime = gameTime;
        this.moonPhase = moonPhase;
        this.fishingRod = fishingRod;
        this.hook = hook;
        this.line = line;
        this.bait = bait;
        this.timestamp = System.currentTimeMillis();
        this.locationId = deterministicId(profile, gridId, offset, fishName);
        this.resSeg = segId;
        this.resTc = segTc;
    }

    /** Full control constructor, used when rehydrating from the database. */
    public FishLocation(String locationId, long gridId, Coord offset,
                        String fishName, String fishResource, String percentage, long timestamp,
                        String gameTime, String moonPhase,
                        String fishingRod, String hook, String line, String bait) {
        this.locationId = locationId;
        this.gridId = gridId;
        this.offset = offset;
        this.legacySegId = Long.MIN_VALUE;
        this.legacyTc = null;
        this.fishName = fishName;
        this.fishResource = fishResource;
        this.percentage = percentage;
        this.timestamp = timestamp;
        this.gameTime = gameTime;
        this.moonPhase = moonPhase;
        this.fishingRod = fishingRod;
        this.hook = hook;
        this.line = line;
        this.bait = bait;
    }

    /**
     * Read a record from JSON. Handles both the v2 (grid-relative) and v1 (segment-relative) shapes; a v1
     * record keeps its original coordinates and its original id so nothing shifts under the user.
     */
    public FishLocation(JSONObject json) {
        this.fishName = json.getString("fishName");
        this.fishResource = json.getString("fishResource");
        this.timestamp = json.optLong("timestamp", System.currentTimeMillis());
        this.percentage = json.optString("percentage", "Unknown");
        this.gameTime = json.optString("gameTime", "Unknown");
        this.moonPhase = json.optString("moonPhase", "Unknown");
        this.fishingRod = json.optString("fishingRod", "Unknown");
        this.hook = json.optString("hook", "Unknown");
        this.line = json.optString("line", "Unknown");
        this.bait = json.optString("bait", "Unknown");

        // v2 position
        this.gridId = json.optLong("gridId", NO_GRID);
        this.offset = json.has("ox") ? new Coord(json.getInt("ox"), json.getInt("oy")) : null;

        // v1 position, retained verbatim when present
        if (json.has("segmentId") && json.has("tileX")) {
            this.legacySegId = json.getLong("segmentId");
            this.legacyTc = new Coord(json.getInt("tileX"), json.getInt("tileY"));
            // A legacy record already knows where it draws.
            this.resSeg = this.legacySegId;
            this.resTc = this.legacyTc;
        } else {
            this.legacySegId = Long.MIN_VALUE;
            this.legacyTc = null;
        }

        this.locationId = json.optString("locationId", null) != null
            ? json.getString("locationId")
            : UUID.randomUUID().toString();
    }

    /**
     * Row id derived from the position and the fish, so the same physical spot yields the same id on every
     * client. That is what makes seeding idempotent and stops two players who saved the same spot from
     * creating two rows.
     */
    public static String deterministicId(String profile, long gridId, Coord offset, String fishName) {
        String key = "fish|" + (profile == null ? "global" : profile) + "|" + gridId + "|"
                   + offset.x + "|" + offset.y + "|" + fishName;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("locationId", locationId);
        if (gridId != NO_GRID && offset != null) {
            json.put("gridId", gridId);
            json.put("ox", offset.x);
            json.put("oy", offset.y);
        }
        // Legacy coordinates are written back out untouched: they are the only position a v1 record has, and
        // dropping them would strand it.
        if (legacyTc != null) {
            json.put("segmentId", legacySegId);
            json.put("tileX", legacyTc.x);
            json.put("tileY", legacyTc.y);
        }
        json.put("fishName", fishName);
        json.put("fishResource", fishResource);
        json.put("percentage", percentage);
        json.put("timestamp", timestamp);
        json.put("gameTime", gameTime);
        json.put("moonPhase", moonPhase);
        json.put("fishingRod", fishingRod);
        json.put("hook", hook);
        json.put("line", line);
        json.put("bait", bait);
        return json;
    }

    /* ---- position ---- */

    public long getGridId() { return gridId; }
    public Coord getOffset() { return offset; }
    public boolean hasGrid() { return gridId != NO_GRID && offset != null; }

    public long getLegacySegId() { return legacySegId; }
    public Coord getLegacyTc() { return legacyTc; }

    /** True once this record knows where it draws on this client's map. */
    public boolean isResolved() { return resTc != null; }

    void setResolved(long seg, Coord tc) {
        this.resSeg = seg;
        this.resTc = tc;
    }

    /** Map-file segment this record draws in, or {@link Long#MIN_VALUE} if not resolved yet. */
    public long getSegmentId() { return resSeg; }

    /** Segment-relative tile coord this record draws at, or null if not resolved yet. */
    public Coord getTileCoords() { return resTc; }

    /* ---- data ---- */

    public String getLocationId() { return locationId; }
    public String getFishName() { return fishName; }
    public String getFishResource() { return fishResource; }
    public String getPercentage() { return percentage; }
    public long getTimestamp() { return timestamp; }
    public String getGameTime() { return gameTime; }
    public String getMoonPhase() { return moonPhase; }
    public String getFishingRod() { return fishingRod; }
    public String getHook() { return hook; }
    public String getLine() { return line; }
    public String getBait() { return bait; }
}
