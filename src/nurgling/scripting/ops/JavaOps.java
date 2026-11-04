package nurgling.scripting.ops;

import haven.*;
import nurgling.actions.Action;
import nurgling.actions.Results;
import nurgling.areas.NArea;
import nurgling.scripting.JavaRefs;
import nurgling.scripting.api.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.*;
import java.util.*;

import static nurgling.scripting.api.ApiOp.Kind.ACTION;
import static nurgling.scripting.api.ApiOp.Kind.QUERY;

/**
 * Layer 4: reflective access to the client's Java code. No stability guarantees: names change
 * whenever the client is refactored. Enabled per connection (connect(java=True) or the script
 * header '# nurgling: java=true').
 *
 * Values cross the boundary like this: null, booleans, numbers, strings and enums as themselves,
 * Coord/Coord2d as [x, y], everything else as a reference {"ref": n, "class": ..., "str": ...}
 * that later calls accept as a target or argument. Snapshots ({"gob": id}, {"item": id},
 * {"wdg": id}, {"area": id}) are accepted wherever a Gob, item, widget or area is expected.
 */
public final class JavaOps {

    private static final String[] SPECIALS = {"gui", "ui", "map", "player", "glob", "inventory", "run"};

    // ------------------------------------------------------------------ value conversion

    static Object out(OpContext c, Object o) {
        if (o == null) return JSONObject.NULL;
        if (o instanceof String || o instanceof Boolean || o instanceof Number || o instanceof Character)
            return (o instanceof Character) ? String.valueOf(o) : Codec.toJson(o);
        if (o instanceof Enum) return ((Enum<?>) o).name();
        if (o instanceof Coord || o instanceof Coord2d) return Codec.toJson(o);
        JSONObject r = new JSONObject();
        r.put("ref", c.conn.refs.put(o));
        r.put("class", o.getClass().getName());
        r.put("str", safeString(o));
        if (o instanceof Gob) r.put("snapshot", Codec.gob((Gob) o));
        else if (o instanceof GItem) r.put("snapshot", Codec.item((GItem) o));
        else if (o instanceof WItem) r.put("snapshot", Codec.item((WItem) o));
        else if (o instanceof NArea) r.put("snapshot", Codec.area((NArea) o));
        else if (o instanceof Widget && ((Widget) o).ui != null) r.put("wdg", ((Widget) o).wdgid());
        return r;
    }

    private static String safeString(Object o) {
        try {
            String s = String.valueOf(o);
            return s.length() > 200 ? s.substring(0, 200) + "..." : s;
        } catch (RuntimeException e) {
            // toString() of half-initialised client objects can throw; the reference is still usable.
            return o.getClass().getName();
        }
    }

    /** Resolves a target: a ref, a handle snapshot, or a special name. */
    static Object target(OpContext c, Object t) {
        if (t instanceof String) return special(c, (String) t);
        if (t instanceof JSONObject) {
            JSONObject j = (JSONObject) t;
            if (j.has("ref")) {
                Object o = c.conn.refs.get(j.getInt("ref"));
                if (o == null) throw new ApiError(Errors.JAVA, "reference " + j.get("ref") + " was released");
                return o;
            }
            if (j.has("gob")) {
                Gob g = c.gui.map.glob.oc.getgob(j.getLong("gob"));
                if (g == null) throw new ApiError(Errors.GOB_GONE, "gob " + j.get("gob") + " no longer exists");
                return g;
            }
            if (j.has("item") || j.has("wdg")) {
                int id = j.has("item") ? j.getInt("item") : j.getInt("wdg");
                Widget w = c.ui.getwidget(id);
                if (w == null) throw new ApiError(Errors.WIDGET_GONE, "widget " + id + " no longer exists");
                return w;
            }
            if (j.has("area")) {
                NArea a = Areas.byId(c, j.getInt("area"));
                if (a == null) throw new ApiError(Errors.NO_SUCH_AREA, "no area " + j.get("area"));
                return a;
            }
        }
        throw ApiError.badArgs("target must be a {\"ref\": n}, a snapshot, or one of " + Arrays.toString(SPECIALS));
    }

    static Object special(OpContext c, String name) {
        switch (name) {
            case "gui": return c.gui;
            case "ui": return c.ui;
            case "map": return c.gui.map;
            case "player": return c.gui.map.player();
            case "glob": return c.gui.map.glob;
            case "inventory": return c.gui.getInventory();
            case "run": return c.run;
            default: throw ApiError.badArgs("unknown special '" + name + "'; use one of " + Arrays.toString(SPECIALS));
        }
    }

    /** Converts a JSON value to {@code type}, or returns NO if it cannot. */
    static Object in(OpContext c, Object v, Class<?> type) {
        if (v == null || v == JSONObject.NULL) return type.isPrimitive() ? NO : null;
        Class<?> bt = box(type);
        if (v instanceof JSONObject) {
            JSONObject j = (JSONObject) v;
            if (j.has("ref") || j.has("gob") || j.has("item") || j.has("wdg") || j.has("area")) {
                Object o = target(c, j);
                if (bt.isInstance(o)) return o;
                if (o instanceof GItem && WItem.class.isAssignableFrom(bt)) {
                    WItem w = Codec.findWItem((GItem) o);
                    return w != null ? w : NO;
                }
                return NO;
            }
            if (bt.isAssignableFrom(HashMap.class)) return j.toMap();
            return NO;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            if (bt == Coord.class && a.length() == 2) return new Coord(a.getInt(0), a.getInt(1));
            if (bt == Coord2d.class && a.length() == 2) return new Coord2d(a.getDouble(0), a.getDouble(1));
            if (bt.isArray()) {
                Class<?> ct = bt.getComponentType();
                Object arr = Array.newInstance(ct, a.length());
                for (int i = 0; i < a.length(); i++) {
                    Object e = in(c, a.get(i), ct);
                    if (e == NO) return NO;
                    Array.set(arr, i, e);
                }
                return arr;
            }
            if (bt.isAssignableFrom(ArrayList.class)) {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    Object e = a.get(i);
                    l.add(e instanceof JSONObject && ((JSONObject) e).has("ref") ? target(c, e) : (e == JSONObject.NULL ? null : e));
                }
                return l;
            }
            return NO;
        }
        if (v instanceof Boolean) return bt == Boolean.class || bt == Object.class ? v : NO;
        if (v instanceof Number) {
            Number n = (Number) v;
            if (bt == Integer.class) return n.intValue();
            if (bt == Long.class) return n.longValue();
            if (bt == Double.class) return n.doubleValue();
            if (bt == Float.class) return n.floatValue();
            if (bt == Short.class) return n.shortValue();
            if (bt == Byte.class) return n.byteValue();
            if (bt == Object.class || bt == Number.class) return v;
            return NO;
        }
        if (v instanceof String) {
            String s = (String) v;
            if (bt == String.class || bt == Object.class || bt == CharSequence.class) return s;
            if (bt == Character.class && s.length() == 1) return s.charAt(0);
            if (bt.isEnum()) {
                for (Object e : bt.getEnumConstants()) if (((Enum<?>) e).name().equals(s)) return e;
                return NO;
            }
            if (bt == Class.class) {
                try {
                    return Class.forName(s);
                } catch (ClassNotFoundException e) {
                    return NO;
                }
            }
            return NO;
        }
        return NO;
    }

    private static final Object NO = new Object();

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == boolean.class) return Boolean.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private static Object[] convert(OpContext c, JSONArray args, Class<?>[] types) {
        if (args.length() != types.length) return null;
        Object[] res = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Object v = in(c, args.get(i), types[i]);
            if (v == NO) return null;
            res[i] = v;
        }
        return res;
    }

    static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new ApiError(Errors.JAVA, "no class " + name);
        }
    }

    // ------------------------------------------------------------------ member lookup and invocation

    private static List<Method> methods(Class<?> cl, String name, boolean wantStatic) {
        List<Method> res = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> k = cl; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) != wantStatic) continue;
                String sig = Arrays.toString(m.getParameterTypes());
                if (seen.add(sig)) res.add(m);
            }
        }
        for (Class<?> i : cl.getInterfaces()) {
            for (Method m : i.getMethods()) {
                if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()) == wantStatic && seen.add(Arrays.toString(m.getParameterTypes())))
                    res.add(m);
            }
        }
        return res;
    }

    private static Field field(Class<?> cl, String name) {
        for (Class<?> k = cl; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // Keep looking in the superclass.
            }
        }
        return null;
    }

    private static Object invoke(OpContext c, Object self, Class<?> cl, String name, JSONArray args) throws InterruptedException {
        List<Method> cands = methods(cl, name, self == null);
        for (Method m : cands) {
            Object[] conv = convert(c, args, m.getParameterTypes());
            if (conv == null) continue;
            m.setAccessible(true);
            return call(() -> m.invoke(self, conv));
        }
        if (cands.isEmpty()) {
            if (args.length() == 0) {
                Field f = field(cl, name);
                if (f != null && Modifier.isStatic(f.getModifiers()) == (self == null)) return getField(f, self);
            }
            throw new ApiError(Errors.JAVA, cl.getName() + " has no " + (self == null ? "static " : "") + "method or field '" + name + "'");
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : cands) sb.append("\n  ").append(m.toGenericString());
        throw new ApiError(Errors.JAVA, "no overload of " + name + " accepts these " + args.length() + " argument(s); candidates:" + sb);
    }

    private static Object getField(Field f, Object self) {
        try {
            return f.get(self);
        } catch (IllegalAccessException e) {
            throw new ApiError(Errors.JAVA, "cannot read " + f + ": " + e.getMessage());
        }
    }

    interface Call {
        Object run() throws IllegalAccessException, InvocationTargetException, InstantiationException;
    }

    private static Object call(Call k) throws InterruptedException {
        try {
            return k.run();
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof InterruptedException) throw (InterruptedException) t;
            if (t instanceof ApiError) throw (ApiError) t;
            if (t instanceof Loading) throw (Loading) t;
            throw new ApiError(Errors.JAVA, t.getClass().getName() + ": " + t.getMessage());
        } catch (IllegalAccessException | InstantiationException e) {
            throw new ApiError(Errors.JAVA, e.toString());
        }
    }

    private static JSONArray argList(Args a) {
        return a.has("args") ? a.arr("args") : new JSONArray();
    }

    // ------------------------------------------------------------------ ops

    @ApiOp(name = "java.static", kind = ACTION, unstable = true,
            doc = "Calls a static method, or reads a static field when there is no method of that name and no arguments.",
            params = {
                    @ApiParam(name = "cls", type = "str", doc = "Fully qualified class name, e.g. \"nurgling.NUtils\"."),
                    @ApiParam(name = "member", type = "str", doc = "Method or field name."),
                    @ApiParam(name = "args", type = "list[any]", doc = "Arguments.", optional = true, def = "[]")},
            returns = "any", errors = {Errors.JAVA},
            example = "stamina = nc.java.static(\"nurgling.NUtils\", \"getStamina\")")
    public static Object callStatic(OpContext c, Args a) throws InterruptedException {
        return out(c, invoke(c, null, cls(a.str("cls")), a.str("member"), argList(a)));
    }

    @ApiOp(name = "java.call", kind = ACTION, unstable = true,
            doc = "Calls a method on an object, or reads a field when there is no such method and no arguments. "
                    + "The target is a reference, a snapshot (Gob, Item, Widget, Area), or one of \"gui\", \"ui\", \"map\", \"player\", \"glob\", \"inventory\", \"run\".",
            params = {
                    @ApiParam(name = "target", type = "any", doc = "Object to call on."),
                    @ApiParam(name = "member", type = "str", doc = "Method or field name."),
                    @ApiParam(name = "args", type = "list[any]", doc = "Arguments.", optional = true, def = "[]")},
            returns = "any", errors = {Errors.JAVA, Errors.GOB_GONE, Errors.WIDGET_GONE},
            example = "nc.java.call(\"gui\", \"msg\", [\"hello from Python\"])")
    public static Object callMethod(OpContext c, Args a) throws InterruptedException {
        Object self = target(c, a.raw().get("target"));
        return out(c, invoke(c, self, self.getClass(), a.str("member"), argList(a)));
    }

    @ApiOp(name = "java.get", kind = QUERY, unstable = true,
            doc = "Reads a field (any visibility) of an object, or a static field when `target` is a class name prefixed with \"class:\".",
            params = {
                    @ApiParam(name = "target", type = "any", doc = "Object, or \"class:fully.qualified.Name\"."),
                    @ApiParam(name = "field", type = "str", doc = "Field name.")},
            returns = "any", errors = {Errors.JAVA},
            example = "hp = nc.java.get(\"gui\", \"chrid\")")
    public static Object get(OpContext c, Args a) {
        Object t = a.raw().get("target");
        Object self;
        Class<?> cl;
        if (t instanceof String && ((String) t).startsWith("class:")) {
            self = null;
            cl = cls(((String) t).substring(6));
        } else {
            self = target(c, t);
            cl = self.getClass();
        }
        Field f = field(cl, a.str("field"));
        if (f == null) throw new ApiError(Errors.JAVA, cl.getName() + " has no field '" + a.str("field") + "'");
        return out(c, getField(f, self));
    }

    @ApiOp(name = "java.set", kind = ACTION, unstable = true,
            doc = "Writes a field (any visibility, not final) of an object, or a static field with \"class:Name\".",
            params = {
                    @ApiParam(name = "target", type = "any", doc = "Object, or \"class:fully.qualified.Name\"."),
                    @ApiParam(name = "field", type = "str", doc = "Field name."),
                    @ApiParam(name = "value", type = "any", doc = "New value.")},
            returns = "null", errors = {Errors.JAVA})
    public static Object set(OpContext c, Args a) {
        Object t = a.raw().get("target");
        Object self;
        Class<?> cl;
        if (t instanceof String && ((String) t).startsWith("class:")) {
            self = null;
            cl = cls(((String) t).substring(6));
        } else {
            self = target(c, t);
            cl = self.getClass();
        }
        Field f = field(cl, a.str("field"));
        if (f == null) throw new ApiError(Errors.JAVA, cl.getName() + " has no field '" + a.str("field") + "'");
        Object v = in(c, a.raw().get("value"), f.getType());
        if (v == NO) throw new ApiError(Errors.JAVA, "value does not fit field type " + f.getType().getName());
        try {
            f.set(self, v);
        } catch (IllegalAccessException e) {
            throw new ApiError(Errors.JAVA, "cannot write " + f + ": " + e.getMessage());
        }
        return null;
    }

    @ApiOp(name = "java.new", kind = ACTION, unstable = true,
            doc = "Constructs an object and returns a reference to it.",
            params = {
                    @ApiParam(name = "cls", type = "str", doc = "Fully qualified class name."),
                    @ApiParam(name = "args", type = "list[any]", doc = "Constructor arguments.", optional = true, def = "[]")},
            returns = "any", errors = {Errors.JAVA},
            example = "alias = nc.java.new(\"nurgling.tools.NAlias\", [[\"log\"]])")
    public static Object construct(OpContext c, Args a) throws InterruptedException {
        Class<?> cl = cls(a.str("cls"));
        JSONArray args = argList(a);
        for (Constructor<?> k : cl.getDeclaredConstructors()) {
            Object[] conv = convert(c, args, k.getParameterTypes());
            if (conv == null) continue;
            k.setAccessible(true);
            return out(c, call(() -> k.newInstance(conv)));
        }
        throw new ApiError(Errors.JAVA, "no constructor of " + cl.getName() + " accepts these " + args.length() + " argument(s)");
    }

    @ApiOp(name = "java.run_action", kind = ACTION, unstable = true,
            doc = "Constructs any nurgling.actions.Action by class name and runs it on this script's bot thread, as Java bots do: "
                    + "`new X(args...).run(gui)`. Gives scripts every reusable action in the client, including ones the curated API does not wrap.",
            params = {
                    @ApiParam(name = "cls", type = "str", doc = "Action class, e.g. \"nurgling.actions.FreeInventory2\"."),
                    @ApiParam(name = "args", type = "list[any]", doc = "Constructor arguments.", optional = true, def = "[]")},
            returns = "bool", returnsDoc = "True if the action returned SUCCESS.", errors = {Errors.JAVA},
            example = "ctx = nc.java.new(\"nurgling.areas.NContext\", [nc.java.ref(\"gui\")])\nnc.java.run_action(\"nurgling.actions.FreeInventory2\", [ctx])")
    public static Object runAction(OpContext c, Args a) throws InterruptedException {
        Class<?> cl = cls(a.str("cls"));
        if (!Action.class.isAssignableFrom(cl)) throw new ApiError(Errors.JAVA, cl.getName() + " is not an Action");
        JSONArray args = argList(a);
        for (Constructor<?> k : cl.getDeclaredConstructors()) {
            Object[] conv = convert(c, args, k.getParameterTypes());
            if (conv == null) continue;
            k.setAccessible(true);
            Action act = (Action) call(() -> k.newInstance(conv));
            Results r = act.run(c.gui);
            return r != null && r.IsSuccess();
        }
        throw new ApiError(Errors.JAVA, "no constructor of " + cl.getName() + " accepts these " + args.length() + " argument(s)");
    }

    @ApiOp(name = "java.ref", kind = QUERY, unstable = true,
            doc = "Turns a snapshot or special name into a reference, e.g. to call several methods on the same object.",
            params = {@ApiParam(name = "target", type = "any", doc = "Snapshot or special name.")},
            returns = "dict")
    public static Object ref(OpContext c, Args a) {
        return out(c, target(c, a.raw().get("target")));
    }

    @ApiOp(name = "java.items", kind = QUERY, unstable = true,
            doc = "Converts a referenced Collection, Map (its entries as [key, value]), Iterable or array to a list, element by element.",
            params = {
                    @ApiParam(name = "target", type = "any", doc = "Reference to a collection."),
                    @ApiParam(name = "limit", type = "int", doc = "Maximum number of elements.", optional = true, def = "1000")},
            returns = "list[any]", errors = {Errors.JAVA})
    public static Object items(OpContext c, Args a) {
        Object o = target(c, a.raw().get("target"));
        int limit = a.i("limit", 1000);
        JSONArray res = new JSONArray();
        Iterable<?> it;
        if (o instanceof Map) it = ((Map<?, ?>) o).entrySet();
        else if (o instanceof Iterable) it = (Iterable<?>) o;
        else if (o.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(o) && i < limit; i++) res.put(out(c, Array.get(o, i)));
            return res;
        } else throw new ApiError(Errors.JAVA, o.getClass().getName() + " is not a collection");
        for (Object e : it) {
            if (res.length() >= limit) break;
            if (e instanceof Map.Entry)
                res.put(new JSONArray().put(out(c, ((Map.Entry<?, ?>) e).getKey())).put(out(c, ((Map.Entry<?, ?>) e).getValue())));
            else res.put(out(c, e));
        }
        return res;
    }

    @ApiOp(name = "java.members", kind = QUERY, unstable = true,
            doc = "Lists the methods and fields of a class or of a referenced object's class, for exploring the client from a REPL.",
            params = {
                    @ApiParam(name = "target", type = "any", doc = "Reference, snapshot, special name, or \"class:Name\"."),
                    @ApiParam(name = "filter", type = "str", doc = "Only members whose name contains this.", optional = true)},
            returns = "dict", returnsDoc = "{class, methods: [str], fields: [str]}")
    public static Object members(OpContext c, Args a) {
        Object t = a.raw().get("target");
        Class<?> cl = (t instanceof String && ((String) t).startsWith("class:")) ? cls(((String) t).substring(6)) : target(c, t).getClass();
        String f = a.str("filter", null);
        TreeSet<String> ms = new TreeSet<>(), fs = new TreeSet<>();
        for (Class<?> k = cl; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods())
                if (f == null || m.getName().contains(f)) ms.add(m.toGenericString().replace("java.lang.", ""));
            for (Field fl : k.getDeclaredFields())
                if (f == null || fl.getName().contains(f)) fs.add(fl.toGenericString().replace("java.lang.", ""));
        }
        return new JSONObject().put("class", cl.getName()).put("methods", new JSONArray(ms)).put("fields", new JSONArray(fs));
    }

    @ApiOp(name = "java.release", kind = QUERY, unstable = true,
            doc = "Releases references so the objects can be garbage collected. All references are released when the script disconnects.",
            params = {@ApiParam(name = "refs", type = "list[int]", doc = "Reference numbers.")},
            returns = "int", returnsDoc = "How many were released.")
    public static Object release(OpContext c, Args a) {
        JavaRefs r = c.conn.refs;
        int n = 0;
        for (Object o : a.arr("refs")) {
            int id = (o instanceof JSONObject) ? ((JSONObject) o).getInt("ref") : ((Number) o).intValue();
            if (r.release(id)) n++;
        }
        return n;
    }

    private JavaOps() {}
}
