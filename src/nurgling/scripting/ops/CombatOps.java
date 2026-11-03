package nurgling.scripting.ops;

import haven.*;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.conf.NCombatData;
import nurgling.scripting.api.*;
import org.json.JSONArray;
import org.json.JSONObject;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/**
 * Combat: the fight state as the client shows it (relations, openings, initiative, cooldowns, the
 * action bar) and the player's combat actions. Reacting to the opponent is event-driven: subscribe
 * to "combat.used" and read combat.state when it fires.
 */
public final class CombatOps {
    private static final String[] OPENINGS = {"offbalance", "reeling", "cornered", "dizzy"};

    // ---- snapshot (built on the UI thread) ----

    private static String resName(Indir<Resource> r) {
        if (r == null) return null;
        if (r instanceof Session.CachedRes.Ref) {
            String nm = ((Session.CachedRes.Ref) r).resnm();
            if (nm != null) return nm;
        }
        try {
            return r.get().name;
        } catch (Loading l) {
            return null;
        }
    }

    /** Openings (0-100 by colour) and the other buffs of one bufflist. */
    private static void buffs(Bufflist bl, JSONObject into) {
        JSONObject op = new JSONObject();
        for (String o : OPENINGS) op.put(o, 0);
        JSONArray other = new JSONArray();
        String stance = null;
        for (Buff b : bl.children(Buff.class)) {
            String nm = resName(b.res);
            if (nm == null) continue;
            int idx = NCombatData.openingIndex(nm);
            if (idx >= 0) {
                Double m;
                try {
                    m = b.ameteri.get();
                } catch (Loading l) {
                    m = null;
                }
                op.put(OPENINGS[idx], m == null ? 0 : (int) Math.round(100 * m));
            } else if (NCombatData.MANEUVERS.contains(nm)) {
                stance = nm;
            } else {
                other.put(nm);
            }
        }
        into.put("openings", op);
        into.put("stance", stance == null ? JSONObject.NULL : stance);
        into.put("buffs", other);
    }

    private static NFightsess fightsess(NGameUI gui) {
        NFightsess fs = gui.fsess;
        return (fs != null && fs.parent != null) ? fs : null;
    }

    private static boolean inFight(NGameUI gui) {
        return gui.fv != null && !gui.fv.lsrel.isEmpty();
    }

    static JSONObject snapshot(NGameUI gui) {
        Fightview fv = gui.fv;
        if (fv == null || fv.lsrel.isEmpty()) return null;
        double now = Utils.rtime();
        JSONObject j = new JSONObject();
        j.put("target", fv.current == null ? JSONObject.NULL : (Object) fv.current.gobid);
        JSONArray opp = new JSONArray();
        Gob pl = gui.map == null ? null : gui.map.player();
        for (Fightview.Relation rel : fv.lsrel) {
            JSONObject r = new JSONObject();
            r.put("gob", rel.gobid);
            Gob g = gui.map == null ? null : gui.map.glob.oc.getgob(rel.gobid);
            r.put("name", g == null || g.ngob == null || g.ngob.name == null ? JSONObject.NULL : g.ngob.name);
            r.put("pose", g == null || Codec.poses(g.pose()).isEmpty() ? JSONObject.NULL : Codec.poses(g.pose()).get(0));
            r.put("dist", g == null || pl == null ? JSONObject.NULL : (Object) pl.rc.dist(g.rc));
            r.put("current", rel == fv.current);
            r.put("ip", rel.ip);
            r.put("oip", rel.oip);
            r.put("give", rel.gst);
            String last = resName(rel.lastact);
            r.put("last_action", last == null ? JSONObject.NULL : last);
            r.put("last_action_age", rel.lastact == null ? JSONObject.NULL : (Object) (now - rel.lastuse));
            buffs(rel.buffs, r);
            opp.put(r);
        }
        j.put("opponents", opp);
        JSONObject me = new JSONObject();
        buffs(fv.buffs, me);
        String last = resName(fv.lastact);
        me.put("last_action", last == null ? JSONObject.NULL : last);
        j.put("me", me);
        j.put("cooldown", Math.max(0, fv.atkct - now));
        j.put("cooldown_total", Math.max(0, fv.atkct - fv.atkcs));
        JSONArray moves = new JSONArray();
        NFightsess fs = fightsess(gui);
        if (fs != null && fs.actions != null) {
            for (int i = 0; i < fs.actions.length; i++) {
                Fightsess.Action act = fs.actions[i];
                if (act == null) continue;
                String nm = resName(act.res);
                double cd = Math.max(0, act.ct - now);
                moves.put(new JSONObject().put("slot", i).put("name", nm == null ? JSONObject.NULL : nm)
                        .put("cooldown", cd).put("ready", cd <= 0));
            }
        }
        j.put("moves", moves);
        return j;
    }

    /** Builds the snapshot on the UI thread, where the fight widgets are updated, so lists are never read mid-change. */
    private static JSONObject snapshotOnUi(OpContext c) throws InterruptedException {
        JSONObject[] res = new JSONObject[1];
        c.waitUntil(() -> {
            res[0] = snapshot(c.gui);
            return true;
        }, 10, "the combat state");
        return res[0];
    }

    private static Fightview.Relation relation(NGameUI gui, long id) {
        if (gui.fv == null) return null;
        for (Fightview.Relation rel : gui.fv.lsrel) {
            if (rel.gobid == id) return rel;
        }
        return null;
    }

    // ---- ops ----

    @ApiOp(name = "combat.state", kind = QUERY,
            doc = "The current fight as the client sees it, or null when not fighting. "
                    + "Openings are 0-100 per colour (`offbalance` green, `reeling` yellow, `cornered` red, `dizzy` blue). "
                    + "`opponents[].openings` are the openings you have made on that opponent; `me.openings` are theirs on you. "
                    + "`ip`/`oip` are your and their initiative points against that opponent; `give` is the peace-flag state (1 = you offered peace). "
                    + "`cooldown` is the seconds left before you can act again; `moves` is the action bar (slot 0 = key 1, slot 5 = Shift+1). "
                    + "Resource names may be null for a moment while they load.",
            returns = "dict|null",
            returnsDoc = "{target, opponents: [{gob, name, pose, dist, current, ip, oip, give, last_action, last_action_age, openings, stance, buffs}], "
                    + "me: {openings, stance, buffs, last_action}, cooldown, cooldown_total, moves: [{slot, name, cooldown, ready}]}",
            example = "st = nc.combat.state()\nif st and st[\"cooldown\"] == 0:\n    tgt = next(o for o in st[\"opponents\"] if o[\"current\"])\n    print(tgt[\"name\"], tgt[\"openings\"][\"cornered\"])")
    public static Object state(OpContext c, Args a) throws InterruptedException {
        return snapshotOnUi(c);
    }

    @ApiOp(name = "combat.attack", kind = ACTION,
            doc = "Attacks an object with the Aggro action (as clicking it with the attack cursor does), waits until it appears in the fight list, "
                    + "and puts the normal cursor back.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "Animal or player to attack."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the fight to start.", optional = true, def = "5")},
            returns = "dict", returnsDoc = "The combat state after the attack (see combat.state).",
            errors = {Errors.GOB_GONE, Errors.TIMEOUT},
            example = "boar = nc.gobs.find(name=\"kritter/boar\")[0]\nnc.combat.attack(boar)")
    public static Object attack(OpContext c, Args a) throws InterruptedException {
        Gob g = a.gob("gob");
        long id = g.id;
        try {
            NUtils.attack(g, true);
            c.waitUntil(() -> relation(c.gui, id) != null, a.d("timeout", 5), "the fight with gob " + id + " to start");
        } finally {
            defaultCursor(c);
        }
        return snapshotOnUi(c);
    }

    /** Right-clicks under the player to drop a held action cursor (what NUtils.getDefaultCur does), with a bounded wait. */
    private static void defaultCursor(OpContext c) throws InterruptedException {
        Gob player = NUtils.player();
        String cur = c.ui.root.cursorRes;
        if (player == null || cur == null || cur.contains("arw")) return;
        c.gui.map.wdgmsg("click", Coord.z, player.rc.floor(OCache.posres), 3, 0);
        c.waitFor(() -> {
            String cr = c.ui.root.cursorRes;
            return cr != null && cr.contains("arw");
        }, 2);
    }

    @ApiOp(name = "combat.use", kind = ACTION,
            doc = "Uses a move from the combat action bar against the current target, like pressing its key. "
                    + "`move` is a slot number (0-9, see combat.state `moves`) or a move's resource name; a name matches exactly, "
                    + "by its last part (\"barrage\" for \"paginae/atk/barrage\"), or as a substring. "
                    + "With `wait` (the default) it first waits for your cooldown and the move's own cooldown to run out.",
            params = {
                    @ApiParam(name = "move", type = "int|str", doc = "Slot or move name."),
                    @ApiParam(name = "wait", type = "bool", doc = "Wait until the move is ready before using it.", optional = true, def = "true"),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the move to be ready.", optional = true, def = "10"),
                    @ApiParam(name = "pos", type = "Coord", doc = "World position for moves aimed at the ground (what the mouse would point at).", optional = true)},
            returns = "dict", returnsDoc = "{slot, name} of the move used.",
            errors = {Errors.NOT_FOUND, Errors.ACTION_FAILED, Errors.TIMEOUT},
            example = "nc.combat.use(\"barrage\")\nnc.combat.use(0, wait=False)")
    public static Object use(OpContext c, Args a) throws InterruptedException {
        NFightsess fs = fightsess(c.gui);
        if (fs == null || !inFight(c.gui)) throw ApiError.failed("not in a fight");
        int slot = findSlot(fs, a.raw().get("move"));
        if (slot < 0) throw ApiError.notFound("no move " + a.raw().get("move") + " on the combat action bar");
        if (a.b("wait", true)) {
            c.waitUntil(() -> {
                NFightsess f = fightsess(c.gui);
                if (f == null || !inFight(c.gui)) return true;
                double now = Utils.rtime();
                Fightsess.Action act = f.actions[slot];
                return now >= c.gui.fv.atkct && (act == null || now >= act.ct);
            }, a.d("timeout", 10), "the move to be ready");
            fs = fightsess(c.gui);
            if (fs == null || !inFight(c.gui)) throw ApiError.failed("the fight ended before the move was ready");
        }
        String name = resName(fs.actions[slot] == null ? null : fs.actions[slot].res);
        if (a.has("pos")) fs.wdgmsg("use", slot, 1, 0, a.coord2d("pos").floor(OCache.posres));
        else fs.wdgmsg("use", slot, 1, 0);
        fs.wdgmsg("rel", slot);
        return new JSONObject().put("slot", slot).put("name", name == null ? JSONObject.NULL : name);
    }

    private static int findSlot(Fightsess fs, Object move) {
        if (fs.actions == null) return -1;
        if (move instanceof Number) {
            int n = ((Number) move).intValue();
            return (n >= 0 && n < fs.actions.length && fs.actions[n] != null) ? n : -1;
        }
        if (!(move instanceof String)) throw ApiError.badArgs("'move' must be a slot number or a move name");
        String want = (String) move;
        int partial = -1;
        for (int i = 0; i < fs.actions.length; i++) {
            Fightsess.Action act = fs.actions[i];
            String nm = (act == null) ? null : resName(act.res);
            if (nm == null) continue;
            if (nm.equals(want) || nm.substring(nm.lastIndexOf('/') + 1).equals(want)) return i;
            if (partial < 0 && nm.contains(want)) partial = i;
        }
        return partial;
    }

    @ApiOp(name = "combat.target", kind = ACTION,
            doc = "Makes another opponent in the fight list the current target (what Ctrl+Tab or clicking its portrait does) and waits until the server confirms.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "An opponent in the fight list (an id works after it left view)."),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the switch.", optional = true, def = "3")},
            returns = "bool", returnsDoc = "True once it is the current target, false if the server did not switch in time.",
            errors = {Errors.NOT_FOUND},
            example = "weakest = min(st[\"opponents\"], key=lambda o: o[\"dist\"] or 99)\nnc.combat.target(weakest[\"gob\"])")
    public static Object target(OpContext c, Args a) throws InterruptedException {
        long id = a.gobId("gob");
        if (relation(c.gui, id) == null) throw ApiError.notFound("gob " + id + " is not in the fight list");
        Fightview.Relation cur = c.gui.fv.current;
        if (cur != null && cur.gobid == id) return true;
        c.gui.fv.wdgmsg("bump", (int) id);
        return c.waitFor(() -> c.gui.fv.current != null && c.gui.fv.current.gobid == id, a.d("timeout", 3));
    }

    @ApiOp(name = "combat.peace", kind = ACTION,
            doc = "Offers peace (the white flag on the portrait) to one opponent, or to every opponent that has not been offered it yet, "
                    + "and waits until the client shows the flag raised. Animals being tamed and players who accept leave the fight.",
            params = {
                    @ApiParam(name = "gob", type = "Gob", doc = "The opponent; omit for all of them.", optional = true),
                    @ApiParam(name = "timeout", type = "float", doc = "Seconds to wait for the flags.", optional = true, def = "3")},
            returns = "int", returnsDoc = "How many opponents were offered peace.",
            errors = {Errors.NOT_FOUND, Errors.TIMEOUT},
            example = "nc.combat.attack(horse)\nnc.combat.peace()")
    public static Object peace(OpContext c, Args a) throws InterruptedException {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        if (a.has("gob")) {
            long id = a.gobId("gob");
            Fightview.Relation rel = relation(c.gui, id);
            if (rel == null) throw ApiError.notFound("gob " + id + " is not in the fight list");
            if (rel.gst != 1) ids.add(id);
        } else if (c.gui.fv != null) {
            for (Fightview.Relation rel : new java.util.ArrayList<>(c.gui.fv.lsrel))
                if (rel.gst != 1) ids.add(rel.gobid);
        }
        for (long id : ids) c.gui.fv.wdgmsg("give", (int) id, 1);
        if (!ids.isEmpty()) {
            c.waitUntil(() -> {
                for (long id : ids) {
                    Fightview.Relation rel = relation(c.gui, id);
                    if (rel != null && rel.gst != 1) return false;
                }
                return true;
            }, a.d("timeout", 3), "the peace flags");
        }
        return ids.size();
    }

    private CombatOps() {}
}
