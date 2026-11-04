package nurgling.scripting.ops;

import nurgling.NInventory;
import nurgling.NUtils;
import nurgling.actions.Action;
import nurgling.actions.ActionWithFinal;
import nurgling.actions.Results;
import nurgling.actions.bots.ScenarioRunner;
import nurgling.actions.bots.registry.BotDescriptor;
import nurgling.actions.bots.registry.BotRegistry;
import nurgling.actions.bots.registry.ParamSpec;
import nurgling.scenarios.BotStep;
import nurgling.scenarios.Scenario;
import nurgling.scripting.api.*;
import nurgling.sessions.BotExecutor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

import static nurgling.scripting.api.ApiOp.Exec.CONNECTION;
import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/** Layer 1: running the client's own bots and scenarios. */
public final class BotOps {

    public static JSONObject describe(BotDescriptor d) {
        JSONObject j = new JSONObject();
        j.put("id", d.id);
        j.put("title", d.getDisplayName());
        j.put("description", d.getDescription() == null ? JSONObject.NULL : d.getDescription());
        j.put("type", d.type.name().toLowerCase());
        j.put("group", d.group() == null ? JSONObject.NULL : d.group());
        j.put("menu", d.allowedAsItemInBotMenu);
        j.put("scenario_step", d.allowedAsStepInScenario);
        j.put("interactive", d.isInteractive());
        j.put("class", d.clazz.getName());
        JSONArray ps = new JSONArray();
        for (ParamSpec p : d.params()) {
            JSONObject pj = new JSONObject().put("name", p.name).put("type", p.type.name().toLowerCase()).put("doc", p.doc)
                    .put("required", p.required());
            if (p.def != null) pj.put("default", p.def);
            if (!p.values.isEmpty()) pj.put("values", new JSONArray(p.values));
            ps.put(pj);
        }
        j.put("params", ps);
        return j;
    }

    @ApiOp(name = "bots.list", kind = QUERY, exec = CONNECTION,
            doc = "Every bot registered in the client, including scripts in the Scripts folder, with their parameters.",
            returns = "list[dict]", returnsDoc = "[{id, title, description, type, group, menu, scenario_step, interactive, class, params}]",
            example = "for b in nc.bots.list():\n    print(b[\"id\"], b[\"title\"])")
    public static Object list(OpContext c, Args a) {
        JSONArray res = new JSONArray();
        for (BotDescriptor d : BotRegistry.all()) res.put(describe(d));
        return res;
    }

    @ApiOp(name = "bots.get", kind = QUERY, exec = CONNECTION,
            doc = "One bot's description.",
            params = {@ApiParam(name = "id", type = "str", doc = "Bot id, e.g. \"goto_area\".")},
            returns = "dict", errors = {Errors.NOT_FOUND})
    public static Object get(OpContext c, Args a) {
        BotDescriptor d = BotRegistry.byId(a.str("id"));
        if (d == null) throw ApiError.notFound("no bot '" + a.str("id") + "'");
        return describe(d);
    }

    @ApiOp(name = "bots.run", kind = ACTION,
            doc = "Runs a registered bot on this script's bot thread and waits for it to finish. "
                    + "The bot behaves exactly as when started from the menu: it uses your areas and specialisations, "
                    + "shows its own dialogs, and stops with the script's stop button. A fresh instance is created for every run. "
                    + "Parameters are checked against the bot's declared settings.",
            params = {
                    @ApiParam(name = "id", type = "str", doc = "Bot id (see bots.list)."),
                    @ApiParam(name = "params", type = "dict", doc = "Bot settings, e.g. {\"areaId\": 12}.", optional = true, def = "{}")},
            returns = "bool", returnsDoc = "True if the bot reported success.",
            errors = {Errors.NOT_FOUND, Errors.NOT_SUPPORTED},
            example = "ok = nc.bots.run(\"goto_area\", params={\"areaId\": 12})")
    public static Object run(OpContext c, Args a) throws InterruptedException {
        BotDescriptor d = BotRegistry.byId(a.str("id"));
        if (d == null) throw ApiError.notFound("no bot '" + a.str("id") + "'");
        Map<String, Object> settings = normalize(d, a.map("params"));
        checkParams(d, settings);
        if (d.isInteractive() && nurgling.scripting.ScriptRuntime.sessionInfo(c.ui).optBoolean("headless"))
            throw ApiError.notSupported("bot '" + d.id + "' asks the player for input and cannot run on a background session");
        return runBot(c, d, d.instantiate(settings)).IsSuccess();
    }

    /** JSON gives ints as Integer or Long and floats as BigDecimal; bots cast to Integer/Double, so convert per spec. */
    static Map<String, Object> normalize(BotDescriptor d, Map<String, Object> settings) {
        for (ParamSpec p : d.params()) {
            Object v = settings.get(p.name);
            if (!(v instanceof Number)) continue;
            Number n = (Number) v;
            if (p.type == ParamSpec.Type.INT || p.type == ParamSpec.Type.AREA) {
                if (n.doubleValue() == Math.rint(n.doubleValue())) settings.put(p.name, n.intValue());
            } else if (p.type == ParamSpec.Type.FLOAT) {
                settings.put(p.name, n.doubleValue());
            }
        }
        return settings;
    }

    static void checkParams(BotDescriptor d, Map<String, Object> settings) {
        List<ParamSpec> specs = d.params();
        if (specs.isEmpty()) return;
        for (String k : settings.keySet()) {
            boolean known = false;
            for (ParamSpec p : specs) if (p.name.equals(k)) known = true;
            if (!known) throw ApiError.badArgs("bot '" + d.id + "' has no setting '" + k + "'");
        }
        for (ParamSpec p : specs) {
            Object v = settings.containsKey(p.name) ? settings.get(p.name) : (d.defaultSettings.containsKey(p.name) ? d.defaultSettings.get(p.name) : p.def);
            String err = p.check(v);
            if (err != null) throw ApiError.badArgs("bot '" + d.id + "': " + err);
        }
    }

    /** Runs an action inline the way BotExecutor would: support threads, stack mode, endAction. */
    static Results runBot(OpContext c, BotDescriptor d, Action bot) throws InterruptedException {
        List<Thread> supports = BotExecutor.startSupports(d.id + "-Support", bot);
        boolean restoreStacks = false;
        if (d.disStacks && c.gui.maininv instanceof NInventory && ((NInventory) c.gui.maininv).bundle.a) {
            NUtils.stackSwitch(false);
            restoreStacks = true;
        }
        try {
            Results r = bot.run(c.gui);
            return r == null ? Results.FAIL() : r;
        } finally {
            for (Thread t : supports) t.interrupt();
            if (restoreStacks) NUtils.stackSwitch(true);
            if (bot instanceof ActionWithFinal) ((ActionWithFinal) bot).endAction();
        }
    }

    @ApiOp(name = "scenarios.list", kind = QUERY,
            doc = "Saved scenarios (bot chains) with their steps.",
            returns = "list[dict]", returnsDoc = "[{id, name, steps: [{id, params}]}]")
    public static Object scenarios(OpContext c, Args a) {
        JSONArray res = new JSONArray();
        for (Scenario s : c.ui.core.scenarioManager.getScenarios().values()) {
            JSONArray steps = new JSONArray();
            for (BotStep st : s.getSteps())
                steps.put(new JSONObject().put("id", st.getId()).put("params", Codec.toJson(st.getSettings())));
            res.put(new JSONObject().put("id", s.getId()).put("name", s.getName()).put("steps", steps));
        }
        return res;
    }

    @ApiOp(name = "scenarios.run", kind = ACTION,
            doc = "Runs a saved scenario step by step on this script's bot thread; stops at the first step that fails.",
            params = {@ApiParam(name = "scenario", type = "int|str", doc = "Scenario id or name.")},
            returns = "bool", returnsDoc = "True if every step succeeded.", errors = {Errors.NOT_FOUND},
            example = "nc.scenarios.run(\"Morning chores\")")
    public static Object runScenario(OpContext c, Args a) throws InterruptedException {
        Object key = a.raw().get("scenario");
        Scenario found = null;
        for (Scenario s : c.ui.core.scenarioManager.getScenarios().values()) {
            if ((key instanceof Number && s.getId() == ((Number) key).intValue()) || s.getName().equals(String.valueOf(key))) {
                found = s;
                break;
            }
        }
        if (found == null) throw ApiError.notFound("no scenario " + key);
        Results r = new ScenarioRunner(found).run(c.gui);
        return r != null && r.IsSuccess();
    }

    private BotOps() {}
}
