package nurgling.leveling;

import haven.Area;
import haven.Coord;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Turns a leveling plan into Land Survey targets.
 *
 * <p>A survey covers a block of tiles and sets a target height for every corner of them. A corner
 * of a plan tile gets the plan's height; every other corner keeps its ground height, so nothing
 * outside the plan changes. Heights are in survey units: map height times the survey's granularity,
 * rounded, as the survey window itself rounds the ground.
 *
 * <p>Pure: where a tile is in the world comes in through a {@link Locator}, so this is tested
 * without a game.
 */
public final class SurveyPlan {
    /** A survey's corner targets are sent as 8-bit offsets from the lowest one. */
    public static final int MAXRANGE = 255;

    /** Where a tile is: its grid id and offset in that grid, as {@code {gid, x, y}}; null if not known. */
    public interface Locator {
        long[] at(Coord tc);
    }

    private static final Coord[] CORNERS = {Coord.of(0, 0), Coord.of(1, 0), Coord.of(0, 1), Coord.of(1, 1)};

    private SurveyPlan() {}

    private static boolean inplan(LevelPlan p, Locator loc, Coord tc) {
        long[] r = loc.at(tc);
        return((r != null) && p.contains(r[0], (int)r[1], (int)r[2]));
    }

    /**
     * The plan that covers most of a survey's tiles, among plans with a target height; null if
     * none covers any of them.
     *
     * @param tiles the survey's tiles
     */
    public static LevelPlan pick(List<LevelPlan> plans, Area tiles, Locator loc) {
        LevelPlan best = null;
        int bestn = 0;
        for(LevelPlan p : plans) {
            if(!p.hasTarget())
                continue;
            int n = 0;
            for(Coord tc : tiles) {
                if(inplan(p, loc, tc))
                    n++;
            }
            if(n > bestn) {
                best = p;
                bestn = n;
            }
        }
        return(best);
    }

    /**
     * The target of every survey corner, indexed like the survey's data ({@code varea.ridx}).
     *
     * @param varea the survey's corners: its tiles' area, bottom-right inclusive
     * @param gran survey units per map height unit
     * @param ground the map height at a corner
     */
    public static int[] targets(LevelPlan p, Area varea, double gran, Locator loc, ToDoubleFunction<Coord> ground) {
        int[] ret = new int[varea.area()];
        int planned = (int)Math.round(p.target * gran);
        for(Coord vc : varea) {
            boolean in = false;
            for(Coord d : CORNERS) {
                if(inplan(p, loc, vc.sub(d))) {
                    in = true;
                    break;
                }
            }
            ret[varea.ridx(vc)] = in ? planned : (int)Math.round(ground.applyAsDouble(vc) * gran);
        }
        return(ret);
    }

    /** Whether the targets fit what a survey can send: no two corners more than {@link #MAXRANGE} apart. */
    public static boolean fits(int[] targets) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for(int t : targets) {
            lo = Math.min(lo, t);
            hi = Math.max(hi, t);
        }
        return((hi - lo) <= MAXRANGE);
    }
}
