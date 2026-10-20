package nurgling.animals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Predicts what an animal bot run will do to one pen, without touching the game. Mirrors the bots:
 * ranks come from the preset bound to the area, while head count and the toggles come from the preset
 * selected in the settings window (the two can differ, which is reported as a warning).
 */
public final class CullPlanner {
    public enum Verdict {
        BREEDER("breeder"),
        YOUNG_KEPT("young"),
        SAVED("saved"),
        UNTOUCHED("spared"),
        RANDOM("random"),
        KILL("kill"),
        DEAD("dead");

        public final String label;

        Verdict(String label) {
            this.label = label;
        }

        public boolean dies() {
            return this == KILL;
        }
    }

    public static final class Outcome {
        public final Verdict verdict;
        public final String reason;
        public final RankBreakdown rank;
        /** First slaughtered animal of its group in rank order: the cull line is drawn above it. */
        public boolean cullLineAbove;

        Outcome(Verdict verdict, String reason, RankBreakdown rank) {
            this.verdict = verdict;
            this.reason = reason;
            this.rank = rank;
        }
    }

    public static final class Group {
        public final boolean male;
        public final int keep;
        /** Rank of the worst kept adult, or null when there is no adult to keep. */
        public final Double bar;
        public final List<AnimalFacts> ordered;
        public final int adults;
        public final int youngKilled;
        public final int ties;

        Group(boolean male, int keep, Double bar, List<AnimalFacts> ordered, int adults, int youngKilled, int ties) {
            this.male = male;
            this.keep = keep;
            this.bar = bar;
            this.ordered = ordered;
            this.adults = adults;
            this.youngKilled = youngKilled;
            this.ties = ties;
        }
    }

    public static final class Plan {
        public final Map<Object, Outcome> outcomes;
        public final List<Group> groups;
        public final List<String> warnings;

        Plan(Map<Object, Outcome> outcomes, List<Group> groups, List<String> warnings) {
            this.outcomes = outcomes;
            this.groups = groups;
            this.warnings = warnings;
        }

        public Outcome outcome(Object id) {
            return outcomes.get(id);
        }

        public int count(Verdict v) {
            int n = 0;
            for (Outcome o : outcomes.values())
                if (o.verdict == v)
                    n++;
            return n;
        }
    }

    private CullPlanner() {
    }

    private static final Comparator<Outcome> SHOW_ORDER =
            Comparator.comparingDouble((Outcome o) -> -o.rank.rank).thenComparing(o -> o.verdict.ordinal());

    public static Plan plan(List<AnimalFacts> herd, HerdRules rankRules, HerdRules countRules) {
        Map<Object, Outcome> outcomes = new HashMap<>();
        List<Group> groups = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (countRules == null) {
            for (AnimalFacts a : herd)
                outcomes.put(a.id, new Outcome(Verdict.UNTOUCHED, "No herd preset selected: the bot stops before culling", HerdRules.breakdown(rankRules, a)));
            warnings.add("No herd preset is selected, so the bot stops with \"Please select rang settings\".");
            return new Plan(outcomes, groups, warnings);
        }
        boolean anyCandidate = false;
        for (boolean male : new boolean[]{false, true}) {
            int keepN = countRules.keep(male);
            Map<Object, RankBreakdown> ranks = new HashMap<>();
            List<AnimalFacts> members = new ArrayList<>();
            for (AnimalFacts a : herd) {
                if (a.male == male) {
                    members.add(a);
                    ranks.put(a.id, HerdRules.breakdown(rankRules, a));
                }
            }
            Comparator<AnimalFacts> comp = (x, y) -> Double.compare(ranks.get(y.id).rank, ranks.get(x.id).rank);
            List<AnimalFacts> candidates = new ArrayList<>();
            if (!countRules.noSlaughter)
                for (AnimalFacts a : members)
                    if (!a.dead && (!a.young || !countRules.saveYoung))
                        candidates.add(a);
            anyCandidate |= !candidates.isEmpty();
            CullSelection<AnimalFacts> sel = CullSelection.select(candidates, comp, a -> !a.young, keepN);
            Double bar = sel.bar == null ? null : ranks.get(sel.bar.id).rank;
            int adultCandidates = 0;
            for (AnimalFacts a : candidates)
                if (!a.young)
                    adultCandidates++;
            boolean arbitrary = rankRules == null && adultCandidates > keepN;
            String sex = male ? "male" : "female";

            List<Outcome> shown = new ArrayList<>();
            Map<Outcome, AnimalFacts> owner = new HashMap<>();
            int adults = 0, youngKilled = 0, ties = 0;
            for (AnimalFacts a : members) {
                RankBreakdown rb = ranks.get(a.id);
                Outcome o;
                if (a.dead) {
                    o = new Outcome(Verdict.DEAD, "Dead: the bot leaves it alone", rb);
                } else if (countRules.noSlaughter) {
                    o = new Outcome(Verdict.UNTOUCHED, "Slaughter is disabled", rb);
                } else if (a.young && countRules.saveYoung) {
                    o = new Outcome(Verdict.SAVED, "Young, protected by \"Save cubs\"", rb);
                } else if (arbitrary && !a.young) {
                    o = new Outcome(Verdict.RANDOM, "No rank preset on this area: the bot keeps " + keepN + " arbitrary adult " + sex + "s", rb);
                } else if (sel.keep.contains(a)) {
                    o = new Outcome(Verdict.BREEDER, "#" + (sel.keep.indexOf(a) + 1) + " of the best " + keepN + " adult " + sex + (keepN == 1 ? "" : "s"), rb);
                } else if (sel.kill.contains(a)) {
                    boolean tie = bar != null && rb.rank == bar;
                    if (a.young)
                        o = new Outcome(Verdict.KILL, "Young, rank " + fmt(rb.rank) + (tie ? " ties" : " is below") + " the bar " + fmt(bar) + (tie ? " (ties die)" : ""), rb);
                    else
                        o = new Outcome(Verdict.KILL, "Adult outside the best " + keepN + (tie ? ", tied with the bar " + fmt(bar) + " (ties die)" : ""), rb);
                    if (a.young)
                        youngKilled++;
                    if (tie)
                        ties++;
                } else if (sel.bar == null) {
                    o = new Outcome(Verdict.UNTOUCHED, "No adult " + sex + " to compare with: nothing is culled", rb);
                } else {
                    o = new Outcome(Verdict.YOUNG_KEPT, "Young, rank " + fmt(rb.rank) + " beats the bar " + fmt(bar), rb);
                }
                if (!a.dead && !a.young)
                    adults++;
                outcomes.put(a.id, o);
                shown.add(o);
                owner.put(o, a);
            }
            shown.sort(SHOW_ORDER);
            List<AnimalFacts> ordered = new ArrayList<>();
            boolean lined = false;
            for (Outcome o : shown) {
                if (!lined && o.verdict == Verdict.KILL) {
                    o.cullLineAbove = true;
                    lined = true;
                }
                ordered.add(owner.get(o));
            }
            groups.add(new Group(male, keepN, bar, Collections.unmodifiableList(ordered), adults, youngKilled, ties));

            String sexes = male ? "males" : "females";
            if (youngKilled > 0 && adults < keepN)
                warnings.add("Only " + adults + " adult " + sexes + " against a target of " + keepN + ", yet " + youngKilled
                        + " young will be slaughtered for not beating the worst adult. The herd can't grow toward " + keepN + ".");
            if (ties > 0 && rankRules != null)
                warnings.add(ties + " " + (ties == 1 ? sex : sexes) + " tie with the bar " + fmt(bar) + " and will be slaughtered.");
        }
        if (rankRules == null && anyCandidate)
            warnings.add(0, "This area has no rank preset bound: every rank is 0, so which animals survive is arbitrary. The bot still slaughters.");
        if (rankRules != null && !Objects.equals(rankRules.preset, countRules.preset))
            warnings.add(0, "Ranks use preset \"" + rankRules.preset + "\" (bound to the area), but head count and toggles use \""
                    + countRules.preset + "\" (selected in the settings window).");
        warnings.addAll(ruleWarnings(rankRules != null ? rankRules : countRules, herd));
        return new Plan(outcomes, groups, warnings);
    }

    /** Settings that do not do what they look like they do, judged against this herd. */
    public static List<String> ruleWarnings(HerdRules r, List<AnimalFacts> herd) {
        List<String> out = new ArrayList<>();
        if (r == null)
            return out;
        for (RankTerm t : r.terms) {
            if (t.kind != RankTerm.Kind.THRESHOLD || t.w1 == t.w2 || t.w1 == 0)
                continue;
            if (t.threshold < minStat(t, herd))
                out.add("\"" + t.label + " 1\" (" + RankTerm.fmt(t.w1) + ") has no effect: every animal is above the threshold ("
                        + RankTerm.fmt(t.threshold) + "), so only \"" + t.label + " 2\" (" + RankTerm.fmt(t.w2) + ") counts.");
        }
        if (!r.anyWeighted())
            out.add("Every rank weight is 0: animals are ranked by effective quality alone.");
        if (r.overbreed < 0 || r.overbreed > 0.3)
            out.add("Overbreed " + RankTerm.fmt(r.overbreed) + " is outside the intended 0 ... 0.3.");
        return out;
    }

    private static int minStat(RankTerm t, List<AnimalFacts> herd) {
        int min = Integer.MAX_VALUE;
        for (AnimalFacts a : herd)
            if (!a.dead)
                min = Math.min(min, a.stat(t.id));
        // With no animals to judge by, assume any positive stat
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    /**
     * Each term's share of how much ranks differ between the living animals (standard deviation of
     * its contribution), or null when no term varies. A big weight on a stat every animal has about
     * equally barely changes the order; this shows that.
     */
    public static double[] influence(HerdRules r, List<AnimalFacts> herd) {
        if (r == null)
            return null;
        List<RankBreakdown> rbs = new ArrayList<>();
        for (AnimalFacts a : herd)
            if (!a.dead)
                rbs.add(r.rank(a));
        int n = r.terms.size();
        double[] sd = new double[n];
        double total = 0;
        if (rbs.isEmpty())
            return null;
        for (int i = 0; i < n; i++) {
            double mean = 0;
            for (RankBreakdown rb : rbs)
                mean += rb.parts[i];
            mean /= rbs.size();
            double var = 0;
            for (RankBreakdown rb : rbs)
                var += (rb.parts[i] - mean) * (rb.parts[i] - mean);
            sd[i] = Math.sqrt(var / rbs.size());
            total += sd[i];
        }
        if (total == 0)
            return null;
        for (int i = 0; i < n; i++)
            sd[i] /= total;
        return sd;
    }

    public static String fmt(Double v) {
        return v == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
