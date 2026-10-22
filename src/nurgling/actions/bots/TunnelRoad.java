package nurgling.actions.bots;

import haven.Button;
import haven.Coord;
import haven.Coord2d;
import haven.Gob;
import nurgling.NGameUI;
import nurgling.NUtils;
import nurgling.actions.ExtendRoad;
import nurgling.actions.Results;
import nurgling.tools.Finder;
import nurgling.widgets.TunnelingDialog.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The milestone road a tunneling run lays behind itself (the Tunneling Bot's "Build road"
 * option). Keeps track of the road's last post and says when the next one is due: a post goes
 * in at the last spot that keeps the following one within range, so the road gets as few
 * posts as the range allows. Where the posts go is up to the tunneler, which knows its
 * corridor; it mines the spot and then calls extendTo.
 */
public class TunnelRoad {

    // The game's post range is 31 tiles
    public static final int MAX_SPACING_TILES = 30;
    public static final int MIN_SPACING_TILES = 5;
    // Most rock tiles a tunneler digs to clear the road's line
    public static final int MAX_LINE_ROCK = 15;

    private static final double TILE_SIZE = 11.0;
    // A spot this close to the last post already has one, e.g. after a restart
    private static final double SAME_SPOT = 3 * TILE_SIZE;
    // ArdClient's milestone facings are all the dig heading minus 165 degrees
    private static final double FACING_OFFSET = Math.toRadians(-165);
    // How many of the nearest milestones to try for an Extend button at the start
    private static final int CANDIDATES = 3;

    private final double angle;
    private final Coord2d along;
    private final double maxDist;
    private long tailId;
    private Coord2d tailPos;
    private List<Coord> rockInTheWay = new ArrayList<>();

    public TunnelRoad(Direction direction, int maxSpacingTiles) {
        this.angle = angleFor(direction);
        this.along = Coord2d.of(direction.dx, direction.dy);
        this.maxDist = clampSpacing(maxSpacingTiles) * TILE_SIZE;
    }

    public static int clampSpacing(int tiles) {
        return Math.max(MIN_SPACING_TILES, Math.min(MAX_SPACING_TILES, tiles));
    }

    /* Facing as Gob.a reads it: 0 is +X (East), turning towards +Y (South). */
    public static double headingOf(Direction direction) {
        switch (direction) {
            case EAST: return 0;
            case SOUTH: return Math.PI / 2;
            case WEST: return Math.PI;
            default: return 3 * Math.PI / 2;
        }
    }

    public static double angleFor(Direction direction) {
        return headingOf(direction) + FACING_OFFSET;
    }

    static Coord2d tileCenter(Coord tile) {
        return new Coord2d(tile.x * TILE_SIZE + TILE_SIZE / 2, tile.y * TILE_SIZE + TILE_SIZE / 2);
    }

    /* A post is due at spot when the spot after it would be out of the last post's range. */
    static boolean isDue(Coord2d tail, Coord2d spot, Coord2d next, double maxDist) {
        if (spot.dist(tail) < SAME_SPOT) {
            return false;
        }
        return next.dist(tail) > maxDist;
    }

    /* Finds the road's last post near the player: the nearest milestone in range that can be
     * extended. Walks to each one it tries. */
    public Results start(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null) {
            return Results.ERROR("Player not found");
        }

        // Search the game's whole post range; the spacing setting only limits new posts
        double searchDist = MAX_SPACING_TILES * TILE_SIZE;
        List<Gob> near = new ArrayList<>();
        for (Gob gob : Finder.findGobs(ExtendRoad.MILESTONES)) {
            if (gob.rc.dist(player.rc) <= searchDist) {
                near.add(gob);
            }
        }
        if (near.isEmpty()) {
            return Results.ERROR("Road: no milestone within " + tiles(searchDist) + " tiles. Build the road's first "
                    + "milestone (90 stones, 4 ropes) by the tunnel, then start the bot.");
        }
        near.sort(Comparator.comparingDouble(g -> g.rc.dist(player.rc)));

        for (int i = 0; i < Math.min(CANDIDATES, near.size()); i++) {
            Gob gob = near.get(i);
            Button extend = ExtendRoad.openExtend(gui, gob);
            if (extend != null) {
                ExtendRoad.closeWindow(gui, ExtendRoad.windowOf(extend));
                tailId = gob.id;
                tailPos = gob.rc;
                gui.msg("Road: extending from the milestone " + tiles(gob.rc.dist(player.rc)) + " tiles away");
                return Results.SUCCESS();
            }
        }
        return Results.ERROR("Road: none of the milestones near you can be extended. Only the end of a trail "
                + "can be, and not one that ends in a sign.");
    }

    /* Whether the post at spot must go in now, given the spot after it. */
    public boolean due(Coord spot, Coord next) {
        return isDue(tailPos, tileCenter(spot), tileCenter(next), maxDist);
    }

    /* Whether the end of the tunnel at spot is far enough from the last post to get one. */
    public boolean worthFinishing(Coord spot) {
        return tileCenter(spot).dist(tailPos) >= MIN_SPACING_TILES * TILE_SIZE;
    }

    /* SUCCESS if a post at spot is within the last post's range. */
    public Results reaches(Coord spot) {
        double dist = tileCenter(spot).dist(tailPos);
        if (dist > maxDist) {
            return Results.ERROR("Road: the road's last milestone is " + tiles(dist) + " tiles from the next "
                    + "milestone spot, more than " + tiles(maxDist) + ". Build a milestone closer and restart.");
        }
        return Results.SUCCESS();
    }

    /* After a failed extendTo: the rock to dig before trying again, empty if rock wasn't
     * the problem. */
    public List<Coord> rockInTheWay() {
        return rockInTheWay;
    }

    /* A spot on the way from the last post to spot, as far as the range allows: for a
     * road whose last post is too far from where the next one should go. */
    public Coord stepToward(Coord spot) {
        return stepToward(tailPos, tileCenter(spot), maxDist);
    }

    static Coord stepToward(Coord2d tail, Coord2d target, double maxDist) {
        Coord2d p = tail.add(target.sub(tail).norm().mul(maxDist - TILE_SIZE));
        return new Coord((int) Math.floor(p.x / TILE_SIZE), (int) Math.floor(p.y / TILE_SIZE));
    }

    /* Builds the next post: the milestone on the tile next to spot towards outward (a tile
     * step), turned so the road ends on the line through spot, back along the tunnel. */
    public Results extendTo(NGameUI gui, Coord spot, Coord outward) throws InterruptedException {
        rockInTheWay = new ArrayList<>();
        Results inRange = reaches(spot);
        if (!inRange.IsSuccess()) {
            return inRange;
        }
        Gob tail = tail();
        if (tail == null) {
            return Results.ERROR("Road: lost sight of the road's last milestone");
        }

        ExtendRoad extend = new ExtendRoad(tail, tileCenter(spot), along, Coord2d.of(outward), angle);
        Results result = extend.run(gui);
        if (!result.IsSuccess()) {
            rockInTheWay = extend.rockInTheWay();
            return result;
        }
        tailId = extend.built().id;
        tailPos = extend.built().rc;
        gui.msg("Road: milestone placed");
        return Results.SUCCESS();
    }

    /* The last post by id, or whatever milestone stands where it was: the game may swap the
     * old end post for a middle one when the road grows. */
    private Gob tail() throws InterruptedException {
        Gob gob = Finder.findGob(tailId);
        if (gob != null) {
            return gob;
        }
        return Finder.findGob(tailPos, ExtendRoad.MILESTONES, null, 1.5 * TILE_SIZE);
    }

    private static long tiles(double dist) {
        return Math.round(dist / TILE_SIZE);
    }
}
