package nurgling.scripting.ops;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.scripting.api.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static haven.OCache.posres;
import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/**
 * Layer 3: the raw UI. Anything a player can do by hand is a widget message; these ops send them
 * and read the widget tree. Message semantics belong to the game server and may change with it.
 */
public final class RawOps {

    // ------------------------------------------------------------------ widget tree

    private static JSONObject node(Widget w, int depth) {
        JSONObject j = Codec.widget(w);
        if (w instanceof Button && ((Button) w).text != null) j.put("text", ((Button) w).text.text);
        if (w instanceof Label) j.put("text", ((Label) w).texts);
        if (depth != 0) {
            JSONArray kids = new JSONArray();
            for (Widget c = w.child; c != null; c = c.next) kids.put(node(c, depth - 1));
            j.put("children", kids);
        }
        return j;
    }

    @ApiOp(name = "ui.tree", kind = QUERY,
            doc = "The widget tree below a widget (default: the game UI). Each node is a Widget snapshot plus `children`, "
                    + "and `text` for buttons and labels. Use `depth` to keep it small; the full tree has thousands of nodes.",
            params = {
                    @ApiParam(name = "root", type = "Widget", doc = "Widget to start from.", optional = true),
                    @ApiParam(name = "depth", type = "int", doc = "Levels below the root; -1 for all.", optional = true, def = "2")},
            returns = "dict", errors = {Errors.WIDGET_GONE},
            example = "tree = nc.ui.tree(depth=1)\nprint([c[\"class\"] for c in tree[\"children\"]])")
    public static Object tree(OpContext c, Args a) {
        Widget root = a.has("root") ? a.widget("root") : c.gui;
        return node(root, a.i("depth", 2));
    }

    @ApiOp(name = "ui.widget", kind = QUERY,
            doc = "One widget's snapshot, or null if it no longer exists.",
            params = {@ApiParam(name = "wdg", type = "Widget", doc = "Widget handle or id.")},
            returns = "Widget|null")
    public static Object widget(OpContext c, Args a) {
        Widget w = c.ui.getwidget((int) a.l("wdg"));
        return w == null ? null : node(w, 0);
    }

    @ApiOp(name = "ui.find", kind = QUERY,
            doc = "Searches the widget tree for widgets whose Java class name ends with `cls` and/or whose caption or text contains `text`.",
            params = {
                    @ApiParam(name = "cls", type = "str", doc = "Class name suffix, e.g. \"Button\" or \"NFlowerMenu\".", optional = true),
                    @ApiParam(name = "text", type = "str", doc = "Substring of a window caption, button or label text.", optional = true),
                    @ApiParam(name = "root", type = "Widget", doc = "Search below this widget only.", optional = true)},
            returns = "list[Widget]",
            example = "ok = nc.ui.find(cls=\"Button\", text=\"Build\")")
    public static Object find(OpContext c, Args a) {
        String cls = a.str("cls", null), text = a.str("text", null);
        Widget root = a.has("root") ? a.widget("root") : c.gui;
        JSONArray res = new JSONArray();
        collect(root, cls, text, res);
        return res;
    }

    private static void collect(Widget w, String cls, String text, JSONArray res) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            boolean ok = (cls == null || ch.getClass().getName().endsWith(cls));
            if (ok && text != null) {
                String t = null;
                if (ch instanceof Window) t = ((Window) ch).cap;
                else if (ch instanceof Button && ((Button) ch).text != null) t = ((Button) ch).text.text;
                else if (ch instanceof Label) t = ((Label) ch).texts;
                ok = t != null && t.contains(text);
            }
            if (ok) res.put(node(ch, 0));
            collect(ch, cls, text, res);
        }
    }

    @ApiOp(name = "ui.wdgmsg", kind = ACTION,
            doc = "Sends a raw widget message to the server, exactly as the widget would. "
                    + "Coordinates in `args` are passed as [x, y] lists and converted to Coord; use `raw.*` ops for world positions, which need server units.",
            params = {
                    @ApiParam(name = "wdg", type = "Widget", doc = "Sending widget (handle or id)."),
                    @ApiParam(name = "msg", type = "str", doc = "Message name, e.g. \"close\", \"click\", \"transfer\"."),
                    @ApiParam(name = "args", type = "list[any]", doc = "Message arguments.", optional = true, def = "[]")},
            returns = "null", errors = {Errors.WIDGET_GONE},
            example = "nc.ui.wdgmsg(wnd, \"close\")")
    public static Object wdgmsg(OpContext c, Args a) {
        Widget w = a.widget("wdg");
        Object[] args = a.has("args") ? toArgs(a.arr("args")) : new Object[0];
        w.wdgmsg(a.str("msg"), args);
        return null;
    }

    static Object[] toArgs(JSONArray arr) {
        Object[] res = new Object[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            Object o = arr.get(i);
            if (o instanceof JSONArray && ((JSONArray) o).length() == 2) {
                JSONArray p = (JSONArray) o;
                res[i] = new Coord(p.getInt(0), p.getInt(1));
            } else if (o == JSONObject.NULL) {
                res[i] = null;
            } else if (o instanceof Long && (Long) o >= Integer.MIN_VALUE && (Long) o <= Integer.MAX_VALUE) {
                res[i] = ((Long) o).intValue();
            } else if (o instanceof java.math.BigDecimal) {
                res[i] = ((java.math.BigDecimal) o).floatValue();
            } else if (o instanceof Double) {
                res[i] = ((Double) o).floatValue();
            } else {
                res[i] = o;
            }
        }
        return res;
    }

    @ApiOp(name = "ui.click_button", kind = ACTION,
            doc = "Clicks the first button whose text equals `text` (inside `window` if given).",
            params = {
                    @ApiParam(name = "text", type = "str", doc = "Button text, e.g. \"Build\"."),
                    @ApiParam(name = "window", type = "Window|str", doc = "Window handle or caption.", optional = true)},
            returns = "null", errors = {Errors.NOT_FOUND})
    public static Object clickButton(OpContext c, Args a) {
        Widget root = a.has("window") ? a.window("window") : c.gui;
        Button b = findButton(root, a.str("text"));
        if (b == null) throw ApiError.notFound("no button '" + a.str("text") + "'");
        b.click();
        return null;
    }

    static Button findButton(Widget w, String text) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            if (ch instanceof Button && ((Button) ch).text != null && text.equals(((Button) ch).text.text)) return (Button) ch;
            Button r = findButton(ch, text);
            if (r != null) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ map clicks

    @ApiOp(name = "raw.click_gob", kind = ACTION,
            doc = "Clicks a game object like the mouse would. Button 1 is left, 3 is right (opens the flower menu). "
                    + "`mods`: 1 shift, 2 ctrl, 4 alt (add them). Does not walk there first and does not wait.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Target."),
                    @ApiParam(name = "button", type = "int", doc = "Mouse button.", optional = true, def = "3"),
                    @ApiParam(name = "mods", type = "int", doc = "Modifier flags.", optional = true, def = "0"),
                    @ApiParam(name = "meshid", type = "int", doc = "Model part to click (doors, sub-meshes); -1 for the whole object.", optional = true, def = "-1"),
                    @ApiParam(name = "offset", type = "Coord", doc = "Click point relative to the object's centre, in its own rotation.", optional = true)},
            returns = "null", errors = {Errors.GOB_GONE})
    public static Object clickGob(OpContext c, Args a) {
        Gob g = a.gob("gob");
        Coord2d target = a.has("offset") ? g.rc.add(a.coord2d("offset").rot(g.a)) : g.rc;
        c.gui.map.wdgmsg("click", Coord.z, target.floor(posres), a.i("button", 3), a.i("mods", 0), 0,
                (int) g.id, g.rc.floor(posres), 0, a.i("meshid", -1));
        return null;
    }

    @ApiOp(name = "raw.click_ground", kind = ACTION,
            doc = "Clicks the ground at a world position. A left click walks there (without pathfinding); with a menu action armed (raw.menu_act) it applies the action there.",
            params = {
                    @ApiParam(name = "pos", type = "Coord", doc = "World position."),
                    @ApiParam(name = "button", type = "int", doc = "Mouse button.", optional = true, def = "1"),
                    @ApiParam(name = "mods", type = "int", doc = "Modifier flags.", optional = true, def = "0")},
            returns = "null")
    public static Object clickGround(OpContext c, Args a) {
        c.gui.map.wdgmsg("click", Coord.z, a.coord2d("pos").floor(posres), a.i("button", 1), a.i("mods", 0));
        return null;
    }

    @ApiOp(name = "raw.itemact_gob", kind = ACTION,
            doc = "Uses the item in hand on a game object (like clicking it while holding the item).",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Target."),
                    @ApiParam(name = "mods", type = "int", doc = "1 = shift (use all / repeat), 3 = shift+ctrl.", optional = true, def = "0")},
            returns = "null", errors = {Errors.GOB_GONE})
    public static Object itemactGob(OpContext c, Args a) {
        Gob g = a.gob("gob");
        c.gui.map.wdgmsg("itemact", Coord.z, g.rc.floor(posres), a.i("mods", 0), 0, (int) g.id, g.rc.floor(posres), 0, -1);
        return null;
    }

    @ApiOp(name = "raw.itemact_ground", kind = ACTION,
            doc = "Uses the item in hand on the ground at a position.",
            params = {
                    @ApiParam(name = "pos", type = "Coord", doc = "World position."),
                    @ApiParam(name = "mods", type = "int", doc = "Modifier flags.", optional = true, def = "0")},
            returns = "null")
    public static Object itemactGround(OpContext c, Args a) {
        c.gui.map.wdgmsg("itemact", Coord.z, a.coord2d("pos").floor(posres), a.i("mods", 0));
        return null;
    }

    @ApiOp(name = "raw.place", kind = ACTION,
            doc = "Places the object being placed (a carried object or a build ghost) at a position and angle. "
                    + "Arm it first (e.g. raw.click_gob with shift on a carried object, or a build recipe).",
            params = {
                    @ApiParam(name = "pos", type = "Coord", doc = "World position."),
                    @ApiParam(name = "angle", type = "float", doc = "Rotation in radians.", optional = true, def = "0")},
            returns = "null")
    public static Object place(OpContext c, Args a) {
        double ang = a.d("angle", 0);
        c.gui.map.wdgmsg("place", a.coord2d("pos").floor(posres), (int) Math.round(ang * 32768 / Math.PI), 1, 0);
        return null;
    }

    @ApiOp(name = "raw.menu_act", kind = ACTION,
            doc = "Triggers a menu-grid action by its internal name, as if its button or hotkey were used: "
                    + "e.g. [\"carry\"], [\"destroy\"], [\"mine\"], [\"dig\"], [\"aggro\"], [\"croster\"], [\"bp\", \"palisade\"]. "
                    + "Most arm the cursor; follow with raw.click_gob or raw.click_ground.",
            params = {@ApiParam(name = "act", type = "list[str]", doc = "Action path.")},
            returns = "null",
            example = "nc.raw.menu_act([\"destroy\"])\nnc.raw.click_gob(stump, button=1)")
    public static Object menuAct(OpContext c, Args a) {
        List<String> act = a.strs("act");
        if (act.isEmpty()) throw ApiError.badArgs("act must not be empty");
        c.ui.rcvr.rcvmsg(c.ui.getMenuGridId(), "act", act.toArray());
        return null;
    }

    @ApiOp(name = "raw.pagina_use", kind = ACTION,
            doc = "Uses a menu-grid entry (pagina) by its display name or resource name, e.g. \"Drink\" or \"paginae/act/drink\". "
                    + "Crafting recipes open their craft window this way.",
            params = {@ApiParam(name = "name", type = "str", doc = "Display name or resource name.")},
            returns = "null", errors = {Errors.NOT_FOUND})
    public static Object paginaUse(OpContext c, Args a) {
        String n = a.str("name");
        for (MenuGrid.Pagina p : new ArrayList<>(c.gui.menu.paginae)) {
            MenuGrid.PagButton b = p.button();
            if (b == null) continue;
            if (n.equals(b.res.name) || n.equals(b.name())) {
                b.use(new MenuGrid.Interaction(1, 0));
                return null;
            }
        }
        throw ApiError.notFound("no menu entry '" + n + "' (is it unlocked and loaded?)");
    }

    // ------------------------------------------------------------------ items

    @ApiOp(name = "raw.item_take", kind = ACTION,
            doc = "Picks an item up onto the cursor (the hand). Does not wait; see wait.item_in_hand.",
            params = {@ApiParam(name = "item", type = "Item", doc = "Item.")},
            returns = "null", errors = {Errors.ITEM_GONE})
    public static Object itemTake(OpContext c, Args a) {
        a.gitem("item").wdgmsg("take", Coord.z);
        return null;
    }

    @ApiOp(name = "raw.item_transfer", kind = ACTION,
            doc = "Shift-clicks an item: moves it to the other open inventory (or out of a container into yours).",
            params = {@ApiParam(name = "item", type = "Item", doc = "Item.")},
            returns = "null", errors = {Errors.ITEM_GONE})
    public static Object itemTransfer(OpContext c, Args a) {
        a.gitem("item").wdgmsg("transfer", Coord.z);
        return null;
    }

    @ApiOp(name = "raw.item_drop", kind = ACTION,
            doc = "Drops an item on the ground at the player's feet (ctrl-click).",
            params = {@ApiParam(name = "item", type = "Item", doc = "Item.")},
            returns = "null", errors = {Errors.ITEM_GONE})
    public static Object itemDrop(OpContext c, Args a) {
        NUtils.drop(a.item("item"));
        return null;
    }

    @ApiOp(name = "raw.item_interact", kind = ACTION,
            doc = "Right-clicks an item: opens its flower menu (Eat, Split, Drink...). Choose with raw.flower_choose or actions.flower_item.",
            params = {@ApiParam(name = "item", type = "Item", doc = "Item.")},
            returns = "null", errors = {Errors.ITEM_GONE})
    public static Object itemInteract(OpContext c, Args a) {
        WItem w = a.item("item");
        w.item.wdgmsg("iact", w.c, 0);
        return null;
    }

    @ApiOp(name = "raw.itemact_item", kind = ACTION,
            doc = "Uses the item in hand on another item (e.g. pour water into a bucket, combine).",
            params = {
                    @ApiParam(name = "item", type = "Item", doc = "Target item."),
                    @ApiParam(name = "mods", type = "int", doc = "Modifier flags; 1 = shift (all).", optional = true, def = "0")},
            returns = "null", errors = {Errors.ITEM_GONE})
    public static Object itemactItem(OpContext c, Args a) {
        a.gitem("item").wdgmsg("itemact", a.i("mods", 0));
        return null;
    }

    @ApiOp(name = "raw.inv_drop", kind = ACTION,
            doc = "Drops the item in hand into an inventory cell.",
            params = {
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Target inventory (\"main\", a window caption, or a handle)."),
                    @ApiParam(name = "slot", type = "Tile", doc = "Cell [x, y].")},
            returns = "null", errors = {Errors.NOT_FOUND})
    public static Object invDrop(OpContext c, Args a) {
        NInventory inv = a.inventory("inventory");
        inv.wdgmsg("drop", a.coord("slot"));
        return null;
    }

    @ApiOp(name = "raw.invxf", kind = ACTION,
            doc = "Moves items from one open inventory to another the way shift+mouse-wheel does: "
                    + "`count` items, taken by the server in its own order. Use actions.transfer for name-based moves.",
            params = {
                    @ApiParam(name = "from", type = "Inventory|str", doc = "Source inventory."),
                    @ApiParam(name = "to", type = "Inventory|str", doc = "Target inventory."),
                    @ApiParam(name = "count", type = "int", doc = "Number of items.", optional = true, def = "1")},
            returns = "null", errors = {Errors.NOT_FOUND})
    public static Object invxf(OpContext c, Args a) {
        NInventory from = a.inventory("from");
        NInventory to = a.inventory("to");
        from.wdgmsg("invxf", to.wdgid(), a.i("count", 1));
        return null;
    }

    // ------------------------------------------------------------------ flower menu

    @ApiOp(name = "raw.flower_options", kind = QUERY,
            doc = "Options of the open flower (right-click) menu, or an empty list if none is open.",
            returns = "list[str]")
    public static Object flowerOptions(OpContext c, Args a) {
        NFlowerMenu fm = findFlower(c);
        JSONArray res = new JSONArray();
        if (fm != null && fm.nopts != null) for (NFlowerMenu.NPetal p : fm.nopts) res.put(p.name);
        return res;
    }

    @ApiOp(name = "raw.flower_choose", kind = ACTION,
            doc = "Chooses an option of the open flower menu by its exact text. Pass null to close the menu without choosing.",
            params = {@ApiParam(name = "option", type = "str|null", doc = "Option text, e.g. \"Chop\".")},
            returns = "bool", returnsDoc = "False if the menu has no such option (the menu is then closed).",
            errors = {Errors.NOT_FOUND})
    public static Object flowerChoose(OpContext c, Args a) {
        NFlowerMenu fm = findFlower(c);
        if (fm == null) throw ApiError.notFound("no flower menu is open");
        if (!a.has("option")) {
            fm.wdgmsg("cl", -1);
            return false;
        }
        return fm.chooseOpt(a.str("option"));
    }

    static NFlowerMenu findFlower(OpContext c) {
        for (Widget w = c.ui.root.child; w != null; w = w.next) {
            if (w instanceof NFlowerMenu) return (NFlowerMenu) w;
        }
        return Args.findChild(c.ui.root, NFlowerMenu.class);
    }

    @ApiOp(name = "raw.cursor", kind = QUERY,
            doc = "Name of the current mouse cursor resource, e.g. \"arw\" (normal), \"dig\", \"mine\", \"chi\" (carry).",
            returns = "str|null")
    public static Object cursor(OpContext c, Args a) {
        return NUtils.getCursorName();
    }

    private RawOps() {}
}
