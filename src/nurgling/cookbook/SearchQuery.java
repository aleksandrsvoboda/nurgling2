package nurgling.cookbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cookbook's search box. Parts separated by {@code ;} must all match:
 *
 * <ul>
 * <li>plain text -- found in the dish name, its ingredients or its smoking woods</li>
 * <li>{@code name:roulade}, {@code name:"Salt Roulade"} (exact), {@code -name:troll}</li>
 * <li>{@code from:pork}, {@code from:"Pork"} (exact), {@code -from:salt} -- ingredients and woods</li>
 * <li>{@code str2>5} -- Strength +2 above 5; without the 2 it is the +1. Operators: {@code < <= = >= >}</li>
 * <li>{@code wil>=30%} -- Will +1 is at least 30% of the dish's total FEP</li>
 * </ul>
 *
 * Everything is matched in memory against loaded rows; nothing typed here reaches the database.
 * A part that does not parse as a token is plain text, so a typo never throws.
 */
public final class SearchQuery {
    private static final Pattern STAT = Pattern.compile(
        "(str|agi|int|con|per|prc|cha|csm|dex|wil|psy)(2?)\\s*(<=|>=|<|>|=)\\s*(\\d+(?:\\.\\d+)?)\\s*(%?)",
        Pattern.CASE_INSENSITIVE);

    private final List<Predicate<CookbookRow>> terms;

    private SearchQuery(List<Predicate<CookbookRow>> terms) {
        this.terms = terms;
    }

    public static SearchQuery parse(String text) {
        List<Predicate<CookbookRow>> terms = new ArrayList<>();
        if(text != null) {
            for(String raw : text.split(";")) {
                Predicate<CookbookRow> term = term(raw.trim());
                if(term != null)
                    terms.add(term);
            }
        }
        return new SearchQuery(terms);
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    public boolean test(CookbookRow row) {
        for(Predicate<CookbookRow> t : terms) {
            if(!t.test(row))
                return false;
        }
        return true;
    }

    private static Predicate<CookbookRow> term(String part) {
        if(part.isEmpty())
            return null;
        String lower = part.toLowerCase(Locale.ROOT);
        if(lower.startsWith("-name:"))
            return negate(nameTerm(part.substring(6)));
        if(lower.startsWith("name:"))
            return nameTerm(part.substring(5));
        if(lower.startsWith("-from:"))
            return negate(fromTerm(part.substring(6)));
        if(lower.startsWith("from:"))
            return fromTerm(part.substring(5));
        Matcher m = STAT.matcher(part);
        if(m.matches())
            return statTerm(m);
        return row -> row.haystack.contains(lower);
    }

    private static Predicate<CookbookRow> negate(Predicate<CookbookRow> p) {
        return (p == null) ? null : p.negate();
    }

    private static Predicate<CookbookRow> nameTerm(String value) {
        String v = value.trim();
        if(isQuoted(v)) {
            String exact = CookbookRow.lower(v.substring(1, v.length() - 1));
            return row -> row.nameKey.equals(exact);
        }
        if(v.isEmpty())
            return null;
        String needle = CookbookRow.lower(v);
        return row -> row.nameKey.contains(needle);
    }

    private static Predicate<CookbookRow> fromTerm(String value) {
        String v = value.trim();
        if(isQuoted(v)) {
            String exact = CookbookRow.lower(v.substring(1, v.length() - 1));
            return row -> row.sourceKeys.contains(exact);
        }
        if(v.isEmpty())
            return null;
        String needle = CookbookRow.lower(v);
        return row -> {
            for(String s : row.sourceKeys) {
                if(s.contains(needle))
                    return true;
            }
            return false;
        };
    }

    private static Predicate<CookbookRow> statTerm(Matcher m) {
        FepAttr attr = FepAttr.byCode(m.group(1));
        int tier = m.group(2).isEmpty() ? 1 : 2;
        String op = m.group(3);
        double limit = Double.parseDouble(m.group(4));
        boolean share = !m.group(5).isEmpty();
        if(share) {
            /* A dish without the FEP holds 0% of it. */
            return row -> compare((row.total > 0) ? row.fepValue(attr, tier) / row.total * 100 : 0, op, limit);
        }
        /* An absolute bound only matches dishes that have the FEP at all, as the old SQL search did. */
        return row -> row.hasFep(attr, tier) && compare(row.fepValue(attr, tier), op, limit);
    }

    private static boolean compare(double v, String op, double limit) {
        switch(op) {
            case "<":
                return v < limit;
            case "<=":
                return v <= limit;
            case ">":
                return v > limit;
            case ">=":
                return v >= limit;
            default:
                return Math.abs(v - limit) < 1e-6;
        }
    }

    private static boolean isQuoted(String v) {
        return (v.length() >= 2) && v.startsWith("\"") && v.endsWith("\"");
    }
}
