package nurgling.animals;

import java.awt.Color;

/** Spells out one animal's rank as RichText markup, with that animal's own numbers. */
public final class RankExplainer {
    private RankExplainer() {
    }

    public static String explain(AnimalFacts a, RankBreakdown rb) {
        StringBuilder b = new StringBuilder();
        if (!rb.bound()) {
            b.append("$b{Rank 0}\nNo rank preset is bound to this animal's area,\nso every animal there ranks 0.");
            return b.toString();
        }
        HerdRules r = rb.rules;
        b.append("$b{Rank ").append(CullPlanner.fmt(rb.rank)).append("}  (preset \"").append(quote(r.preset)).append("\")\n");
        b.append("Effective quality ").append(CullPlanner.fmt(rb.eq)).append(": ").append(eqReason(a, r)).append('\n');
        if (rb.fallback) {
            b.append("Every term is 0, so the rank is the effective quality.");
            return b.toString();
        }
        for (int i = 0; i < rb.parts.length; i++) {
            RankTerm t = r.terms.get(i);
            if (rb.parts[i] == 0 && !t.weighted())
                continue;
            b.append("  ").append(colored(t.label, t.color)).append(": ").append(CullPlanner.fmt(rb.parts[i]))
                    .append(" = ").append(termReason(t, a.stat(t.id), rb.eq, r.ignoreQualityForPercent)).append('\n');
        }
        b.append("Sum ").append(CullPlanner.fmt(rb.sum)).append(", rounded to 0.1");
        return b.toString();
    }

    static String eqReason(AnimalFacts a, HerdRules r) {
        if (!r.usesBreeding(a.male))
            return "Q " + num(a.q) + " (breeding ignored for females)";
        int t = r.target(a.seedq);
        String target = "target " + t + " (breeding Q " + a.seedq + " - gap " + r.gap + ")";
        if (a.q > t)
            return "Q " + num(a.q) + " is above " + target + ", so it is pulled halfway down to it";
        if (r.overbreed == 0)
            return "Q " + num(a.q) + " is not above " + target + "; overbreed 0 adds nothing";
        return "Q " + num(a.q) + " is not above " + target + "; overbreed " + RankTerm.fmt(r.overbreed) + " lifts it toward it";
    }

    static String termReason(RankTerm t, int v, double eq, boolean ignoreQuality) {
        switch (t.kind) {
            case PERCENT:
                return (ignoreQuality ? "" : "EQ " + CullPlanner.fmt(eq) + " x ") + RankTerm.fmt(t.w1) + " x " + v + "%";
            case THRESHOLD:
                if (v > t.threshold)
                    return RankTerm.fmt(t.threshold) + " x " + RankTerm.fmt(t.w1) + " + " + RankTerm.fmt(v - t.threshold)
                            + " above the threshold x " + RankTerm.fmt(t.w2);
                return v + " x " + RankTerm.fmt(t.w1) + " (not above the threshold " + RankTerm.fmt(t.threshold) + ")";
            default:
                return RankTerm.fmt(t.w1) + " x " + v;
        }
    }

    static String colored(String s, Color c) {
        return "$col[" + c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "]{" + quote(s) + "}";
    }

    private static String num(double q) {
        return (q == Math.rint(q)) ? Long.toString((long) q) : CullPlanner.fmt(q);
    }

    /** Same escaping as RichText.Parser.quote, without loading the UI text classes. */
    public static String quote(String in) {
        if (in == null)
            return "";
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if ((c == '$') || (c == '{') || (c == '}'))
                buf.append('$');
            buf.append(c);
        }
        return buf.toString();
    }
}
