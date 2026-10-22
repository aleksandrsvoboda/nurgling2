package nurgling.actions.bots;

import haven.Coord;
import haven.Loading;
import haven.MCache;
import haven.Resource;
import nurgling.NGameUI;

import java.util.HashMap;
import java.util.Map;

/**
 * The Tunneling Bot's "Stop at ore" option. Digging a tile reveals the rock type of the walls
 * around it; until then they read as plain cave. So the tiles around each dug tile are
 * looked at before and after the dig, and a tile once seen as something else that now reads
 * as ore is a find. Ore that was already showing when a tile was first looked at never
 * counts, and a reveal that arrives late is still caught by the next look, which overlaps.
 */
public class OreWatch {

    private static final String ROCK_TILE = "gfx/tiles/rocks/";
    // Covers the dug tile's neighbours, whose rock type the dig reveals
    private static final int RADIUS = 2;

    // MasterMiner's ORE_ITEMS by tile: an ore's tile is named like its item resource
    // (VSpec). Meteorite has no tile.
    private static final Map<String, String> ORES = new HashMap<>();
    static {
        ORES.put("argentite", "Silvershine");
        ORES.put("cassiterite", "Cassiterite");
        ORES.put("chalcopyrite", "Chalcopyrite");
        ORES.put("cinnabar", "Cinnabar");
        ORES.put("cuprite", "Wine Glance");
        ORES.put("galena", "Galena");
        ORES.put("hematite", "Bloodstone");
        ORES.put("hornsilver", "Horn Silver");
        ORES.put("ilmenite", "Heavy Earth");
        ORES.put("leadglance", "Lead Glance");
        ORES.put("limonite", "Iron Ochre");
        ORES.put("magnetite", "Black Ore");
        ORES.put("malachite", "Malachite");
        ORES.put("nagyagite", "Leaf Ore");
        ORES.put("peacockore", "Peacock Ore");
        ORES.put("petzite", "Direvein");
        ORES.put("sylvanite", "Schrifterz");
    }

    // Whether each tile looked at so far was ore
    private final Map<Coord, Boolean> seen = new HashMap<>();
    private String found = null;

    /* The ore a tile resource is, or null. */
    static String oreName(String tileRes) {
        if (tileRes == null || !tileRes.startsWith(ROCK_TILE)) {
            return null;
        }
        return ORES.get(tileRes.substring(ROCK_TILE.length()));
    }

    /* Looks at the tiles around center; returns the find (also kept in found()), or null. */
    public String look(NGameUI gui, Coord center) {
        MCache map = gui.ui.sess.glob.map;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                Coord tile = center.add(dx, dy);
                Resource res;
                try {
                    res = map.tilesetr(map.gettile(tile));
                } catch (Loading e) {
                    continue;
                }
                if (res == null) {
                    continue;
                }
                String ore = oreName(res.name);
                Boolean wasOre = seen.put(tile, ore != null);
                if (ore != null && Boolean.FALSE.equals(wasOre) && found == null) {
                    found = "Found ore: " + ore + " at tile " + tile;
                }
            }
        }
        return found;
    }

    public String found() {
        return found;
    }
}
