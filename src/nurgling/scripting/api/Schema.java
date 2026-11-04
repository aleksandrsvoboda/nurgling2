package nurgling.scripting.api;

import nurgling.scripting.OpRegistry;
import nurgling.scripting.ScriptRuntime;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * The machine-readable description of the API, built from the @ApiOp annotations.
 * Exported as api-schema.json at build time and served by the "describe" op at runtime.
 */
public final class Schema {

    /** Snapshot types: name, description, and fields (name, type, description). */
    private static final String[][][] TYPES = {
            {{"Gob", "A game object in the world (tree, animal, container, player...). A snapshot; call gobs.get to refresh."},
                    {"gob", "int", "Handle: the object's id. Pass the whole snapshot or this id to ops taking a Gob."},
                    {"name", "str|null", "Resource name, e.g. \"gfx/terobjs/trees/oak\". Null while loading."},
                    {"hash", "str|null", "Stable identity that survives reloads (position + resource)."},
                    {"rc", "Coord", "World position."},
                    {"a", "float", "Facing angle in radians."},
                    {"pose", "str|null", "Current pose resource, e.g. \"gfx/borka/idle\" (the first of `poses`)."},
                    {"poses", "list[str]", "All pose resources playing at once (composite objects can have several)."},
                    {"attr", "int", "Model attribute: growth stage for crops and trees, fill flags for containers; -1 if none."},
                    {"overlays", "list[str]", "Resource names of overlays (fire, contents, markers...)."},
                    {"dist", "float|null", "Distance from the player when the snapshot was taken."}},
            {{"Item", "An inventory item. A snapshot; its handle stays valid while the item widget exists."},
                    {"item", "int", "Handle: the item widget id."},
                    {"name", "str|null", "Display name, e.g. \"Block of Birch\"."},
                    {"res", "str|null", "Resource name, e.g. \"gfx/invobjs/wblock-birch\"."},
                    {"q", "float|null", "Quality."},
                    {"num", "int", "Number shown on the item (stack amount, uses), -1 if none."},
                    {"contents", "list[dict]", "Contents of containers (bucket, waterskin): [{name, q}]."},
                    {"stack", "int", "Number of items in this item stack, 0 if it is not a stack."},
                    {"slot", "Tile|null", "Inventory cell [x, y] of the item's top-left corner."},
                    {"size", "Tile|null", "Size in inventory cells [w, h]."},
                    {"parent", "int|null", "Widget id of the inventory or stack holding it."}},
            {{"Inventory", "An open inventory (the player's, or a container's)."},
                    {"wdg", "int", "Handle: the inventory widget id."},
                    {"window", "str|null", "Caption of the window holding it."},
                    {"size", "Tile|null", "Size in cells [w, h]."}},
            {{"Widget", "Any UI element. Windows add a caption."},
                    {"wdg", "int", "Handle: the widget id (server-assigned; -1 for client-only widgets)."},
                    {"class", "str", "Java class name."},
                    {"caption", "str", "Window caption (windows only)."},
                    {"parent", "int|null", "Parent widget id."},
                    {"visible", "bool", "Whether it is shown."},
                    {"c", "Tile", "Position inside the parent, in UI pixels."},
                    {"sz", "Tile", "Size in UI pixels."}},
            {{"Area", "A saved area (zone) with its specialisations."},
                    {"area", "int", "Handle: the area id."},
                    {"name", "str", "Area name."},
                    {"specs", "list[dict]", "Specialisations: [{name, subtype?}]."},
                    {"rect", "Rect|null", "World rectangle; null if the area's part of the map is not loaded."},
                    {"in", "list[dict]", "Items configured to be taken from here."},
                    {"out", "list[dict]", "Items configured to be put here."}},
            {{"Coord", "A world position [x, y] in world units (11 units per tile)."}},
            {{"Tile", "An integer pair [x, y]: a tile, an inventory cell, or a size."}},
            {{"Rect", "A world rectangle [[x1, y1], [x2, y2]]."}},
            {{"Alias", "A name matcher: \"text\", [\"a\", \"b\"], or {\"include\": [...], \"exclude\": [...]}. Matches substrings of resource and display names, like the client's NAlias."}},
    };

    public static JSONObject build() {
        JSONObject j = new JSONObject();
        j.put("api", ScriptRuntime.API_VERSION);
        j.put("protocol", new JSONObject()
                .put("framing", "4-byte big-endian length followed by UTF-8 JSON")
                .put("request", "{\"id\": any, \"op\": str, \"args\": object, \"run\"?: str}")
                .put("response", "{\"id\": any, \"ok\": value} or {\"id\": any, \"err\": {\"type\": str, \"msg\": str}}")
                .put("event", "{\"ev\": str, \"sub\"?: int, \"data\": value, \"dropped\"?: int}"));
        JSONArray types = new JSONArray();
        for (String[][] t : TYPES) {
            JSONObject tj = new JSONObject().put("name", t[0][0]).put("doc", t[0][1]);
            JSONArray fields = new JSONArray();
            for (int i = 1; i < t.length; i++)
                fields.put(new JSONObject().put("name", t[i][0]).put("type", t[i][1]).put("doc", t[i][2]));
            tj.put("fields", fields);
            types.put(tj);
        }
        j.put("types", types);
        JSONObject errs = new JSONObject();
        for (Map.Entry<String, String> e : Errors.all().entrySet()) errs.put(e.getKey(), e.getValue());
        j.put("errors", errs);
        JSONArray ops = new JSONArray();
        for (OpRegistry.OpDef d : OpRegistry.all().values()) ops.put(op(d.op));
        j.put("ops", ops);
        return j;
    }

    public static JSONObject op(ApiOp a) {
        JSONObject o = new JSONObject();
        o.put("name", a.name());
        o.put("doc", a.doc());
        o.put("since", a.since());
        o.put("kind", a.kind().name().toLowerCase());
        o.put("exec", a.exec().name().toLowerCase());
        JSONArray ps = new JSONArray();
        for (ApiParam p : a.params()) {
            JSONObject pj = new JSONObject().put("name", p.name()).put("type", p.type()).put("doc", p.doc()).put("optional", p.optional());
            if (!p.def().isEmpty()) pj.put("default", p.def());
            if (p.values().length > 0) pj.put("values", new JSONArray(p.values()));
            ps.put(pj);
        }
        o.put("params", ps);
        o.put("returns", a.returns());
        if (!a.returnsDoc().isEmpty()) o.put("returnsDoc", a.returnsDoc());
        o.put("errors", new JSONArray(a.errors()));
        if (!a.example().isEmpty()) o.put("example", a.example());
        o.put("unstable", a.unstable());
        if (!a.deprecated().isEmpty()) o.put("deprecated", a.deprecated());
        return o;
    }

    private Schema() {}
}
