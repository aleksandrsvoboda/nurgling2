package nurgling.scripting.ops;

import haven.*;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.scripting.api.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.widgets.Specialisation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** Saved areas (zones), their specialisations and item routing. */
public final class AreaOps {

    private static JSONObject snap(NArea a) {
        return Codec.area(a).put("disabled", a.isDisabled());
    }

    private static List<JSONObject> snaps(List<NArea> as) {
        List<JSONObject> res = new ArrayList<>();
        for (NArea a : as) res.add(snap(a));
        return res;
    }

    @ApiOp(name = "areas.list", kind = QUERY,
            doc = "Every saved area of this world, enabled or not. `rect` is null for areas that are not loaded around the player; "
                    + "`disabled` areas are ignored by the client's bots.",
            returns = "list[Area]", returnsDoc = "Area snapshots {area, name, specs, rect, in, out, disabled}, by id.",
            example = "for a in nc.areas.list():\n    print(a[\"area\"], a[\"name\"], [s[\"name\"] for s in a[\"specs\"]])")
    public static Object list(OpContext c, Args a) {
        List<NArea> as = new ArrayList<>(Areas.all(c).values());
        as.sort(Comparator.comparingInt(x -> x.id));
        return snaps(as);
    }

    @ApiOp(name = "areas.get", kind = QUERY,
            doc = "One area by id or name.",
            params = {@ApiParam(name = "area", type = "Area|int|str", doc = "Area id, exact name, or snapshot.")},
            returns = "Area", errors = {Errors.NO_SUCH_AREA},
            example = "logs = nc.areas.get(\"Logs\")")
    public static Object get(OpContext c, Args a) {
        return snap(a.area("area"));
    }

    @ApiOp(name = "areas.by_spec", kind = QUERY,
            doc = "Enabled areas carrying a specialisation, optionally with a subtype (e.g. spec \"crop\", subtype \"Carrot\"). "
                    + "The names are those of `areas.specs`.",
            params = {
                    @ApiParam(name = "spec", type = "str", doc = "Specialisation name, e.g. \"smelter\" or \"water\"."),
                    @ApiParam(name = "subtype", type = "str", doc = "Exact subtype.", optional = true)},
            returns = "list[Area]",
            example = "for field in nc.areas.by_spec(\"crop\", subtype=\"Carrot\"):\n    print(field[\"name\"])")
    public static Object bySpec(OpContext c, Args a) {
        return snaps(Areas.bySpec(c, a.str("spec"), a.str("subtype", null)));
    }

    @ApiOp(name = "areas.specs", kind = QUERY,
            doc = "All specialisation names the client knows (the values `areas.by_spec` accepts).",
            returns = "list[str]",
            example = "print(nc.areas.specs())")
    public static Object specs(OpContext c, Args a) {
        List<String> res = new ArrayList<>();
        for (Specialisation.SpecName s : Specialisation.SpecName.values()) res.add(s.name());
        return res;
    }

    @ApiOp(name = "areas.at", kind = QUERY,
            doc = "The visible areas containing a world position (areas can overlap). Only areas loaded around the player are considered.",
            params = {@ApiParam(name = "pos", type = "Coord|Gob", doc = "World position, or a gob for its position.")},
            returns = "list[Area]", errors = {Errors.GOB_GONE},
            example = "here = nc.areas.at(nc.gobs.player())")
    public static Object at(OpContext c, Args a) {
        Coord2d pos = a.coord2d("pos");
        List<NArea> res = new ArrayList<>();
        for (NArea ar : Areas.all(c).values()) {
            if (ar.checkHit(pos)) res.add(ar);
        }
        res.sort(Comparator.comparingInt(x -> x.id));
        return snaps(res);
    }

    @ApiOp(name = "areas.for_item", kind = QUERY,
            doc = "Enabled areas configured to take in (`direction=\"in\"`) or hand out (`\"out\"`) an item, by the item's exact display name, "
                    + "as set in the area's item lists. Loaded areas come first, nearest first; areas elsewhere in the world follow (their `rect` is null). "
                    + "For \"out\" areas each snapshot has `th`, the quality threshold of the matching entry (1 if none).",
            params = {
                    @ApiParam(name = "name", type = "str", doc = "Item display name, e.g. \"Oak Block\"."),
                    @ApiParam(name = "direction", type = "str", doc = "Which list to search.", values = {"in", "out"})},
            returns = "list[Area]", returnsDoc = "Area snapshots (\"out\": plus `th`).",
            example = "dest = nc.areas.for_item(\"Oak Block\", direction=\"in\")\nif not dest:\n    print(\"no area takes oak blocks\")")
    public static Object forItem(OpContext c, Args a) {
        String name = a.str("name");
        String dir = a.str("direction");
        if (!dir.equals("in") && !dir.equals("out")) throw ApiError.badArgs("direction must be \"in\" or \"out\"");
        boolean in = dir.equals("in");
        Gob pl = c.gui.map.player();
        List<NArea> found = new ArrayList<>();
        List<Integer> ths = new ArrayList<>();
        for (NArea ar : Areas.all(c).values()) {
            if (ar.isDisabled()) continue;
            JSONArray list = in ? ar.jin : ar.jout;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                if (e != null && name.equals(e.optString("name", null))) {
                    found.add(ar);
                    ths.add(e.has("th") ? e.optInt("th", 1) : 1);
                    break;
                }
            }
        }
        List<Integer> order = new ArrayList<>();
        final double[] dist = new double[found.size()];
        for (int i = 0; i < found.size(); i++) {
            order.add(i);
            Pair<Coord2d, Coord2d> rc = found.get(i).getRCArea();
            dist[i] = (rc == null || pl == null) ? Double.MAX_VALUE : rc.a.add(rc.b).div(2).dist(pl.rc);
        }
        order.sort(Comparator.comparingDouble(i -> dist[i]));
        List<JSONObject> res = new ArrayList<>();
        for (int i : order) {
            JSONObject j = snap(found.get(i));
            if (!in) j.put("th", ths.get(i));
            res.add(j);
        }
        return res;
    }

    @ApiOp(name = "areas.containers", kind = QUERY,
            doc = "The storage containers inside an area that the client's bots know how to use (chests, crates, cupboards, sheds, baskets, "
                    + "tables, study desks, ...), nearest first. Each Gob snapshot has `cap`, the caption of the window it opens, "
                    + "which is what `inv.items(inventory=...)` accepts once it is open. Stockpiles and barrels are not included (use `gobs.find`). "
                    + "Raises NotFound if the area is not loaded around the player.",
            params = {@ApiParam(name = "area", type = "Area|int|str", doc = "Area id, name or snapshot.")},
            returns = "list[Gob]", returnsDoc = "Gob snapshots plus `cap`.",
            errors = {Errors.NO_SUCH_AREA, Errors.NOT_FOUND},
            example = "for box in nc.areas.containers(\"Cheese\"):\n    print(box[\"cap\"], box[\"rc\"])")
    public static Object containers(OpContext c, Args a) throws InterruptedException {
        NArea ar = a.area("area");
        if (ar.getRCArea() == null)
            throw ApiError.notFound("area '" + ar.name + "' is not loaded around the player (too far away or hidden)");
        NAlias caps = new NAlias(new ArrayList<String>(NContext.contcaps.keySet()), new ArrayList<String>());
        List<Gob> gobs = Finder.findGobs(ar, caps);
        Gob pl = c.gui.map.player();
        if (pl != null) {
            final Coord2d from = pl.rc;
            gobs.sort(Comparator.comparingDouble(g -> g.rc.dist(from)));
        }
        List<JSONObject> res = new ArrayList<>();
        for (Gob g : gobs) {
            String nm = (g.ngob == null) ? null : g.ngob.name;
            String cap = (nm == null) ? null : NContext.contcaps.get(nm);
            if (cap == null) continue;
            res.add(Codec.gob(g).put("cap", cap));
        }
        return res;
    }

    private AreaOps() {}
}
