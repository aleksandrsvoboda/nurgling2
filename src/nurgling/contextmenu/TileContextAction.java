package nurgling.contextmenu;

import haven.Coord2d;
import nurgling.actions.Action;

public interface TileContextAction {
    boolean appliesTo(Coord2d mapPos);
    String label();
    Action create(Coord2d mapPos);
}
