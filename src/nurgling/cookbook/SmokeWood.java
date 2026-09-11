package nurgling.cookbook;

import haven.ItemInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * One wood from a food's "Smoked with ..." tooltip line.
 *
 * <p>The game sends one {@code haven.res.ui.tt.smoked.Smoke} info per wood. That class ships from the
 * resource server, not with the client, so its public {@code name} and {@code val} fields are read
 * by reflection. {@code val} is the wood's share of the smoke and is null when the game shows none.
 */
public class SmokeWood {
    public final String name;
    /** Share of the smoke in percent; 100 when the game sends no share. */
    public final double percentage;

    private static volatile boolean warned = false;

    public SmokeWood(String name, double percentage) {
        this.name = name;
        this.percentage = percentage;
    }

    /** The smoking woods among an item's infos, sorted by name like the game's tooltip. */
    public static List<SmokeWood> from(Collection<? extends ItemInfo> info) {
        List<SmokeWood> woods = new ArrayList<>();
        if (info == null) {
            return woods;
        }
        for (ItemInfo inf : info) {
            Class<?> cl = inf.getClass();
            if (!cl.getSimpleName().equals("Smoke") || !cl.getName().contains("smoked")) {
                continue;
            }
            try {
                String name = (String) cl.getField("name").get(inf);
                Double val = (Double) cl.getField("val").get(inf);
                if (name != null && !name.isEmpty()) {
                    woods.add(new SmokeWood(name, val != null ? val * 100 : 100.0));
                }
            } catch (ReflectiveOperationException | ClassCastException e) {
                /* The server changed the class. Once per session is enough to notice; recipes
                 * keep being saved, just without their wood. */
                if (!warned) {
                    warned = true;
                    System.out.println("[Cookbook] cannot read smoking wood from " + cl.getName() + ": " + e);
                }
            }
        }
        woods.sort(Comparator.comparing((SmokeWood w) -> w.name));
        return woods;
    }

    /**
     * The woods as they enter the recipe hash: name then percentage, per wood.
     *
     * <p>A single wood with no share reads "Apple tree100.0", the same text the hash carried back when
     * every wood was recorded at 100%, so those recipes keep their hash. Changing this format re-keys
     * every smoked recipe in every village database.
     */
    public static String signature(List<SmokeWood> woods) {
        StringBuilder sb = new StringBuilder();
        for (SmokeWood w : woods) {
            sb.append(w.name).append(w.percentage);
        }
        return sb.toString();
    }
}
