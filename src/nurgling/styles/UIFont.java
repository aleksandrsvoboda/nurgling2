package nurgling.styles;

import haven.Resource;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    /** Rich-text attributes with an Open Sans FONT in place of the requested family, so it does
     * not depend on the font being installed. Size, posture and weight carry over (bold becomes
     * Semibold); colours and links are kept. An explicit FONT is replaced as such, since AWT
     * ignores the other font attributes when FONT is present. Monospace stays as requested. */
    public static Map<Attribute, Object> attributes(Map<? extends Attribute, ?> src) {
        Map<Attribute, Object> ret = new HashMap<>(src);
        Object font = src.get(TextAttribute.FONT);
        if(font instanceof Font) {
            ret.put(TextAttribute.FONT, replace((Font)font));
            return(ret);
        }
        Object family = src.get(TextAttribute.FAMILY);
        if((family instanceof String) && !replaces((String)family))
            return(ret);
        Object weight = src.get(TextAttribute.WEIGHT);
        boolean heavy = (weight instanceof Number) && (((Number)weight).floatValue() >= TextAttribute.WEIGHT_SEMIBOLD);
        Map<Attribute, Object> derive = new HashMap<>();
        Object size = src.get(TextAttribute.SIZE);
        derive.put(TextAttribute.SIZE, (size instanceof Number) ? ((Number)size).floatValue() : 12f);
        if(src.containsKey(TextAttribute.POSTURE))
            derive.put(TextAttribute.POSTURE, src.get(TextAttribute.POSTURE));
        ret.remove(TextAttribute.FAMILY);
        ret.remove(TextAttribute.WEIGHT);
        ret.put(TextAttribute.FONT, (heavy ? semibold : regular).deriveFont(derive));
        return(ret);
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
