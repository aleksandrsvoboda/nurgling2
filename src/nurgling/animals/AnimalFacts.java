package nurgling.animals;

import java.util.HashMap;
import java.util.Map;

/** A UI-free snapshot of one roster entry: what the rank and the cull look at. */
public final class AnimalFacts {
    public final Object id;
    public final String name;
    public final boolean male;
    public final boolean young;
    public final boolean dead;
    public final double q;
    public final int seedq;
    private final Map<String, Integer> stats = new HashMap<>();

    public AnimalFacts(Object id, String name, boolean male, boolean young, boolean dead, double q, int seedq) {
        this.id = id;
        this.name = name;
        this.male = male;
        this.young = young;
        this.dead = dead;
        this.q = q;
        this.seedq = seedq;
    }

    /** Records a stat under its {@link RankTerm#id}. Returns this for chaining while building. */
    public AnimalFacts with(String termId, int value) {
        stats.put(termId, value);
        return this;
    }

    public int stat(String termId) {
        Integer v = stats.get(termId);
        return v == null ? 0 : v;
    }
}
