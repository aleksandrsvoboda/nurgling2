package nurgling.tools;

import nurgling.NGItem;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared knowledge about the containers a character can carry water in.
 *
 * Pure functions only - no inventory or widget queries. DrinkMeter runs on the UI
 * thread and NInventory queries block on NCore, so anything that scans containers
 * must stay in the caller.
 */
public class DrinkContainers
{
    /** Personal drink containers: belt, feet, inventory. */
    public static final NAlias ALL = new NAlias("Waterskin", "Glass Jug", "Waterflask", "Kuksa");

    /** Buckets are carried in hands and hold far more, so they are handled separately. */
    public static final NAlias BUCKET = new NAlias("Bucket");

    public static final float BUCKET_CAPACITY = 10.0f;
    public static final float WATERSKIN_CAPACITY = 3.0f;
    public static final float GLASSJUG_CAPACITY = 5.0f;
    public static final float WATERFLASK_CAPACITY = 2.0f;
    public static final float KUKSA_CAPACITY = 0.8f;

    /** Matches item content lines such as "1.5 l of Water". */
    private static final Pattern CONTENT_PATTERN = Pattern.compile("([\\d.]+)\\s*l\\s+of\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /** Litres below which a container counts as full - the server never fills to an exact float. */
    private static final float FULL_EPSILON = 0.05f;

    /** Capacity in litres, or 0 when the name is not a known container. */
    public static float capacity(String name)
    {
        if (name == null)
            return 0f;
        if (NParser.checkName(name, BUCKET))
            return BUCKET_CAPACITY;
        if (NParser.checkName(name, "Waterskin"))
            return WATERSKIN_CAPACITY;
        if (NParser.checkName(name, "Glass Jug"))
            return GLASSJUG_CAPACITY;
        if (NParser.checkName(name, "Waterflask"))
            return WATERFLASK_CAPACITY;
        if (NParser.checkName(name, "Kuksa"))
            return KUKSA_CAPACITY;
        return 0f;
    }

    /** True for any container this class knows how to fill, buckets included. */
    public static boolean isContainer(String name)
    {
        return name != null && (NParser.checkName(name, ALL) || NParser.checkName(name, BUCKET));
    }

    /** Litres currently inside, summed over every content line. */
    public static float amount(NGItem item)
    {
        if (item == null)
            return 0f;
        float total = 0f;
        for (NGItem.NContent content : item.content()) {
            String name = content.name();
            if (name == null)
                continue;
            Matcher m = CONTENT_PATTERN.matcher(name);
            if (m.find()) {
                try {
                    total += Float.parseFloat(m.group(1));
                } catch (NumberFormatException ignored) {
                    // Malformed content line, ignore this one
                }
            }
        }
        return total;
    }

    /**
     * True when the container holds drinkable water.
     *
     * Note "Saltwater" does not match - the capital W in "Water" is what separates
     * fresh water from the ocean, and the rest of the codebase relies on the same
     * distinction.
     */
    public static boolean isWater(NGItem item)
    {
        if (item == null)
            return false;
        for (NGItem.NContent content : item.content()) {
            if (content.name() != null && content.name().contains("Water"))
                return true;
        }
        return false;
    }

    /**
     * True when a water source can add something to this container: either it is
     * empty, or it already holds water and is not yet full.
     *
     * Containers holding tea, milk, saltwater or anything else are left alone.
     */
    public static boolean isFillable(NGItem item)
    {
        if (item == null)
            return false;
        if (item.content().isEmpty())
            return true;
        if (!isWater(item))
            return false;
        float cap = capacity(item.name());
        return cap > 0f && amount(item) < cap - FULL_EPSILON;
    }
}
