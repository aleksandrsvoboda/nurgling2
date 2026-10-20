package nurgling.tools;

import haven.ItemInfo;
import haven.Loading;
import haven.WItem;
import haven.res.ui.tt.ingred.Ingredient;
import nurgling.NGItem;
import org.json.JSONObject;

import java.util.*;

/**
 * Kinds of one game item that areas and autocraft tell apart although the game names them alike.
 * Butter is "Butter" whatever it was churned from: cow, goat and sheep butter carry an Ingredient
 * line naming their milk ("Made with Cowsmilk"), butter from generic milk carries none. Such an item
 * is routed under a kind key, "Butter (Cowsmilk)", while NGItem.name() stays the game's name, which
 * stack sizes, the storage index and icons are keyed on. A plain "Butter" entry keeps meaning any kind.
 * <p>
 * Milk itself is a liquid in barrels, named by kind ("12 l of Cowsmilk"); the kinds are listed here
 * so that an area can hold a barrel of one kind and a make-window Milk input can ask for one.
 */
public final class ItemVariants {
    private ItemVariants() {}

    public static final String BUTTER = "Butter";
    public static final String MILK = "Milk";
    public static final List<String> MILKS = List.of("Cowsmilk", "Goatsmilk", "Sheepsmilk");
    /** The kind of butter whose tooltip names no milk. */
    public static final String GENERIC = "Generic";
    /** The area and recipe name for a barrel of generic milk; a plain "Milk" entry means any milk. */
    public static final String GENERIC_MILK = "Generic Milk";

    private static final String BUTTER_RES = "gfx/invobjs/butter";
    private static final String MILK_RES = "gfx/invobjs/milk";

    private static final List<String> KINDS;
    static {
        List<String> kinds = new ArrayList<>(MILKS);
        kinds.add(GENERIC);
        KINDS = Collections.unmodifiableList(kinds);
    }

    /** Whether items of this game name come in kinds. */
    public static boolean has(String name) {
        return BUTTER.equals(name);
    }

    /** "Butter" + "Cowsmilk" -> "Butter (Cowsmilk)". */
    public static String key(String name, String kind) {
        return name + " (" + kind + ")";
    }

    /** Whether a name is a kind key such as "Butter (Cowsmilk)". */
    public static boolean isKey(String key) {
        return kind(key) != null;
    }

    /** The kind a key names, or null when the name is not a kind key. */
    public static String kind(String key) {
        if(key == null || !key.endsWith(")"))
            return null;
        int open = key.lastIndexOf(" (");
        if(open <= 0)
            return null;
        String kind = key.substring(open + 2, key.length() - 1);
        return (has(key.substring(0, open)) && KINDS.contains(kind)) ? kind : null;
    }

    /** The game name a key stands for: "Butter (Cowsmilk)" -> "Butter"; any other name unchanged. */
    public static String base(String key) {
        return isKey(key) ? key.substring(0, key.lastIndexOf(" (")) : key;
    }

    /** Every kind key of an item, or an empty list for an item that has no kinds. */
    public static List<String> keys(String name) {
        if(!has(name))
            return List.of();
        List<String> ret = new ArrayList<>();
        for(String kind : KINDS)
            ret.add(key(name, kind));
        return ret;
    }

    /** "Butter made with Cowsmilk" for a kind key, null for any other name. */
    public static String describe(String key) {
        String kind = kind(key);
        if(kind == null)
            return null;
        return base(key) + " made with " + (GENERIC.equals(kind) ? "generic milk" : kind);
    }

    /**
     * The kind of an item in hand: its milk's name, or GENERIC once its tooltip has arrived and names
     * none. Null for an item without kinds, and while the tooltip has not arrived or still loads, so
     * that an unread butter is never taken for generic.
     */
    public static String kindOf(NGItem item) {
        if(item == null || !has(item.name()) || item.infoseq <= 0)
            return null;
        List<ItemInfo> info;
        try {
            info = item.info();
        } catch(Loading l) {
            return null;
        }
        for(ItemInfo inf : info) {
            if(inf instanceof Ingredient && MILKS.contains(((Ingredient) inf).name))
                return ((Ingredient) inf).name;
        }
        return GENERIC;
    }

    /** The item's kind key ("Butter (Cowsmilk)"), or null when it has no kind or it is not known yet. */
    public static String keyOf(NGItem item) {
        String kind = kindOf(item);
        return (kind == null) ? null : key(item.name(), kind);
    }

    /** Whether the item's kind is not known yet although its name has kinds. */
    public static boolean unread(NGItem item) {
        return item != null && has(item.name()) && kindOf(item) == null;
    }

    /** Whether any of these items still waits for the tooltip its kind is read from. */
    public static boolean anyUnread(Collection<WItem> items) {
        for(WItem w : items) {
            if(w.item instanceof NGItem && unread((NGItem) w.item))
                return true;
        }
        return false;
    }

    /** Whether an item is what a name asks for: that kind for a kind key, else the exact game name. */
    public static boolean matches(NGItem item, String name) {
        if(item == null || item.name() == null)
            return false;
        if(!isKey(name))
            return item.name().equals(name);
        return item.name().equals(base(name)) && name.equals(keyOf(item));
    }

    /**
     * The liquid a barrel must hold for an input or area entry naming one kind of milk: "Cowsmilk"
     * for "Cowsmilk", "Milk" for "Generic Milk". Null for any other name, a plain "Milk" included,
     * which takes any milk as it always has.
     */
    public static String liquidOf(String name) {
        if(MILKS.contains(name))
            return name;
        return GENERIC_MILK.equals(name) ? MILK : null;
    }

    /** The barrel entries for each kind of milk. */
    public static List<String> milkEntries() {
        List<String> ret = new ArrayList<>(MILKS);
        ret.add(GENERIC_MILK);
        return ret;
    }

    /**
     * Picker options a make-window input gains here: the kinds of butter for a Butter input, every
     * kind of milk for a Milk input. They share one icon, so the caller must not de-duplicate them
     * by icon. Empty for any other input.
     */
    public static List<JSONObject> craftOptions(String input) {
        List<JSONObject> ret = new ArrayList<>();
        if(has(input)) {
            for(String key : keys(input))
                ret.add(option(BUTTER_RES, key));
        } else if(MILK.equals(input)) {
            for(String name : milkEntries())
                ret.add(option(MILK_RES, name));
        }
        return ret;
    }

    /** Catalog groups for the area item picker: each kind of milk and each kind of butter. */
    public static Map<String, List<JSONObject>> catalog() {
        Map<String, List<JSONObject>> ret = new LinkedHashMap<>();
        ret.put(MILK, craftOptions(MILK));
        ret.put(BUTTER, craftOptions(BUTTER));
        return ret;
    }

    private static JSONObject option(String res, String name) {
        return new JSONObject().put("static", res).put("name", name);
    }
}
