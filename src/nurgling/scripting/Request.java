package nurgling.scripting;

import org.json.JSONObject;

/** One request frame from the script. */
public final class Request {
    /** Request id echoed in the response; null for fire-and-forget. */
    public final Object id;
    public final String op;
    public final JSONObject args;
    /** Target run id; null means the connection's primary run. */
    public final String run;

    static final Request POISON = new Request(null, "<poison>", null, null);

    Request(Object id, String op, JSONObject args, String run) {
        this.id = id;
        this.op = op;
        this.args = args;
        this.run = run;
    }

    static Request parse(JSONObject f) {
        Object id = f.has("id") ? f.get("id") : null;
        String op = f.optString("op", null);
        JSONObject args = f.optJSONObject("args");
        String run = f.has("run") && !f.isNull("run") ? f.getString("run") : null;
        return new Request(id, op, args == null ? new JSONObject() : args, run);
    }
}
