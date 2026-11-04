package nurgling.actions.bots.registry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Describes one setting a bot reads from its {@code (Map)} constructor, so scripts can validate
 * and the docs can list bot parameters. Purely descriptive: the bot still reads its own map.
 */
public final class ParamSpec {
    public enum Type { INT, FLOAT, BOOL, STRING, AREA, ENUM }

    public final String name;
    public final Type type;
    public final String doc;
    /** Default value, or null when the bot has none (then the setting is required). */
    public final Object def;
    /** Allowed values for ENUM. */
    public final List<String> values;

    private ParamSpec(String name, Type type, String doc, Object def, List<String> values) {
        this.name = name;
        this.type = type;
        this.doc = doc;
        this.def = def;
        this.values = values;
    }

    public static ParamSpec of(String name, Type type, String doc, Object def) {
        return new ParamSpec(name, type, doc, def, Collections.<String>emptyList());
    }

    public static ParamSpec choice(String name, String doc, Object def, String... values) {
        return new ParamSpec(name, Type.ENUM, doc, def, Collections.unmodifiableList(Arrays.asList(values)));
    }

    public boolean required() {
        return def == null;
    }

    /** Checks one value against the spec; returns an error message or null. */
    public String check(Object v) {
        if (v == null) return required() ? "'" + name + "' is required" : null;
        switch (type) {
            case INT:
            case AREA:
                if (!(v instanceof Integer || v instanceof Long)) return "'" + name + "' must be an int";
                return null;
            case FLOAT:
                if (!(v instanceof Number)) return "'" + name + "' must be a number";
                return null;
            case BOOL:
                if (!(v instanceof Boolean)) return "'" + name + "' must be a bool";
                return null;
            case STRING:
                if (!(v instanceof String)) return "'" + name + "' must be a string";
                return null;
            case ENUM:
                if (!values.contains(String.valueOf(v))) return "'" + name + "' must be one of " + values;
                return null;
            default:
                return null;
        }
    }
}
