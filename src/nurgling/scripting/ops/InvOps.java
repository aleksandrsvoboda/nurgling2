package nurgling.scripting.ops;

import haven.*;
import nurgling.NGItem;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.scripting.api.*;
import nurgling.tools.NAlias;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** Inventories, items, the hand and stockpile windows. */
public final class InvOps {

    private static final String INV_DOC = "Inventory: \"main\" (default), a window caption such as \"Cupboard\", or an Inventory handle.";

    private static void collectInventories(Widget w, List<NInventory> res) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            if (ch instanceof NInventory) {
                if (!res.contains(ch)) res.add((NInventory) ch);
            } else {
                collectInventories(ch, res);
            }
        }
    }

    @ApiOp(name = "inv.list", kind = QUERY,
            doc = "Every open inventory: the player's main inventory first, then those inside open windows (containers, belt, cupboards). "
                    + "Each snapshot carries `free`, the number of empty cells.",
            returns = "list[Inventory]", returnsDoc = "Inventory snapshots {wdg, window, size, free}.",
            example = "for inv in nc.inv.list():\n    print(inv[\"window\"], inv[\"free\"])")
    public static Object list(OpContext c, Args a) throws InterruptedException {
        List<NInventory> invs = new ArrayList<>();
        NInventory main = c.gui.getInventory();
        if (main != null) invs.add(main);
        for (Widget w = c.gui.child; w != null; w = w.next) {
            if (w instanceof Window) collectInventories(w, invs);
        }
        List<JSONObject> res = new ArrayList<>();
        for (NInventory inv : invs) {
            JSONObject j = Codec.inventory(inv);
            j.put("free", inv.getFreeSpace());
            res.add(j);
        }
        return res;
    }

    private static List<WItem> items(NInventory inv, NAlias name, boolean expand) throws InterruptedException {
        return expand ? inv.getItems(name) : inv.getWItems(name);
    }

    @ApiOp(name = "inv.items", kind = QUERY,
            doc = "Items in an inventory, optionally filtered. `name` matches the item's display name (\"Oak Block\", \"Cheese Tray\"), "
                    + "case-insensitively by substring; resource paths such as \"items/x\" do not match. "
                    + "With `expand_stacks` (the default) the members of item stacks are listed one by one and the stack itself is not, "
                    + "which is what Java bots see; with `expand_stacks=False` you get the top-level grid items, stacks included as one item each (`stack` > 0). "
                    + "`quality_min` drops items below that quality (items without a quality are dropped too).",
            params = {
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = INV_DOC, optional = true, def = "\"main\""),
                    @ApiParam(name = "name", type = "Alias", doc = "Display-name pattern(s).", optional = true),
                    @ApiParam(name = "quality_min", type = "float", doc = "Minimum quality (exact float, not the rounded display).", optional = true),
                    @ApiParam(name = "expand_stacks", type = "bool", doc = "List stack members instead of stacks.", optional = true, def = "true")},
            returns = "list[Item]", errors = {Errors.NOT_FOUND},
            example = "logs = nc.inv.items(name=\"Block\", quality_min=30)\nprint(len(logs), \"good blocks\")")
    public static Object items(OpContext c, Args a) throws InterruptedException {
        NInventory inv = a.inventory("inventory");
        List<WItem> its = items(inv, a.alias("name", null), a.b("expand_stacks", true));
        if (a.has("quality_min")) {
            double q = a.d("quality_min");
            List<WItem> res = new ArrayList<>();
            for (WItem w : its) {
                Float iq = (w.item instanceof NGItem) ? ((NGItem) w.item).quality : null;
                if (iq != null && iq >= q) res.add(w);
            }
            its = res;
        }
        return its;
    }

    @ApiOp(name = "inv.item", kind = QUERY,
            doc = "A fresh snapshot of one item, or null if its widget no longer exists (moved to another inventory, used up, container closed). "
                    + "Works for items in inventories, stacks, equipment and the hand. Never raises ItemGone.",
            params = {@ApiParam(name = "item", type = "Item", doc = "Item handle (snapshot) or widget id.")},
            returns = "Item|null",
            example = "it = nc.inv.item(it)\nif it is None:\n    print(\"gone\")")
    public static Object item(OpContext c, Args a) {
        Object o = a.raw().get("item");
        long id;
        if (o instanceof Number) id = ((Number) o).longValue();
        else if (o instanceof JSONObject && ((JSONObject) o).has("item")) id = ((JSONObject) o).getLong("item");
        else throw ApiError.badArgs("'item' must be an item handle (an id or a snapshot)");
        Widget w = c.ui.getwidget((int) id);
        if (!(w instanceof GItem)) return null;
        return Codec.item((GItem) w);
    }

    @ApiOp(name = "inv.find", kind = QUERY,
            doc = "The first item whose display name matches `name` (stack members included), or null.",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Display-name pattern(s)."),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = INV_DOC, optional = true, def = "\"main\"")},
            returns = "Item|null", errors = {Errors.NOT_FOUND},
            example = "knife = nc.inv.find(\"Stone Axe\")")
    public static Object find(OpContext c, Args a) throws InterruptedException {
        List<WItem> its = a.inventory("inventory").getItems(a.alias("name"));
        return its.isEmpty() ? null : its.get(0);
    }

    @ApiOp(name = "inv.count", kind = QUERY,
            doc = "How many items match `name`, counting each stack member as one item. "
                    + "This counts items, not amounts: a pile of seeds that is one item counts 1; sum `num` from `inv.items` for amounts.",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Display-name pattern(s)."),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = INV_DOC, optional = true, def = "\"main\"")},
            returns = "int", errors = {Errors.NOT_FOUND},
            example = "if nc.inv.count(\"Branch\") < 5:\n    print(\"need branches\")")
    public static Object count(OpContext c, Args a) throws InterruptedException {
        return a.inventory("inventory").getItems(a.alias("name")).size();
    }

    @ApiOp(name = "inv.free_space", kind = QUERY,
            doc = "Number of empty cells in an inventory.",
            params = {@ApiParam(name = "inventory", type = "Inventory|str", doc = INV_DOC, optional = true, def = "\"main\"")},
            returns = "int", errors = {Errors.NOT_FOUND},
            example = "if nc.inv.free_space() < 4:\n    nc.bots.run(\"free_inventory\")")
    public static Object freeSpace(OpContext c, Args a) throws InterruptedException {
        return a.inventory("inventory").getFreeSpace();
    }

    @ApiOp(name = "inv.free_slots", kind = QUERY,
            doc = "How many items of the given size still fit, placed without overlapping (the client's own space check, as Java bots use it). "
                    + "A 1x1 item is [1, 1]; a cheese tray is [1, 2]. Use the `size` field of an item snapshot.",
            params = {
                    @ApiParam(name = "size", type = "Tile", doc = "Item size in cells, [w, h]."),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = INV_DOC, optional = true, def = "\"main\"")},
            returns = "int", errors = {Errors.NOT_FOUND},
            example = "trays = nc.inv.free_slots([1, 2])")
    public static Object freeSlots(OpContext c, Args a) throws InterruptedException {
        Coord sz = a.coord("size");
        if (sz.x < 1 || sz.y < 1) throw ApiError.badArgs("size must be at least [1, 1]");
        return a.inventory("inventory").getNumberFreeCoord(sz);
    }

    @ApiOp(name = "inv.hand", kind = QUERY,
            doc = "The item held on the cursor, or null.",
            returns = "Item|null",
            example = "held = nc.inv.hand()\nif held:\n    print(\"holding\", held[\"name\"])")
    public static Object hand(OpContext c, Args a) {
        WItem w = c.gui.vhand;
        return w == null ? null : Codec.item(w);
    }

    @ApiOp(name = "inv.stockpile", kind = QUERY,
            doc = "The open stockpile window's state, or null if none is open. `count` items are in the pile, `free` more fit, "
                    + "`capacity` = count + free; `res` is the piled item's resource name. Counts are null if the window has not shown them yet.",
            returns = "dict|null", returnsDoc = "{wdg, res, count, free, capacity}",
            example = "sp = nc.inv.stockpile()\nif sp and sp[\"free\"] == 0:\n    print(\"pile is full\")")
    public static Object stockpile(OpContext c, Args a) {
        NISBox sp = c.gui.getStockpile();
        if (sp == null) return null;
        int free = sp.calcFreeSpace();
        int count = sp.calcCount();
        JSONObject j = new JSONObject();
        j.put("wdg", sp.wdgid());
        j.put("res", Codec.toJson(sp.itemres));
        j.put("count", count < 0 ? JSONObject.NULL : (Object) count);
        j.put("free", free < 0 ? JSONObject.NULL : (Object) free);
        j.put("capacity", (count < 0 || free < 0) ? JSONObject.NULL : (Object) (count + free));
        return j;
    }

    private InvOps() {}
}
