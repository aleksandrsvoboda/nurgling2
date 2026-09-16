package nurgling.cookbook;

/** One food event of a dish: which attribute, which tier, and how much at quality 10. */
public final class FepValue {
    public final FepAttr attr;
    public final int tier;
    /** The name the recipe stores it under, e.g. "Strength +2". */
    public final String name;
    public final double value;

    public FepValue(FepAttr attr, int tier, String name, double value) {
        this.attr = attr;
        this.tier = tier;
        this.name = name;
        this.value = value;
    }

    /** A FEP from its stored name, e.g. {@code of("Strength +2", 6.8)}. */
    public static FepValue of(String name, double value) {
        return new FepValue(FepAttr.of(name), FepAttr.tierOf(name), name, value);
    }

    public FepValue withValue(double value) {
        return new FepValue(attr, tier, name, value);
    }

    /** Whether this is the given attribute and tier. */
    public boolean is(FepAttr attr, int tier) {
        return (this.attr == attr) && (this.tier == tier);
    }
}
