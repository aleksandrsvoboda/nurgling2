package nurgling.actions.mount;

import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import haven.MCache;
import nurgling.NGameUI;
import nurgling.NHitBox;
import nurgling.NUtils;
import nurgling.actions.GoTo;
import nurgling.actions.PathFinder;
import nurgling.tasks.NTask;

import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Lines a ridden horse up to go through a building door.
 *
 * <p>From the user: on horseback a door only works with the horse right up against the wall beside it, parallel to
 * the wall, either side; riding straight at the door doesn't. The same holds leaving, against the inside wall.
 * Stairs, cellars, ladders and mineholes don't need this - only building doors (a building's exterior, whose door is
 * on its +x face, and the {@code -door} gobs inside).
 *
 * <p>The horse ends against the wall ({@link #WALL_GAPS}), its nose {@link #DOOR_GAP} past the door's edge, facing the door:
 * it gets there from a run-up point further along the wall, so the last stretch is straight along it.
 */
public final class HorseDoor {
    /** Half the door's width along the wall: doors are one tile wide. */
    private static final double DOOR_HALF = MCache.tilesz.x / 2;
    /**
     * Gap between the horse's flank and the wall, per try: flush first (a 1-unit gap fails at a great hall), then
     * aimed a little into the wall (the horse stops against it), then the 1-unit gap that did for a stone mansion.
     */
    private static final double[] WALL_GAPS = {0, -1, 1};
    private static final double DOOR_GAP = 1;
    /** How much further along the wall the run-up starts. */
    private static final double RUN_UP = 10;

    private HorseDoor() {
    }

    /** Whether going through this gob is a door the horse has to line up for. */
    public static boolean isDoor(Gob portal) {
        if (portal == null || portal.ngob == null || portal.ngob.name == null)
            return false;
        String n = portal.ngob.name.toLowerCase();
        if (n.contains("stairs") || n.contains("cellar") || n.contains("ladder") || n.contains("minehole")
                || n.contains("cave"))
            return false;
        return n.contains("-door") || nurgling.navigation.ChunkPortal.isBuildingExterior(portal.ngob.name);
    }

    /** Tries at going through one door before giving up, each closer to the wall. */
    private static final int ATTEMPTS = 3;
    /** How long one click gets to take the character through. */
    private static final long CROSS_MS = 8000;

    /**
     * Rides through a building door: line up beside it, click it, and wait (bounded) for {@code crossed}. When the
     * click doesn't take - the line-up was a little off - lines up again and clicks again. False after
     * {@link #ATTEMPTS} misses. {@code rclick}: the door is clicked as a plain object (inside doors) rather than with
     * openDoorOnAGob (building exteriors).
     */
    public static boolean goThrough(NGameUI gui, Gob portal, boolean rclick, BooleanSupplier crossed) throws InterruptedException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int mesh = lineUp(gui, portal, attempt);
            if (mesh == NO_ROOM)
                continue;
            if (rclick)
                NUtils.rclickGob(portal);
            else if (mesh >= 0)
                gui.map.wdgmsg("click", Coord.z, portal.rc.floor(posres), 3, 0, 1, (int) portal.id, portal.rc.floor(posres), 0, mesh);
            else
                NUtils.openDoorOnAGob(gui, portal);
            long deadline = System.currentTimeMillis() + CROSS_MS;
            NUtils.addTask(new NTask() {
                @Override
                public boolean check() {
                    return crossed.getAsBoolean() || System.currentTimeMillis() > deadline;
                }
            });
            if (crossed.getAsBoolean())
                return true;
        }
        return false;
    }

    private static final int NO_ROOM = -2;

    /**
     * Rides alongside the wall next to the door. Returns the great hall's door mesh to click (the door it lined up
     * beside, not the one nearest the character afterwards), -1 for other buildings, or {@link #NO_ROOM}.
     * Each {@code attempt} stands closer to the wall ({@link #WALL_GAPS}); nearest side first.
     */
    private static int lineUp(NGameUI gui, Gob portal, int attempt) throws InterruptedException {
        Gob me = gui.map.player();
        if (me == null)
            return -1;
        Coord2d door, out;
        int mesh = -1;
        String n = portal.ngob.name.toLowerCase();
        if (n.contains("-door")) {
            // Inside: the door gob faces out of the building; we stand on its inner side.
            door = portal.rc;
            out = new Coord2d(-Math.cos(portal.a), -Math.sin(portal.a));
        } else {
            NHitBox hb = portal.ngob.hitBox;
            if (hb == null)
                return -1;
            double a = portal.a + (n.contains("windmill") ? Math.PI / 2 : 0);
            out = new Coord2d(Math.cos(a), Math.sin(a));
            door = portal.rc.add(out.mul(hb.end.x));
            if (n.contains("greathall")) {
                // Three doors along the +x face at a quarter, half and three quarters of it (openDoorOnAGob's meshes
                // 18, 17, 16); line up for the one nearest the character and click exactly that one.
                Coord2d along = new Coord2d(-out.y, out.x);
                Coord2d aEnd = new Coord2d(hb.end.x, hb.begin.y).rot(portal.a).add(portal.rc);
                Coord2d bEnd = new Coord2d(hb.end.x, hb.end.y).rot(portal.a).add(portal.rc);
                Coord2d[] doors = {aEnd.add(bEnd.sub(aEnd).div(4)), bEnd.sub(aEnd).div(2).add(aEnd), bEnd.add(aEnd.sub(bEnd).div(4))};
                int[] meshes = {18, 17, 16};
                int best = 1;
                for (int i = 0; i < 3; i++)
                    if (doors[i].dist(me.rc) < doors[best].dist(me.rc))
                        best = i;
                door = doors[best];
                mesh = meshes[best];
            }
        }
        Coord2d along = new Coord2d(-out.y, out.x);
        // Flush against the wall first; see WALL_GAPS for the retries.
        double offWall = HorseRide.HALF_WIDTH + WALL_GAPS[Math.min(attempt, WALL_GAPS.length - 1)];
        double alongDoor = DOOR_HALF + DOOR_GAP + HorseRide.HALF_LENGTH;
        double near = (me.rc.sub(door).dot(along) >= 0) ? 1 : -1;
        double first = near;
        for (double side : new double[]{first, -first}) {
            Coord2d stand = door.add(out.mul(offWall)).add(along.mul(side * alongDoor));
            Coord2d runUp = stand.add(along.mul(side * RUN_UP));
            if (!PathFinder.isAvailable(runUp))
                continue;
            new PathFinder(runUp).run(gui);
            new GoTo(stand, new StepStuckWatch(gui)).run(gui);
            return mesh;
        }
        return NO_ROOM;
    }
}
