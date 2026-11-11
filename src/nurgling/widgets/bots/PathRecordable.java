package nurgling.widgets.bots;

import haven.Widget;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerWaypoint;

/**
 * Interface for bot widgets that support path recording via map clicks.
 * Widgets implementing this interface can have waypoints added by clicking on the minimap.
 */
public interface PathRecordable {

    /**
     * Returns true if the widget is currently in recording mode.
     */
    boolean isRecording();

    /**
     * Adds a waypoint to the path being recorded.
     */
    void addWaypointToRecording(ForagerWaypoint wp);

    /**
     * Returns the path currently being recorded or loaded for display.
     */
    ForagerPath getCurrentLoadedPath();

    /**
     * The topmost visible PathRecordable among the game UI's children, or null. Hidden ones don't count:
     * a window closed by hiding (Route Walker, the bot settings windows) would otherwise keep its route
     * drawn on the maps and shadow an open window behind it.
     */
    static PathRecordable find(Widget gui) {
        for (Widget wdg = gui.lchild; wdg != null; wdg = wdg.prev) {
            if (wdg instanceof PathRecordable && wdg.visible) {
                return (PathRecordable) wdg;
            }
        }
        return null;
    }
}
