package nurgling.actions.bots;

import haven.Coord;
import nurgling.NGameUI;
import nurgling.NHitBox;
import nurgling.actions.Build;
import nurgling.areas.NContext;
import nurgling.tools.NAlias;
import nurgling.tools.VSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of all in-game buildings supported by the multi-building bots. Each entry
 * knows its menu name, resource path, display name, tile footprint and how to build
 * its ingredient list via BuildMaterialHelper. Used by the world blueprint editor and
 * its Build From Ghosts flow.
 */
public class BuildCatalog
{
    @FunctionalInterface
    public interface IngredientBuilder
    {
        void add(Build.Command cmd, BuildMaterialHelper helper, NContext context) throws InterruptedException;
    }

    public static final class BuildingDef
    {
        public final String name;
        public final String resName;
        public final String displayName;
        public final String windowName;
        public final boolean useCustomHitbox;
        public final Coord tileFootprint;
        private final IngredientBuilder ingredientBuilder;

        public BuildingDef(String name, String resName, String displayName, String windowName,
                           boolean useCustomHitbox, Coord tileFootprint, IngredientBuilder ingredientBuilder)
        {
            this.name = name;
            this.resName = resName;
            this.displayName = displayName;
            this.windowName = windowName;
            this.useCustomHitbox = useCustomHitbox;
            this.tileFootprint = tileFootprint;
            this.ingredientBuilder = ingredientBuilder;
        }

        public Build.Command buildCommand(NContext context, NGameUI gui) throws InterruptedException
        {
            Build.Command cmd = new Build.Command();
            cmd.name = name;
            if (windowName != null)
                cmd.windowName = windowName;
            if (useCustomHitbox)
                cmd.customHitBox = NHitBox.findCustom(resName);
            BuildMaterialHelper helper = new BuildMaterialHelper(context, gui);
            ingredientBuilder.add(cmd, helper, context);
            return cmd;
        }
    }

    private static final Map<String, BuildingDef> defs = new LinkedHashMap<>();

    static
    {
        register(new BuildingDef(
                "Cupboard", "gfx/terobjs/cupboard", "Cupboard", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> cmd.ingredients.add(h.getBoards(8))));

        register(new BuildingDef(
                "Barrel", "gfx/terobjs/barrel", "Barrel", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> cmd.ingredients.add(h.getBoards(5))));

        register(new BuildingDef(
                "Cheese Rack", "gfx/terobjs/cheeserack", "Cheese Rack", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBoards(6));
                    cmd.ingredients.add(h.getBlocks(4));
                }));

        register(new BuildingDef(
                "Crate", "gfx/terobjs/crate", "Crate", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> cmd.ingredients.add(h.getBoards(4))));

        register(new BuildingDef(
                "Wooden Chest", "gfx/terobjs/chest", "Wooden Chest", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBoards(4));
                    cmd.ingredients.add(h.getNuggets(4));
                }));

        register(new BuildingDef(
                "Drying Frame", "gfx/terobjs/dframe", "Drying Frame", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Branch"),
                            5,
                            "baubles/branchStart",
                            "Please, select area for branch"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(2, 1),
                            new NAlias("Bough"),
                            2,
                            "baubles/boughStart",
                            "Please, select area for bough"));
                    cmd.ingredients.add(h.getStrings(2));
                }));

        register(new BuildingDef(
                "Herbalist Table", "gfx/terobjs/htable", "Herbalist Table", null, false,
                new Coord(1, 2),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBlocks(4));
                    cmd.ingredients.add(h.getBoards(4));
                    cmd.ingredients.add(h.getFinerPlantFibre(8));
                }));

        register(new BuildingDef(
                "Kiln", "gfx/terobjs/kiln", "Kiln", null, false,
                new Coord(2, 2),
                (cmd, h, ctx) -> cmd.ingredients.add(h.getIngredient(
                        new Coord(1, 1),
                        new NAlias("Clay"),
                        35,
                        "baubles/clayPiles",
                        "Please, select area for clay"))));

        register(new BuildingDef(
                "Large Chest", "gfx/terobjs/largechest", "Large Chest", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBoards(5));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Bar of Bronze", "Bar of Cast Iron", "Bar of Wrought Iron"),
                            2,
                            "baubles/mbars",
                            "Please, select area for metal bars"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Leather"),
                            4,
                            "baubles/leather",
                            "Please, select area for leather"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 2),
                            new NAlias("Rope"),
                            2,
                            "baubles/rope",
                            "Please, select area for rope"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Bone Glue"),
                            3,
                            "baubles/glue",
                            "Please, select area for bone glue"));
                }));

        register(new BuildingDef(
                "Mound Bed", "gfx/terobjs/moundbed", "Mound Bed", "Moundbed", true,
                new Coord(3, 2),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Mulch"),
                            12,
                            "baubles/mulchArea",
                            "Please, select area for mulch"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Straw"),
                            6,
                            "baubles/strawArea",
                            "Please, select area for straw"));
                }));

        register(new BuildingDef(
                "Smoke Shed", "gfx/terobjs/smokeshed", "Smoke Shed", null, false,
                new Coord(2, 2),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBoards(12));
                    cmd.ingredients.add(h.getBlocks(4));
                    NAlias thatchAlias = new NAlias("Straw", "Reeds", "Glimmermoss", "Tarsticks", "Brown Kelp");
                    NAlias boughAlias = new NAlias("Bough");
                    if (h.hasZone(thatchAlias))
                    {
                        cmd.ingredients.add(h.getIngredient(
                                new Coord(1, 1),
                                thatchAlias,
                                6,
                                "baubles/tatching",
                                "Please, select area for thatching material"));
                    } else
                    {
                        cmd.ingredients.add(h.getIngredient(
                                new Coord(2, 1),
                                boughAlias,
                                6,
                                "baubles/tatching",
                                "Please, select area for boughs"));
                    }
                    cmd.ingredients.add(h.getBricks(10));
                }));

        register(new BuildingDef(
                "Stone Casket", "gfx/terobjs/stonecasket", "Stone Casket", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getStone(20));
                    cmd.ingredients.add(h.getNuggets(2));
                }));

        register(new BuildingDef(
                "Tar Kiln", "gfx/terobjs/tarkiln", "Tar Kiln", null, false,
                new Coord(2, 2),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            VSpec.getNamesInCategory("Stone"),
                            35,
                            "baubles/chipperPiles",
                            "Please, select area for stone"));
                    cmd.ingredients.add(h.getIngredient(
                            new Coord(1, 1),
                            new NAlias("Clay"),
                            50,
                            "baubles/clayPiles",
                            "Please, select area for clay"));
                }));

        register(new BuildingDef(
                "Tanning Tub", "gfx/terobjs/ttub", "Tanning Tub", null, false,
                new Coord(1, 1),
                (cmd, h, ctx) -> {
                    cmd.ingredients.add(h.getBoards(4));
                    cmd.ingredients.add(h.getBlocks(2));
                }));
    }

    private static void register(BuildingDef def) { defs.put(def.name, def); }

    public static BuildingDef get(String name) { return defs.get(name); }

    public static List<BuildingDef> all() { return Collections.unmodifiableList(new ArrayList<>(defs.values())); }

    public static Build.Command commandFor(String name, NContext context, NGameUI gui) throws InterruptedException
    {
        BuildingDef def = defs.get(name);
        if (def == null) return null;
        return def.buildCommand(context, gui);
    }
}
