package nurgling.scripting.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** The error vocabulary of the protocol. Python maps each type to an exception class of the same name. */
public final class Errors {
    public static final String STOPPED = "Stopped";
    public static final String TIMEOUT = "Timeout";
    public static final String GOB_GONE = "GobGone";
    public static final String ITEM_GONE = "ItemGone";
    public static final String WIDGET_GONE = "WidgetGone";
    public static final String PATH_NOT_FOUND = "PathNotFound";
    public static final String NOT_FOUND = "NotFound";
    public static final String NO_SUCH_AREA = "NoSuchArea";
    public static final String BAD_ARGS = "BadArgs";
    public static final String ACTION_FAILED = "ActionFailed";
    public static final String NOT_SUPPORTED = "NotSupported";
    public static final String CANCELLED = "Cancelled";
    public static final String AUTH = "AuthFailed";
    public static final String UNKNOWN_OP = "UnknownOp";
    public static final String JAVA = "JavaError";
    public static final String INTERNAL = "Internal";

    /** Type to description, for the schema and the docs. */
    public static Map<String, String> all() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(STOPPED, "The run was stopped (stop button, script exit, or session closed). Not catchable as a normal failure: the run is over.");
        m.put(TIMEOUT, "A wait or blocking call did not finish within its timeout.");
        m.put(GOB_GONE, "The game object no longer exists (out of view, destroyed, or picked up).");
        m.put(ITEM_GONE, "The item widget no longer exists (moved, used up, or its container closed).");
        m.put(WIDGET_GONE, "The window or widget no longer exists.");
        m.put(PATH_NOT_FOUND, "The pathfinder found no route to the target.");
        m.put(NOT_FOUND, "Something the op needed (a window, a menu option, an item) was not found.");
        m.put(NO_SUCH_AREA, "No area with that id, name or specialisation exists.");
        m.put(BAD_ARGS, "Missing, unknown or wrongly typed arguments.");
        m.put(ACTION_FAILED, "A game action ran but reported failure (the Java action returned FAIL or ERROR).");
        m.put(NOT_SUPPORTED, "The op cannot run in this context (e.g. an interactive prompt on a headless session).");
        m.put(CANCELLED, "The user dismissed a form or picker.");
        m.put(AUTH, "The handshake token was wrong or missing.");
        m.put(UNKNOWN_OP, "No op with that name exists in this client version.");
        m.put(JAVA, "A Java exception thrown inside a java.* call (layer 4). The message carries the Java class and message.");
        m.put(INTERNAL, "An unexpected error inside the client. Please report it with the message.");
        return m;
    }

    private Errors() {}
}
