package nurgling.sessions.commander;

import nurgling.sessions.SessionContext;
import nurgling.sessions.squad.MemberReport;

import java.util.*;

/**
 * One connected piece of the explored map holding at least one character: a map segment. Inside a theater every
 * position is real and the camera pans freely; between theaters the camera jumps, because pieces of map that don't
 * touch have no known position relative to each other. Two theaters become one when the map file joins their segments.
 */
public final class Theater {
    public final long seg;
    /** Its characters, in session-bar order. */
    public final List<SessionContext> members;

    Theater(long seg, List<SessionContext> members) {
        this.seg = seg;
        this.members = Collections.unmodifiableList(members);
    }

    /** A short name: its characters' names. */
    public String label() {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (SessionContext c : members) {
            MemberReport r = c.squadReport;
            if (r == null)
                continue;
            if (shown == 2) {
                b.append(" +").append(members.size() - 2);
                break;
            }
            if (shown > 0)
                b.append(", ");
            b.append(clip(r.name, 12));
            shown++;
        }
        return b.toString();
    }

    static String clip(String s, int n) {
        return (s == null) ? "" : (s.length() <= n) ? s : s.substring(0, n - 1) + "…";
    }

    /** Group these sessions by the segment their own reports place them on; unplaced ones are left out. */
    static List<Theater> of(List<SessionContext> sessions) {
        Map<Long, List<SessionContext>> by = new LinkedHashMap<>();
        for (SessionContext c : sessions) {
            MemberReport r = c.squadReport;
            if (r == null || r.pos == null || r.pos.segPos == null)
                continue;
            by.computeIfAbsent(r.pos.segId, k -> new ArrayList<>()).add(c);
        }
        List<Theater> out = new ArrayList<>();
        for (Map.Entry<Long, List<SessionContext>> e : by.entrySet())
            out.add(new Theater(e.getKey(), e.getValue()));
        return out;
    }

    /** Same segment and same characters. */
    boolean same(Theater o) {
        if (o == null || o.seg != seg || o.members.size() != members.size())
            return false;
        for (int i = 0; i < members.size(); i++)
            if (members.get(i) != o.members.get(i))
                return false;
        return true;
    }
}
