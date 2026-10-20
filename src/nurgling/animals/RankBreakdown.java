package nurgling.animals;

import java.util.Collections;
import java.util.List;

/** How one animal's rank was put together: the effective quality and each term's share. */
public final class RankBreakdown {
    /** The rank of an animal whose area has no rank preset bound. */
    public static final RankBreakdown UNBOUND = new RankBreakdown(null, 0, new double[0], 0, 0, false);

    public final HerdRules rules;
    public final double eq;
    public final double[] parts;
    public final double sum;
    public final double rank;
    /** Every term came to exactly 0, so the rank fell back to the effective quality. */
    public final boolean fallback;

    RankBreakdown(HerdRules rules, double eq, double[] parts, double sum, double rank, boolean fallback) {
        this.rules = rules;
        this.eq = eq;
        this.parts = parts;
        this.sum = sum;
        this.rank = rank;
        this.fallback = fallback;
    }

    public boolean bound() {
        return rules != null;
    }

    public List<RankTerm> terms() {
        return rules == null ? Collections.emptyList() : rules.terms;
    }
}
