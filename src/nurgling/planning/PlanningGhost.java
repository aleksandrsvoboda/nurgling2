package nurgling.planning;

import haven.Coord2d;
import org.json.JSONObject;

import java.util.Base64;

/**
 * Persistent ghost entry for the planning layer. Grid-anchored so the ghost
 * survives world re-segmentation across sessions: we store the grid id captured
 * at placement time and an offset relative to that grid's origin, and resolve
 * world position back at materialization time.
 */
public class PlanningGhost {
    public final long id;
    public final String resName;
    public final byte[] sdt;      // sprite data bytes; may be null/empty for default variant
    public final long gridId;     // MCache.Grid.id of the grid that owned the placement tile
    public final double ox;       // local offset within the grid, in world pixels
    public final double oy;
    public final double angle;    // rotation in radians; freeform so 45° and finer steps survive

    public PlanningGhost(long id, String resName, byte[] sdt, long gridId, double ox, double oy, double angle) {
        this.id = id;
        this.resName = resName;
        this.sdt = sdt;
        this.gridId = gridId;
        this.ox = ox;
        this.oy = oy;
        this.angle = angle;
    }

    public double angleRadians() {
        return angle;
    }

    public Coord2d offset() {
        return new Coord2d(ox, oy);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("res", resName);
        if (sdt != null && sdt.length > 0) {
            o.put("sdt", Base64.getEncoder().encodeToString(sdt));
        }
        o.put("gridId", gridId);
        o.put("ox", ox);
        o.put("oy", oy);
        o.put("angle", angle);
        return o;
    }

    public static PlanningGhost fromJson(JSONObject o) {
        long id = o.getLong("id");
        String res = o.getString("res");
        byte[] sdt = null;
        if (o.has("sdt")) {
            String enc = o.optString("sdt", null);
            if (enc != null && !enc.isEmpty()) {
                sdt = Base64.getDecoder().decode(enc);
            }
        }
        long gridId = o.getLong("gridId");
        double ox = o.getDouble("ox");
        double oy = o.getDouble("oy");
        double angle;
        if (o.has("angle")) {
            angle = o.getDouble("angle");
        } else {
            // Legacy v1 format used integer quarter turns under "rot".
            int rot = o.optInt("rot", 0);
            angle = (rot & 3) * Math.PI / 2.0;
        }
        return new PlanningGhost(id, res, sdt, gridId, ox, oy, angle);
    }
}
