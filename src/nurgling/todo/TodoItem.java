package nurgling.todo;

import org.json.JSONObject;

/**
 * One task. Plain mutable fields: the store only ever hands out copies, so a widget holding one can
 * never see it change underneath it.
 */
public class TodoItem {
    public static final int MAX_TITLE = 120;
    public static final int MAX_NOTES = 2000;
    private static final long HOUR_MS = 3_600_000L;

    public int id;
    public int listId;
    public String title = "";
    public String notes = "";
    /** Character name, or empty for "anyone". */
    public String assignee = "";
    public boolean urgent;
    public double order;
    public boolean hasLoc;
    public long locGrid;
    public int locX, locY;
    /** Reopen this many hours after being finished; 0 means never. */
    public int repeatH;
    public boolean done;
    public String doneBy = "";
    public long doneAt;
    public String createdBy = "";
    public long createdAt;
    public String touchedBy = "";
    public long touchedAt;
    public boolean deleted;
    /** Row version in the database; 0 until the row exists there. Unused in file mode. */
    public int version;

    public TodoItem copy() {
        TodoItem c = new TodoItem();
        c.id = id;
        c.listId = listId;
        c.title = title;
        c.notes = notes;
        c.assignee = assignee;
        c.urgent = urgent;
        c.order = order;
        c.hasLoc = hasLoc;
        c.locGrid = locGrid;
        c.locX = locX;
        c.locY = locY;
        c.repeatH = repeatH;
        c.done = done;
        c.doneBy = doneBy;
        c.doneAt = doneAt;
        c.createdBy = createdBy;
        c.createdAt = createdAt;
        c.touchedBy = touchedBy;
        c.touchedAt = touchedAt;
        c.deleted = deleted;
        c.version = version;
        return c;
    }

    /**
     * Whether the task counts as open right now. A finished repeating task reopens on its own once its
     * interval has passed: nobody writes that, so no two clients can race to reopen it.
     */
    public boolean isOpen(long now) {
        if (deleted)
            return false;
        if (!done)
            return true;
        return repeatH > 0 && now >= doneAt + repeatH * HOUR_MS;
    }

    public boolean isAssignedTo(String name) {
        return name != null && !name.isEmpty() && name.equals(assignee);
    }

    /** Everything except id, title and version, which have their own columns in the database. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("list", listId);
        o.put("notes", notes);
        o.put("assignee", assignee);
        o.put("urgent", urgent);
        o.put("order", order);
        if (hasLoc) {
            JSONObject loc = new JSONObject();
            loc.put("gid", locGrid);
            loc.put("x", locX);
            loc.put("y", locY);
            o.put("loc", loc);
        }
        o.put("repeatH", repeatH);
        o.put("done", done);
        o.put("doneBy", doneBy);
        o.put("doneAt", doneAt);
        o.put("createdBy", createdBy);
        o.put("createdAt", createdAt);
        o.put("touchedBy", touchedBy);
        o.put("touchedAt", touchedAt);
        o.put("deleted", deleted);
        return o;
    }

    public static TodoItem fromJson(int id, String title, JSONObject o) {
        TodoItem it = new TodoItem();
        it.id = id;
        it.title = title == null ? "" : title;
        it.listId = o.optInt("list", 0);
        it.notes = o.optString("notes", "");
        it.assignee = o.optString("assignee", "");
        it.urgent = o.optBoolean("urgent", false);
        it.order = o.optDouble("order", 0);
        JSONObject loc = o.optJSONObject("loc");
        if (loc != null) {
            it.hasLoc = true;
            it.locGrid = loc.optLong("gid", 0);
            it.locX = loc.optInt("x", 0);
            it.locY = loc.optInt("y", 0);
        }
        it.repeatH = o.optInt("repeatH", 0);
        it.done = o.optBoolean("done", false);
        it.doneBy = o.optString("doneBy", "");
        it.doneAt = o.optLong("doneAt", 0);
        it.createdBy = o.optString("createdBy", "");
        it.createdAt = o.optLong("createdAt", 0);
        it.touchedBy = o.optString("touchedBy", "");
        it.touchedAt = o.optLong("touchedAt", 0);
        it.deleted = o.optBoolean("deleted", false);
        return it;
    }
}
