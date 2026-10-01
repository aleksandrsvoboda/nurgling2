package nurgling.timers;

import haven.Coord;
import haven.MCache;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One timer: a countdown on a localized resource, on a free spot of the map, or a plain reminder with no
 * position at all.
 *
 * <p>Only the fields every villager shares live here. What one player did about a timer - dismissed it,
 * snoozed it, muted it - is {@link TimerStore.Local}, kept per client and never synced, so dismissing a
 * village timer on one machine leaves it live for everybody else.
 *
 * <p>Instances are never changed after they are published: the store replaces the whole object on every
 * edit, so a render pass can hold one without locking.
 *
 * <p>Position is a server grid id plus the tile offset inside that grid, the one coordinate two clients
 * agree on (see {@link nurgling.tools.GridLocator}). Segment ids are local to one map file and change when
 * segments merge; they only survive here as {@link #legacySeg}/{@link #legacyTc} for records written by the
 * old segment-keyed timers until the map file can convert them.
 */
public final class Timer {
    public enum Kind {
        RESOURCE, PIN, REMINDER,
        /** The deadline of a To-Do task; see {@link nurgling.todo.TaskDeadlines}. */
        TASK;

        public String key() {return name().toLowerCase();}

        public static Kind of(String s) {
            for(Kind k : values()) {
                if(k.key().equals(s))
                    return k;
            }
            return PIN;
        }
    }

    public final String id;
    public final Kind kind;
    /** 0 when the timer has no position (a reminder, or a legacy record not converted yet). */
    public final long gridId;
    public final int ox, oy;
    /** Minimap icon resource of the resource, e.g. {@code gfx/terobjs/mm/tarpit}; resources only. */
    public final String resType;
    /** The resource's name, the pin's label, or the reminder's text. */
    public final String name;
    /** Pin colour key, see {@link nurgling.widgets.timers.TimerIcons#pinColor}. */
    public final String icon;
    /** Epoch ms on this client's clock. The sync service converts to and from the database clock. */
    public final long startedAt;
    public final long durationMs;
    /** 0 = one-shot. Otherwise each cycle after the first lasts this long. */
    public final long repeatMs;
    public final String setBy;
    /** Shared timers go to the database; private ones only ever live in the local file. */
    public final boolean shared;
    /** Database row version last seen; 0 for a timer that has never been in the database. */
    public final int version;
    public final long legacySeg;
    public final Coord legacyTc;
    /** The To-Do task a {@link Kind#TASK} timer is the deadline of; 0 for every other kind. */
    public final int taskId;
    /** Character the task is assigned to; empty for "anyone" and for every other kind. */
    public final String assignee;

    public Timer(String id, Kind kind, long gridId, int ox, int oy, String resType, String name, String icon, long startedAt, long durationMs, long repeatMs, String setBy, boolean shared,
                 int version, long legacySeg, Coord legacyTc) {
        this(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs, setBy, shared, version,
            legacySeg, legacyTc, 0, "");
    }

    public Timer(String id, Kind kind, long gridId, int ox, int oy, String resType, String name, String icon, long startedAt, long durationMs, long repeatMs, String setBy, boolean shared,
                 int version, long legacySeg, Coord legacyTc, int taskId, String assignee) {
        this.id = id;
        this.kind = kind;
        this.gridId = gridId;
        this.ox = ox;
        this.oy = oy;
        this.resType = resType;
        this.name = (name == null) ? "" : name;
        this.icon = icon;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.repeatMs = repeatMs;
        this.setBy = setBy;
        this.shared = shared;
        this.version = version;
        this.legacySeg = legacySeg;
        this.legacyTc = legacyTc;
        this.taskId = taskId;
        this.assignee = (assignee == null) ? "" : assignee;
    }

    /** Resource timers are keyed on the spot, so two villagers timing one tar pit end up on one row. */
    public static String resourceId(String genus, long gridId, int ox, int oy, String resType) {
        String key = "timer|" + genus + "|" + gridId + "|" + ox + "|" + oy + "|" + resType;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String randomId() {
        return UUID.randomUUID().toString();
    }

    public boolean hasLocation() {return gridId != 0;}

    public long readyAt() {return startedAt + durationMs;}

    public long remaining(long now) {return Math.max(0, readyAt() - now);}

    public boolean isReady(long now) {return now >= readyAt();}

    /** 0 at start, 1 when ready. */
    public double progress(long now) {
        if(durationMs <= 0)
            return 1;
        return Math.min(1, Math.max(0, (now - startedAt) / (double) durationMs));
    }

    /** Same timer, started again now with the given length. */
    public Timer restarted(long now, long duration) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, now, duration, repeatMs, setBy,
            shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    /**
     * The next cycle of a repeating timer. Cycles stay on their own schedule - a daily reminder keeps its
     * time of day however late it was dismissed - so this skips whole cycles until the next one is ahead.
     */
    public Timer nextCycle(long now) {
        long start = readyAt();
        long ready = start + repeatMs;
        if(ready <= now) {
            long skip = (now - ready) / repeatMs + 1;
            start += skip * repeatMs;
        }
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, start, repeatMs, repeatMs, setBy,
            shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    public Timer withLocation(long gridId, int ox, int oy) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs,
            setBy, shared, version, 0, null, taskId, assignee);
    }

    public Timer withVersion(int version) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs,
            setBy, shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    public Timer withDetails(String name, String icon, long repeatMs, boolean shared) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs,
            setBy, shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    /** A task deadline following its task: who it is assigned to now, and its current title. */
    public Timer withTask(String assignee, String name) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs,
            setBy, shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    /** A task deadline's id: one per task per world, the same on every client. */
    public static String taskTimerId(String genus, int taskId) {
        String key = "task|" + genus + "|" + taskId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public Timer withId(String id) {
        return new Timer(id, kind, gridId, ox, oy, resType, name, icon, startedAt, durationMs, repeatMs,
            setBy, shared, version, legacySeg, legacyTc, taskId, assignee);
    }

    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        j.put("id", id);
        j.put("kind", kind.key());
        if(gridId != 0) {
            j.put("gridId", gridId);
            j.put("ox", ox);
            j.put("oy", oy);
        }
        if(resType != null)
            j.put("resType", resType);
        j.put("name", name);
        if(icon != null)
            j.put("icon", icon);
        j.put("startedAt", startedAt);
        j.put("durationMs", durationMs);
        if(repeatMs != 0)
            j.put("repeatMs", repeatMs);
        if(setBy != null)
            j.put("setBy", setBy);
        j.put("shared", shared);
        if(version != 0)
            j.put("version", version);
        if(legacyTc != null) {
            j.put("legacySeg", legacySeg);
            j.put("legacyX", legacyTc.x);
            j.put("legacyY", legacyTc.y);
        }
        if(taskId != 0) {
            j.put("taskId", taskId);
            j.put("assignee", assignee);
        }
        return j;
    }

    public static Timer fromJson(JSONObject j) {
        Coord legacy = j.has("legacyX") ? new Coord(j.getInt("legacyX"), j.getInt("legacyY")) : null;
        return new Timer(
            j.getString("id"),
            Kind.of(j.optString("kind", "pin")),
            j.optLong("gridId", 0),
            j.optInt("ox", 0),
            j.optInt("oy", 0),
            j.has("resType") ? j.getString("resType") : null,
            j.optString("name", ""),
            j.has("icon") ? j.getString("icon") : null,
            j.getLong("startedAt"),
            j.getLong("durationMs"),
            j.optLong("repeatMs", 0),
            j.has("setBy") ? j.getString("setBy") : null,
            j.optBoolean("shared", false),
            j.optInt("version", 0),
            j.optLong("legacySeg", 0),
            legacy,
            j.optInt("taskId", 0),
            j.optString("assignee", ""));
    }

    /**
     * A record from the old segment-keyed timer file. It keeps its segment tile until the map file can
     * turn that into a grid id; see {@link TimerStore#convertLegacy}.
     */
    public static Timer fromLegacyJson(JSONObject j) {
        Coord tc = new Coord(j.getInt("tileX"), j.getInt("tileY"));
        return new Timer(
            j.getString("resourceId"),
            Kind.RESOURCE,
            0, 0, 0,
            j.getString("resourceType"),
            j.optString("description", j.optString("resourceName", "")),
            null,
            j.getLong("startTime"),
            j.getLong("duration"),
            0, null, false, 0,
            j.getLong("segmentId"),
            tc);
    }

    /** Offset of a tile inside its grid. */
    public static Coord gridOffset(Coord tc) {
        return tc.mod(MCache.cmaps);
    }
}
