package nurgling.timers;

import haven.Coord;
import haven.MCache;
import haven.MapFile;
import nurgling.tools.NFileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every timer of one world, shared by all sessions logged into that world.
 *
 * <p>One store per genus rather than one per session: two sessions on the same world used to each load
 * their own copy of the same file, and whichever saved last silently dropped the timers the other had
 * added. {@link nurgling.sessions.SessionManager#timerStore} hands out the shared instance.
 *
 * <p>The file always holds everything - private timers, the last copy of the shared ones, and this
 * client's {@link Local} state - so the list works offline and survives a restart. When a database is
 * connected, {@link nurgling.db.service.TimerSyncService} is the authority for shared timers: it pushes
 * the pending local edits and applies the rows other villagers changed.
 */
public class TimerStore {
    /** Private ready timers the player dismissed disappear this long after they became ready. */
    public static final long STALE_MS = 7L * 24 * 60 * 60 * 1000;
    /** Start times this close are the same start, seen through two measurements of the database clock. */
    private static final long SAME_START_MS = 5000;

    /**
     * What this client did about a timer. Never synced: dismissing or muting a village timer here must
     * leave it live for everybody else.
     *
     * <p>{@link #ackedStart} and {@link #notifiedStart} hold the {@link Timer#startedAt} of the cycle they
     * refer to, so a timer somebody restarts is automatically "not seen yet" again.
     */
    public static final class Local {
        public static final Local MINE = new Local(-1, -1, 0, true, -1);
        public static final Local NOT_MINE = new Local(-1, -1, 0, false, -1);

        public final long ackedStart;
        public final long notifiedStart;
        public final long snoozeUntil;
        /** Whether this client wants banners and sounds for the timer. */
        public final boolean notify;
        /** The cycle a "due soon" heads-up was shown for (task deadlines only). */
        public final long soonStart;

        public Local(long ackedStart, long notifiedStart, long snoozeUntil, boolean notify, long soonStart) {
            this.ackedStart = ackedStart;
            this.notifiedStart = notifiedStart;
            this.snoozeUntil = snoozeUntil;
            this.notify = notify;
            this.soonStart = soonStart;
        }

        JSONObject toJson() {
            JSONObject j = new JSONObject();
            j.put("ackedStart", ackedStart);
            j.put("notifiedStart", notifiedStart);
            j.put("snoozeUntil", snoozeUntil);
            j.put("notify", notify);
            j.put("soonStart", soonStart);
            return j;
        }

        static Local fromJson(JSONObject j) {
            return new Local(j.optLong("ackedStart", -1), j.optLong("notifiedStart", -1),
                j.optLong("snoozeUntil", 0), j.optBoolean("notify", true), j.optLong("soonStart", -1));
        }
    }

    private final String genus;
    private final String path;

    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final Map<String, Local> local = new HashMap<>();
    private final Map<String, Long> lastDurations = new HashMap<>();
    /** Shared timers whose current content the database has not confirmed, with the edit generation. */
    private final Map<String, Long> pendingUpsert = new LinkedHashMap<>();
    /** Ids to tombstone in the database. */
    private final Map<String, Long> pendingDelete = new LinkedHashMap<>();
    private long generation = 0;
    /**
     * Task events already shown on this client ("assigned|42|John", "done|42|1759…"), shared by every
     * session on the world so one assignment raises one banner. Kept in the file so assignments made
     * while the player was offline are announced at login, and only once.
     */
    private final java.util.Set<String> announced = new java.util.HashSet<>();
    private boolean announceSeeded = false;
    private final Object saveLock = new Object();

    private volatile List<Timer> snapshot = Collections.emptyList();
    private volatile long revision = 0;

    public TimerStore(String genus, String path) {
        this.genus = genus;
        this.path = path;
        load();
    }

    public String genus() {return genus;}

    /** Bumped on every change; widgets compare it to decide whether to rebuild. */
    public long revision() {return revision;}

    /** Every timer, in no particular order. Safe to hold: the list and the timers never change. */
    public List<Timer> timers() {return snapshot;}

    public synchronized Timer get(String id) {
        return timers.get(id);
    }

    public synchronized Local local(String id) {
        Local l = local.get(id);
        return (l == null) ? Local.MINE : l;
    }

    public synchronized Timer findResource(long gridId, int ox, int oy, String resType) {
        return timers.get(Timer.resourceId(genus, gridId, ox, oy, resType));
    }

    // -------------------- Edits from this client --------------------

    /** Create a timer, or replace one with the same id. */
    public void put(Timer t) {
        synchronized(this) {
            Timer old = timers.get(t.id);
            if(old != null)
                t = t.withVersion(old.version);
            timers.put(t.id, t);
            if(!local.containsKey(t.id))
                local.put(t.id, Local.MINE);
            if(t.shared) {
                pendingUpsert.put(t.id, ++generation);
                pendingDelete.remove(t.id);
            } else if(old != null && old.shared && old.version > 0) {
                // Made private: take it out of the village's list.
                pendingUpsert.remove(t.id);
                pendingDelete.put(t.id, ++generation);
            }
        }
        changed();
    }

    /** Delete for everyone (for a shared timer) - the trash button, not Dismiss. */
    public void remove(String id) {
        synchronized(this) {
            Timer t = timers.remove(id);
            local.remove(id);
            if(t == null)
                return;
            pendingUpsert.remove(id);
            if(t.shared && t.version > 0)
                pendingDelete.put(id, ++generation);
        }
        changed();
    }

    /** Start the same timer again with its last length: "I just collected it". */
    public void restart(String id, long now) {
        Timer t = get(id);
        if(t == null)
            return;
        put(t.restarted(now, t.durationMs));
    }

    /**
     * Hide a ready timer for this player only. A repeating timer moves on to its next cycle instead, which
     * is shared - the reminder is due again for everyone.
     */
    public void dismiss(String id, long now) {
        Timer t = get(id);
        if(t == null)
            return;
        if(t.repeatMs > 0) {
            put(t.nextCycle(now));
            return;
        }
        synchronized(this) {
            Local l = local(id);
            local.put(id, new Local(t.startedAt, l.notifiedStart, 0, l.notify, l.soonStart));
        }
        changed();
    }

    public void snooze(String id, long until) {
        synchronized(this) {
            if(!timers.containsKey(id))
                return;
            Local l = local(id);
            local.put(id, new Local(l.ackedStart, l.notifiedStart, until, l.notify, l.soonStart));
        }
        changed();
    }

    public void setNotify(String id, boolean notify) {
        synchronized(this) {
            if(!timers.containsKey(id))
                return;
            Local l = local(id);
            local.put(id, new Local(l.ackedStart, l.notifiedStart, l.snoozeUntil, notify, l.soonStart));
        }
        changed();
    }

    /** Record that a banner went up for the current cycle, so it is not shown again. */
    public void markNotified(Collection<Timer> shown) {
        synchronized(this) {
            for(Timer t : shown) {
                Local l = local(t.id);
                local.put(t.id, new Local(l.ackedStart, t.startedAt, 0, l.notify, l.soonStart));
            }
        }
        changed();
    }

    /** Record that the "due soon" heads-up went up for the current cycle. */
    public void markSoon(Collection<Timer> shown) {
        synchronized(this) {
            for(Timer t : shown) {
                Local l = local(t.id);
                local.put(t.id, new Local(l.ackedStart, l.notifiedStart, l.snoozeUntil, l.notify, t.startedAt));
            }
        }
        changed();
    }

    /** Task deadlines within {@code lead} of being due that have not had their heads-up yet. */
    public synchronized List<Timer> dueSoon(long now, long lead) {
        List<Timer> out = new ArrayList<>();
        if(lead <= 0)
            return out;
        for(Timer t : timers.values()) {
            if(t.kind != Timer.Kind.TASK || t.isReady(now) || t.remaining(now) > lead || !wantsNotice(t))
                continue;
            Local l = local(t.id);
            if(l.notify && l.soonStart != t.startedAt && l.ackedStart != t.startedAt)
                out.add(t);
        }
        return out;
    }

    /**
     * Whether this client should be told about a timer at all. Everything but a task deadline: yes. A task
     * deadline goes to its assignee only, or to its creator while nobody has taken it.
     */
    public static boolean wantsNotice(Timer t) {
        if(t.kind != Timer.Kind.TASK)
            return true;
        return MyCharacters.contains(t.assignee.isEmpty() ? t.setBy : t.assignee);
    }

    /**
     * Timers that should raise a banner now: ready, wanted, and either never announced for this cycle or
     * back from a snooze.
     */
    public synchronized List<Timer> due(long now) {
        List<Timer> out = new ArrayList<>();
        for(Timer t : timers.values()) {
            if(!t.isReady(now) || !wantsNotice(t))
                continue;
            Local l = local(t.id);
            if(!l.notify || l.ackedStart == t.startedAt)
                continue;
            boolean fresh = l.notifiedStart != t.startedAt;
            boolean snoozeOver = (l.snoozeUntil > 0) && (now >= l.snoozeUntil);
            if(fresh || snoozeOver)
                out.add(t);
        }
        return out;
    }

    public synchronized int unseenReadyCount(long now) {
        int n = 0;
        for(Timer t : timers.values()) {
            Local l = local(t.id);
            if(t.isReady(now) && wantsNotice(t) && l.ackedStart != t.startedAt && (l.snoozeUntil == 0 || now >= l.snoozeUntil))
                n++;
        }
        return n;
    }

    /** Whether any timer is due within the given time, for the minimap button's heads-up ring. */
    public boolean anyDueWithin(long now, long window) {
        for(Timer t : snapshot) {
            long r = t.readyAt() - now;
            if(r > 0 && r <= window && wantsNotice(t))
                return true;
        }
        return false;
    }

    /**
     * Compare the task events that are true now with the ones already shown, remember the current set,
     * and return the new ones. The very first call only records: a player updating to this version should
     * not get a banner for every task already assigned to them.
     */
    public List<String> newlyAnnounced(java.util.Set<String> current) {
        List<String> fresh = new ArrayList<>();
        synchronized(this) {
            if(announceSeeded) {
                for(String k : current) {
                    if(!announced.contains(k))
                        fresh.add(k);
                }
                if(fresh.isEmpty() && announced.equals(current))
                    return fresh;
            }
            announced.clear();
            announced.addAll(current);
            announceSeeded = true;
        }
        save();
        return fresh;
    }

    /** The last length used for this kind of timer; key is the resource type, "pin" or "reminder". */
    public synchronized long lastDuration(String key) {
        Long v = lastDurations.get(key);
        return (v == null) ? -1 : v;
    }

    public void rememberDuration(String key, long ms) {
        synchronized(this) {
            lastDurations.put(key, ms);
        }
        save();
    }

    /**
     * Whether a timer was set by one of this client's characters (or has no author, i.e. predates
     * sharing). Those notify by default and read as "you"; other villagers' are opt-in.
     */
    public static boolean isLocalCharacter(String setBy) {
        return setBy == null || MyCharacters.contains(setBy);
    }

    public static String durationKey(Timer.Kind kind, String resType) {
        return (kind == Timer.Kind.RESOURCE && resType != null) ? resType : kind.key();
    }

    // -------------------- Maintenance --------------------

    /**
     * Turn records from the old segment-keyed file into grid-keyed ones, once the map file knows their
     * segment. Returns whether any are still waiting.
     */
    public boolean convertLegacy(MapFile file) {
        List<Timer> waiting = new ArrayList<>();
        synchronized(this) {
            for(Timer t : timers.values()) {
                if(t.legacyTc != null)
                    waiting.add(t);
            }
        }
        if(waiting.isEmpty() || file == null)
            return !waiting.isEmpty();
        List<Timer> converted = new ArrayList<>();
        file.lock.readLock().lock();
        try {
            for(Timer t : waiting) {
                MapFile.Segment seg = file.segments.get(t.legacySeg);
                if(seg == null)
                    continue;
                Long gid = seg.map.get(t.legacyTc.div(MCache.cmaps));
                if(gid == null)
                    continue;
                Coord off = Timer.gridOffset(t.legacyTc);
                converted.add(t.withLocation(gid, off.x, off.y));
            }
        } finally {
            file.lock.readLock().unlock();
        }
        if(converted.isEmpty())
            return true;
        synchronized(this) {
            for(Timer t : converted) {
                timers.remove(t.id);
                Local l = local.remove(t.id);
                Timer keyed = t.withId(Timer.resourceId(genus, t.gridId, t.ox, t.oy, t.resType));
                timers.put(keyed.id, keyed);
                local.put(keyed.id, (l == null) ? Local.MINE : l);
            }
        }
        changed();
        return waiting.size() > converted.size();
    }

    /** Drop private one-shot timers the player dismissed a week ago. Shared ones are only hidden. */
    public void prune(long now) {
        boolean any = false;
        synchronized(this) {
            for(java.util.Iterator<Timer> it = timers.values().iterator(); it.hasNext(); ) {
                Timer t = it.next();
                if(t.shared || t.repeatMs > 0 || !isStale(t, now))
                    continue;
                it.remove();
                local.remove(t.id);
                any = true;
            }
        }
        if(any)
            changed();
    }

    /** A dismissed one-shot timer that has been ready for over a week; the panel no longer lists it. */
    public synchronized boolean isStale(Timer t, long now) {
        Local l = local(t.id);
        return (t.repeatMs == 0) && (l.ackedStart == t.startedAt) && (now - t.readyAt() > STALE_MS);
    }

    // -------------------- Database sync --------------------

    /** A pending database write with the edit generation it belongs to. */
    public static final class Pending<T> {
        public final T item;
        public final long gen;

        Pending(T item, long gen) {
            this.item = item;
            this.gen = gen;
        }
    }

    public synchronized List<Pending<Timer>> pendingUpserts() {
        List<Pending<Timer>> out = new ArrayList<>();
        for(Map.Entry<String, Long> e : pendingUpsert.entrySet()) {
            Timer t = timers.get(e.getKey());
            boolean located = (t != null) && (t.kind == Timer.Kind.RESOURCE || t.kind == Timer.Kind.PIN);
            if(t != null && t.hasLocation() == located && t.legacyTc == null)
                out.add(new Pending<>(t, e.getValue()));
        }
        return out;
    }

    public synchronized List<Pending<String>> pendingDeletes() {
        List<Pending<String>> out = new ArrayList<>();
        for(Map.Entry<String, Long> e : pendingDelete.entrySet())
            out.add(new Pending<>(e.getKey(), e.getValue()));
        return out;
    }

    /** The database took this edit. A newer edit made meanwhile stays pending. */
    public void confirmUpsert(String id, long gen, int version) {
        synchronized(this) {
            Long cur = pendingUpsert.get(id);
            if(cur != null && cur == gen)
                pendingUpsert.remove(id);
            Timer t = timers.get(id);
            if(t != null)
                timers.put(id, t.withVersion(version));
            else if(!pendingUpsert.containsKey(id))
                pendingDelete.put(id, ++generation);   // removed while the upload was on its way
        }
        changed();
    }

    public void confirmDelete(String id, long gen) {
        synchronized(this) {
            Long cur = pendingDelete.get(id);
            if(cur != null && cur == gen)
                pendingDelete.remove(id);
        }
        save();
    }

    /**
     * Apply what the database says.
     *
     * @param live      rows that exist, with their versions
     * @param tombstoned ids somebody removed
     * @param full      whether {@code live} is every row of this world (a bulk load); a shared timer
     *                  missing from a full load was purged if it had been synced, or never uploaded if not
     * @param isMine    whether a new row was set by one of this client's characters; those notify by default
     */
    public void applyRemote(List<Timer> live, Collection<String> tombstoned, boolean full, Predicate<String> isMine) {
        boolean any = false;
        synchronized(this) {
            Set<String> seen = new java.util.HashSet<>();
            for(Timer row : live) {
                seen.add(row.id);
                if(pendingUpsert.containsKey(row.id) || pendingDelete.containsKey(row.id))
                    continue;   // our own edit is on its way; it wins
                Timer old = timers.get(row.id);
                if(old == null) {
                    // A task deadline is filtered by wantsNotice instead: it follows the assignee as they change.
                    boolean mine = row.kind == Timer.Kind.TASK || isMine.test(row.setBy);
                    local.put(row.id, mine ? Local.MINE : Local.NOT_MINE);
                } else if(!old.shared) {
                    continue;   // made private here; the database copy is being withdrawn
                } else {
                    /* The same start seen through a slightly different clock offset. Keep ours: the
                     * dismiss and notify state are keyed on the exact start time. */
                    if(Math.abs(old.startedAt - row.startedAt) <= SAME_START_MS)
                        row = row.restarted(old.startedAt, row.durationMs);
                }
                if(old != null && sameContent(old, row)) {
                    if(old.version != row.version) {
                        timers.put(row.id, row);
                        any = true;
                    }
                    continue;
                }
                timers.put(row.id, row);
                any = true;
            }
            for(String id : tombstoned) {
                if(pendingUpsert.containsKey(id))
                    continue;
                pendingDelete.remove(id);
                Timer mine = timers.get(id);
                if(mine != null && !mine.shared)
                    continue;   // our own withdrawal of a timer we made private
                if(timers.remove(id) != null) {
                    local.remove(id);
                    any = true;
                }
            }
            if(full) {
                for(java.util.Iterator<Timer> it = timers.values().iterator(); it.hasNext(); ) {
                    Timer t = it.next();
                    if(!t.shared || seen.contains(t.id) || tombstoned.contains(t.id) || pendingUpsert.containsKey(t.id))
                        continue;
                    if(t.version > 0) {
                        it.remove();
                        local.remove(t.id);
                    } else {
                        pendingUpsert.put(t.id, ++generation);
                    }
                    any = true;
                }
            }
        }
        if(any)
            changed();
    }

    private static boolean sameContent(Timer a, Timer b) {
        return a.startedAt == b.startedAt && a.durationMs == b.durationMs && a.repeatMs == b.repeatMs
            && a.gridId == b.gridId && a.ox == b.ox && a.oy == b.oy && a.shared == b.shared
            && a.name.equals(b.name) && java.util.Objects.equals(a.icon, b.icon)
            && a.taskId == b.taskId && a.assignee.equals(b.assignee);
    }

    // -------------------- Persistence --------------------

    private void changed() {
        synchronized(this) {
            snapshot = Collections.unmodifiableList(new ArrayList<>(timers.values()));
            revision++;
        }
        save();
    }

    private void load() {
        String content = NFileUtils.readWithBackupFallback(path);
        if(content == null || content.isEmpty())
            return;
        synchronized(this) {
            try {
                JSONObject main = new JSONObject(content);
                JSONArray arr = main.optJSONArray("timers");
                if(arr == null)
                    return;
                if(main.optInt("version", 1) < 2) {
                    backupLegacyFile();
                    /* The old format dropped timers that ran out while nobody was logged in. Keep them
                     * all: they are exactly the ones the "ready while you were away" banner is for. */
                    for(int i = 0; i < arr.length(); i++) {
                        Timer t = Timer.fromLegacyJson(arr.getJSONObject(i));
                        timers.put(t.id, t);
                        local.put(t.id, Local.MINE);
                    }
                } else {
                    for(int i = 0; i < arr.length(); i++) {
                        JSONObject j = arr.getJSONObject(i);
                        Timer t = Timer.fromJson(j);
                        timers.put(t.id, t);
                        JSONObject lj = j.optJSONObject("local");
                        local.put(t.id, (lj == null) ? Local.MINE : Local.fromJson(lj));
                    }
                    JSONObject ld = main.optJSONObject("lastDurations");
                    if(ld != null) {
                        for(String k : ld.keySet())
                            lastDurations.put(k, ld.getLong(k));
                    }
                    JSONArray pu = main.optJSONArray("pendingUpsert");
                    if(pu != null) {
                        for(int i = 0; i < pu.length(); i++)
                            pendingUpsert.put(pu.getString(i), ++generation);
                    }
                    JSONArray an = main.optJSONArray("announced");
                    if(an != null) {
                        for(int i = 0; i < an.length(); i++)
                            announced.add(an.getString(i));
                    }
                    announceSeeded = main.optBoolean("announceSeeded", false);
                    JSONArray pd = main.optJSONArray("pendingDelete");
                    if(pd != null) {
                        for(int i = 0; i < pd.length(); i++)
                            pendingDelete.put(pd.getString(i), ++generation);
                    }
                }
            } catch(JSONException e) {
                System.err.println("[Timers] could not read " + path + ": " + e.getMessage());
            }
            snapshot = Collections.unmodifiableList(new ArrayList<>(timers.values()));
            revision++;
        }
    }

    private void backupLegacyFile() {
        try {
            Path src = Paths.get(path);
            Path bak = Paths.get(path + ".v1.bak");
            if(Files.exists(src) && !Files.exists(bak))
                Files.copy(src, bak, StandardCopyOption.COPY_ATTRIBUTES);
        } catch(IOException e) {
            System.err.println("[Timers] could not back up the old timer file: " + e.getMessage());
        }
    }

    /**
     * Serialise and write in one step under its own lock, so two threads saving at once cannot write
     * their snapshots in the wrong order. Never taken while holding the store lock.
     */
    private void save() {
        synchronized(saveLock) {
            try {
                NFileUtils.writeAtomically(path, serialize());
            } catch(IOException e) {
                System.err.println("[Timers] could not save " + path + ": " + e.getMessage());
            }
        }
    }

    private synchronized String serialize() {
        JSONObject main = new JSONObject();
        main.put("version", 2);
        JSONArray arr = new JSONArray();
        for(Timer t : timers.values()) {
            JSONObject j = t.toJson();
            j.put("local", local(t.id).toJson());
            arr.put(j);
        }
        main.put("timers", arr);
        JSONObject ld = new JSONObject();
        for(Map.Entry<String, Long> e : lastDurations.entrySet())
            ld.put(e.getKey(), e.getValue());
        main.put("lastDurations", ld);
        main.put("pendingUpsert", new JSONArray(pendingUpsert.keySet()));
        main.put("pendingDelete", new JSONArray(pendingDelete.keySet()));
        main.put("announced", new JSONArray(announced));
        main.put("announceSeeded", announceSeeded);
        return main.toString(2);
    }
}
