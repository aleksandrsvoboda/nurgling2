package nurgling.actions.bots.riverexplorer;

import haven.Coord;
import nurgling.NGameUI;

/** Something a bot sailing with Boat stops for on the way: the boat asks every tick of a leg, halts when it is due, and sails
 *  on (planning its course again) once stop() returns. */
public interface Stopover {
    /** Cheap: called every tick with the segment tile under the boat (the player's) and its tile name. */
    boolean due(Coord segTile, String tileName);

    /** The boat is halted and still. May throw InterruptedException. */
    void stop(NGameUI gui) throws InterruptedException;
}
