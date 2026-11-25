package nurgling.actions.mount;

import haven.Coord;
import haven.Following;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.pf.AgentClearance;
import nurgling.pf.NPFMap;

/**
 * "This session's character is riding this horse."
 *
 * <p>Riding is the character {@code Following} the horse, as {@code NUI.checkHorseMountState} and
 * {@code GoTo} already read it. While mounted, the horse is the moving body, so the pathfinder plans with
 * the horse's size instead of a bare character's. With the character on foot this is null and every walk
 * is exactly what it was.
 */
public final class HorseRide {
    public final long horseId;

    private HorseRide(long horseId) {
        this.horseId = horseId;
    }

    /** The horse the character is riding right now, or null. */
    public static HorseRide of(NGameUI gui) {
        if (gui == null || gui.map == null)
            return null;
        Gob player = gui.map.player();
        if (player == null)
            return null;
        Following fl = player.getattr(Following.class);
        if (fl == null)
            return null;
        Gob mount = gui.ui.sess.glob.oc.getgob(fl.tgt);
        return isHorse(mount) ? new HorseRide(fl.tgt) : null;
    }

    public static boolean isHorse(Gob g) {
        return g != null && g.ngob != null && g.ngob.name != null && g.ngob.name.startsWith("gfx/kritter/horse/");
    }

    public Gob horse(NGameUI gui) {
        return gui.ui.sess.glob.oc.getgob(horseId);
    }

    /** The horse's half-width (NHitBox ±8 × ±4, long axis along its heading): what gaps it needs. */
    public static final double HALF_WIDTH = 4;
    /** The horse's half-length: how far its body reaches in front of or behind the rider. */
    public static final double HALF_LENGTH = 8;
    /**
     * How much further off an object a ridden horse stops than a character on foot would. Measured on stockpiles: a
     * horse pressed against one can't open it (by hand either); a click from about 3 units further out walks it in
     * and opens (failed 8-9.6 from the pile's centre, opened 10.9-12.1).
     */
    public static final double APPROACH_GAP = 3;

    /** The id of the horse the character is riding, or -1 (cheap enough to call per object scan). */
    public static long riddenId(NGameUI gui) {
        HorseRide r = of(gui);
        return (r == null) ? -1 : r.horseId;
    }
    /**
     * A walk step that ends this close to its target counts as reached: the horse's half-length. Its body can't
     * always get the rider onto a point next to an obstacle (a waypoint by a wall, the spot beside a chest).
     */
    public static final double REACH = 8;

    /** Room for the horse on the walk's pf map, at this clearance level (see {@link AgentClearance}). */
    public void dilate(NPFMap map, Coord start, Coord goal, int level) {
        AgentClearance.dilate(map, start, goal, level, AgentClearance.cellsFor(HALF_WIDTH), null, null, 0);
    }
}
