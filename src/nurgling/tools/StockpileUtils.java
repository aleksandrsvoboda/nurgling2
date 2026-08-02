package nurgling.tools;

import haven.Coord;
import haven.Gob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class StockpileUtils {
    public static final String STOCKPILE_CAP = "Stockpile";
    public static final String SACK_CAP = "Sack";

    /**
     * Substring of a gob resource name -> caption of the window that holds its ISBox.
     * Matching is done by substring because the resource name changes with the content
     * (stockpile-board, stockpile-hide, ...) and with the fill level (producesack-closed0..4).
     */
    private static final Map<String, String> isboxcaps = new LinkedHashMap<>();
    static {
        isboxcaps.put("stockpile", STOCKPILE_CAP);
        isboxcaps.put("producesack", SACK_CAP);
    }

    private static final NAlias isboxNames = new NAlias(new ArrayList<>(isboxcaps.keySet()));

    /**
     * Caption of the ISBox window for the given gob, or null if the gob is not an ISBox storage.
     */
    public static String capFor(Gob gob) {
        if (gob == null || gob.ngob == null || gob.ngob.name == null)
            return null;
        return capFor(gob.ngob.name);
    }

    public static String capFor(String resName) {
        if (resName == null)
            return null;
        String lower = resName.toLowerCase();
        for (Map.Entry<String, String> e : isboxcaps.entrySet()) {
            if (lower.contains(e.getKey()))
                return e.getValue();
        }
        return null;
    }

    /**
     * True if the window caption belongs to a known ISBox storage.
     */
    public static boolean isISBoxCap(String cap) {
        return cap != null && isboxcaps.containsValue(cap);
    }

    /**
     * Alias matching every known ISBox storage gob, for Finder.findGobs.
     */
    public static NAlias isboxNames() {
        return isboxNames;
    }

    public static HashMap<String, Coord> itemMaxSize = new HashMap<>();
    static {
        itemMaxSize.put("gfx/terobjs/stockpile-hide", new Coord(2,2));
        itemMaxSize.put("gfx/terobjs/stockpile-fish", new Coord(2,3));
        itemMaxSize.put("gfx/terobjs/stockpile-bone", new Coord(3,2));
        itemMaxSize.put("gfx/terobjs/stockpile-board", new Coord(4,1));
        itemMaxSize.put("gfx/terobjs/stockpile-wblock", new Coord(1,2));
        itemMaxSize.put("gfx/terobjs/stockpile-pumpkin", new Coord(3,3));
    }

    public static HashMap<String, String> defaultItems = new HashMap<>();
    static {
        defaultItems.put("gfx/terobjs/stockpile-hide", "Bear Hide");
        defaultItems.put("gfx/terobjs/stockpile-fish", "Pike");
        defaultItems.put("gfx/terobjs/stockpile-bone", "Bone Material");
        defaultItems.put("gfx/terobjs/stockpile-ore", "Cassiterite");
    }


//    public static String getDefaultItem(Gob pile) {
//        if(pile.ngob.name!=null)
//        {
//            return defaultItems.get(pile.ngob.name);
//        }
//        return null;
//    }
}
