package nurgling.discovery;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pairs items arriving in the player's possession with LP rises to tell discoveries apart.
 * <p>
 * An item and an LP rise within {@link #WINDOW_MS} of each other make a discovery, however the
 * item arrived. Without an LP rise, an item the player's own action produced was already
 * discovered (KNOWN); a received one (trade, container, login) tells nothing (HELD). LP from a
 * curio finishing in the same window is subtracted first. An arrival paired 1:1 with an item of
 * the same resource leaving the player's own inventories is a move and is ignored; paired with
 * one leaving a foreign inventory, it is received.
 * <p>
 * Plain Java with the clock passed in, so it is unit tested without the client.
 */
public final class DiscoveryCorrelator {
    public static final long WINDOW_MS = 1500;
    /** An arrival whose name never resolves within this long is dropped. */
    public static final long NAME_TIMEOUT_MS = 30_000;
    private static final long KEEP_MS = 60_000;

    /** The arriving item, read lazily: its resource and name may still be loading at arrival. */
    public interface Item {
        /** Null while the resource is loading. */
        String res();
        /** Null while the name is unresolved. */
        String name();
    }

    public enum Kind { DISCOVERED, KNOWN, HELD, UNATTRIBUTED }

    public static final class Verdict {
        public final Kind kind;
        public final Item item;
        public final String name, res, src;
        public final int lp;
        public final boolean shared;
        public final long at;

        Verdict(Kind kind, Item item, String name, String res, String src, int lp, boolean shared, long at) {
            this.kind = kind; this.item = item; this.name = name; this.res = res;
            this.src = src; this.lp = lp; this.shared = shared; this.at = at;
        }

        @Override
        public String toString() {
            return kind + (name != null ? " " + name : "") + (lp != 0 ? " +" + lp + " LP" : "") + (shared ? " (shared)" : "");
        }
    }

    private static final class Arrival {
        final Item item;
        final long t;
        final boolean produced;
        final String src;
        boolean moved, received, consumed;

        Arrival(Item item, long t, boolean produced, String src) {
            this.item = item; this.t = t; this.produced = produced; this.src = src;
        }
    }

    private static final class Departure {
        final String res;
        final long t;
        final boolean owned;
        boolean used;

        Departure(String res, long t, boolean owned) {
            this.res = res; this.t = t; this.owned = owned;
        }
    }

    private static final class Rise {
        final int delta;
        final long t;

        Rise(int delta, long t) {
            this.delta = delta; this.t = t;
        }
    }

    private static final class Curio {
        /** Negative when the curio's LP could not be read. */
        final int exp;
        final long t;
        boolean used;

        Curio(int exp, long t) {
            this.exp = exp; this.t = t;
        }
    }

    private final List<Arrival> arrivals = new ArrayList<>();
    private final List<Departure> departures = new ArrayList<>();
    private final List<Rise> rises = new ArrayList<>();
    private final List<Curio> curios = new ArrayList<>();

    /** An item entered the player's possession. produced: the player's own action made it. */
    public synchronized void arrival(Item item, long t, boolean produced, String src) {
        arrivals.add(new Arrival(item, t, produced, src));
    }

    /** An item left an inventory; owned: one of the player's own. */
    public synchronized void departure(String res, long t, boolean owned) {
        if (res != null)
            departures.add(new Departure(res, t, owned));
    }

    /** LP rose by delta. Drops are not events: the caller resets its baseline. */
    public synchronized void lp(int delta, long t) {
        if (delta > 0)
            rises.add(new Rise(delta, t));
    }

    /** A curio left the study; exp is its LP, or negative when unknown. */
    public synchronized void curio(int exp, long t) {
        curios.add(new Curio(exp, t));
    }

    public synchronized boolean idle() {
        return arrivals.isEmpty() && rises.isEmpty();
    }

    /** Every verdict that can be given at now. done: names already counted as discovered. */
    public synchronized List<Verdict> poll(long now, Predicate<String> done) {
        List<Verdict> out = new ArrayList<>();
        pairDepartures();
        for (Iterator<Rise> it = rises.iterator(); it.hasNext(); ) {
            Rise rise = it.next();
            if (now < rise.t + WINDOW_MS)
                continue;
            List<Arrival> window = new ArrayList<>();
            boolean waiting = false;
            for (Arrival a : arrivals) {
                if (a.consumed || Math.abs(a.t - rise.t) > WINDOW_MS)
                    continue;
                if (a.item.name() == null && now - a.t < NAME_TIMEOUT_MS) {
                    waiting = true;
                    break;
                }
                window.add(a);
            }
            if (waiting)
                continue;
            it.remove();

            int remainder = rise.delta;
            for (Curio c : curios) {
                if (c.used || Math.abs(c.t - rise.t) > WINDOW_MS)
                    continue;
                c.used = true;
                remainder = c.exp < 0 ? 0 : remainder - c.exp;
            }
            if (remainder <= 0)
                continue;

            List<Arrival> candidates = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (Arrival a : window) {
                String name = a.item.name();
                if (a.moved || name == null || done.test(name))
                    continue;
                candidates.add(a);
                names.add(name);
            }
            if (names.isEmpty()) {
                out.add(new Verdict(Kind.UNATTRIBUTED, null, null, null, null, remainder, false, rise.t));
                continue;
            }
            Set<String> given = new LinkedHashSet<>();
            for (Arrival a : candidates) {
                a.consumed = true;
                String name = a.item.name();
                if (given.add(name))
                    out.add(new Verdict(Kind.DISCOVERED, a.item, name, a.item.res(), a.src, remainder, names.size() > 1, a.t));
            }
        }

        for (Iterator<Arrival> it = arrivals.iterator(); it.hasNext(); ) {
            Arrival a = it.next();
            if (a.consumed) {
                it.remove();
                continue;
            }
            if (now < a.t + WINDOW_MS || pendingRiseNear(a.t))
                continue;
            String name = a.item.name();
            if (name == null) {
                if (now - a.t >= NAME_TIMEOUT_MS)
                    it.remove();
                continue;
            }
            it.remove();
            if (a.moved)
                continue;
            boolean produced = a.produced && !a.received;
            out.add(new Verdict(produced ? Kind.KNOWN : Kind.HELD, a.item, name, a.item.res(), a.src, 0, false, a.t));
        }

        departures.removeIf(d -> d.used || now - d.t > KEEP_MS);
        curios.removeIf(c -> c.used || now - c.t > KEEP_MS);
        return out;
    }

    private boolean pendingRiseNear(long t) {
        for (Rise r : rises)
            if (Math.abs(r.t - t) <= WINDOW_MS)
                return true;
        return false;
    }

    /** Each departure cancels at most one arrival of the same resource, so a stack gaining a new item still counts it. */
    private void pairDepartures() {
        for (Departure d : departures) {
            if (d.used)
                continue;
            for (Arrival a : arrivals) {
                if (a.consumed || a.moved || a.received || Math.abs(a.t - d.t) > WINDOW_MS)
                    continue;
                if (!d.res.equals(a.item.res()))
                    continue;
                if (d.owned)
                    a.moved = true;
                else
                    a.received = true;
                d.used = true;
                break;
            }
        }
    }
}
