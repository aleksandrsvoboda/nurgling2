package nurgling.colony;

import nurgling.tools.VSpec;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The items the colony counts, by in-game name: building materials, tool-chain ingredients and tools. */
public final class ColonyItems {
    private ColonyItems() {}

    public enum Material {
        CLAY("Clay"), STONE("Stone"), BOARD("Board"), BLOCK("Block of Wood"),
        BONE("Bone Material"), BRANCH(null), BOUGH(null);

        /** The VSpec category listing the names, or null for a rule in {@link #of}. */
        final String category;

        Material(String category) {
            this.category = category;
        }
    }

    public enum Tool { AXE, SAW, SHOVEL }

    private static volatile Map<Material, Set<String>> names;

    private static Map<Material, Set<String>> names() {
        Map<Material, Set<String>> n = names;
        if (n == null) {
            n = new EnumMap<>(Material.class);
            for (Material m : Material.values()) {
                if (m.category == null)
                    continue;
                Set<String> s = new HashSet<>(VSpec.getNamesInCategory(m.category).getKeys());
                n.put(m, Collections.unmodifiableSet(s));
            }
            names = n;
        }
        return n;
    }

    /** Every item name of a VSpec material (clay, stone, boards, blocks, bones); empty for the others. */
    public static Set<String> names(Material m) {
        Set<String> s = names().get(m);
        return (s == null) ? Collections.emptySet() : s;
    }

    /** The material an item of this name counts as, or null. */
    public static Material material(String name) {
        if (name == null)
            return null;
        for (Map.Entry<Material, Set<String>> e : names().entrySet())
            if (e.getValue().contains(name))
                return e.getKey();
        if (name.equals("Branch"))
            return Material.BRANCH;
        if (name.endsWith(" Bough"))
            return Material.BOUGH;
        return null;
    }

    /** The material a construction-sign box asks for, by its tooltip ("Clay", "Block of Wood", or one item's name). */
    public static Material slot(String name) {
        if (name == null)
            return null;
        for (Material m : Material.values())
            if (name.equals(m.category))
                return m;
        return material(name);
    }

    /** The tool an item of this name is, or null. "Pickaxe" is no axe; the Bone Saw is called "Bonesaw". */
    public static Tool tool(String name) {
        if (name == null)
            return null;
        if (name.endsWith("Axe") && !name.equals("Pickaxe"))
            return Tool.AXE;
        if (name.endsWith(" Saw") || name.equals("Bonesaw"))
            return Tool.SAW;
        if (name.endsWith("Shovel"))
            return Tool.SHOVEL;
        return null;
    }
}
