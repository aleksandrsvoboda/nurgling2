package nurgling.styles;

import haven.Resource;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.*;

/** Typography shared by login, character sheets, controls and FPS diagnostics.
 * Keep the compatibility boundary here: resource widgets and saved preferences
 * can still request old families, but cannot reintroduce them into the UI. */
public final class UIFont {
    private UIFont() {}

    public static final Font sans = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    public static final Font regular = load("opensans");
    public static final Font semibold = load("opensans-semibold");
    public static final List<String> FAMILIES = Collections.unmodifiableList(
        Arrays.asList("Open Sans", "Open Sans Semibold", "Sans"));

    private static Font load(String resource) {
        return Resource.local().loadwait("nurgling/font/" + resource)
            .flayer(Resource.Font.class).font.deriveFont(Font.PLAIN);
    }

    public static String family(String name) {
        if(name == null) return "Open Sans";
        if(name.equalsIgnoreCase("Open Sans Semibold") || name.equalsIgnoreCase("Fractur") ||
           name.equalsIgnoreCase("Fraktur")) return "Open Sans Semibold";
        if(name.equalsIgnoreCase("Sans") || name.equalsIgnoreCase("SansSerif") ||
           name.equalsIgnoreCase("Dialog")) return "Sans";
        return "Open Sans";
    }

    public static Font named(String name) {
        switch(family(name)) {
        case "Sans": return sans;
        case "Open Sans Semibold": return semibold;
        default: return regular;
        }
    }

    public static Font normalize(Font font) {
        String family = font.getFamily(Locale.ROOT);
        if(family.equals(regular.getFamily(Locale.ROOT)) ||
           family.equals(semibold.getFamily(Locale.ROOT)) || family.equals(Font.SANS_SERIF))
            return font;
        Map<TextAttribute, Object> attrs = new HashMap<>(font.getAttributes());
        attrs.remove(TextAttribute.FAMILY);
        return named(font.getName()).deriveFont(attrs);
    }

    /** Resolve to an actual bundled font, rather than relying on OS registration.
     * Keep rich-text size, weight, italics, colour, links and underlines intact. */
    public static Map<Attribute, Object> attributes(Map<? extends Attribute, ?> source) {
        Map<Attribute, Object> result = new HashMap<>(source);
        Font base = (Font)source.get(TextAttribute.FONT);
        Object family = source.get(TextAttribute.FAMILY);
        if(base == null) base = named(family instanceof String ? (String)family : "Sans");
        else base = normalize(base);
        Map<Attribute, Object> overrides = new HashMap<>(source);
        overrides.remove(TextAttribute.FONT);
        overrides.remove(TextAttribute.FAMILY);
        result.put(TextAttribute.FONT, base.deriveFont(overrides));
        result.remove(TextAttribute.FAMILY);
        return result;
    }
}
