package nurgling.cookbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What sprinkling a spice would do to a dish -- a what-if, since a sprinkle is not part of the
 * recipe. Ported from the Kitten Rider cookbook (cookbook.kittenrider.com, About, "Spices are a
 * what-if"), whose multipliers were measured in game:
 *
 * <ul>
 * <li>Black truffle: every FEP x 0.84*sqrt(q/10), plus a Will +1 worth a quarter of the result.</li>
 * <li>White truffle: the same, with the quarter-share going to Strength +1. Its multiplier is
 *     carried over from black truffle and has not been confirmed directly.</li>
 * <li>Pepper: every FEP and the satiation x (1 + 0.5*sqrt(q/10)).</li>
 * </ul>
 *
 * A truffle scales satiation by how much the tier-weighted FEP sum grew, so where every FEP is a
 * +1 it leaves F/H unchanged. Salt is measured to change nothing and has no entry. Energy is never
 * touched. Spices apply in declaration order.
 */
public final class SpiceCalc {
    public enum Spice {
        BLACK_TRUFFLE(FepAttr.WIL),
        WHITE_TRUFFLE(FepAttr.STR),
        PEPPER(null);

        /** The attribute a truffle adds its quarter-share to; null for a plain multiplier. */
        public final FepAttr adds;

        Spice(FepAttr adds) {
            this.adds = adds;
        }

        /** The factor every FEP is multiplied by at the given spice quality. */
        public double multiplier(double quality) {
            double s = Math.sqrt(quality / 10.0);
            return (adds == null) ? 1 + 0.5 * s : 0.84 * s;
        }
    }

    public static final class Result {
        public final List<FepValue> feps;
        public final double hunger;

        Result(List<FepValue> feps, double hunger) {
            this.feps = feps;
            this.hunger = hunger;
        }
    }

    private SpiceCalc() {
    }

    /**
     * The FEPs and satiation of a dish as if sprinkled with each spice in {@code qualities} whose
     * quality is above zero. With no such spice it returns the input unchanged.
     */
    public static Result apply(List<FepValue> feps, double hunger, Map<Spice, Double> qualities) {
        List<FepValue> cur = new ArrayList<>(feps);
        double h = hunger;
        for(Spice s : Spice.values()) {
            Double q = qualities.get(s);
            if((q == null) || !(q > 0))
                continue;
            double m = s.multiplier(q);
            if(s.adds == null) {
                cur = scale(cur, m);
                h *= m;
                continue;
            }
            if(cur.isEmpty())
                continue;
            double total = sum(cur), before = weighted(cur);
            cur = scale(cur, m);
            double extra = 0.25 * m * total;
            int i = indexOf(cur, s.adds, 1);
            if(i >= 0)
                cur.set(i, cur.get(i).withValue(cur.get(i).value + extra));
            else
                cur.add(new FepValue(s.adds, 1, s.adds.fepName(1), extra));
            if(before > 0)
                h *= weighted(cur) / before;
        }
        return new Result(cur, h);
    }

    private static List<FepValue> scale(List<FepValue> feps, double m) {
        List<FepValue> ret = new ArrayList<>(feps.size());
        for(FepValue f : feps)
            ret.add(f.withValue(f.value * m));
        return ret;
    }

    private static double sum(List<FepValue> feps) {
        double s = 0;
        for(FepValue f : feps)
            s += f.value;
        return s;
    }

    private static double weighted(List<FepValue> feps) {
        double s = 0;
        for(FepValue f : feps)
            s += f.tier * f.value;
        return s;
    }

    private static int indexOf(List<FepValue> feps, FepAttr attr, int tier) {
        for(int i = 0; i < feps.size(); i++) {
            if(feps.get(i).is(attr, tier))
                return i;
        }
        return -1;
    }
}
