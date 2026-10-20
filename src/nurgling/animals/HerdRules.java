package nurgling.animals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One herd preset, read out of its species-specific config class (CowsHerd, GoatsHerd, ...) into a
 * shape every species shares. Built by {@link HerdRulesFactory}.
 */
public final class HerdRules {
    public final String species;
    public final String preset;
    public final boolean saveYoung;
    public final boolean noSlaughter;
    public final boolean ignoreBreedingForFemale;
    public final boolean ignoreQualityForPercent;
    public final int keepFemales;
    public final int keepMales;
    public final int gap;
    public final double overbreed;
    public final List<RankTerm> terms;

    private HerdRules(Builder b) {
        this.species = b.species;
        this.preset = b.preset;
        this.saveYoung = b.saveYoung;
        this.noSlaughter = b.noSlaughter;
        this.ignoreBreedingForFemale = b.ignoreBreedingForFemale;
        this.ignoreQualityForPercent = b.ignoreQualityForPercent;
        this.keepFemales = b.keepFemales;
        this.keepMales = b.keepMales;
        this.gap = b.gap;
        this.overbreed = b.overbreed;
        this.terms = Collections.unmodifiableList(new ArrayList<>(b.terms));
    }

    public int keep(boolean male) {
        return male ? keepMales : keepFemales;
    }

    /** The breeding-quality target the effective quality is pulled toward. */
    public int target(int seedq) {
        return seedq - gap;
    }

    public boolean usesBreeding(boolean male) {
        return !ignoreBreedingForFemale || male;
    }

    public double effectiveQuality(boolean male, double q, int seedq) {
        return usesBreeding(male) ? (q > (seedq - gap)) ? (q + seedq - gap) / 2. : q + ((seedq - gap) - q) * overbreed : q;
    }

    public RankBreakdown rank(AnimalFacts a) {
        double eq = effectiveQuality(a.male, a.q, a.seedq);
        double[] parts = new double[terms.size()];
        double sum = 0;
        for (int i = 0; i < parts.length; i++) {
            RankTerm t = terms.get(i);
            parts[i] = t.value(a.stat(t.id), eq, ignoreQualityForPercent);
            sum = (i == 0) ? parts[i] : sum + parts[i];
        }
        boolean fallback = sum == 0;
        double rank = fallback ? eq : Math.round(sum * 10) / 10.;
        return new RankBreakdown(this, eq, parts, sum, rank, fallback);
    }

    /** The rank the bot sorts by: 0 when the animal's area has no preset bound. */
    public static RankBreakdown breakdown(HerdRules rules, AnimalFacts a) {
        return (rules == null || a == null) ? RankBreakdown.UNBOUND : rules.rank(a);
    }

    public boolean anyWeighted() {
        for (RankTerm t : terms)
            if (t.weighted())
                return true;
        return false;
    }

    public static final class Builder {
        private final String species;
        private final String preset;
        private boolean saveYoung, noSlaughter, ignoreBreedingForFemale, ignoreQualityForPercent;
        private int keepFemales, keepMales, gap;
        private double overbreed;
        private final List<RankTerm> terms = new ArrayList<>();

        public Builder(String species, String preset) {
            this.species = species;
            this.preset = preset;
        }

        public Builder flags(boolean saveYoung, boolean noSlaughter, boolean ignoreBreedingForFemale, boolean ignoreQualityForPercent) {
            this.saveYoung = saveYoung;
            this.noSlaughter = noSlaughter;
            this.ignoreBreedingForFemale = ignoreBreedingForFemale;
            this.ignoreQualityForPercent = ignoreQualityForPercent;
            return this;
        }

        public Builder keep(int females, int males) {
            this.keepFemales = females;
            this.keepMales = males;
            return this;
        }

        public Builder breeding(int gap, double overbreed) {
            this.gap = gap;
            this.overbreed = overbreed;
            return this;
        }

        /** Terms must be added in the order the original rang() summed them. */
        public Builder term(RankTerm t) {
            terms.add(t);
            return this;
        }

        public HerdRules build() {
            return new HerdRules(this);
        }
    }
}
