package nurgling.areas;

import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;

/**
 * A frozen view of an NArea's field-group values, captured as canonical JSON
 * strings so equality checks are cheap and order-independent comparisons can
 * be added later if needed.
 *
 * The merger uses three snapshots:
 *   - baseline: the area as it was on the server the last time we synced it
 *   - local:    the area as we have it in memory right now
 *   - remote:   the area as the server currently has it
 *
 * For each AreaFieldGroup we compare against baseline to determine "did we
 * change this group?" / "did the server change this group?". If only one side
 * changed -> take that side. If both changed -> in-group conflict.
 */
public class AreaSnapshot {
    public final String name;
    public final String path;
    public final boolean hide;
    public final int colorR, colorG, colorB, colorA;
    public final String spaceJson;     // serialized JSONArray of grid polygons
    public final String inJson;        // serialized jin
    public final String outJson;       // serialized jout
    public final String specJson;      // serialized jspec
    public final int version;          // version this snapshot was captured at

    private AreaSnapshot(String name, String path, boolean hide,
                         int r, int g, int b, int a,
                         String spaceJson, String inJson, String outJson, String specJson,
                         int version) {
        this.name = name == null ? "" : name;
        this.path = path == null ? "" : path;
        this.hide = hide;
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
        this.colorA = a;
        this.spaceJson = spaceJson == null ? "[]" : spaceJson;
        this.inJson = inJson == null ? "[]" : inJson;
        this.outJson = outJson == null ? "[]" : outJson;
        this.specJson = specJson == null ? "[]" : specJson;
        this.version = version;
    }

    /**
     * Capture a snapshot of an NArea in memory.
     */
    public static AreaSnapshot of(NArea area) {
        JSONObject json = area.toJson();
        String space = json.has("space") ? json.get("space").toString() : "[]";
        String in = json.has("in") ? json.get("in").toString() : "[]";
        String out = json.has("out") ? json.get("out").toString() : "[]";
        String spec = json.has("spec") ? json.get("spec").toString() : "[]";
        Color c = area.color;
        return new AreaSnapshot(area.name, area.path, area.hide,
            c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha(),
            space, in, out, spec, area.version);
    }

    /**
     * Build a snapshot from the same canonical JSON fragments used in the
     * areas.data column.
     */
    public static AreaSnapshot of(String name, String path, boolean hide,
                                  int r, int g, int b, int a,
                                  String spaceJson, String inJson, String outJson, String specJson,
                                  int version) {
        return new AreaSnapshot(name, path, hide, r, g, b, a,
            spaceJson, inJson, outJson, specJson, version);
    }

    /**
     * Build a snapshot from a stored data JSON (the contents of the data
     * column) plus the row-level fields.
     */
    public static AreaSnapshot fromStored(String name, String path, boolean hide,
                                          int r, int g, int b, int a,
                                          String storedDataJson, int version) {
        JSONObject data = new JSONObject(storedDataJson);
        String space = data.has("space") ? data.get("space").toString() : "[]";
        String in = data.has("in") ? data.get("in").toString() : "[]";
        String out = data.has("out") ? data.get("out").toString() : "[]";
        String spec = data.has("spec") ? data.get("spec").toString() : "[]";
        return new AreaSnapshot(name, path, hide, r, g, b, a, space, in, out, spec, version);
    }

    /**
     * Which groups differ between two snapshots? Null on either side means
     * "no baseline yet" -> treated as all groups differing.
     */
    public static Set<AreaFieldGroup> diff(AreaSnapshot a, AreaSnapshot b) {
        EnumSet<AreaFieldGroup> result = EnumSet.noneOf(AreaFieldGroup.class);
        if (a == null || b == null) {
            result.add(AreaFieldGroup.GEOMETRY);
            result.add(AreaFieldGroup.IDENTITY);
            result.add(AreaFieldGroup.COSMETIC);
            result.add(AreaFieldGroup.ROUTING);
            return result;
        }
        if (!a.spaceJson.equals(b.spaceJson)) result.add(AreaFieldGroup.GEOMETRY);
        if (!a.name.equals(b.name) || !a.path.equals(b.path)) result.add(AreaFieldGroup.IDENTITY);
        if (a.hide != b.hide || a.colorR != b.colorR || a.colorG != b.colorG
            || a.colorB != b.colorB || a.colorA != b.colorA) {
            result.add(AreaFieldGroup.COSMETIC);
        }
        if (!a.inJson.equals(b.inJson) || !a.outJson.equals(b.outJson)
            || !a.specJson.equals(b.specJson)) {
            result.add(AreaFieldGroup.ROUTING);
        }
        return result;
    }

    /**
     * Build a JSONObject suitable for constructing an NArea, copying each
     * field-group value either from "local" or "remote" depending on
     * takeRemoteGroups. Groups not in takeRemoteGroups come from local.
     */
    public static JSONObject buildMergedJson(int id, String uuid,
                                             AreaSnapshot local, AreaSnapshot remote,
                                             Set<AreaFieldGroup> takeRemoteGroups,
                                             int newVersion) {
        JSONObject out = new JSONObject();
        out.put("id", id);
        if (uuid != null) out.put("uuid", uuid);

        AreaSnapshot identitySrc = takeRemoteGroups.contains(AreaFieldGroup.IDENTITY) ? remote : local;
        out.put("name", identitySrc.name);
        out.put("path", identitySrc.path);

        AreaSnapshot cosmeticSrc = takeRemoteGroups.contains(AreaFieldGroup.COSMETIC) ? remote : local;
        out.put("hide", cosmeticSrc.hide);
        JSONObject color = new JSONObject();
        color.put("r", cosmeticSrc.colorR);
        color.put("g", cosmeticSrc.colorG);
        color.put("b", cosmeticSrc.colorB);
        color.put("a", cosmeticSrc.colorA);
        out.put("color", color);

        AreaSnapshot geometrySrc = takeRemoteGroups.contains(AreaFieldGroup.GEOMETRY) ? remote : local;
        out.put("space", new JSONArray(geometrySrc.spaceJson));

        AreaSnapshot routingSrc = takeRemoteGroups.contains(AreaFieldGroup.ROUTING) ? remote : local;
        out.put("in", new JSONArray(routingSrc.inJson));
        out.put("out", new JSONArray(routingSrc.outJson));
        out.put("spec", new JSONArray(routingSrc.specJson));

        out.put("version", newVersion);
        return out;
    }
}
