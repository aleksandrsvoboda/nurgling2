package nurgling.leveling;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One area to level to one flat height.
 *
 * <p>The area is a set of tiles kept as a bitmask per map grid, keyed by the server's grid id - the
 * one coordinate that survives segment merges and reads the same in every session. Rectangles and
 * brush strokes both just set or clear tiles.
 *
 * <p>Immutable: every edit makes a new plan, so the map renderer can read one on a loader thread
 * while the player keeps painting. A mask array is never written once it is in a plan.
 */
public final class LevelPlan {
    /** How the target height is picked while the plan is not locked. */
    public enum Mode {
        /** Everything dug fills the holes: no soil in or out. */
        BALANCED,
        /** The least ground moved in total. */
        LEAST_WORK,
        /** Use up a stockpile of soil exactly. */
        BUDGET,
        /** A height the player picked. */
        CUSTOM
    }

    public final String id;
    public final String name;
    /** Grid id to tile mask; see {@link TileMask}. Unmodifiable. */
    public final Map<Long, long[]> masks;
    public final Mode mode;
    /** Soil on hand for {@link Mode#BUDGET}, in survey units. */
    public final int budget;
    /** The height chosen in {@link Mode#CUSTOM}; NaN until one is picked. */
    public final double custom;
    /** The height the ground is levelled to; NaN until the plan has ground to measure. */
    public final double target;
    /** A locked plan keeps its target however the ground changes, so a half-done job never shifts. */
    public final boolean locked;
    /** Ground to move (dig + fill, height units) when the plan was locked; the base for progress. */
    public final double baseline;

    private LevelPlan(String id, String name, Map<Long, long[]> masks, Mode mode, int budget,
                      double custom, double target, boolean locked, double baseline) {
        this.id = id;
        this.name = name;
        this.masks = masks;
        this.mode = mode;
        this.budget = budget;
        this.custom = custom;
        this.target = target;
        this.locked = locked;
        this.baseline = baseline;
    }

    public static LevelPlan create(String name) {
        return(new LevelPlan(UUID.randomUUID().toString(), name, Collections.emptyMap(), Mode.BALANCED,
                             0, Double.NaN, Double.NaN, false, Double.NaN));
    }

    public LevelPlan withName(String name) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, locked, baseline));
    }

    public LevelPlan withMode(Mode mode) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, locked, baseline));
    }

    public LevelPlan withBudget(int budget) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, locked, baseline));
    }

    public LevelPlan withCustom(double custom) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, locked, baseline));
    }

    public LevelPlan withTarget(double target) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, locked, baseline));
    }

    public LevelPlan lock(double baseline) {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, true, baseline));
    }

    public LevelPlan unlock() {
        return(new LevelPlan(id, name, masks, mode, budget, custom, target, false, Double.NaN));
    }

    /** This plan with one grid's mask replaced; an empty mask drops the grid. */
    LevelPlan withMask(long gid, long[] mask) {
        Map<Long, long[]> nm = new LinkedHashMap<>(masks);
        if(TileMask.empty(mask))
            nm.remove(gid);
        else
            nm.put(gid, mask);
        return(new LevelPlan(id, name, Collections.unmodifiableMap(nm), mode, budget, custom, target, locked, baseline));
    }

    public boolean contains(long gid, int x, int y) {
        long[] m = masks.get(gid);
        return((m != null) && TileMask.get(m, x, y));
    }

    public int tiles() {
        int n = 0;
        for(long[] m : masks.values())
            n += TileMask.count(m);
        return(n);
    }

    public boolean hasTarget() {
        return(!Double.isNaN(target));
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("mode", mode.name());
        o.put("budget", budget);
        if(!Double.isNaN(custom))
            o.put("custom", custom);
        if(!Double.isNaN(target))
            o.put("target", target);
        o.put("locked", locked);
        if(!Double.isNaN(baseline))
            o.put("baseline", baseline);
        JSONObject jm = new JSONObject();
        for(Map.Entry<Long, long[]> e : masks.entrySet())
            jm.put(Long.toString(e.getKey()), encode(e.getValue()));
        o.put("masks", jm);
        return(o);
    }

    static LevelPlan fromJson(JSONObject o) {
        Mode mode;
        try {
            mode = Mode.valueOf(o.optString("mode", "BALANCED"));
        } catch(IllegalArgumentException e) {
            mode = Mode.BALANCED;
        }
        Map<Long, long[]> masks = new LinkedHashMap<>();
        JSONObject jm = o.optJSONObject("masks");
        if(jm != null) {
            for(String k : jm.keySet()) {
                long[] m = decode(jm.getString(k));
                if((m != null) && !TileMask.empty(m))
                    masks.put(Long.parseLong(k), m);
            }
        }
        return(new LevelPlan(o.getString("id"), o.optString("name", "Plan"), Collections.unmodifiableMap(masks), mode,
                             o.optInt("budget", 0), o.optDouble("custom", Double.NaN), o.optDouble("target", Double.NaN),
                             o.optBoolean("locked", false), o.optDouble("baseline", Double.NaN)));
    }

    private static String encode(long[] m) {
        ByteBuffer buf = ByteBuffer.allocate(m.length * 8);
        for(long l : m)
            buf.putLong(l);
        return(Base64.getEncoder().encodeToString(buf.array()));
    }

    private static long[] decode(String s) {
        byte[] b = Base64.getDecoder().decode(s);
        if(b.length != TileMask.WORDS * 8)
            return(null);
        ByteBuffer buf = ByteBuffer.wrap(b);
        long[] m = new long[TileMask.WORDS];
        for(int i = 0; i < m.length; i++)
            m[i] = buf.getLong();
        return(m);
    }
}
