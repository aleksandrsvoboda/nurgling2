package nurgling.styles;

import haven.Resource;
import java.awt.Font;
import java.util.Locale;

/** New UI typography: Open Sans in place of the client's assorted fonts. Decided at client
 * start like the image swaps, because rendered text is cached everywhere. Saved font
 * settings are never rewritten; with New UI off the original fonts are used. */
public final class UIFont {
    private UIFont() {}

    public static final Font regular = load("opensans");
    public static final Font semibold = load("opensans-semibold");

    private static Font load(String resource) {
        return Resource.local().loadwait("nurgling/font/" + resource)
            .flayer(Resource.Font.class).font.deriveFont(Font.PLAIN);
    }

    public static boolean active() {
        return UIResources.active();
    }

    private static boolean mono(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("mono") || n.contains("courier") || n.contains("code") || n.contains("consol");
    }

    /** True for families that become Open Sans: everything except Open Sans itself and monospace. */
    public static boolean replaces(String family) {
        return (family != null) && !mono(family) && !family.startsWith("Open Sans") &&
            !family.equals(regular.getFamily(Locale.ROOT)) && !family.equals(semibold.getFamily(Locale.ROOT));
    }

    /** Open Sans in place of a font, keeping size and italics; bold and Fraktur become Semibold.
     * Monospaced fonts are kept, so the console stays aligned. */
    public static Font replace(Font f) {
        String family = f.getFamily(Locale.ROOT), name = f.getName();
        if(!replaces(family) || !replaces(name))
            return f;
        boolean heavy = f.isBold() || name.toLowerCase(Locale.ROOT).startsWith("fra");
        return (heavy ? semibold : regular).deriveFont(f.getStyle() & Font.ITALIC, f.getSize2D());
    }
}
