package nurgling.colony;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The goal "spawn a colonist at home", worked out from what the colonists and base storage hold right now. Phase 1
 * only shows it: each row is a need, whether it's covered (OK), would become a job (JOB), or can't be done (BLOCKED),
 * with a localisation key and arguments saying how. Pure: no client state, so it can be tested.
 *
 * <p>Order of sources for every need: what colonists carry, then base storage, then making it. Gathering covers only
 * what's missing.
 */
public final class SpawnPlan {
    public enum State { OK, JOB, BLOCKED }

    /** One line of the plan. {@code key} is a {@code colony.plan.*} localisation key, filled with {@code args}. */
    public static final class Row {
        public final int depth;
        public final State state;
        public final String key;
        public final Object[] args;

        Row(int depth, State state, String key, Object... args) {
            this.depth = depth;
            this.state = state;
            this.key = key;
            this.args = args;
        }

        @Override
        public String toString() {
            return depth + " " + state + " " + key + " " + java.util.Arrays.toString(args);
        }
    }

    /** A colonist as the plan sees it: its report, whether it's free now, and whether it may spend LP on skills. */
    public static final class Member {
        public final ColonistReport r;
        public final boolean idle;
        public final boolean learns;

        public Member(ColonistReport r, boolean idle, boolean learns) {
            this.r = r;
            this.idle = idle;
            this.learns = learns;
        }
    }

    /** The Wilderness Beacon's recipe, in the order the panel lists it. */
    public static final Map<ColonyItems.Material, Integer> BEACON;
    /** Foraging, then Lumberjacking, then Carpentry, with the costs a fresh character sees. */
    public static final Map<String, Integer> CARPENTRY_CHAIN;
    public static final String CARPENTRY = "crp", LUMBERJACKING = "lumber";

    static {
        Map<ColonyItems.Material, Integer> b = new EnumMap<>(ColonyItems.Material.class);
        b.put(ColonyItems.Material.CLAY, 10);
        b.put(ColonyItems.Material.STONE, 10);
        b.put(ColonyItems.Material.BLOCK, 20);
        b.put(ColonyItems.Material.BOARD, 10);
        BEACON = Collections.unmodifiableMap(b);
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("forage", 25);
        c.put(LUMBERJACKING, 150);
        c.put(CARPENTRY, 200);
        CARPENTRY_CHAIN = Collections.unmodifiableMap(c);
    }

    private SpawnPlan() {}

    /**
     * @param crew      colonists at home (ticked accounts, in range)
     * @param storage   materials in base storage, or null when the index can't be read
     * @param creators  saved accounts the colony may create characters on
     * @param online    accounts logged in right now
     */
    public static List<Row> plan(List<Member> crew, Map<ColonyItems.Material, Integer> storage,
                                 Collection<String> creators, Set<String> online) {
        List<Row> rows = new ArrayList<>();
        if (crew.isEmpty()) {
            rows.add(new Row(0, State.BLOCKED, "colony.plan.nocrew"));
            return rows;
        }
        int idle = 0;
        for (Member m : crew)
            if (m.idle)
                idle++;
        rows.add(new Row(0, State.OK, "colony.plan.crew", crew.size(), idle));

        builder(crew, rows);
        for (Map.Entry<ColonyItems.Material, Integer> e : BEACON.entrySet())
            material(crew, storage, e.getKey(), e.getValue(), rows);
        account(creators, online, rows);
        rows.add(new Row(0, State.JOB, "colony.plan.light"));
        rows.add(new Row(0, State.JOB, "colony.plan.admit"));
        return rows;
    }

    /** Who builds: someone with Carpentry, or the colonist allowed to learn who is closest to affording it. */
    private static void builder(List<Member> crew, List<Row> rows) {
        Member carp = withSkill(crew, CARPENTRY);
        if (carp != null) {
            rows.add(new Row(0, State.OK, "colony.plan.builder", carp.r.name));
            return;
        }
        learner(crew, CARPENTRY, rows, 0, "colony.plan.builder.learn");
    }

    /**
     * The colonist allowed to learn whose LP is closest to the skill chain up to {@code skill}, as a JOB row; BLOCKED
     * when nobody may learn.
     */
    private static void learner(List<Member> crew, String skill, List<Row> rows, int depth, String key) {
        Member best = null;
        int bestGap = Integer.MAX_VALUE, bestCost = 0;
        for (Member m : crew) {
            if (!m.learns)
                continue;
            int cost = chainCost(m.r, skill);
            int gap = (m.r.lp < 0) ? cost : Math.max(0, cost - m.r.lp);
            if (gap < bestGap) {
                best = m;
                bestGap = gap;
                bestCost = cost;
            }
        }
        if (best == null) {
            rows.add(new Row(depth, State.BLOCKED, "colony.plan.nolearner"));
            return;
        }
        rows.add(new Row(depth, State.JOB, key, best.r.name, bestCost, bestGap));
    }

    /** LP still to spend on the chain up to and including {@code skill}, using the costs the character is offered. */
    static int chainCost(ColonistReport r, String skill) {
        int cost = 0;
        for (Map.Entry<String, Integer> e : CARPENTRY_CHAIN.entrySet()) {
            if (!r.knows(e.getKey())) {
                Integer offered = r.buyable.get(e.getKey());
                cost += (offered != null) ? offered : e.getValue();
            }
            if (e.getKey().equals(skill))
                break;
        }
        return cost;
    }

    private static void material(List<Member> crew, Map<ColonyItems.Material, Integer> storage, ColonyItems.Material m,
                                 int need, List<Row> rows) {
        int carried = 0;
        for (Member c : crew)
            carried += c.r.count(m);
        int stored = (storage == null) ? 0 : storage.getOrDefault(m, 0);
        int have = carried + stored;
        String key = "colony.plan.mat." + m.name().toLowerCase();
        if (have >= need) {
            rows.add(new Row(0, State.OK, key, Math.min(have, need), need, carried, stored));
            return;
        }
        rows.add(new Row(0, State.JOB, key, have, need, carried, stored));
        int missing = need - have;
        switch (m) {
            case CLAY:
                rows.add(new Row(1, State.JOB, "colony.plan.dig", missing));
                break;
            case STONE:
                rows.add(new Row(1, State.JOB, "colony.plan.chip", missing));
                break;
            case BLOCK:
                rows.add(new Row(1, State.JOB, "colony.plan.chop", missing));
                skillRow(crew, LUMBERJACKING, "colony.plan.lumber", "colony.plan.lumber.learn", rows);
                axe(crew, rows);
                break;
            case BOARD:
                rows.add(new Row(1, State.JOB, "colony.plan.saw", missing));
                saw(crew, rows);
                break;
            default:
                break;
        }
    }

    private static void skillRow(List<Member> crew, String skill, String okKey, String learnKey, List<Row> rows) {
        Member has = withSkill(crew, skill);
        if (has != null)
            rows.add(new Row(2, State.OK, okKey, has.r.name));
        else
            learner(crew, skill, rows, 2, learnKey);
    }

    private static void axe(List<Member> crew, List<Row> rows) {
        Member has = withTool(crew, ColonyItems.Tool.AXE);
        if (has != null) {
            rows.add(new Row(2, State.OK, "colony.plan.tool.has", has.r.name, has.r.tools.get(ColonyItems.Tool.AXE)));
            return;
        }
        rows.add(new Row(2, State.JOB, "colony.plan.craft.axe"));
        ingredient(crew, ColonyItems.Material.STONE, "colony.plan.ing.stone", rows);
        ingredient(crew, ColonyItems.Material.BRANCH, "colony.plan.ing.branch", rows);
    }

    private static void saw(List<Member> crew, List<Row> rows) {
        Member has = withTool(crew, ColonyItems.Tool.SAW);
        if (has != null) {
            rows.add(new Row(2, State.OK, "colony.plan.tool.has", has.r.name, has.r.tools.get(ColonyItems.Tool.SAW)));
            return;
        }
        rows.add(new Row(2, State.JOB, "colony.plan.craft.saw"));
        Member bone = withMaterial(crew, ColonyItems.Material.BONE);
        if (bone != null)
            rows.add(new Row(3, State.OK, "colony.plan.ing.bone.has", bone.r.name));
        else
            rows.add(new Row(3, State.JOB, "colony.plan.ing.bone.hunt"));
        ingredient(crew, ColonyItems.Material.BOUGH, "colony.plan.ing.bough", rows);
    }

    private static void ingredient(List<Member> crew, ColonyItems.Material m, String key, List<Row> rows) {
        Member has = withMaterial(crew, m);
        rows.add(new Row(3, (has != null) ? State.OK : State.JOB, key + ((has != null) ? ".has" : ".get"),
                         (has != null) ? has.r.name : ""));
    }

    private static void account(Collection<String> creators, Set<String> online, List<Row> rows) {
        if (creators.isEmpty()) {
            rows.add(new Row(0, State.BLOCKED, "colony.plan.account.none"));
            return;
        }
        for (String a : creators) {
            if (!online.contains(a)) {
                rows.add(new Row(0, State.OK, "colony.plan.account", a));
                return;
            }
        }
        rows.add(new Row(0, State.BLOCKED, "colony.plan.account.online"));
    }

    /* Idle colonists are preferred: they could start now. */

    private static Member withSkill(List<Member> crew, String skill) {
        Member found = null;
        for (Member m : crew) {
            if (m.r.knows(skill)) {
                if (m.idle)
                    return m;
                if (found == null)
                    found = m;
            }
        }
        return found;
    }

    private static Member withTool(List<Member> crew, ColonyItems.Tool t) {
        Member found = null;
        for (Member m : crew) {
            if (m.r.tools.containsKey(t)) {
                if (m.idle)
                    return m;
                if (found == null)
                    found = m;
            }
        }
        return found;
    }

    private static Member withMaterial(List<Member> crew, ColonyItems.Material mat) {
        for (Member m : crew)
            if (m.r.count(mat) > 0)
                return m;
        return null;
    }
}
