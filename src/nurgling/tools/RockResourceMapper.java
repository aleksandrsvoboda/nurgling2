package nurgling.tools;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps bumbling rock gobs to their corresponding tile resources.
 * When a user selects a bumbling icon (e.g., feldspar rock on ground),
 * this mapper identifies which mine tiles should also be highlighted.
 */
public class RockResourceMapper {

    // Map from bumbling/inventory resource name to tile resource name
    private static final Map<String, String> gobToTileMap = new HashMap<>();
    // Map from a human-readable item/display name to the tile resource it comes from
    private static final Map<String, String> itemToTileMap = new HashMap<>();

    static {
        // Register all rock type mappings
        registerRock("feldspar");
        registerRock("basalt");
        registerRock("granite");
        registerRock("porphyry");
        registerRock("gneiss");
        registerRock("schist");
        registerRock("marble");
        registerRock("dolomite");
        registerRock("quartzite");
        registerRock("limestone");
        registerRock("sandstone");
        registerRock("slate");
        registerRock("gabbro");
        registerRock("diabase");
        registerRock("flint");
        registerRock("arkose");
        registerRock("diorite");
        registerRock("andesite");
        registerRock("rhyolite");
        registerRock("pumice");
        registerRock("obsidian");
        registerRock("scoria");
        registerRock("breccia");
        registerRock("hornblende");
        registerRock("pegmatite");
        registerRock("syenite");
        registerRock("greenschist");
        registerRock("serpentine");
        registerRock("soapstone");
        registerRock("chert");
        registerRock("alabaster");
        registerRock("graywacke");
        registerRock("conglomerate");
        registerRock("mudstone");
        registerRock("shale");
        registerRock("siltstone");
        registerRock("claystone");
        registerRock("orthoclase");
        registerRock("olivine");
        registerRock("mica");
        registerRock("quartz");
        registerRock("calcite");
        registerRock("fluorite");
        registerRock("apatite");
        registerRock("sodalite");
        registerRock("zeolite");
        registerRock("cinnabar");
        registerRock("pyrite");
        registerRock("hematite");
        registerRock("magnetite");
        registerRock("limonite");
        registerRock("malachite");
        registerRock("azurite");
        registerRock("cassiterite");
        registerRock("galena");
        registerRock("argentite");
        registerRock("chalcopyrite");
        registerRock("hornsilver");
        registerRock("peacockore");
        registerRock("cuprite");
        registerRock("sylvanite");
        registerRock("petzite");
        registerRock("nagyagite");
        registerRock("lead-glance");
        registerRock("smithsonite");
        registerRock("zincspar");
        registerRock("ilmenite");
        registerRock("rutile");
        registerRock("bauxite");
        registerRock("diaspore");
        registerRock("gibbsite");
        registerRock("jasper");
        registerRock("agate");
        registerRock("carnelian");
        registerRock("bloodstone");
        registerRock("catseye");
        registerRock("microlite");
        registerRock("corund");
        registerRock("alabaster");
        registerRock("talc");
        registerRock("kyanite");
        registerRock("wollastonite");
        registerRock("tremolite");
        registerRock("actinolite");
        registerRock("eclogite");
        registerRock("bentonite");
        registerRock("muscovite");
        registerRock("phyllite");
        registerRock("taconite");
        registerRock("diatomite");
        registerRock("tufa");
        registerRock("zircon");
        registerRock("chromite");
        registerRock("columbite");
        registerRock("wolframite");
        registerRock("scheelite");
        registerRock("molybdenite");
        registerRock("sphalerite");
        registerRock("pentlandite");
        registerRock("chalcocite");
        registerRock("bornite");
        registerRock("tetrahedrite");
        registerRock("tennantite");
        registerRock("enargite");
        registerRock("proustite");
        registerRock("pyrargyrite");
        registerRock("stephanite");
        registerRock("acanthite");
        registerRock("electrum");
        registerRock("cerargyrite");
        registerRock("bromargyrite");
        registerRock("iodargyrite");
        registerRock("stromeyerite");
        registerRock("calaverite");
        registerRock("krennerite");
        registerRock("hessite");
        registerRock("coloradoite");
        registerRock("altaite");
        registerRock("stannite");
        registerRock("germanite");
        registerRock("argyrodite");
        registerRock("canfieldite");
        registerRock("blackcoal");
        registerRock("sunstone");

        // Inventory/display names used by the terrain-search ore presets.
        registerItemAlias("Heavy Earth", "ilmenite");
        registerItemAlias("Iron Ochre", "limonite");
        registerItemAlias("Bloodstone", "hematite");
        registerItemAlias("Black Ore", "magnetite");
        registerItemAlias("Silvershine", "argentite");
        registerItemAlias("Wine Glance", "cuprite");
        registerItemAlias("Lead Glance", "leadglance");
        registerItemAlias("Leaf Ore", "petzite");
        registerItemAlias("Schrifterz", "sylvanite");
        registerItemAlias("Direvein", "nagyagite");
        registerItemAlias("Quarryartz", "quartz");
        registerItemAlias("Rock Salt", "halite");
        registerItemAlias("Korund", "corund");
    }

    /**
     * Registers a rock type by creating bidirectional mappings between
     * tile resources and bumbling/inventory resources.
     */
    private static void registerRock(String rockName) {
        String tileResource = "gfx/tiles/rocks/" + rockName;
        itemToTileMap.put(normalize(rockName), tileResource);

        // Common patterns for bumbling and inventory resources
        String[] gobPatterns = {
            "gfx/terobjs/bumblings/" + rockName,
            "gfx/invobjs/" + rockName,
            "gfx/invobjs/ore-" + rockName,
            "gfx/invobjs/stone-" + rockName,
            "gfx/invobjs/gems/" + rockName
        };

        for (String gobPattern : gobPatterns) {
            gobToTileMap.put(gobPattern, tileResource);
        }
    }

    /** Maps a display name that does not match its tile name, e.g. "Iron Ochre" -> limonite. */
    private static void registerItemAlias(String itemName, String rockName) {
        itemToTileMap.put(normalize(itemName), "gfx/tiles/rocks/" + rockName);
    }

    /**
     * Given a gob resource name (bumbling or inventory), returns the corresponding tile resource.
     * @param gobResource The gob resource (e.g., "gfx/terobjs/bumblings/feldspar")
     * @return The tile resource, or null if none found
     */
    public static String getTileResourceForGob(String gobResource) {
        return gobToTileMap.get(gobResource);
    }

    /**
     * Gets all tile resources that should be highlighted based on selected gob resources.
     * @param selectedGobResources Set of selected gob resource names from Icon Settings
     * @return Set of tile resources to highlight
     */
    public static Set<String> getTileResourcesToHighlight(Set<String> selectedGobResources) {
        Set<String> result = new HashSet<>();

        for (String gobResource : selectedGobResources) {
            String tileResource = getTileResourceForGob(gobResource);
            if (tileResource != null) {
                result.add(tileResource);
            }
        }

        return result;
    }

    /**
     * Given an item or rock display name (e.g. "Flint", "Iron Ochre"), returns the tile
     * resource it is mined from. Empty when the name is not a known rock.
     */
    public static Set<String> getTileResourcesForItem(String itemName) {
        String tile = itemToTileMap.get(normalize(itemName));
        return tile == null ? Collections.emptySet() : Collections.singleton(tile);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }
}
