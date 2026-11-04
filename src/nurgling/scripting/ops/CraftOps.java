package nurgling.scripting.ops;

import haven.*;
import nurgling.scripting.api.*;
import nurgling.widgets.NMakewindow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.*;

/**
 * Crafting through the game's own craft window: find a recipe in the menu, open it, press Craft.
 * Ingredients must already be in the inventory (or in open containers / the workstation the recipe needs).
 */
public final class CraftOps {

    /** A menu entry's description, or null while its resource is loading. */
    private static JSONObject entry(MenuGrid.Pagina p) {
        try {
            MenuGrid.PagButton b = p.button();
            if (b == null) return null;
            Resource.AButton act = b.act();
            JSONObject j = new JSONObject().put("name", b.name()).put("res", b.res.name);
            JSONArray ad = new JSONArray();
            if (act != null && act.ad != null) for (String s : act.ad) ad.put(s);
            j.put("act", ad);
            return j;
        } catch (Loading l) {
            return null;
        }
    }

    private static boolean isCraft(JSONObject e) {
        JSONArray ad = e.getJSONArray("act");
        return ad.length() > 0 && "craft".equals(ad.getString(0));
    }

    @ApiOp(name = "craft.list", kind = QUERY,
            doc = "Crafting recipes your character knows (the menu grid's craft entries), with their display name and resource. "
                    + "`filter` keeps entries whose name or resource contains the text (case-insensitive). "
                    + "With `all`, every menu entry is listed (actions, buildings...), not only crafts. "
                    + "Entries whose resources are still loading are skipped.",
            params = {
                    @ApiParam(name = "filter", type = "str", doc = "Substring of the name or resource.", optional = true),
                    @ApiParam(name = "all", type = "bool", doc = "List every menu entry, not only craft recipes.", optional = true, def = "false")},
            returns = "list[dict]", returnsDoc = "[{name, res, act}] where `act` is the entry's action path, e.g. [\"craft\", \"cheesetray\"].",
            example = "for r in nc.craft.list(filter=\"tray\"):\n    print(r[\"name\"], r[\"res\"])")
    public static Object list(OpContext c, Args a) {
        String f = a.str("filter", null);
        String fl = f == null ? null : f.toLowerCase();
        boolean all = a.b("all", false);
        JSONArray res = new JSONArray();
        List<MenuGrid.Pagina> pags;
        synchronized (c.gui.menu.paginae) {
            pags = new ArrayList<>(c.gui.menu.paginae);
        }
        for (MenuGrid.Pagina p : pags) {
            JSONObject e = entry(p);
            if (e == null) continue;
            if (!all && !isCraft(e)) continue;
            if (fl != null && !e.getString("name").toLowerCase().contains(fl) && !e.getString("res").toLowerCase().contains(fl))
                continue;
            res.put(e);
        }
        return res;
    }

    private static NMakewindow makeWindow(OpContext c) {
        return (c.gui.craftwnd == null) ? null : c.gui.craftwnd.makeWidget;
    }

    private static boolean specsLoaded(NMakewindow mw) {
        if (mw.inputs == null || mw.outputs == null || mw.inputs.isEmpty()) return false;
        for (NMakewindow.Spec s : mw.inputs) {
            if (s.ing != null && s.ing.isIgnored) continue;
            if (s.name == null) return false;
        }
        for (NMakewindow.Spec s : mw.outputs) if (s.name == null) return false;
        return true;
    }

    private static JSONArray specs(List<NMakewindow.Spec> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (NMakewindow.Spec s : list) {
            JSONObject j = new JSONObject();
            j.put("name", s.name == null ? JSONObject.NULL : s.name);
            Object r = Codec.toJson(s.res);
            j.put("res", r);
            j.put("count", s.count);
            j.put("chosen", (s.ing == null) ? JSONObject.NULL : s.ing.name);
            arr.put(j);
        }
        return arr;
    }

    private static JSONObject describe(NMakewindow mw) {
        JSONObject j = new JSONObject();
        j.put("wdg", mw.wdgid());
        j.put("recipe", mw.rcpnm == null ? JSONObject.NULL : mw.rcpnm);
        j.put("res", mw.recipeResource == null ? JSONObject.NULL : mw.recipeResource);
        j.put("inputs", specs(mw.inputs));
        j.put("outputs", specs(mw.outputs));
        JSONArray tools = new JSONArray();
        for (Indir<Resource> t : new ArrayList<>(mw.tools)) tools.put(Codec.toJson(t));
        j.put("tools", tools);
        j.put("loaded", specsLoaded(mw));
        return j;
    }

    @ApiOp(name = "craft.open", kind = ACTION,
            doc = "Opens the crafting window for a recipe, by its display name or resource name (see craft.list), "
                    + "and waits until the window shows that recipe with its ingredient names loaded.",
            params = {
                    @ApiParam(name = "name", type = "str", doc = "Recipe display name (\"Cheese Tray\") or resource (\"paginae/craft/cheesetray\")."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the window.", optional = true, def = "10")},
            returns = "dict", returnsDoc = "The window as craft.window describes it.",
            errors = {Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "w = nc.craft.open(\"Cheese Tray\")\nprint(w[\"inputs\"])")
    public static Object open(OpContext c, Args a) throws InterruptedException {
        String n = a.str("name");
        MenuGrid.PagButton found = null;
        List<MenuGrid.Pagina> pags;
        synchronized (c.gui.menu.paginae) {
            pags = new ArrayList<>(c.gui.menu.paginae);
        }
        for (MenuGrid.Pagina p : pags) {
            try {
                MenuGrid.PagButton b = p.button();
                if (b != null && (n.equals(b.name()) || n.equals(b.res.name))) {
                    found = b;
                    break;
                }
            } catch (Loading l) {
                // not loaded yet; skip
            }
        }
        if (found == null) throw ApiError.notFound("no recipe '" + n + "' (is it known and loaded?)");
        final String want = found.name();
        found.use(new MenuGrid.Interaction(1, 0));
        c.waitUntil(() -> {
            NMakewindow mw = makeWindow(c);
            return mw != null && want.equals(mw.rcpnm) && specsLoaded(mw);
        }, a.d("timeout", 10), "the craft window for '" + want + "'");
        return describe(makeWindow(c));
    }

    @ApiOp(name = "craft.window", kind = QUERY,
            doc = "Describes the open crafting window: the recipe, its inputs and outputs with counts, and the tools it needs. "
                    + "`chosen` is the ingredient picked for a generic input (e.g. which \"Raw Meat\"), if any. "
                    + "Returns null if no crafting window is open.",
            returns = "dict|null", returnsDoc = "{wdg, recipe, res, inputs: [{name, res, count, chosen}], outputs: [...], tools: [res], loaded}",
            example = "w = nc.craft.window()\nif w: print(w[\"recipe\"], [i[\"name\"] for i in w[\"inputs\"]])")
    public static Object window(OpContext c, Args a) {
        NMakewindow mw = makeWindow(c);
        return mw == null ? null : describe(mw);
    }

    /** True once no progress bar has been seen for quietMs. */
    private static java.util.function.BooleanSupplier doneCond(OpContext c, long quietMs) {
        final long[] last = {System.currentTimeMillis()};
        return () -> {
            long now = System.currentTimeMillis();
            if (c.gui.prog != null && c.gui.prog.prog > 0) {
                last[0] = now;
                return false;
            }
            return now - last[0] >= quietMs;
        };
    }

    @ApiOp(name = "craft.make", kind = ACTION,
            doc = "Presses Craft (or Craft All) in the open crafting window. "
                    + "Waits up to 5 seconds for the work to start (the hourglass progress bar); a game error such as missing ingredients raises ActionFailed. "
                    + "With `wait` (the default) it then waits until the progress bar has been gone for 1.5 s, so a Craft All run is finished, not just its first item.",
            params = {
                    @ApiParam(name = "all", type = "bool", doc = "Craft All instead of one.", optional = true, def = "false"),
                    @ApiParam(name = "wait", type = "bool", doc = "Wait for the crafting to finish.", optional = true, def = "true"),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for it to finish.", optional = true, def = "120")},
            returns = "bool", returnsDoc = "True if crafting started; false if nothing happened within 5 s (some recipes finish instantly without a progress bar).",
            errors = {Errors.NOT_FOUND, Errors.ACTION_FAILED, Errors.TIMEOUT},
            example = "nc.craft.open(\"Cheese Tray\")\nnc.craft.make(all=True)")
    public static Object make(OpContext c, Args a) throws InterruptedException {
        NMakewindow mw = makeWindow(c);
        if (mw == null) throw ApiError.notFound("no crafting window is open (craft.open first)");
        c.ui.dropLastError();
        final String[] err = {null};
        mw.wdgmsg("make", a.b("all", false) ? 1 : 0);
        boolean started = c.waitFor(() -> {
            String e = c.ui.getLastError();
            if (e != null) {
                err[0] = e;
                return true;
            }
            return c.gui.prog != null && c.gui.prog.prog > 0;
        }, 5);
        if (err[0] != null) throw ApiError.failed("craft: " + err[0]);
        if (!started) return false;
        if (a.b("wait", true))
            c.waitUntil(doneCond(c, 1500), a.d("timeout", 120), "crafting to finish");
        return true;
    }

    @ApiOp(name = "craft.wait_done", kind = WAIT,
            doc = "Waits until the hourglass progress bar has been gone for `quiet` seconds: the current craft (or any timed action) is over. "
                    + "Returns at once if nothing is in progress and has not been for `quiet` seconds.",
            params = {
                    @ApiParam(name = "quiet", type = "float", doc = "Seconds without a progress bar that count as done.", optional = true, def = "1.5"),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait.", optional = true, def = "120")},
            returns = "null", errors = {Errors.TIMEOUT},
            example = "nc.craft.make(all=True, wait=False)\nnc.craft.wait_done(timeout=600)")
    public static Object waitDone(OpContext c, Args a) throws InterruptedException {
        c.waitUntil(doneCond(c, (long) (a.d("quiet", 1.5) * 1000)), a.d("timeout", 120), "crafting to finish");
        return null;
    }

    @ApiOp(name = "craft.close", kind = ACTION,
            doc = "Closes the crafting window if it is open.",
            returns = "null",
            example = "nc.craft.close()")
    public static Object close(OpContext c, Args a) throws InterruptedException {
        final Window w = c.gui.craftwnd;
        if (w == null || !w.visible()) return null;
        w.wdgmsg("close");
        c.waitFor(() -> !w.visible() || c.gui.craftwnd != w, 5);
        return null;
    }

    private CraftOps() {}
}
