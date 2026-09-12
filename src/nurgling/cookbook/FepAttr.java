package nurgling.cookbook;

import java.awt.Color;
import java.util.Locale;

/**
 * The nine attributes a food event raises, as the cookbook shows them.
 *
 * <p>Recipes store their FEPs under the game's display names ("Strength +2"). {@link #of} parses
 * those and answers {@link #UNKNOWN} for anything it does not recognise instead of failing, so a
 * renamed or localised event still shows up, just uncoloured.
 */
public enum FepAttr {
    STR("str", "Strength", new Color(249, 151, 144), new Color(236, 79, 68)),
    AGI("agi", "Agility", new Color(115, 146, 255), new Color(53, 81, 226)),
    INT("int", "Intelligence", new Color(154, 248, 255), new Color(2, 176, 189)),
    CON("con", "Constitution", new Color(242, 127, 202), new Color(194, 20, 133)),
    PER("per", "Perception", new Color(249, 185, 118), new Color(217, 113, 3)),
    CHA("cha", "Charisma", new Color(128, 255, 161), new Color(0, 203, 54)),
    DEX("dex", "Dexterity", new Color(255, 245, 153), new Color(227, 206, 13)),
    WIL("wil", "Will", new Color(229, 255, 85), new Color(169, 199, 0)),
    PSY("psy", "Psyche", new Color(181, 109, 255), new Color(116, 66, 168)),
    UNKNOWN("?", "?", new Color(159, 176, 196), new Color(159, 176, 196));

    /** Every attribute but UNKNOWN, in the order the cookbook lists them. */
    public static final FepAttr[] KNOWN = {STR, AGI, INT, CON, PER, CHA, DEX, WIL, PSY};

    /** Minimum relative luminance of text drawn on the cookbook's dark background. */
    static final double READABLE = 0.45;

    public final String code;
    public final String title;
    private final Color swatch1, swatch2, text1, text2;

    FepAttr(String code, String title, Color swatch1, Color swatch2) {
        this.code = code;
        this.title = title;
        this.swatch1 = swatch1;
        this.swatch2 = swatch2;
        this.text1 = readable(swatch1);
        this.text2 = readable(swatch2);
    }

    /** The attribute of a stored FEP name such as "Strength +2". */
    public static FepAttr of(String fepName) {
        if(fepName == null)
            return UNKNOWN;
        String base = fepName.replaceFirst("\\s*\\+\\d+\\s*$", "").trim();
        for(FepAttr a : KNOWN) {
            if(a.title.equalsIgnoreCase(base))
                return a;
        }
        return UNKNOWN;
    }

    /** The tier of a stored FEP name: 2 for "... +2", otherwise 1. */
    public static int tierOf(String fepName) {
        return ((fepName != null) && fepName.trim().endsWith("+2")) ? 2 : 1;
    }

    /** An attribute by its short code, accepting the Kitten Rider spellings prc and csm too. */
    public static FepAttr byCode(String code) {
        if(code == null)
            return UNKNOWN;
        String c = code.toLowerCase(Locale.ROOT);
        if(c.equals("prc"))
            return PER;
        if(c.equals("csm"))
            return CHA;
        for(FepAttr a : KNOWN) {
            if(a.code.equals(c))
                return a;
        }
        return UNKNOWN;
    }

    /** The stored FEP name of a tier, e.g. "Will +1". */
    public String fepName(int tier) {
        return title + " +" + tier;
    }

    /** The key the FEPs filter uses for a tier, e.g. "str2". */
    public String key(int tier) {
        return code + tier;
    }

    /** The short label a FEP chip shows, e.g. "str" or "str+2". */
    public String label(int tier) {
        return (tier > 1) ? code + "+2" : code;
    }

    /** The attribute's own colour, for swatches. */
    public Color swatch(int tier) {
        return (tier > 1) ? swatch2 : swatch1;
    }

    /** The colour to write the attribute's values in, lightened until it reads on a dark background. */
    public Color color(int tier) {
        return (tier > 1) ? text2 : text1;
    }

    static double luminance(Color c) {
        return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
    }

    /* Mixes the colour towards white in 12% steps until its luminance reaches READABLE -- the same
     * rule the Kitten Rider cookbook uses for its FEP chips. Dark +2 colours need it the most. */
    static Color readable(Color c) {
        double r = c.getRed(), g = c.getGreen(), b = c.getBlue();
        for(int i = 0; (i < 40) && (((0.2126 * r) + (0.7152 * g) + (0.0722 * b)) / 255.0 < READABLE); i++) {
            r += (255 - r) * 0.12;
            g += (255 - g) * 0.12;
            b += (255 - b) * 0.12;
        }
        return new Color((int)Math.ceil(r), (int)Math.ceil(g), (int)Math.ceil(b));
    }
}
