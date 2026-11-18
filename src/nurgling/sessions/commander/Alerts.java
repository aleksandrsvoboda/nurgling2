package nurgling.sessions.commander;

import haven.error.FileLogger;
import nurgling.i18n.L10n;
import nurgling.sessions.SessionContext;
import nurgling.sessions.squad.MemberReport;

import java.util.*;

/**
 * What the characters need attention for, read from their reports the way an RTS raises "under attack" and "idle
 * worker": combat, damage, a bot that finished, an order that failed, low stamina or energy, a full inventory, a
 * dropped connection. Each alert remembers where it happened, so Space (or a click in the feed) jumps the camera there.
 * Ticked on the viewpoint's UI thread; the feed reads a copy.
 */
public final class Alerts {
    public enum Kind { COMBAT, DAMAGE, BOT_DONE, STUCK, STAMINA, ENERGY, FULL, LOST }

    /** One per character and kind at most this often. */
    private static final long COOLDOWN_MS = 20000;
    private static final int KEEP = 30;
    private static final long TICK_MS = 200;
    private static final double LOW = 0.25, DAMAGE = 0.02;

    public static final class Alert {
        public final Kind kind;
        public final String sessionId;
        public final String text;
        public final Focus where;
        public final long time;

        Alert(Kind kind, String sessionId, String text, Focus where, long time) {
            this.kind = kind;
            this.sessionId = sessionId;
            this.text = text;
            this.where = where;
            this.time = time;
        }

        /** Combat and damage are urgent (red); the rest are notices (orange). */
        public boolean urgent() {
            return kind == Kind.COMBAT || kind == Kind.DAMAGE || kind == Kind.LOST;
        }
    }

    private static final class Last {
        MemberReport r;
        boolean connected = true;
        final Map<Kind, Long> raised = new EnumMap<>(Kind.class);
    }

    private final ArrayDeque<Alert> list = new ArrayDeque<>();
    private final Map<String, Last> last = new HashMap<>();
    private long lastTick;
    /** How far back Space has gone; reset by a new alert. */
    private int cycle = 0;
    private volatile int version = 0;

    void tick(Collection<SessionContext> sessions) {
        long now = System.currentTimeMillis();
        if (now - lastTick < TICK_MS)
            return;
        lastTick = now;
        Set<String> alive = new HashSet<>();
        for (SessionContext c : sessions) {
            alive.add(c.sessionId);
            Last l = last.computeIfAbsent(c.sessionId, k -> new Last());
            MemberReport r = c.squadReport;
            boolean conn = c.isConnected();
            if (l.connected && !conn && l.r != null)
                raise(l, Kind.LOST, c, l.r, now, L10n.get("commander.alert.lost", l.r.name));
            l.connected = conn;
            if (r == null || !conn)
                continue;
            MemberReport p = l.r;
            if (p != null && p.time != r.time)
                compare(l, c, p, r, now);
            l.r = r;
        }
        last.keySet().retainAll(alive);
    }

    private void compare(Last l, SessionContext c, MemberReport p, MemberReport r, long now) {
        if (r.status == MemberReport.Status.COMBAT && p.status != MemberReport.Status.COMBAT)
            raise(l, Kind.COMBAT, c, r, now, L10n.get("commander.alert.combat", r.name));
        else if (r.hp >= 0 && p.hp >= 0 && p.hp - r.hp >= DAMAGE && r.status != MemberReport.Status.COMBAT)
            raise(l, Kind.DAMAGE, c, r, now, L10n.get("commander.alert.damage", r.name, Math.round(r.hp * 100)));
        if (p.status == MemberReport.Status.BOT && r.status != MemberReport.Status.BOT && r.status != MemberReport.Status.COMBAT)
            raise(l, Kind.BOT_DONE, c, r, now, L10n.get("commander.alert.bot", r.name, (p.detail == null) ? "" : p.detail));
        if ((r.status == MemberReport.Status.UNREACHABLE || r.status == MemberReport.Status.SKIPPED) && r.status != p.status)
            raise(l, Kind.STUCK, c, r, now, L10n.get("commander.alert.stuck", r.name, (r.detail == null) ? "" : r.detail));
        if (r.stamina >= 0 && r.stamina < LOW && p.stamina >= LOW)
            raise(l, Kind.STAMINA, c, r, now, L10n.get("commander.alert.stamina", r.name));
        if (r.energy >= 0 && r.energy < LOW && p.energy >= LOW)
            raise(l, Kind.ENERGY, c, r, now, L10n.get("commander.alert.energy", r.name));
        if (r.invTotal > 0 && r.invFree == 0 && p.invFree > 0)
            raise(l, Kind.FULL, c, r, now, L10n.get("commander.alert.full", r.name));
    }

    private void raise(Last l, Kind k, SessionContext c, MemberReport r, long now, String text) {
        Long at = l.raised.get(k);
        if (at != null && now - at < COOLDOWN_MS)
            return;
        l.raised.put(k, now);
        Alert a = new Alert(k, c.sessionId, text, Focus.of(r.pos), now);
        synchronized (list) {
            list.addFirst(a);
            while (list.size() > KEEP)
                list.removeLast();
        }
        cycle = 0;
        version++;
        FileLogger.log("[Commander alert] " + k + " " + text);
    }

    /** The alerts, newest first. */
    public List<Alert> all() {
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public int version() {
        return version;
    }

    /** The next alert for Space: the newest first, then older ones on each press. Null when there are none. */
    Alert nextForJump() {
        List<Alert> a = all();
        if (a.isEmpty())
            return null;
        Alert ret = a.get(Math.min(cycle, a.size() - 1));
        cycle = (cycle + 1) % a.size();
        return ret;
    }

    /** Forget what the characters were like: reports seen before the mode went on must not raise alerts now. */
    void restart() {
        last.clear();
    }

    public void clear() {
        synchronized (list) {
            list.clear();
        }
        version++;
    }
}
