package nurgling.sessions.squad;

import java.util.ArrayList;
import java.util.List;

/**
 * A squad milestone trip. A post has one spot to travel from, and a traveller stands on it through the loading and its
 * whole road preview, so the squad travels one at a time: the scout (the character on screen) goes first and the
 * player decides in its preview; when it arrives, the others go in turn and confirm their own previews.
 *
 * Shared by the members' squad threads; every method is synchronized and cheap.
 */
public final class RoadTrip {
    /** What a member should do now. */
    public enum Turn { WAIT, GO, STOP }

    /** A trip is dropped after this long, whatever state it is in, so no member waits forever. */
    private static final long EXPIRE_MS = 10 * 60 * 1000;

    public final long seq;
    public final String road;
    public final int n;
    public final String scout;
    private final List<String> queue;
    private final long started = System.currentTimeMillis();
    /** Null while the scout decides; then whether it went. */
    private Boolean went;

    RoadTrip(long seq, String road, int n, String scout, List<String> queue) {
        this.seq = seq;
        this.road = road;
        this.n = n;
        this.scout = scout;
        this.queue = new ArrayList<>(queue);
    }

    /** The scout arrived at the road's end (true) or stayed (false). */
    public synchronized void scouted(boolean arrived) {
        if (went == null)
            went = arrived;
    }

    /** This member is done, travelled or not; the next one may go. A scout leaving undecided means: stay. */
    public synchronized void leave(String id) {
        if (id.equals(scout))
            scouted(false);
        else
            queue.remove(id);
    }

    public synchronized Turn turn(String id) {
        if (Boolean.FALSE.equals(went) || !queue.contains(id) || System.currentTimeMillis() - started > EXPIRE_MS)
            return Turn.STOP;
        if (went == null)
            return Turn.WAIT;
        return queue.get(0).equals(id) ? Turn.GO : Turn.WAIT;
    }
}
