package nurgling.scripting.ops;

import haven.*;
import nurgling.NFlowerMenu;
import nurgling.NGItem;
import nurgling.NInventory;
import nurgling.scripting.api.*;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.WAIT;

/**
 * Named wait conditions. Each blocks the script's bot thread until its condition holds, checking
 * once per client tick, and raises Timeout after {@code timeout} seconds (0 waits forever).
 * Conditions run on the UI thread, so they only read widget and gob fields.
 */
public final class WaitOps {

    private static final String TIMEOUT_DOC = "Seconds to wait; 0 waits forever.";

    // ------------------------------------------------------------------ time

    @ApiOp(name = "wait.seconds", kind = WAIT,
            doc = "Sleeps for a number of seconds of wall-clock time. Unlike Python's time.sleep it ends at once when the script is stopped.",
            params = {@ApiParam(name = "seconds", type = "float", doc = "How long; 0 or less returns at once.")},
            returns = "null",
            example = "nc.wait.seconds(2.5)")
    public static Object seconds(OpContext c, Args a) throws InterruptedException {
        double s = a.d("seconds");
        if (s > 0) c.waitFor(() -> false, s);
        return null;
    }

    @ApiOp(name = "wait.ticks", kind = WAIT,
            doc = "Waits for a number of client ticks (frames of the game logic, roughly the frame rate).",
            params = {
                    @ApiParam(name = "count", type = "int", doc = "Number of ticks."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "30")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.ticks(5)")
    public static Object ticks(OpContext c, Args a) throws InterruptedException {
        final int n = a.i("count");
        if (n <= 0) return null;
        final int[] k = {0};
        c.waitUntil(() -> ++k[0] >= n, a.d("timeout", 30), n + " ticks");
        return null;
    }

    // ------------------------------------------------------------------ game objects

    private static boolean poseMatches(Gob g, List<String> poses) {
        String p = g.pose();
        if (p == null) return false;
        for (String s : poses) if (p.contains(s)) return true;
        return false;
    }

    /** The pose resource name as gob snapshots report it (see Codec.poses). */
    private static Object cleanPose(Gob g) {
        org.json.JSONArray p = Codec.poses(g.pose());
        return p.isEmpty() ? null : p.get(0);
    }

    private static Gob gob(OpContext c, long id) {
        return c.gui.map.glob.oc.getgob(id);
    }

    @ApiOp(name = "wait.pose", kind = WAIT,
            doc = "Waits until a game object's (usually the player's) animation pose contains one of the given texts, "
                    + "e.g. \"chop\", \"shoveldig\", \"gfx/borka/idle\".",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Whose pose; usually nc.player()."),
                    @ApiParam(name = "pose", type = "str|list[str]", doc = "Substring(s) of the pose resource."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "str", returnsDoc = "The pose that matched.",
            errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "nc.actions.flower(tree, \"Chop\")\nnc.wait.pose(nc.player(), \"chop\")")
    public static Object pose(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.gob("gob");
        final List<String> poses = a.strs("pose");
        if (poses.isEmpty()) throw ApiError.badArgs("pose must not be empty");
        c.waitUntil(() -> poseMatches(g, poses), a.d("timeout", 10), "pose " + poses);
        return cleanPose(g);
    }

    @ApiOp(name = "wait.pose_not", kind = WAIT,
            doc = "Waits until a game object's pose no longer contains any of the given texts: the work it shows has ended "
                    + "(chopping stopped because the tree fell, or stamina ran out). Also ends if the object disappears.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Whose pose; usually nc.player()."),
                    @ApiParam(name = "pose", type = "str|list[str]", doc = "Substring(s) of the pose resource."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "60")},
            returns = "str|null", returnsDoc = "The pose afterwards, or null if the object is gone.",
            errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "nc.wait.pose_not(nc.player(), \"chop\", timeout=120)")
    public static Object poseNot(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.gob("gob");
        final long id = g.id;
        final List<String> poses = a.strs("pose");
        if (poses.isEmpty()) throw ApiError.badArgs("pose must not be empty");
        c.waitUntil(() -> gob(c, id) == null || !poseMatches(g, poses), a.d("timeout", 60), "pose other than " + poses);
        return gob(c, id) == null ? null : cleanPose(g);
    }

    @ApiOp(name = "wait.gob_gone", kind = WAIT,
            doc = "Waits until a game object no longer exists (destroyed, picked up, felled, or out of view). Returns at once if it is already gone.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "The object."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "30")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.gob_gone(tree, timeout=120)")
    public static Object gobGone(OpContext c, Args a) throws InterruptedException {
        final long id = a.gobId("gob");
        c.waitUntil(() -> gob(c, id) == null, a.d("timeout", 30), "gob " + id + " to disappear");
        return null;
    }

    private static Gob nearest(OpContext c, NAlias name, double radius) {
        Gob pl = c.gui.map.player();
        if (pl == null) return null;
        Gob best = null;
        double bd = Double.MAX_VALUE;
        OCache oc = c.gui.map.glob.oc;
        synchronized (oc) {
            for (Gob g : oc) {
                if (g instanceof OCache.Virtual || g.ngob == null || g.ngob.name == null) continue;
                if (!NParser.checkName(g.ngob.name, name)) continue;
                double d = g.rc.dist(pl.rc);
                if (d <= radius && d < bd) {
                    bd = d;
                    best = g;
                }
            }
        }
        return best;
    }

    @ApiOp(name = "wait.gob_appears", kind = WAIT,
            doc = "Waits until a game object whose resource name matches `name` exists within `radius` of the player, and returns the nearest one. "
                    + "Returns at once if one is already there.",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Resource name match, e.g. \"gfx/terobjs/items/\" or \"stockpile\"."),
                    @ApiParam(name = "radius", type = "float", doc = "Distance from the player in world units (a tile is 11).", optional = true, def = "100"),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "Gob", returnsDoc = "The nearest matching object.",
            errors = {Errors.TIMEOUT},
            example = "log = nc.wait.gob_appears(\"gfx/terobjs/trees/oaklog\", radius=50)")
    public static Object gobAppears(OpContext c, Args a) throws InterruptedException {
        final NAlias name = a.alias("name");
        final double radius = a.d("radius", 100);
        final Gob[] got = {null};
        c.waitUntil(() -> (got[0] = nearest(c, name, radius)) != null, a.d("timeout", 10), "a gob matching " + name.keys);
        return Codec.gob(got[0]);
    }

    @ApiOp(name = "wait.gob_attr", kind = WAIT,
            doc = "Waits until a game object's model attribute (the `attr` field of its snapshot: fill level, stage, lit state...) "
                    + "equals `value`, or, without `value`, until it changes from what it is now.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "The object."),
                    @ApiParam(name = "value", type = "int", doc = "Wanted value; omit to wait for any change.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "30")},
            returns = "int", returnsDoc = "The attribute afterwards.",
            errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "nc.wait.gob_attr(crucible, value=0, timeout=600)")
    public static Object gobAttr(OpContext c, Args a) throws InterruptedException {
        final Gob g = a.gob("gob");
        if (g.ngob == null) throw ApiError.notFound("gob " + g.id + " has no model attribute");
        final long start = g.ngob.getModelAttribute();
        final boolean hasValue = a.has("value");
        final long want = hasValue ? a.l("value") : 0;
        c.waitUntil(() -> {
            long v = g.ngob.getModelAttribute();
            return hasValue ? v == want : v != start;
        }, a.d("timeout", 30), "model attribute of gob " + g.id);
        return g.ngob.getModelAttribute();
    }

    @ApiOp(name = "wait.near", kind = WAIT,
            doc = "Waits until the player is within `dist` of a position or game object (e.g. after a walk started with raw.click_ground).",
            params = {
                    @ApiParam(name = "pos", type = "Coord|Gob", doc = "Position or object."),
                    @ApiParam(name = "dist", type = "float", doc = "Distance in world units.", optional = true, def = "2"),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "30")},
            returns = "null", errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "nc.raw.click_ground([x, y])\nnc.wait.near([x, y])")
    public static Object near(OpContext c, Args a) throws InterruptedException {
        final Coord2d p = a.coord2d("pos");
        final double d = a.d("dist", 2);
        c.waitUntil(() -> {
            Gob pl = c.gui.map.player();
            return pl != null && pl.rc.dist(p) <= d;
        }, a.d("timeout", 30), "the player to reach " + p);
        return null;
    }

    // ------------------------------------------------------------------ player state

    private static boolean walking(Gob pl) {
        String p = pl.pose();
        return p != null && NParser.checkName(p, "borka/walking", "borka/running", "borka/wading");
    }

    private static boolean progress(OpContext c) {
        return c.gui.prog != null && c.gui.prog.prog > 0;
    }

    @ApiOp(name = "wait.not_moving", kind = WAIT,
            doc = "Waits until the player has stopped moving (no movement and no walking pose).",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "30")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.not_moving()")
    public static Object notMoving(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> {
            Gob pl = c.gui.map.player();
            return pl != null && pl.getattr(Moving.class) == null && !walking(pl);
        }, a.d("timeout", 30), "the player to stop moving");
        return null;
    }

    @ApiOp(name = "wait.player_idle", kind = WAIT,
            doc = "Waits until the player is idle: not moving, no hourglass progress bar, and the pose is one of the idle poses "
                    + "(standing, or standing while carrying something).",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "60")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.actions.flower(bush, \"Pick\")\nnc.wait.player_idle()")
    public static Object playerIdle(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> {
            Gob pl = c.gui.map.player();
            if (pl == null || pl.getattr(Moving.class) != null || progress(c)) return false;
            String p = pl.pose();
            return p != null && (p.contains("idle") || p.contains("banzai"));
        }, a.d("timeout", 60), "the player to be idle");
        return null;
    }

    private static double meter(OpContext c, String name) {
        IMeter.Meter m = c.gui.getmeter(name, 0);
        return m == null ? -1 : m.a;
    }

    @ApiOp(name = "wait.stamina", kind = WAIT,
            doc = "Waits until stamina is at least `at_least` (0..1), e.g. while resting.",
            params = {
                    @ApiParam(name = "at_least", type = "float", doc = "Stamina fraction 0..1."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "60")},
            returns = "float", returnsDoc = "Stamina afterwards.",
            errors = {Errors.TIMEOUT},
            example = "nc.wait.stamina(0.9, timeout=300)")
    public static Object stamina(OpContext c, Args a) throws InterruptedException {
        final double lvl = a.d("at_least");
        c.waitUntil(() -> meter(c, "stam") >= lvl, a.d("timeout", 60), "stamina " + lvl);
        return meter(c, "stam");
    }

    @ApiOp(name = "wait.energy", kind = WAIT,
            doc = "Waits until energy is at least `at_least` (0..1; 1 = 10000 energy points).",
            params = {
                    @ApiParam(name = "at_least", type = "float", doc = "Energy fraction 0..1."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "60")},
            returns = "float", returnsDoc = "Energy afterwards.",
            errors = {Errors.TIMEOUT},
            example = "nc.wait.energy(0.5)")
    public static Object energy(OpContext c, Args a) throws InterruptedException {
        final double lvl = a.d("at_least");
        c.waitUntil(() -> meter(c, "nrj") >= lvl, a.d("timeout", 60), "energy " + lvl);
        return meter(c, "nrj");
    }

    @ApiOp(name = "wait.progress", kind = WAIT,
            doc = "Waits until the hourglass progress bar appears: a timed action (crafting, building, butchering) has started.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.progress()")
    public static Object progressStart(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> progress(c), a.d("timeout", 5), "a progress bar");
        return null;
    }

    @ApiOp(name = "wait.progress_done", kind = WAIT,
            doc = "Waits until no hourglass progress bar is shown: the timed action finished or was interrupted. "
                    + "Returns at once if none is shown. For Craft All, which shows a new bar per item, prefer craft.wait_done.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "120")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.progress()\nnc.wait.progress_done()")
    public static Object progressDone(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> !progress(c), a.d("timeout", 120), "the progress bar to finish");
        return null;
    }

    @ApiOp(name = "wait.plob_ready", kind = WAIT,
            doc = "Waits until a placement ghost (a building blueprint or a carried object being placed) is loaded and ready to place with raw.place.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.raw.pagina_use(\"Palisade\")\nnc.wait.plob_ready()\nnc.raw.place([x, y])")
    public static Object plobReady(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> c.gui.map.placing != null && c.gui.map.placing.ready(), a.d("timeout", 10), "the placement ghost");
        return null;
    }

    @ApiOp(name = "wait.cursor", kind = WAIT,
            doc = "Waits until the mouse cursor's resource name contains `name`, e.g. \"mine\", \"dig\", \"arw\" (normal).",
            params = {
                    @ApiParam(name = "name", type = "str", doc = "Substring of the cursor resource."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "str", returnsDoc = "The cursor resource name.",
            errors = {Errors.TIMEOUT},
            example = "nc.raw.menu_act([\"dig\"])\nnc.wait.cursor(\"dig\")")
    public static Object cursor(OpContext c, Args a) throws InterruptedException {
        final String n = a.str("name");
        c.waitUntil(() -> ActionOps.cursorIs(c, n), a.d("timeout", 5), "cursor " + n);
        return c.ui.root.cursorRes;
    }

    // ------------------------------------------------------------------ hand and items

    @ApiOp(name = "wait.item_in_hand", kind = WAIT,
            doc = "Waits until an item is on the cursor (the hand), optionally one whose name matches.",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Name match; any item if omitted.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "Item", returnsDoc = "The item in hand.",
            errors = {Errors.TIMEOUT},
            example = "nc.raw.item_take(bucket)\nnc.wait.item_in_hand(\"Bucket\")")
    public static Object itemInHand(OpContext c, Args a) throws InterruptedException {
        final NAlias name = a.alias("name", null);
        c.waitUntil(() -> {
            WItem h = c.gui.vhand;
            if (h == null) return false;
            if (name == null) return true;
            String n = (h.item instanceof NGItem) ? ((NGItem) h.item).name() : null;
            return n != null && NParser.checkName(n, name);
        }, a.d("timeout", 5), "an item in hand");
        return Codec.item(c.gui.vhand);
    }

    @ApiOp(name = "wait.hand_free", kind = WAIT,
            doc = "Waits until nothing is on the cursor (the hand is empty). Returns at once if it already is.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.actions.use_on_gob(trough, item=carrot)\nnc.wait.hand_free()")
    public static Object handFree(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> c.gui.vhand == null, a.d("timeout", 5), "the hand to empty");
        return null;
    }

    /** Matching items in an inventory, counting stacks by their members; -1 while names are loading. */
    private static int count(Widget inv, NAlias name) {
        int n = 0;
        for (Widget w = inv.child; w != null; w = w.next) {
            if (!(w instanceof WItem)) continue;
            WItem wi = (WItem) w;
            String nm = (wi.item instanceof NGItem) ? ((NGItem) wi.item).name() : null;
            if (nm == null) return -1;
            if (wi.item.contents != null) {
                if (NParser.checkName(nm, name)) {
                    int k = count(wi.item.contents, name);
                    if (k < 0) return -1;
                    n += k;
                }
            } else if (NParser.checkName(nm, name)) {
                n++;
            }
        }
        return n;
    }

    @ApiOp(name = "wait.items", kind = WAIT,
            doc = "Waits until an inventory holds at least `count` items matching `name` (stacked items count one by one).",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Which items."),
                    @ApiParam(name = "count", type = "int", doc = "Minimum number.", optional = true, def = "1"),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Inventory; your own if omitted.", optional = true, def = "\"main\""),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "int", returnsDoc = "The number of matching items.",
            errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE, Errors.TIMEOUT},
            example = "nc.wait.items(\"Block\", count=5)")
    public static Object items(OpContext c, Args a) throws InterruptedException {
        final NInventory inv = a.inventory("inventory");
        final NAlias name = a.alias("name");
        final int n = a.i("count", 1);
        final int[] got = {0};
        c.waitUntil(() -> (got[0] = count(inv, name)) >= n, a.d("timeout", 10), n + " items matching " + name.keys);
        return got[0];
    }

    @ApiOp(name = "wait.items_at_most", kind = WAIT,
            doc = "Waits until an inventory holds at most `count` items matching `name` (e.g. until they are used up or moved away).",
            params = {
                    @ApiParam(name = "name", type = "Alias", doc = "Which items."),
                    @ApiParam(name = "count", type = "int", doc = "Maximum number.", optional = true, def = "0"),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Inventory; your own if omitted.", optional = true, def = "\"main\""),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "int", returnsDoc = "The number of matching items.",
            errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE, Errors.TIMEOUT},
            example = "nc.wait.items_at_most(\"Seed\", count=0, timeout=120)")
    public static Object itemsAtMost(OpContext c, Args a) throws InterruptedException {
        final NInventory inv = a.inventory("inventory");
        final NAlias name = a.alias("name");
        final int n = a.i("count", 0);
        final int[] got = {0};
        c.waitUntil(() -> {
            got[0] = count(inv, name);
            return got[0] >= 0 && got[0] <= n;
        }, a.d("timeout", 10), "at most " + n + " items matching " + name.keys);
        return got[0];
    }

    @ApiOp(name = "wait.inventory_free", kind = WAIT,
            doc = "Waits until an inventory has at least `cells` free cells.",
            params = {
                    @ApiParam(name = "cells", type = "int", doc = "Free cells needed.", optional = true, def = "1"),
                    @ApiParam(name = "inventory", type = "Inventory|str", doc = "Inventory; your own if omitted.", optional = true, def = "\"main\""),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "int", returnsDoc = "Free cells afterwards.",
            errors = {Errors.NOT_FOUND, Errors.WIDGET_GONE, Errors.TIMEOUT},
            example = "nc.wait.inventory_free(cells=4)")
    public static Object inventoryFree(OpContext c, Args a) throws InterruptedException {
        final NInventory inv = a.inventory("inventory");
        final int n = a.i("cells", 1);
        final int[] got = {0};
        c.waitUntil(() -> (got[0] = inv.calcFreeSpace()) >= n, a.d("timeout", 10), n + " free cells");
        return got[0];
    }

    // ------------------------------------------------------------------ windows and menus

    private static boolean captionMatches(String cap, String want) {
        if (cap == null) return false;
        if (want.endsWith("*")) return cap.startsWith(want.substring(0, want.length() - 1));
        return cap.equals(want);
    }

    @ApiOp(name = "wait.window", kind = WAIT,
            doc = "Waits until a window with the given caption is open. A caption ending in `*` matches a prefix (\"Cupboard*\").",
            params = {
                    @ApiParam(name = "caption", type = "str", doc = "Window caption, exact or with a trailing `*`."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "Window", returnsDoc = "The window.",
            errors = {Errors.TIMEOUT},
            example = "wnd = nc.wait.window(\"Barter Stand\")")
    public static Object window(OpContext c, Args a) throws InterruptedException {
        final String want = a.str("caption");
        final Window[] got = {null};
        c.waitUntil(() -> {
            for (Widget w = c.gui.child; w != null; w = w.next) {
                if (w instanceof Window && captionMatches(((Window) w).cap, want)) {
                    got[0] = (Window) w;
                    return true;
                }
            }
            return false;
        }, a.d("timeout", 10), "window '" + want + "'");
        return Codec.widget(got[0]);
    }

    @ApiOp(name = "wait.window_closed", kind = WAIT,
            doc = "Waits until a window is gone. Pass a handle for one particular window, or a caption to wait until no window with it is open.",
            params = {
                    @ApiParam(name = "window", type = "Window|Inventory|str", doc = "Window handle, an inventory in it, or a caption."),
                    @ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "10")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.wait.window_closed(\"Crafting\")")
    public static Object windowClosed(OpContext c, Args a) throws InterruptedException {
        Object raw = a.raw().get("window");
        double t = a.d("timeout", 10);
        if (raw instanceof String) {
            final String want = (String) raw;
            c.waitUntil(() -> {
                for (Widget w = c.gui.child; w != null; w = w.next)
                    if (w instanceof Window && captionMatches(((Window) w).cap, want)) return false;
                return true;
            }, t, "window '" + want + "' to close");
            return null;
        }
        int id;
        if (raw instanceof Number) id = ((Number) raw).intValue();
        else if (raw instanceof JSONObject && ((JSONObject) raw).has("wdg")) id = ((JSONObject) raw).getInt("wdg");
        else throw ApiError.badArgs("'window' must be a window, an inventory or a caption");
        Widget w = c.ui.getwidget(id);
        if (w == null) return null;
        final Window wnd = (w instanceof Window) ? (Window) w : w.getparent(Window.class);
        if (wnd == null) throw ApiError.badArgs("'window' is not inside a window");
        c.waitUntil(() -> !c.gui.isWindowExist(wnd), t, "window '" + wnd.cap + "' to close");
        return null;
    }

    @ApiOp(name = "wait.flower_menu", kind = WAIT,
            doc = "Waits until a flower (right-click) menu is open and returns its options. Choose one with raw.flower_choose.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "list[str]", returnsDoc = "The menu's options.",
            errors = {Errors.TIMEOUT},
            example = "nc.raw.click_gob(gob)\nopts = nc.wait.flower_menu()")
    public static Object flowerMenu(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> RawOps.findFlower(c) != null, a.d("timeout", 5), "a flower menu");
        NFlowerMenu fm = RawOps.findFlower(c);
        JSONArray res = new JSONArray();
        if (fm != null && fm.nopts != null) for (NFlowerMenu.NPetal p : fm.nopts) res.put(p.name);
        return res;
    }

    @ApiOp(name = "wait.flower_menu_closed", kind = WAIT,
            doc = "Waits until no flower menu is open. Returns at once if none is.",
            params = {@ApiParam(name = "timeout", type = "float", doc = TIMEOUT_DOC, optional = true, def = "5")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.raw.flower_choose(\"Pick\")\nnc.wait.flower_menu_closed()")
    public static Object flowerMenuClosed(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(() -> RawOps.findFlower(c) == null, a.d("timeout", 5), "the flower menu to close");
        return null;
    }

    private WaitOps() {}
}
