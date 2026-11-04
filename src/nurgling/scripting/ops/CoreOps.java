package nurgling.scripting.ops;

import nurgling.NUI;
import nurgling.scripting.OpRegistry;
import nurgling.scripting.ScriptRun;
import nurgling.scripting.ScriptRuntime;
import nurgling.scripting.api.*;
import nurgling.sessions.SessionContext;
import nurgling.sessions.SessionManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

import static nurgling.scripting.api.ApiOp.Exec.CONNECTION;
import static nurgling.scripting.api.ApiOp.Kind.CONTROL;

/** Connection, session, run and event plumbing. */
public final class CoreOps {

    @ApiOp(name = "core.ping", kind = CONTROL, exec = CONNECTION,
            doc = "Round-trip check. Returns the client's time in milliseconds.",
            returns = "int", example = "nc.core.ping()")
    public static Object ping(OpContext c, Args a) {
        return System.currentTimeMillis();
    }

    @ApiOp(name = "core.describe", kind = CONTROL, exec = CONNECTION,
            doc = "The API schema: every op with its parameters, types and docs. With `op`, only that op.",
            params = {@ApiParam(name = "op", type = "str", doc = "Op name, e.g. \"gobs.find\".", optional = true)},
            returns = "dict", errors = {Errors.NOT_FOUND},
            example = "print(nc.core.describe(op=\"gobs.find\")[\"doc\"])")
    public static Object describe(OpContext c, Args a) {
        if (a.has("op")) {
            OpRegistry.OpDef d = OpRegistry.get(a.str("op"));
            if (d == null) throw ApiError.notFound("no op '" + a.str("op") + "'");
            return Schema.op(d.op);
        }
        return Schema.build();
    }

    @ApiOp(name = "core.version", kind = CONTROL, exec = CONNECTION,
            doc = "API version and client build.",
            returns = "dict", returnsDoc = "{api, client}")
    public static Object version(OpContext c, Args a) {
        return new JSONObject().put("api", ScriptRuntime.API_VERSION).put("client", ScriptRuntime.clientVersion());
    }

    @ApiOp(name = "sessions.list", kind = CONTROL, exec = CONNECTION,
            doc = "All logged-in sessions (tabs), visible and background.",
            returns = "list[dict]", returnsDoc = "[{id, character, username, headless, active}]",
            example = "for s in nc.sessions.list(): print(s[\"character\"])")
    public static Object listSessions(OpContext c, Args a) {
        JSONArray res = new JSONArray();
        for (SessionContext sc : SessionManager.getInstance().getAllSessions()) {
            if (sc.ui != null) res.put(ScriptRuntime.sessionInfo(sc.ui));
        }
        return res;
    }

    @ApiOp(name = "runs.open", kind = CONTROL, exec = CONNECTION,
            doc = "Starts a second bot thread bound to another session, for scripts that drive several characters. "
                    + "Requests carrying its run id execute on that session. The SDK wraps this as `nc.session(name)`, which returns a client for that character. "
                    + "The new run shows as its own row in that session's Bot Status strip and can be stopped on its own.",
            params = {@ApiParam(name = "session", type = "str", doc = "Character name, username or session id.")},
            returns = "dict", returnsDoc = "{run, session}", errors = {Errors.NOT_FOUND, Errors.NOT_SUPPORTED},
            example = "alt = nc.session(\"Karasique\")\nalt.actions.path_to(gob)")
    public static Object openRun(OpContext c, Args a) {
        NUI ui = ScriptRuntime.resolveSession(a.str("session"));
        if (ui == null || ui.gui == null) throw ApiError.notFound("no logged-in session '" + a.str("session") + "'");
        ScriptRun run = ScriptRuntime.startAttachedRun(ui, "py: " + c.conn.clientName);
        if (run == null) throw ApiError.notSupported("could not start a bot thread on that session");
        c.conn.attach(run);
        return new JSONObject().put("run", run.id).put("session", ScriptRuntime.sessionInfo(ui));
    }

    @ApiOp(name = "runs.close", kind = CONTROL, exec = CONNECTION,
            doc = "Ends a run opened with runs.open after its queued requests finish. Closing the primary run ends the script's connection.",
            params = {@ApiParam(name = "run", type = "str", doc = "Run id from runs.open.")},
            returns = "bool", returnsDoc = "False if no such run.")
    public static Object closeRun(OpContext c, Args a) {
        ScriptRun r = c.conn.run(a.str("run"));
        if (r == null) return false;
        nurgling.scripting.ScriptRuntimeAccess.close(r);
        return true;
    }

    @ApiOp(name = "runs.list", kind = CONTROL, exec = CONNECTION,
            doc = "This connection's runs: the primary one and any opened with runs.open.",
            returns = "list[dict]", returnsDoc = "[{run, primary, session}]")
    public static Object listRuns(OpContext c, Args a) {
        JSONArray res = new JSONArray();
        for (ScriptRun r : c.conn.runs())
            res.put(new JSONObject().put("run", r.id).put("primary", r == c.conn.primary()).put("session", ScriptRuntime.sessionInfo(r.ui)));
        return res;
    }

    @ApiOp(name = "run.finish", kind = CONTROL, exec = CONNECTION,
            doc = "Reports the script's outcome before it exits. A scenario step that runs this script continues only on success. "
                    + "Without this call, exit code 0 counts as success.",
            params = {@ApiParam(name = "success", type = "bool", doc = "True if the script did its job.")},
            returns = "null", example = "nc.run.finish(success=False)")
    public static Object finish(OpContext c, Args a) {
        if (c.run != null) c.run.reportedSuccess = a.b("success", true);
        return null;
    }

    @ApiOp(name = "run.params", kind = CONTROL, exec = CONNECTION,
            doc = "Parameter values this run was started with: the answers to the script's header form, or a scenario step's settings.",
            returns = "dict", example = "count = nc.run.params().get(\"count\", 10)")
    public static Object params(OpContext c, Args a) {
        return c.run == null ? new JSONObject() : c.run.params;
    }

    @ApiOp(name = "events.subscribe", kind = CONTROL, exec = CONNECTION,
            doc = "Starts delivering events of the given kinds from this run's session. `kind.*` subscribes to a whole family. "
                    + "Kinds: `ui.new`, `ui.add`, `ui.msg`, `ui.destroy`, `ui.out` (raw widget traffic), `combat.used`, `chat.msg`, `gob.new`, `gob.gone`, `form.*`. "
                    + "The filter keeps events whose data fields equal the given values; a string ending in `*` matches a prefix. "
                    + "Events are delivered asynchronously; use the SDK's `nc.events(...)` iterator or `nc.on(kind, fn)`.",
            params = {
                    @ApiParam(name = "kinds", type = "list[str]", doc = "Event kinds, e.g. [\"ui.msg\"] or [\"ui.*\"]."),
                    @ApiParam(name = "filter", type = "dict", doc = "Field filter, e.g. {\"msg\": \"tt\"} or {\"caption\": \"Cupboard*\"}.", optional = true)},
            returns = "int", returnsDoc = "Subscription id, carried by each event as `sub`.",
            example = "for ev in nc.events(\"ui.new\", caption=\"Cupboard*\"):\n    print(ev.data)")
    public static Object subscribe(OpContext c, Args a) {
        if (c.run == null) throw ApiError.notSupported("no run to subscribe on");
        Set<String> kinds = new LinkedHashSet<>(a.strs("kinds"));
        if (kinds.isEmpty()) throw ApiError.badArgs("kinds must not be empty");
        JSONObject filter = a.has("filter") ? a.obj("filter") : new JSONObject();
        return c.conn.subscribe(kinds, c.run, filter);
    }

    @ApiOp(name = "events.unsubscribe", kind = CONTROL, exec = CONNECTION,
            doc = "Stops a subscription.",
            params = {@ApiParam(name = "sub", type = "int", doc = "Subscription id from events.subscribe.")},
            returns = "bool")
    public static Object unsubscribe(OpContext c, Args a) {
        return c.conn.unsubscribe(a.i("sub"));
    }

    private CoreOps() {}
}
