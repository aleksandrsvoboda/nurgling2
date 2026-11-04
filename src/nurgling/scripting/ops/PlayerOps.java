package nurgling.scripting.ops;

import haven.*;
import nurgling.NGameUI;
import nurgling.scripting.api.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** The player character of the run's session: meters, buffs, speed, attributes. */
public final class PlayerOps {

    /** A meter's bar fraction (0..1), or null if the meter is not shown. */
    private static Object meter(NGameUI gui, String name, int idx) {
        IMeter.Meter m = gui.getmeter(name, idx);
        return m == null ? JSONObject.NULL : (Object) m.a;
    }

    private static JSONObject hp(NGameUI gui) {
        JSONObject j = new JSONObject();
        IMeter im = gui.getimeter("hp");
        j.put("current", (im == null || im.curHealth < 0) ? JSONObject.NULL : (Object) im.curHealth);
        j.put("max", (im == null || im.maxHealth < 0) ? JSONObject.NULL : (Object) im.maxHealth);
        j.put("fraction", meter(gui, "hp", 0));
        Object soft = meter(gui, "hp", 1);
        j.put("soft_fraction", soft == JSONObject.NULL ? j.get("fraction") : soft);
        return j;
    }

    @ApiOp(name = "player.get", kind = QUERY,
            doc = "The character's current state in one call: the player's gob snapshot fields plus `character` (name), "
                    + "`stamina` and `energy` (fractions 0..1, null if the meter is not shown), `hp` "
                    + "{current, max, fraction, soft_fraction}, `speed` {cur, max} (0 crawl .. 3 sprint), `pose`, `moving`, "
                    + "`cursor` (cursor resource name, e.g. \"gfx/hud/curs/arw\") and `hand` (the item held on the cursor, or null). "
                    + "`hp.current`/`hp.max` come from the meter's tooltip and only update when it is hovered; use `hp.fraction` for safety checks.",
            returns = "dict", errors = {Errors.NOT_FOUND},
            example = "me = nc.player.get()\nif me[\"stamina\"] < 0.3:\n    nc.actions.drink()")
    public static Object get(OpContext c, Args a) {
        Gob pl = c.gui.map.player();
        if (pl == null) throw ApiError.notFound("the player object is not loaded");
        JSONObject j = Codec.gob(pl);
        j.put("character", c.gui.chrid == null ? JSONObject.NULL : c.gui.chrid);
        j.put("stamina", meter(c.gui, "stam", 0));
        j.put("energy", meter(c.gui, "nrj", 0));
        j.put("hp", hp(c.gui));
        j.put("speed", speed(c.gui));
        j.put("moving", pl.getattr(Moving.class) != null);
        j.put("cursor", c.ui.root == null || c.ui.root.cursorRes == null ? JSONObject.NULL : c.ui.root.cursorRes);
        WItem hand = c.gui.vhand;
        j.put("hand", hand == null ? JSONObject.NULL : Codec.item(hand));
        return j;
    }

    private static Object speed(NGameUI gui) {
        Speedget sg = gui.speedget;
        if (sg == null) return JSONObject.NULL;
        return new JSONObject().put("cur", sg.cur).put("max", sg.max);
    }

    private static void collectMeters(Widget w, List<IMeter> res, int depth) {
        for (Widget ch = w.child; ch != null; ch = ch.next) {
            if (ch instanceof IMeter) res.add((IMeter) ch);
            else if (depth > 0) collectMeters(ch, res, depth - 1);
        }
    }

    @ApiOp(name = "player.meters", kind = QUERY,
            doc = "Every meter on the HUD by its resource base name (\"hp\", \"stam\", \"nrj\", and others the server shows, such as "
                    + "the happiness/insanity meters). Each value is the list of bar fractions (0..1) in the meter, "
                    + "e.g. hp is [hard, soft] while wounded. Meters whose resource has not loaded are left out.",
            returns = "dict", returnsDoc = "{name: [fraction, ...]}",
            example = "m = nc.player.meters()\nprint(m.get(\"stam\"), m.get(\"nrj\"))")
    public static Object meters(OpContext c, Args a) {
        List<IMeter> ms = new ArrayList<>();
        collectMeters(c.gui, ms, 2);
        JSONObject j = new JSONObject();
        for (IMeter im : ms) {
            Resource r;
            try {
                r = im.bg.get();
            } catch (Loading l) {
                continue;
            }
            JSONArray bars = new JSONArray();
            for (LayerMeter.Meter m : new ArrayList<>(im.meters)) bars.put(m.a);
            j.put(r.basename(), bars);
        }
        return j;
    }

    @ApiOp(name = "player.buffs", kind = QUERY,
            doc = "The buffs and debuffs shown in the buff bar: `res` (resource name), `name` (its tooltip name, null if none), "
                    + "and `meter` (the buff's progress meter in percent, -1 when it has none).",
            returns = "list[dict]", returnsDoc = "[{res, name, meter, wdg}]",
            example = "if any(\"drunk\" in b[\"res\"] for b in nc.player.buffs()):\n    nc.chat.notify(\"sober up first\")")
    public static Object buffs(OpContext c, Args a) {
        JSONArray res = new JSONArray();
        if (c.gui.buffs == null) return res;
        for (Widget w = c.gui.buffs.child; w != null; w = w.next) {
            if (!(w instanceof Buff)) continue;
            Buff b = (Buff) w;
            Resource r = (b.res == null) ? null : b.res.get();
            JSONObject j = new JSONObject();
            j.put("wdg", b.wdgid());
            j.put("res", r == null ? JSONObject.NULL : r.name);
            Resource.Tooltip tt = (r == null) ? null : r.layer(Resource.tooltip);
            j.put("name", tt == null ? JSONObject.NULL : tt.t);
            j.put("meter", b.ameter());
            res.put(j);
        }
        return res;
    }

    @ApiOp(name = "player.set_speed", kind = ACTION,
            doc = "Sets the movement speed: 0 crawl, 1 walk, 2 run, 3 sprint. Values above the allowed maximum are clamped by the server.",
            params = {@ApiParam(name = "speed", type = "int", doc = "Speed level 0..3.")},
            returns = "null", errors = {Errors.NOT_SUPPORTED},
            example = "nc.player.set_speed(2)")
    public static Object setSpeed(OpContext c, Args a) {
        int s = a.i("speed");
        if (s < 0 || s > 3) throw ApiError.badArgs("speed must be 0..3");
        if (c.gui.speedget == null) throw ApiError.notSupported("the speed selector is not available yet");
        c.gui.speedget.set(s);
        return null;
    }

    private static JSONObject attrs(Iterable<? extends CharWnd.AttrWdg> ws) {
        JSONObject j = new JSONObject();
        for (CharWnd.AttrWdg w : ws) {
            Glob.CAttr at = w.attr;
            j.put(w.nm, new JSONObject().put("base", at.base).put("comp", at.comp));
        }
        return j;
    }

    @ApiOp(name = "player.attributes", kind = QUERY,
            doc = "Base attributes and skills from the character sheet. `base` holds strength, agility, intelligence, constitution, perception, "
                    + "charisma, dexterity, will and psyche under their short names (str, agi, int, con, prc, csm, dex, wil, psy); "
                    + "`skills` holds unarmed, melee, ranged, explore, stealth, sewing, smithing, masonry, carpentry, cooking, farming, survive, lore. "
                    + "Each entry is {base, comp}: `base` the trained value, `comp` the value after equipment and buffs. "
                    + "The values arrive with the character sheet at login; before that the dicts are empty.",
            returns = "dict", returnsDoc = "{base: {name: {base, comp}}, skills: {name: {base, comp}}}",
            example = "print(nc.player.attributes()[\"base\"][\"str\"][\"comp\"])")
    public static Object attributes(OpContext c, Args a) {
        JSONObject j = new JSONObject();
        CharWnd cw = c.gui.chrwdg;
        j.put("base", (cw == null || cw.battr == null || cw.battr.attrs == null) ? new JSONObject() : attrs(cw.battr.attrs));
        j.put("skills", (cw == null || cw.sattr == null || cw.sattr.attrs == null) ? new JSONObject() : attrs(cw.sattr.attrs));
        return j;
    }

    private PlayerOps() {}
}
