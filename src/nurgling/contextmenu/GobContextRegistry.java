package nurgling.contextmenu;

import haven.Gob;

import java.util.ArrayList;
import java.util.List;

public class GobContextRegistry {
    private static final List<GobContextAction> actions = new ArrayList<>();

    public static void register(GobContextAction action) {
        actions.add(action);
    }

    public static List<GobContextAction> getActionsFor(Gob gob) {
        if (gob == null || gob.ngob == null || gob.ngob.name == null)
            return List.of();
        List<GobContextAction> result = new ArrayList<>();
        for (GobContextAction action : actions) {
            if (action.appliesTo(gob))
                result.add(action);
        }
        return result;
    }

    static {
        register(new TestTreeAction());
    }
}
