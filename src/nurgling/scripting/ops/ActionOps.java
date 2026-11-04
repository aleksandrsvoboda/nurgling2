package nurgling.scripting.ops;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NGob;
import nurgling.NHitBox;
import nurgling.NISBox;
import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.*;
import nurgling.actions.bots.LightObject;
import nurgling.areas.NArea;
import nurgling.areas.NContext;
import nurgling.scripting.api.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

import static haven.OCache.posres;
import static nurgling.scripting.api.ApiOp.Kind.ACTION;

/**
 * Layer 2: the client's own Java actions, wrapped for scripts.
 *
 * Ops that wrap a composite Java action (free_inventory, harvest, to_piles, ...) run until that
 * action returns and have no timeout of their own: the action decides when it is done, and the
 * script's stop button ends it. Ops that only send a message and wait for its effect take a
 * {@code timeout} in seconds.
 */
public final class ActionOps {

    // ------------------------------------------------------------------ walking

    @ApiOp(name = "actions.path_to", kind = ACTION,
            doc = "Walks to a game object or a world position with the client's pathfinder, avoiding obstacles. "
                    + "For a game object it stops next to it, close enough to interact. "
                    + "`hard` mode walks right up to the object's edge (what container and pile bots use). "
                    + "Only works within the loaded area around the player; use actions.go_to_area for long distances. "
                    + "Runs until the pathfinder arrives or gives up; there is no timeout.",
            params = {
                    @ApiParam(name = "target", type = "Gob|Coord", doc = "Game object, or a world position [x, y]."),
                    @ApiParam(name = "hard", type = "bool", doc = "Walk right up to the object's hitbox instead of the nearest free spot.", optional = true, def = "false")},
            returns = "null", errors = {Errors.PATH_NOT_FOUND, Errors.GOB_GONE},
            example = "tree = nc.gobs.find(name=\"trees/\")[0]\nnc.actions.path_to(tree)")
    public static Object pathTo(OpContext c, Args a) throws InterruptedException {
        PathFinder pf;
        String what;
        if (isGobArg(a, "target")) {
            Gob g = a.gob("target");
            pf = new PathFinder(g);
            what = "gob " + g.id;
        } else {
            Coord2d p = a.coord2d("target");
            pf = new PathFinder(p);
            what = p.toString();
        }
        pf.isHardMode = a.b("hard", false);
        Results r = pf.run(c.gui);
        if (r == null || !r.IsSuccess())
            throw new ApiError(Errors.PATH_NOT_FOUND, "no path to " + what);
        return null;
    }

    @ApiOp(name = "actions.go_to_area", kind = ACTION,
            doc = "Travels to an area using the client's navigation: the local pathfinder when the area is near, "
                    + "otherwise the global chunk navigation (roads, doors, stairs) the bots use. "
                    + "With `enter` (the default) it also walks onto the area so its objects are loaded; "
                    + "without it, an area that is already reachable is not walked to. "
                    + "Runs until the navigation finishes; there is no timeout.",
            params = {
                    @ApiParam(name = "area", type = "Area", doc = "Area handle, id or name."),
                    @ApiParam(name = "enter", type = "bool", doc = "Walk onto the area even if it is already reachable.", optional = true, def = "true")},
            returns = "Area", returnsDoc = "The area's snapshot after arriving.",
            errors = {Errors.NO_SUCH_AREA, Errors.PATH_NOT_FOUND},
            example = "nc.actions.go_to_area(\"Logs\")")
    public static Object goToArea(OpContext c, Args a) throws InterruptedException {
        NArea area = a.area("area");
        if (!NUtils.navigateToArea(area, a.b("enter", true)))
            throw new ApiError(Errors.PATH_NOT_FOUND, "could not navigate to area '" + area.name + "'");
        return Codec.area(area);
    }

    @ApiOp(name = "actions.stop", kind = ACTION,
            doc = "Stops the character where it stands (a left click on its own position). Cancels walking and most ongoing work.",
            returns = "null",
            example = "nc.actions.stop()")
    public static Object stop(OpContext c, Args a) {
        PathFinder.stopHere(c.gui);
        return null;
    }

    @ApiOp(name = "actions.face", kind = ACTION,
            doc = "Turns the character to face a position or game object without walking there, and waits until it does.",
            params = {
                    @ApiParam(name = "target", type = "Gob|Coord", doc = "What to face."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the turn.", optional = true, def = "3")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.TIMEOUT, Errors.NOT_FOUND},
            example = "nc.actions.face(nc.gobs.find(name=\"anvil\")[0])")
    public static Object face(OpContext c, Args a) throws InterruptedException {
        Coord2d target = a.coord2d("target");
        final Gob pl = player(c);
        Coord2d dir = target.sub(pl.rc);
        if (dir.abs() < 0.01) return null;
        dir = dir.norm();
        final double want = dir.curAngle();
        c.gui.map.wdgmsg("click", Coord.z, pl.rc.add(dir.div(10)).floor(posres), 1, 0);
        c.waitUntil(() -> {
            double d = Math.abs(pl.a - want) % (2 * Math.PI);
            return Math.min(d, 2 * Math.PI - d) < Math.PI / 9;
        }, a.d("timeout", 3), "the character to turn");
        return null;
    }

    // ------------------------------------------------------------------ flower menus

    @ApiOp(name = "actions.flower", kind = ACTION,
            doc = "Right-clicks a game object and chooses an option from its flower menu, then waits for the menu to close. "
                    + "Pass a list to try several options in order (e.g. [\"Chop\", \"Chop into blocks\"]); the first one the menu offers is chosen. "
                    + "The server walks the character to the object by itself, so no path_to is needed for reachable objects. "
                    + "This op returns once the option is chosen, not when the work is finished: follow it with a wait such as wait.pose_not.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Object to right-click."),
                    @ApiParam(name = "option", type = "str|list[str]", doc = "Option text, or candidates in order of preference. Exact match."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the menu to appear.", optional = true, def = "5")},
            returns = "str", returnsDoc = "The option that was chosen.",
            errors = {Errors.GOB_GONE, Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "nc.actions.flower(tree, \"Chop\")\nnc.wait.pose_not(nc.player(), \"chop\", timeout=120)")
    public static Object flower(OpContext c, Args a) throws InterruptedException {
        Gob g = a.gob("gob");
        List<String> opts = a.strs("option");
        if (opts.isEmpty()) throw ApiError.badArgs("option must not be empty");
        closeStaleFlower(c);
        c.gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 1, (int) g.id, g.rc.floor(posres), 0, -1);
        return chooseFlower(c, opts, a.d("timeout", 5), "gob " + g.id);
    }

    @ApiOp(name = "actions.flower_item", kind = ACTION,
            doc = "Right-clicks an inventory item and chooses an option from its flower menu (e.g. \"Eat\", \"Split\", \"Drink\"), "
                    + "then waits for the menu to close.",
            params = {
                    @ApiParam(name = "item", type = "Item", doc = "Item to right-click."),
                    @ApiParam(name = "option", type = "str|list[str]", doc = "Option text, or candidates in order of preference. Exact match."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the menu to appear.", optional = true, def = "5")},
            returns = "str", returnsDoc = "The option that was chosen.",
            errors = {Errors.ITEM_GONE, Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "apple = nc.inv.items(name=\"Apple\")[0]\nnc.actions.flower_item(apple, \"Eat\")")
    public static Object flowerItem(OpContext c, Args a) throws InterruptedException {
        WItem w = a.item("item");
        List<String> opts = a.strs("option");
        if (opts.isEmpty()) throw ApiError.badArgs("option must not be empty");
        closeStaleFlower(c);
        w.item.wdgmsg("iact", w.c, 0);
        return chooseFlower(c, opts, a.d("timeout", 5), "item " + w.item.wdgid());
    }

    private static void closeStaleFlower(OpContext c) throws InterruptedException {
        NFlowerMenu old = RawOps.findFlower(c);
        if (old != null) {
            old.wdgmsg("cl", -1);
            c.waitUntil(() -> RawOps.findFlower(c) == null, 3, "an old flower menu to close");
        }
    }

    private static String chooseFlower(OpContext c, List<String> opts, double timeout, String what) throws InterruptedException {
        if (!c.waitFor(() -> RawOps.findFlower(c) != null, timeout))
            throw ApiError.notFound("no flower menu appeared for " + what);
        NFlowerMenu fm = RawOps.findFlower(c);
        List<String> offered = new ArrayList<>();
        for (NFlowerMenu.NPetal p : fm.nopts) offered.add(p.name);
        String chosen = null;
        for (String o : opts) {
            if (offered.contains(o)) {
                chosen = o;
                break;
            }
        }
        if (chosen == null) {
            fm.wdgmsg("cl", -1);
            c.waitFor(() -> RawOps.findFlower(c) == null, 3);
            throw ApiError.notFound("flower menu of " + what + " has no option " + opts + "; it offers " + offered);
        }
        fm.chooseOpt(chosen);
        c.waitUntil(() -> RawOps.findFlower(c) == null, 5, "the flower menu to close");
        return chosen;
    }

    // ------------------------------------------------------------------ containers

    @ApiOp(name = "actions.open", kind = ACTION,
            doc = "Walks to a container (chest, cupboard, cart, stockpile, barrel...) and opens it. "
                    + "If its window is already open it is reused. "
                    + "A container that answers with an \"Open\" flower menu (cauldrons, study desks) is opened through it. "
                    + "Close it again with actions.close when done.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "The container."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the window after arriving.", optional = true, def = "10")},
            returns = "Inventory|Window", returnsDoc = "The container's inventory, or its window if it has no item grid (stockpiles, barrels).",
            errors = {Errors.GOB_GONE, Errors.PATH_NOT_FOUND, Errors.TIMEOUT},
            example = "inv = nc.actions.open(chest)\nprint(nc.inv.items(inventory=inv))\nnc.actions.close(inv)")
    public static Object open(OpContext c, Args a) throws InterruptedException {
        Window w = openGob(c, a.gob("gob"), a.d("timeout", 10));
        NInventory inv = Args.findChild(w, NInventory.class);
        return inv != null ? Codec.inventory(inv) : Codec.widget(w);
    }

    @ApiOp(name = "actions.close", kind = ACTION,
            doc = "Closes a window (a container, a crafting window, any window) and waits until it is gone. "
                    + "Closing a window that is already gone does nothing.",
            params = {
                    @ApiParam(name = "window", type = "Window|Inventory|str", doc = "Window handle, an inventory inside it, or its caption."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for it to close.", optional = true, def = "5")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.actions.close(\"Cupboard\")")
    public static Object close(OpContext c, Args a) throws InterruptedException {
        Window w;
        Object raw = a.raw().get("window");
        if (raw instanceof String) {
            w = c.gui.getWindow((String) raw);
            if (w == null) return null;
        } else {
            Widget wdg = c.ui.getwidget((int) widgetId(a, "window"));
            if (wdg == null) return null;
            w = (wdg instanceof Window) ? (Window) wdg : wdg.getparent(Window.class);
            if (w == null) throw ApiError.badArgs("'window' is not inside a window");
        }
        closeWindow(c, w, a.d("timeout", 5));
        return null;
    }

    @ApiOp(name = "actions.transfer", kind = ACTION,
            doc = "Moves items from your inventory into a container. "
                    + "With a game object, walks to it, opens it, transfers and closes it again; "
                    + "with an open inventory (or its window caption), transfers directly. "
                    + "Moves as many as fit, up to `count`. Different item kinds matching the alias are moved one kind after another.",
            params = {
                    @ApiParam(name = "to", type = "Gob|Inventory|str", doc = "Container object, or an open inventory / window caption."),
                    @ApiParam(name = "items", type = "Alias", doc = "Which items to move (name match, as everywhere)."),
                    @ApiParam(name = "count", type = "int", doc = "Maximum number of items to move; all matching if omitted.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the container window when opening a game object.", optional = true, def = "10")},
            returns = "int", returnsDoc = "Number of items that left your inventory.",
            errors = {Errors.GOB_GONE, Errors.PATH_NOT_FOUND, Errors.NOT_FOUND, Errors.TIMEOUT, Errors.NOT_SUPPORTED},
            example = "moved = nc.actions.transfer(chest, \"Block\", count=20)")
    public static Object transfer(OpContext c, Args a) throws InterruptedException {
        NAlias items = a.alias("items");
        int count = a.has("count") ? a.i("count") : -1;
        if (isGobArg(a, "to")) {
            Window w = openGob(c, a.gob("to"), a.d("timeout", 10));
            NInventory inv = containerInventory(c, w);
            int moved = moveItems(c, c.gui.getInventory(), inv, items, null, count);
            closeWindow(c, w, 5);
            return moved;
        }
        return moveItems(c, c.gui.getInventory(), a.inventory("to"), items, null, count);
    }

    @ApiOp(name = "actions.take_from", kind = ACTION,
            doc = "Takes items from a container into your inventory. "
                    + "With a game object, walks to it, opens it, takes and closes it again; "
                    + "with an open inventory (or its window caption), takes directly. "
                    + "Takes as many as fit, up to `count`.",
            params = {
                    @ApiParam(name = "container", type = "Gob|Inventory|str", doc = "Container object, or an open inventory / window caption."),
                    @ApiParam(name = "items", type = "Alias", doc = "Which items to take."),
                    @ApiParam(name = "count", type = "int", doc = "Maximum number of items; all matching if omitted.", optional = true),
                    @ApiParam(name = "quality_min", type = "float", doc = "Only items of at least this quality.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the container window when opening a game object.", optional = true, def = "10")},
            returns = "int", returnsDoc = "Number of items that arrived in your inventory.",
            errors = {Errors.GOB_GONE, Errors.PATH_NOT_FOUND, Errors.NOT_FOUND, Errors.TIMEOUT, Errors.NOT_SUPPORTED},
            example = "nc.actions.take_from(cupboard, \"Wheat Flour\", count=10, quality_min=20)")
    public static Object takeFrom(OpContext c, Args a) throws InterruptedException {
        NAlias items = a.alias("items");
        int count = a.has("count") ? a.i("count") : -1;
        Double q = a.has("quality_min") ? a.d("quality_min") : null;
        if (isGobArg(a, "container")) {
            Window w = openGob(c, a.gob("container"), a.d("timeout", 10));
            NInventory inv = containerInventory(c, w);
            int moved = moveItems(c, inv, c.gui.getInventory(), items, q, count);
            closeWindow(c, w, 5);
            return moved;
        }
        return moveItems(c, a.inventory("container"), c.gui.getInventory(), items, q, count);
    }

    // ------------------------------------------------------------------ zone logistics (NContext)

    @ApiOp(name = "actions.free_inventory", kind = ACTION,
            doc = "Puts everything in your inventory away into the areas set up for it (the item's \"out\" areas and specialisations), "
                    + "like the bots do at the end of a cycle. Items with no area stay in the inventory. "
                    + "Walks wherever needed; runs until done, no timeout.",
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.free_inventory()")
    public static Object freeInventory(OpContext c, Args a) throws InterruptedException {
        c.require(new FreeInventory2(new NContext(c.gui)).run(c.gui), "free_inventory");
        return null;
    }

    @ApiOp(name = "actions.take_items", kind = ACTION,
            doc = "Fetches items from the areas where they are stored (the item's \"in\" areas) into your inventory, "
                    + "walking there as needed. Counts include what you already carry. Runs until done, no timeout.",
            params = {
                    @ApiParam(name = "name", type = "str", doc = "Exact item name, e.g. \"Block of Pine\"."),
                    @ApiParam(name = "count", type = "int", doc = "How many you want to have.")},
            returns = "int", returnsDoc = "How many matching items are in your inventory afterwards.",
            errors = {Errors.ACTION_FAILED},
            example = "nc.actions.take_items(\"Wheat Flour\", 12)")
    public static Object takeItems(OpContext c, Args a) throws InterruptedException {
        String name = a.str("name");
        c.require(new TakeItems2(new NContext(c.gui), name, a.i("count")).run(c.gui), "take_items " + name);
        return c.gui.getInventory().getItems(name).size();
    }

    @ApiOp(name = "actions.transfer_items", kind = ACTION,
            doc = "Delivers the named items from your inventory to their \"out\" areas, walking as needed. "
                    + "Unlike free_inventory, only the listed item names are moved. Runs until done, no timeout.",
            params = {@ApiParam(name = "names", type = "list[str]", doc = "Exact item names.")},
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.transfer_items([\"Block of Pine\", \"Branch\"])")
    public static Object transferItems(OpContext c, Args a) throws InterruptedException {
        c.require(new TransferItems2(new NContext(c.gui), new HashSet<>(a.strs("names"))).run(c.gui), "transfer_items");
        return null;
    }

    // ------------------------------------------------------------------ hand

    @ApiOp(name = "actions.take_to_hand", kind = ACTION,
            doc = "Picks an item up onto the cursor (the hand) and waits until it is there. "
                    + "If something is already in the hand the two are swapped, as in the game.",
            params = {
                    @ApiParam(name = "item", type = "Item", doc = "Item to pick up."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait.", optional = true, def = "5")},
            returns = "Item", returnsDoc = "The item now in the hand.",
            errors = {Errors.ITEM_GONE, Errors.TIMEOUT},
            example = "bucket = nc.actions.take_to_hand(nc.inv.items(name=\"Bucket\")[0])")
    public static Object takeToHand(OpContext c, Args a) throws InterruptedException {
        GItem gi = a.gitem("item");
        return takeHand(c, gi, a.d("timeout", 5));
    }

    private static Object takeHand(OpContext c, GItem gi, double timeout) throws InterruptedException {
        final WItem prev = c.gui.vhand;
        gi.wdgmsg("take", Coord.z);
        c.waitUntil(() -> c.gui.vhand != null && c.gui.vhand != prev, timeout, "the item in hand");
        return Codec.item(c.gui.vhand);
    }

    @ApiOp(name = "actions.drop_hand_to_inventory", kind = ACTION,
            doc = "Puts the item in hand into the first free cell of an inventory and waits until the hand is empty. Does nothing if the hand is empty.",
            params = {
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Target inventory; your own if omitted.", optional = true, def = "\"main\""),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait.", optional = true, def = "5")},
            returns = "null", errors = {Errors.NOT_FOUND, Errors.ACTION_FAILED, Errors.TIMEOUT},
            example = "nc.actions.drop_hand_to_inventory()")
    public static Object dropHandToInventory(OpContext c, Args a) throws InterruptedException {
        if (c.gui.vhand == null) return null;
        NInventory inv = a.inventory("inventory");
        Coord dc = inv.findFreeCoord(c.gui.vhand);
        if (dc == null) throw ApiError.failed("no free cell for the item in hand");
        inv.dropOn(dc);
        c.waitUntil(() -> c.gui.vhand == null, a.d("timeout", 5), "the hand to empty");
        return null;
    }

    @ApiOp(name = "actions.drop_hand", kind = ACTION,
            doc = "Drops the item in hand on the ground at the character's feet and waits until the hand is empty. Does nothing if the hand is empty.",
            params = {@ApiParam(name = "timeout", type = "float", doc = "Seconds to wait.", optional = true, def = "5")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.actions.drop_hand()")
    public static Object dropHand(OpContext c, Args a) throws InterruptedException {
        if (c.gui.vhand == null) return null;
        Gob pl = player(c);
        c.gui.map.wdgmsg("drop", Coord.z, pl.rc.floor(posres), 0);
        c.waitUntil(() -> c.gui.vhand == null, a.d("timeout", 5), "the hand to empty");
        return null;
    }

    // ------------------------------------------------------------------ items

    @ApiOp(name = "actions.use_on_gob", kind = ACTION,
            doc = "Uses the item in hand on a game object, like clicking the object while holding it "
                    + "(water into a trough, seeds into a pot, a log into a chipping block). "
                    + "If `item` is given it is first picked up into the hand. "
                    + "Does not wait for the result; follow it with a wait (wait.hand_free, wait.pose_not...).",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Target object."),
                    @ApiParam(name = "item", type = "Item", doc = "Item to pick up first; otherwise the item already in hand is used.", optional = true),
                    @ApiParam(name = "all", type = "bool", doc = "Shift-click: repeat with every matching item (e.g. fill all seeds).", optional = true, def = "false"),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the item to reach the hand.", optional = true, def = "5")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.ITEM_GONE, Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "nc.actions.use_on_gob(trough, item=nc.inv.items(name=\"Carrot\")[0], all=True)")
    public static Object useOnGob(OpContext c, Args a) throws InterruptedException {
        Gob g = a.gob("gob");
        if (a.has("item")) takeHand(c, a.gitem("item"), a.d("timeout", 5));
        if (c.gui.vhand == null) throw ApiError.notFound("nothing in hand to use");
        c.gui.map.wdgmsg("itemact", Coord.z, g.rc.floor(posres), a.b("all", false) ? 1 : 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
        return null;
    }

    @ApiOp(name = "actions.use_on_item", kind = ACTION,
            doc = "Uses the item in hand on another item (pour a bucket into a jar, put a whetstone on a tool, combine). "
                    + "If `source` is given that item is picked up first. Does not wait for the result.",
            params = {
                    @ApiParam(name = "target", type = "Item", doc = "Item to use the hand on."),
                    @ApiParam(name = "source", type = "Item", doc = "Item to pick up first; otherwise the item already in hand is used.", optional = true),
                    @ApiParam(name = "all", type = "bool", doc = "Shift: apply to all.", optional = true, def = "false"),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the source to reach the hand.", optional = true, def = "5")},
            returns = "null", errors = {Errors.ITEM_GONE, Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "nc.actions.use_on_item(jar, source=bucket)")
    public static Object useOnItem(OpContext c, Args a) throws InterruptedException {
        GItem target = a.gitem("target");
        if (a.has("source")) takeHand(c, a.gitem("source"), a.d("timeout", 5));
        if (c.gui.vhand == null) throw ApiError.notFound("nothing in hand to use");
        target.wdgmsg("itemact", a.b("all", false) ? 1 : 0);
        return null;
    }

    @ApiOp(name = "actions.drop", kind = ACTION,
            doc = "Drops items on the ground at the character's feet and waits until they are gone from the inventory. "
                    + "Pass item handles, or an alias to drop every matching item of an inventory. "
                    + "Drops are paced to stay under the server's flood limit.",
            params = {
                    @ApiParam(name = "items", type = "Item|list[Item]|Alias", doc = "Items, or an alias such as \"Stone\"."),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Where to look up an alias; your own if omitted.", optional = true, def = "\"main\""),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the items to disappear.", optional = true, def = "10")},
            returns = "int", returnsDoc = "Number of items dropped.",
            errors = {Errors.ITEM_GONE, Errors.TIMEOUT},
            example = "nc.actions.drop(\"Stone\")")
    public static Object drop(OpContext c, Args a) throws InterruptedException {
        List<WItem> items = new ArrayList<>();
        Object raw = a.raw().get("items");
        if (raw instanceof JSONObject && ((JSONObject) raw).has("item")) {
            items.add(a.item("items"));
        } else if (raw instanceof JSONArray && ((JSONArray) raw).length() > 0 && !(((JSONArray) raw).get(0) instanceof String)) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.length(); i++) items.add(itemOf(c, arr.get(i)));
        } else {
            items.addAll(a.inventory("inventory").getItems(a.alias("items")));
        }
        player(c);
        final List<Integer> ids = new ArrayList<>();
        for (WItem w : items) {
            c.waitUntil(NUtils::dropSlotReady, 5, "a drop slot");
            NUtils.drop(w);
            ids.add(w.item.wdgid());
        }
        c.waitUntil(() -> {
            for (int id : ids) if (c.ui.getwidget(id) != null) return false;
            return true;
        }, a.d("timeout", 10), "the dropped items to leave the inventory");
        return ids.size();
    }

    private static WItem itemOf(OpContext c, Object o) {
        int id;
        if (o instanceof Number) id = ((Number) o).intValue();
        else if (o instanceof JSONObject && ((JSONObject) o).has("item")) id = ((JSONObject) o).getInt("item");
        else throw ApiError.badArgs("'items' must hold item handles");
        Widget w = c.ui.getwidget(id);
        if (!(w instanceof GItem)) throw new ApiError(Errors.ITEM_GONE, "item " + id + " no longer exists");
        WItem wi = Codec.findWItem((GItem) w);
        if (wi == null) throw new ApiError(Errors.ITEM_GONE, "item " + id + " is not shown in any inventory");
        return wi;
    }

    @ApiOp(name = "actions.equip", kind = ACTION,
            doc = "Equips a tool or weapon in the hands, taking it from the belt or the inventory and putting away what was held if needed. "
                    + "Does nothing if it is already in a hand.",
            params = {@ApiParam(name = "name", type = "Alias", doc = "Item name, e.g. \"Axe\" or [\"Pickaxe\", \"Stone Axe\"].")},
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.equip(\"Shovel\")")
    public static Object equip(OpContext c, Args a) throws InterruptedException {
        c.require(new Equip(a.alias("name")).run(c.gui), "equip");
        return null;
    }

    // ------------------------------------------------------------------ lift and place

    @ApiOp(name = "actions.lift", kind = ACTION,
            doc = "Walks to an object and lifts it (carry), then waits until the character holds it.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Liftable object (log, barrel, chest, cart...)."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the lift after arriving.", optional = true, def = "10")},
            returns = "Gob", returnsDoc = "The carried object.",
            errors = {Errors.GOB_GONE, Errors.PATH_NOT_FOUND, Errors.TIMEOUT},
            example = "barrel = nc.actions.lift(barrel)\nnc.actions.place([100, 200])")
    public static Object lift(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.gob("gob");
        Results r = new PathFinder(g).run(c.gui);
        if (r == null || !r.IsSuccess()) throw new ApiError(Errors.PATH_NOT_FOUND, "no path to gob " + g.id);
        c.ui.rcvr.rcvmsg(c.ui.getMenuGridId(), "act", "carry");
        c.gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 1, 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
        final long plid = c.gui.map.plgob;
        c.waitUntil(() -> {
            Following fl = g.getattr(Following.class);
            return fl != null && fl.tgt == plid;
        }, a.d("timeout", 10), "the object to be lifted");
        return Codec.gob(g);
    }

    @ApiOp(name = "actions.place", kind = ACTION,
            doc = "Walks next to a spot and puts the carried object down there at the given angle, then waits until it is placed.",
            params = {
                    @ApiParam(name = "pos", type = "Coord", doc = "World position for the object's centre."),
                    @ApiParam(name = "angle", type = "float", doc = "Rotation in radians.", optional = true, def = "0"),
                    @ApiParam(name = "gob", type = "Gob", doc = "The carried object; found automatically if omitted.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for placement after arriving.", optional = true, def = "10")},
            returns = "Gob", returnsDoc = "The placed object.",
            errors = {Errors.NOT_FOUND, Errors.GOB_GONE, Errors.PATH_NOT_FOUND, Errors.TIMEOUT},
            example = "nc.actions.place([1024.0, -88.0], angle=1.5708)")
    public static Object place(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.has("gob") ? a.gob("gob") : Finder.findLiftedbyPlayer();
        if (g == null) throw ApiError.notFound("not carrying anything");
        Coord2d pos = a.coord2d("pos");
        double ang = a.d("angle", 0);
        double timeout = a.d("timeout", 10);
        PathFinder pf = (g.ngob != null && g.ngob.hitBox != null)
                ? new PathFinder(NGob.getDummy(pos, ang, g.ngob.hitBox), true)
                : new PathFinder(pos);
        pf.isHardMode = true;
        Results r = pf.run(c.gui);
        if (r == null || !r.IsSuccess()) throw new ApiError(Errors.PATH_NOT_FOUND, "no path to " + pos);
        NUtils.activateGob(g);
        c.waitUntil(() -> c.gui.map.placing != null && c.gui.map.placing.ready(), timeout, "the placement ghost");
        c.gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(ang * 32768 / Math.PI), 1, 1);
        c.waitUntil(() -> g.getattr(Following.class) == null, timeout, "the object to be placed");
        return Codec.gob(g);
    }

    // ------------------------------------------------------------------ player upkeep

    @ApiOp(name = "actions.drink", kind = ACTION,
            doc = "Drinks from your waterskins or other vessels until stamina reaches `level` (0..1). Does nothing if stamina is already there.",
            params = {@ApiParam(name = "level", type = "float", doc = "Target stamina, 0..1.", optional = true, def = "0.9")},
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "if nc.player.stamina() < 0.4:\n    nc.actions.drink()")
    public static Object drink(OpContext c, Args a) throws InterruptedException {
        c.require(new Drink(a.d("level", 0.9), false).run(c.gui), "drink (no water?)");
        return null;
    }

    @ApiOp(name = "actions.restore_resources", kind = ACTION,
            doc = "Restores stamina and energy the way the bots do: drinks below 50% stamina (refilling waterskins at a water area if needed), "
                    + "eats from the eating area below 35% energy, then walks back. Runs until done, no timeout.",
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.restore_resources()")
    public static Object restoreResources(OpContext c, Args a) throws InterruptedException {
        c.require(new RestoreResources().run(c.gui), "restore_resources");
        return null;
    }

    @ApiOp(name = "actions.eat", kind = ACTION,
            doc = "Eats the listed foods until energy reaches `energy` (0..1): first from your inventory, "
                    + "then from containers in the area with the \"eat\" specialisation, walking there if needed.",
            params = {
                    @ApiParam(name = "items", type = "list[str]", doc = "Food names, in order of preference."),
                    @ApiParam(name = "energy", type = "float", doc = "Target energy, 0..1 (1 = 10000 energy points).", optional = true, def = "0.8")},
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.eat([\"Bread\", \"Apple\"])")
    public static Object eat(OpContext c, Args a) throws InterruptedException {
        ArrayList<String> items = new ArrayList<>(a.strs("items"));
        if (items.isEmpty()) throw ApiError.badArgs("items must not be empty");
        c.require(new FindAndEatItems(new NContext(c.gui), items, (int) Math.round(a.d("energy", 0.8) * 10000)).run(c.gui), "eat");
        return null;
    }

    @ApiOp(name = "actions.fill_waterskins", kind = ACTION,
            doc = "Refills your waterskins and other water containers at the nearest water area (the \"water\" specialisation). "
                    + "Runs until done, no timeout.",
            params = {@ApiParam(name = "global", type = "bool", doc = "Use global navigation to reach a far water area.", optional = true, def = "false")},
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.fill_waterskins(global=True)")
    public static Object fillWaterskins(OpContext c, Args a) throws InterruptedException {
        c.require(new FillWaterskins(a.b("global", false)).run(c.gui), "fill_waterskins");
        return null;
    }

    @ApiOp(name = "actions.travel_to_hearth", kind = ACTION,
            doc = "Travels to your hearth fire (the \"Travel home\" action) and waits until you arrive.",
            returns = "null", errors = {Errors.ACTION_FAILED},
            example = "nc.actions.travel_to_hearth()")
    public static Object travelToHearth(OpContext c, Args a) throws InterruptedException {
        c.require(new TravelToHearthFire().run(c.gui), "travel_to_hearth");
        return null;
    }

    // ------------------------------------------------------------------ piles

    @ApiOp(name = "actions.to_piles", kind = ACTION,
            doc = "Puts matching items from your inventory into stockpiles inside an area, filling existing piles first "
                    + "and starting new ones where there is room. Walks onto the area first. Runs until done, no timeout. "
                    + "Use an area that only holds piles of this item.",
            params = {
                    @ApiParam(name = "area", type = "Area", doc = "Area for the piles."),
                    @ApiParam(name = "items", type = "Alias", doc = "Which items to pile.")},
            returns = "null", errors = {Errors.NO_SUCH_AREA, Errors.PATH_NOT_FOUND, Errors.ACTION_FAILED},
            example = "nc.actions.to_piles(\"Stones\", \"Stone\")")
    public static Object toPiles(OpContext c, Args a) throws InterruptedException {
        NArea area = a.area("area");
        c.require(new TransferToPiles(loadedRect(c, area), a.alias("items")).run(c.gui), "to_piles");
        return null;
    }

    @ApiOp(name = "actions.collect_to_pile", kind = ACTION,
            doc = "Picks up matching items lying on the ground in one area and stockpiles them in another, "
                    + "emptying the inventory into piles whenever it fills. Runs until the source area is clear, no timeout.",
            params = {
                    @ApiParam(name = "source", type = "Area", doc = "Area to pick items up from."),
                    @ApiParam(name = "dest", type = "Area", doc = "Area for the piles."),
                    @ApiParam(name = "items", type = "Alias", doc = "Which ground items to collect.")},
            returns = "null", errors = {Errors.NO_SUCH_AREA, Errors.PATH_NOT_FOUND, Errors.ACTION_FAILED},
            example = "nc.actions.collect_to_pile(\"Chop site\", \"Blocks\", \"Block\")")
    public static Object collectToPile(OpContext c, Args a) throws InterruptedException {
        NArea src = a.area("source"), dst = a.area("dest");
        Pair<Coord2d, Coord2d> in = loadedRect(c, src);
        Pair<Coord2d, Coord2d> out = rectOf(dst);
        c.require(new CollectItemsToPile(in, out, a.alias("items")).run(c.gui), "collect_to_pile");
        return null;
    }

    @ApiOp(name = "actions.take_from_pile", kind = ACTION,
            doc = "Walks to a stockpile, opens it and takes up to `count` items into your inventory, then closes it. "
                    + "A pile that is emptied disappears from the world.",
            params = {
                    @ApiParam(name = "pile", type = "Gob", doc = "The stockpile."),
                    @ApiParam(name = "count", type = "int", doc = "Maximum number of items; as many as fit if omitted.", optional = true)},
            returns = "int", returnsDoc = "Number of items taken.",
            errors = {Errors.GOB_GONE, Errors.PATH_NOT_FOUND},
            example = "n = nc.actions.take_from_pile(pile, count=10)")
    public static Object takeFromPile(OpContext c, Args a) throws InterruptedException {
        Gob g = a.gob("pile");
        PathFinder pf = new PathFinder(g);
        pf.isHardMode = true;
        Results r = pf.run(c.gui);
        if (r == null || !r.IsSuccess()) throw new ApiError(Errors.PATH_NOT_FOUND, "no path to gob " + g.id);
        new OpenTargetContainer("Stockpile", g).run(c.gui);
        NISBox box = c.gui.getStockpile();
        if (box == null) return 0;
        TakeItemsFromPile t = new TakeItemsFromPile(g, box, a.has("count") ? a.i("count") : Integer.MAX_VALUE);
        t.run(c.gui);
        new CloseTargetContainer("Stockpile").run(c.gui);
        return t.getResult();
    }

    // ------------------------------------------------------------------ world work

    @ApiOp(name = "actions.light", kind = ACTION,
            doc = "Lights a fireplace, oven, kiln, smelter or other burnable workstation with whatever the client can use "
                    + "(a torch or candelabrum, or fire from branches), like the right-click \"Light\" menu. Runs until done, no timeout.",
            params = {@ApiParam(name = "gob", type = "Gob", doc = "Object to light.")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.ACTION_FAILED},
            example = "nc.actions.light(kiln)")
    public static Object light(OpContext c, Args a) throws InterruptedException {
        c.require(new LightObject(a.gob("gob")).run(c.gui), "light");
        return null;
    }

    @ApiOp(name = "actions.destroy", kind = ACTION,
            doc = "Destroys an object with the Destroy action (stumps, ruins, your own buildings) and waits until it is gone. "
                    + "Needs the right tool equipped (e.g. a shovel for stumps). Work stops when stamina runs out, so drink first for large objects. "
                    + "The cursor is reset afterwards, also on timeout.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Object to destroy."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for it to disappear.", optional = true, def = "300")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "nc.actions.equip(\"Shovel\")\nnc.actions.destroy(stump)")
    public static Object destroy(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.gob("gob");
        final long id = g.id;
        try {
            c.ui.rcvr.rcvmsg(c.ui.getMenuGridId(), "act", "destroy");
            c.waitUntil(() -> cursorIs(c, "mine"), 5, "the destroy cursor");
            c.gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 1, 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
            c.waitUntil(() -> c.gui.map.glob.oc.getgob(id) == null, a.d("timeout", 300), "gob " + id + " to be destroyed");
        } finally {
            resetCursor(c);
        }
        return null;
    }

    @ApiOp(name = "actions.mine", kind = ACTION,
            doc = "Walks next to a rock tile (or a cave wall) and mines it, then waits until the tile changes. "
                    + "Needs a pickaxe equipped. Mining stops when stamina runs out; drink and call again. "
                    + "The cursor is reset afterwards.",
            params = {
                    @ApiParam(name = "pos", type = "Coord", doc = "World position anywhere on the tile."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the tile to change.", optional = true, def = "60")},
            returns = "bool", returnsDoc = "True if the tile changed (was mined out), false on timeout.",
            errors = {Errors.PATH_NOT_FOUND},
            example = "while not nc.actions.mine([x, y]):\n    nc.actions.drink()")
    public static Object mine(OpContext c, Args a) throws InterruptedException {
        Coord2d pos = a.coord2d("pos");
        final Coord tc = pos.floor(MCache.tilesz);
        Coord2d centre = tc.mul(MCache.tilesz).add(MCache.tilesz.div(2));
        PathFinder pf = new PathFinder(NGob.getDummy(centre, 0, new NHitBox(new Coord2d(-5.5, -5.5), new Coord2d(5.5, 5.5))), true);
        pf.isHardMode = true;
        Results r = pf.run(c.gui);
        if (r == null || !r.IsSuccess()) throw new ApiError(Errors.PATH_NOT_FOUND, "no path to tile " + tc);
        final MCache map = c.gui.map.glob.map;
        final Resource before = tileRes(map, tc);
        try {
            NUtils.mine(centre);
            c.gui.map.wdgmsg("sel", tc, tc, 0);
            return c.waitFor(() -> {
                Resource now = tileRes(map, tc);
                return now != null && now != before;
            }, a.d("timeout", 60));
        } finally {
            resetCursor(c);
        }
    }

    @ApiOp(name = "actions.dig", kind = ACTION,
            doc = "Starts digging at the character's feet (soil, clay, sand). Needs a shovel equipped. "
                    + "Returns once the digging pose starts; wait for it to end with wait.pose_not(player, \"shoveldig\").",
            params = {@ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the digging pose.", optional = true, def = "5")},
            returns = "bool", returnsDoc = "True if digging started.",
            example = "if nc.actions.dig():\n    nc.wait.pose_not(nc.player(), \"shoveldig\", timeout=60)")
    public static Object dig(OpContext c, Args a) throws InterruptedException {
        NUtils.dig();
        return c.waitFor(() -> {
            Gob pl = c.gui.map.player();
            String p = pl == null ? null : pl.pose();
            return p != null && p.contains("dig");
        }, a.d("timeout", 5));
    }

    // ------------------------------------------------------------------ barrels and troughs

    @ApiOp(name = "actions.transfer_to_barrel", kind = ACTION,
            doc = "Walks to a barrel and pours matching items from your inventory into it (seeds, flour, water from buckets...). Runs until done, no timeout.",
            params = {
                    @ApiParam(name = "barrel", type = "Gob", doc = "The barrel."),
                    @ApiParam(name = "items", type = "Alias", doc = "Which items to put in.")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.ACTION_FAILED},
            example = "nc.actions.transfer_to_barrel(barrel, \"Wheat Seed\")")
    public static Object transferToBarrel(OpContext c, Args a) throws InterruptedException {
        c.require(new TransferToBarrel(a.gob("barrel"), a.alias("items")).run(c.gui), "transfer_to_barrel");
        return null;
    }

    @ApiOp(name = "actions.take_from_barrel", kind = ACTION,
            doc = "Walks to a barrel and takes its contents into your inventory, `count` items or as many as fit. Runs until done, no timeout.",
            params = {
                    @ApiParam(name = "barrel", type = "Gob", doc = "The barrel."),
                    @ApiParam(name = "items", type = "Alias", doc = "Expected contents, e.g. \"Wheat Seed\"."),
                    @ApiParam(name = "count", type = "int", doc = "How many; as many as fit if omitted.", optional = true)},
            returns = "null", errors = {Errors.GOB_GONE, Errors.ACTION_FAILED},
            example = "nc.actions.take_from_barrel(barrel, \"Flax Seed\", count=20)")
    public static Object takeFromBarrel(OpContext c, Args a) throws InterruptedException {
        Gob b = a.gob("barrel");
        NAlias items = a.alias("items");
        TakeFromBarrel t = a.has("count") ? new TakeFromBarrel(b, items, a.i("count")) : new TakeFromBarrel(b, items);
        c.require(t.run(c.gui), "take_from_barrel");
        return null;
    }

    @ApiOp(name = "actions.fill_troughs", kind = ACTION,
            doc = "Feeds matching items from your inventory into troughs, moving on to the next trough when one is full. Runs until done, no timeout.",
            params = {
                    @ApiParam(name = "troughs", type = "Gob|list[Gob]", doc = "Trough(s), filled in order."),
                    @ApiParam(name = "items", type = "Alias", doc = "Food items to put in.")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.ACTION_FAILED},
            example = "nc.actions.fill_troughs(nc.gobs.find(name=\"trough\"), [\"Carrot\", \"Beetroot\"])")
    public static Object fillTroughs(OpContext c, Args a) throws InterruptedException {
        List<Gob> troughs = new ArrayList<>();
        Object raw = a.raw().get("troughs");
        if (raw instanceof JSONArray) {
            for (Object o : (JSONArray) raw) troughs.add(gobOf(c, o));
        } else {
            troughs.add(a.gob("troughs"));
        }
        c.require(new TransferToTrough(troughs, a.alias("items")).run(c.gui), "fill_troughs");
        return null;
    }

    // ------------------------------------------------------------------ farming

    @ApiOp(name = "actions.harvest", kind = ACTION,
            doc = "Harvests every ripe plant of a crop in a field area, like the farmer bots: products go to their out areas, "
                    + "seeds to the seed area's barrels or containers, leftovers to troughs. Runs until the field is done, no timeout.",
            params = {
                    @ApiParam(name = "field", type = "Area", doc = "The field."),
                    @ApiParam(name = "seed", type = "Area", doc = "Area holding the seed barrels / containers."),
                    @ApiParam(name = "crop", type = "Alias", doc = "The plant, e.g. \"plants/carrot\" or \"carrot\"."),
                    @ApiParam(name = "trough", type = "Area", doc = "Area with troughs for leftovers.", optional = true)},
            returns = "null", errors = {Errors.NO_SUCH_AREA, Errors.ACTION_FAILED},
            example = "nc.actions.harvest(\"Carrot field\", \"Carrot seeds\", \"plants/carrot\")")
    public static Object harvest(OpContext c, Args a) throws InterruptedException {
        NArea trough = a.has("trough") ? a.area("trough") : null;
        c.require(new HarvestCrop(a.area("field"), a.area("seed"), trough, a.alias("crop")).run(c.gui), "harvest");
        return null;
    }

    @ApiOp(name = "actions.seed", kind = ACTION,
            doc = "Plants a crop on every free tile of a field area, taking seeds from the seed area. Runs until the field is planted, no timeout.",
            params = {
                    @ApiParam(name = "field", type = "Area", doc = "The field."),
                    @ApiParam(name = "seed", type = "Area", doc = "Area holding the seed barrels / containers."),
                    @ApiParam(name = "crop", type = "Alias", doc = "The plant, e.g. \"plants/carrot\".")},
            returns = "null", errors = {Errors.NO_SUCH_AREA, Errors.ACTION_FAILED},
            example = "nc.actions.seed(\"Carrot field\", \"Carrot seeds\", \"plants/carrot\")")
    public static Object seed(OpContext c, Args a) throws InterruptedException {
        c.require(new SeedCrop(a.area("field"), a.area("seed"), a.alias("crop")).run(c.gui), "seed");
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isGobArg(Args a, String n) {
        Object o = a.raw().opt(n);
        return o instanceof JSONObject && ((JSONObject) o).has("gob");
    }

    private static long widgetId(Args a, String n) {
        Object o = a.raw().get(n);
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof JSONObject && ((JSONObject) o).has("wdg")) return ((JSONObject) o).getLong("wdg");
        throw ApiError.badArgs("'" + n + "' must be a window, an inventory or a caption");
    }

    private static Gob gobOf(OpContext c, Object o) {
        long id;
        if (o instanceof Number) id = ((Number) o).longValue();
        else if (o instanceof JSONObject && ((JSONObject) o).has("gob")) id = ((JSONObject) o).getLong("gob");
        else throw ApiError.badArgs("expected a gob handle");
        Gob g = c.gui.map.glob.oc.getgob(id);
        if (g == null) throw new ApiError(Errors.GOB_GONE, "gob " + id + " no longer exists");
        return g;
    }

    static Gob player(OpContext c) {
        Gob pl = c.gui.map.player();
        if (pl == null) throw ApiError.notFound("the player is not loaded");
        return pl;
    }

    static boolean cursorIs(OpContext c, String name) {
        String cur = c.ui.root.cursorRes;
        return cur != null && cur.contains(name);
    }

    /** Back to the normal cursor with a right click on the player; sends only, never blocks. */
    private static void resetCursor(OpContext c) {
        Gob pl = c.gui.map.player();
        if (pl != null && !cursorIs(c, "arw"))
            c.gui.map.wdgmsg("click", Coord.z, pl.rc.floor(posres), 3, 0);
    }

    private static Resource tileRes(MCache map, Coord tc) {
        try {
            return map.tilesetr(map.gettile(tc));
        } catch (Loading l) {
            return null;
        }
    }

    private static Pair<Coord2d, Coord2d> rectOf(NArea area) {
        Pair<Coord2d, Coord2d> rc;
        try {
            rc = area.getRCArea();
        } catch (NullPointerException e) {
            rc = null;
        }
        if (rc == null) throw ApiError.failed("area '" + area.name + "' is not loaded; go there first (actions.go_to_area)");
        return rc;
    }

    /** Walks onto the area so its objects are loaded, then returns its rectangle. */
    private static Pair<Coord2d, Coord2d> loadedRect(OpContext c, NArea area) throws InterruptedException {
        if (!NUtils.navigateToArea(area, true))
            throw new ApiError(Errors.PATH_NOT_FOUND, "could not navigate to area '" + area.name + "'");
        return rectOf(area);
    }

    private static NInventory containerInventory(OpContext c, Window w) throws InterruptedException {
        NInventory inv = Args.findChild(w, NInventory.class);
        if (inv == null) {
            closeWindow(c, w, 5);
            throw ApiError.notSupported("'" + w.cap + "' has no item grid; use take_from_pile / to_piles / the barrel ops");
        }
        return inv;
    }

    /** The open window whose inventory belongs to gob, or null. */
    private static Window ownedWindow(OpContext c, Gob gob) {
        for (Widget w = c.gui.child; w != null; w = w.next) {
            if (!(w instanceof Window)) continue;
            NInventory inv = Args.findChild(w, NInventory.class);
            if (inv != null && inv.parentGob != null && inv.parentGob.id == gob.id) return (Window) w;
        }
        return null;
    }

    private static boolean hasContents(Widget w) {
        return Args.findChild(w, NInventory.class) != null || Args.findChild(w, NISBox.class) != null;
    }

    /**
     * Container pattern step 1+2: walk to the gob and open it, returning its window.
     * Follows OpenTargetContainer (binds the window to the gob, answers an "Open" flower menu)
     * but recognises the window as whatever new window appears, so it needs no caption table,
     * and gives up after timeout instead of waiting forever.
     */
    static Window openGob(OpContext c, Gob gob, double timeout) throws InterruptedException {
        Window own = ownedWindow(c, gob);
        if (own != null) return own;
        PathFinder pf = new PathFinder(gob);
        pf.isHardMode = true;
        Results r = pf.run(c.gui);
        if (r == null || !r.IsSuccess()) throw new ApiError(Errors.PATH_NOT_FOUND, "no path to gob " + gob.id);
        final Set<Widget> before = new HashSet<>();
        for (Widget w = c.gui.child; w != null; w = w.next) if (w instanceof Window) before.add(w);
        c.ui.core.setLastAction(gob);
        c.gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), 3, 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
        final Window[] got = {null};
        final Map<Widget, Long> seen = new HashMap<>();
        final boolean[] flowered = {false};
        c.waitUntil(() -> {
            if (!flowered[0]) {
                NFlowerMenu fm = RawOps.findFlower(c);
                if (fm != null) {
                    flowered[0] = true;
                    if (!fm.chooseOpt("Open")) return false;
                }
            }
            long now = System.currentTimeMillis();
            for (Widget w = c.gui.child; w != null; w = w.next) {
                if (!(w instanceof Window) || before.contains(w)) continue;
                Long first = seen.get(w);
                if (first == null) seen.put(w, first = now);
                if (hasContents(w) || now - first > 500) {
                    got[0] = (Window) w;
                    return true;
                }
            }
            return false;
        }, timeout, "a window from gob " + gob.id);
        return got[0];
    }

    static void closeWindow(OpContext c, final Window w, double timeout) throws InterruptedException {
        if (!c.gui.isWindowExist(w)) return;
        w.wdgmsg("close");
        c.waitUntil(() -> !c.gui.isWindowExist(w), timeout, "window '" + w.cap + "' to close");
    }

    /**
     * Shift-click transfer of matching items, one item kind at a time, through
     * SimpleTransferToContainer (which caps by free cells and waits for arrival).
     * Returns how many left the source.
     */
    private static int moveItems(OpContext c, NInventory from, NInventory to, NAlias alias, Double qmin, int count) throws InterruptedException {
        ArrayList<WItem> items = matching(from, alias, qmin);
        int before = items.size();
        int limit = count < 0 ? Integer.MAX_VALUE : count;
        Map<String, ArrayList<WItem>> groups = new LinkedHashMap<>();
        for (WItem w : items) {
            String n = ((NGItem) w.item).name();
            if (n == null) continue;
            ArrayList<WItem> g = groups.get(n);
            if (g == null) groups.put(n, g = new ArrayList<>());
            g.add(w);
        }
        int moved = 0;
        for (ArrayList<WItem> g : groups.values()) {
            if (moved >= limit) break;
            new SimpleTransferToContainer(to, g, Math.min(limit - moved, g.size())).run(c.gui);
            moved = before - matching(from, alias, qmin).size();
        }
        return Math.max(0, moved);
    }

    private static ArrayList<WItem> matching(NInventory inv, NAlias alias, Double qmin) throws InterruptedException {
        return qmin == null ? inv.getItems(alias) : inv.getItems(alias, qmin);
    }

    private ActionOps() {}
}
