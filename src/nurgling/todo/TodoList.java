package nurgling.todo;

import org.json.JSONObject;

/** A tab of tasks. The Personal list is never stored as a row; it is {@link #PERSONAL}. */
public class TodoList {
    /** List id of the private list that never leaves this machine. */
    public static final int PERSONAL = -1;
    public static final int MAX_NAME = 40;

    public int id;
    public String name = "";
    public double order;
    public boolean deleted;
    public String touchedBy = "";
    public long touchedAt;
    public int version;

    public TodoList copy() {
        TodoList c = new TodoList();
        c.id = id;
        c.name = name;
        c.order = order;
        c.deleted = deleted;
        c.touchedBy = touchedBy;
        c.touchedAt = touchedAt;
        c.version = version;
        return c;
    }

    public boolean isPersonal() {
        return id == PERSONAL;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("order", order);
        o.put("touchedBy", touchedBy);
        o.put("touchedAt", touchedAt);
        o.put("deleted", deleted);
        return o;
    }

    public static TodoList fromJson(int id, String name, JSONObject o) {
        TodoList l = new TodoList();
        l.id = id;
        l.name = name == null ? "" : name;
        l.order = o.optDouble("order", 0);
        l.touchedBy = o.optString("touchedBy", "");
        l.touchedAt = o.optLong("touchedAt", 0);
        l.deleted = o.optBoolean("deleted", false);
        return l;
    }
}
