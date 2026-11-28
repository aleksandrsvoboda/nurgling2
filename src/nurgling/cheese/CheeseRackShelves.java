package nurgling.cheese;

import haven.MessageBuf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a cheese rack's shelves hold, read from the rack's gfx/fx/eq overlays, and what that
 * means for the player.
 *
 * Facts this rests on (checked against the res server and CheeseBranch):
 * - the rack has three bone offsets e1/e2/e3, pure z offsets 1.5 / 6.9 / 12.2, so e3 is the top shelf;
 * - a tray on a shelf is gfx/terobjs/items/cheesetray (empty), cheesetray-curd, or cheesetray-l1..l4,
 *   where the number is the steps since curd;
 * - no chain has two steps in a row in the same place, and since Hat Placement a cheese ages one
 *   step and then waits to be moved. So a tray goes up at most one tier per placement: a tray that
 *   went up on this rack is finished on this rack.
 */
public final class CheeseRackShelves {
    public static final String RACK_RES = "gfx/terobjs/cheeserack";
    public static final String EQ_RES = "gfx/fx/eq";
    public static final int SHELVES = 3;
    /** Tier of an empty tray; curd is 0 and cheeses are 1..4. */
    public static final int EMPTY = -1;

    private static final String TRAY = "gfx/terobjs/items/cheesetray";

    private CheeseRackShelves() {}

    /** Tier of a tray resource on a rack, or null when the resource is not a cheese tray. */
    public static Integer tierOf(String res) {
        if (res == null || !res.startsWith(TRAY))
            return null;
        String rest = res.substring(TRAY.length());
        if (rest.isEmpty())
            return EMPTY;
        if (rest.equals("-curd"))
            return 0;
        if (rest.length() == 3 && rest.startsWith("-l") && Character.isDigit(rest.charAt(2)))
            return rest.charAt(2) - '0';
        return null;
    }

    /** Shelf index of a rack bone: e1 = 0 (bottom) .. e3 = 2 (top), or -1. */
    public static int shelfOf(String bone) {
        if (bone == null || bone.length() != 2 || bone.charAt(0) != 'e')
            return -1;
        int i = bone.charAt(1) - '1';
        return (i >= 0 && i < SHELVES) ? i : -1;
    }

    /** A gfx/fx/eq overlay's sprite data: uint16 res id, uint8 flags, bone name, [flags&2: uint8 len, sub data]. */
    public static final class Eq {
        public final int resid;
        public final String bone;
        public final byte[] sub;

        Eq(int resid, String bone, byte[] sub) {
            this.resid = resid;
            this.bone = bone;
            this.sub = sub;
        }
    }

    /** Decodes an eq overlay's sprite data, or returns null when it is too short to be one. */
    public static Eq decodeEq(byte[] sdt) {
        if (sdt == null || sdt.length < 4)
            return null;
        MessageBuf m = new MessageBuf(sdt);
        int resid = m.uint16();
        int fl = m.uint8();
        String bone = m.string();
        byte[] sub = null;
        if ((fl & 2) != 0 && !m.eom())
            sub = m.bytes(m.uint8());
        return new Eq(resid, bone, sub);
    }

    public enum Place {
        INSIDE("Inside", CheeseBranch.Place.inside),
        CELLAR("Cellar", CheeseBranch.Place.cellar),
        OUTSIDE("Outside", CheeseBranch.Place.outside),
        MINE("Mine", CheeseBranch.Place.mine);

        public final String label;
        public final CheeseBranch.Place branch;

        Place(String label, CheeseBranch.Place branch) {
            this.label = label;
            this.branch = branch;
        }

        /** The place for a cheese-rack area subtype (Inside/Cellar/Outside/Mine), or null. */
        public static Place ofSubtype(String subtype) {
            if (subtype == null)
                return null;
            for (Place p : values())
                if (p.label.equalsIgnoreCase(subtype))
                    return p;
            return null;
        }

        public static Place of(CheeseBranch.Place p) {
            for (Place v : values())
                if (v.branch == p)
                    return v;
            return null;
        }
    }

    // ------------------------------------------------------------------ cheese chains

    /** Steps since curd for a tray content name (curd = 0), or null when no chain knows it. */
    public static Integer tierOfName(String name) {
        if (name == null)
            return null;
        for (CheeseBranch b : CheeseBranch.branches)
            for (int i = 0; i < b.steps.size(); i++)
                if (b.steps.get(i).name.equals(name))
                    return i;
        return null;
    }

    /** Where this cheese is made (null for curds and unknown names). */
    public static CheeseBranch.Place madeIn(String name) {
        for (CheeseBranch b : CheeseBranch.branches)
            for (int i = 1; i < b.steps.size(); i++)
                if (b.steps.get(i).name.equals(name))
                    return b.steps.get(i).place;
        return null;
    }

    /** Every step that can follow this cheese, without duplicates, in chain order. */
    public static List<CheeseBranch.Cheese> nextSteps(String name) {
        List<CheeseBranch.Cheese> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CheeseBranch b : CheeseBranch.branches)
            for (int i = 0; i + 1 < b.steps.size(); i++)
                if (b.steps.get(i).name.equals(name)) {
                    CheeseBranch.Cheese n = b.steps.get(i + 1);
                    if (seen.add(n.name + "@" + n.place))
                        out.add(n);
                }
        return out;
    }

    /** The cheese this one turns into in this place, or null when it doesn't age there. */
    public static CheeseBranch.Cheese nextIn(String name, CheeseBranch.Place place) {
        if (place == null)
            return null;
        for (CheeseBranch.Cheese n : nextSteps(name))
            if (n.place == place)
                return n;
        return null;
    }

    // ------------------------------------------------------------------ state rules

    public enum State {
        /** Empty tray on the shelf. */
        EMPTY,
        /** Aging here: it will go up a tier on this rack. */
        AGING,
        /** Went up a tier on this rack, or was made in this place: move it on. */
        READY,
        /** The last cheese of its chain: done. */
        FINAL,
        /** Its next step needs another place: it will never age on this rack. */
        WRONG_PLACE,
        /** First seen already at this tier: it may or may not have aged here. */
        UNKNOWN
    }

    /** What we remember about the tray on one shelf. */
    public static final class Memory {
        /** Overlay id of the tray: a different id is a different tray. */
        public int olId;
        /** Tier the tray had when we first saw it on this shelf. */
        public int baseTier;
        /** When we first saw it (epoch ms). */
        public long since;
        /** We saw the tray go on, so baseTier is its tier at placement and since is its placement time. */
        public boolean placedLive;
        /** Content name read from the open rack, and the tier it was read at; null when not known. */
        public String cheese;
        public int cheeseTier;

        public Memory(int olId, int baseTier, long since, boolean placedLive) {
            this.olId = olId;
            this.baseTier = baseTier;
            this.since = since;
            this.placedLive = placedLive;
        }
    }

    /** One shelf, evaluated. */
    public static final class Status {
        public final State state;
        public final int tier;
        /** Content name, read or inferred; null when not known. */
        public final String cheese;
        /** What the cheese turns into here, when it is aging and that is known. */
        public final CheeseBranch.Cheese next;
        /** Wiki hours of the current step here, or -1. */
        public final int stepHours;
        /** Hours aged here so far, or -1 when the placement time isn't known. */
        public final double agedHours;

        Status(State state, int tier, String cheese, CheeseBranch.Cheese next, int stepHours, double agedHours) {
            this.state = state;
            this.tier = tier;
            this.cheese = cheese;
            this.next = next;
            this.stepHours = stepHours;
            this.agedHours = agedHours;
        }

        /** Hours left by the wiki, or NaN when unknown. Can be negative (overdue by the wiki). */
        public double hoursLeft() {
            if (stepHours < 0 || agedHours < 0)
                return Double.NaN;
            return stepHours - agedHours;
        }
    }

    /** Content name for the tray's current tier: as read, or inferred one step on when the place decides it. */
    static String cheeseNow(Memory m, int tier, CheeseBranch.Place place) {
        if (m == null || m.cheese == null)
            return null;
        if (m.cheeseTier == tier)
            return m.cheese;
        if (tier == m.cheeseTier + 1) {
            CheeseBranch.Cheese n = nextIn(m.cheese, place);
            return n == null ? null : n.name;
        }
        return null;
    }

    /**
     * Evaluates a shelf.
     *
     * @param m         what we remember (null = nothing, treated as first seen now)
     * @param tier      the tier on the shelf now
     * @param place     where the rack is, or null when unknown
     * @param now       epoch ms
     * @param overrides stage-hour overrides from the cheese calculator, or null
     */
    public static Status evaluate(Memory m, int tier, CheeseBranch.Place place, long now, java.util.Map<String, Integer> overrides) {
        if (tier == EMPTY)
            return new Status(State.EMPTY, tier, null, null, -1, -1);
        String cheese = cheeseNow(m, tier, place);
        boolean wentUp = m != null && tier > m.baseTier;
        if (wentUp)
            return new Status(cheese != null && nextSteps(cheese).isEmpty() ? State.FINAL : State.READY, tier, cheese, null, -1, -1);
        if (cheese != null) {
            if (nextSteps(cheese).isEmpty())
                return new Status(State.FINAL, tier, cheese, null, -1, -1);
            // Made in this very place: it already took its step here.
            if (place != null && tier >= 1 && madeIn(cheese) == place)
                return new Status(State.READY, tier, cheese, null, -1, -1);
            if (place != null) {
                CheeseBranch.Cheese next = nextIn(cheese, place);
                if (next == null)
                    return new Status(State.WRONG_PLACE, tier, cheese, null, -1, -1);
                int hours = CheeseStageHours.hours(next.name, place, overrides);
                double aged = (m != null && m.placedLive) ? (now - m.since) / 3600000.0 : -1;
                return new Status(State.AGING, tier, cheese, next, hours, aged);
            }
            return new Status(State.AGING, tier, cheese, null, -1, -1);
        }
        // Name unknown: only the tier tells. Curd can't have aged yet; a tray we saw go on is at its start tier.
        if (tier == 0 || (m != null && m.placedLive))
            return new Status(State.AGING, tier, null, null, -1, -1);
        return new Status(State.UNKNOWN, tier, null, null, -1, -1);
    }
}
