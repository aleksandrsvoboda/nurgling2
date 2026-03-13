package nurgling.contextmenu;

import haven.Coord2d;

import java.util.ArrayList;
import java.util.List;

public class TileContextRegistry {
    private static final List<TileContextAction> actions = new ArrayList<>();

    public static void register(TileContextAction action) {
        actions.add(action);
    }

    public static List<TileContextAction> getActionsFor(Coord2d mapPos) {
        List<TileContextAction> result = new ArrayList<>();
        for (TileContextAction action : actions) {
            if (action.appliesTo(mapPos))
                result.add(action);
        }
        return result;
    }

    static {
        register(new FillFromWaterTileAction());
    }
}
