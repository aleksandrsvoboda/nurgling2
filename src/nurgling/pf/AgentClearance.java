package nurgling.pf;

import haven.Coord;
import haven.Coord2d;

import java.util.List;
import java.util.function.Predicate;

/**
 * Room for an agent bigger than a bare character (a towed cart, a horse being ridden) on a pf map.
 *
 * <p>The map already keeps a bare character's half-width of 3 clear of every obstacle (the NHitBox min clamp),
 * so a bigger agent only needs the difference: blocked cells grow by {@code cells} into free ones, as far as
 * {@code level} allows:
 * <ol start="0">
 *   <li>everywhere but the start and its 8 neighbours;</li>
 *   <li>also not within {@link #ROOMY_EXEMPT} cells of the start or the goal - the cramped spots a walk begins or
 *       ends in (stepping off a ladder, standing against a wall) - while keeping the room along the way;</li>
 *   <li>no growth at all.</li>
 * </ol>
 * Approach cells (not free) and cells the agent itself stands on ({@code own}) are never grown from. Grown cells
 * get value 3, which reads as blocked to the graph search and to the corridor check alike. Extra spots to keep
 * out of ({@code avoid}, radius {@code avoidR}) are blocked at every level.
 */
public final class AgentClearance {
    /** Clearance levels a walk can step down through; the last is a bare character. */
    public static final int LEVELS = 3;
    /** Cells around the start and goal left ungrown at level 1: about a cart's length. */
    public static final int ROOMY_EXEMPT = 3;
    /** The half-width the pf map already allows for (a bare character). */
    public static final double BARE_HALF_WIDTH = 3.0;

    private AgentClearance() {
    }

    /** Cells to grow by for an agent of this half-width. */
    public static int cellsFor(double halfWidth) {
        return Math.max(0, (int) Math.ceil((halfWidth - BARE_HALF_WIDTH) / haven.MCache.tilehsz.x));
    }

    public static void dilate(NPFMap map, Coord start, Coord goal, int level, int grow,
                              Predicate<NPFMap.Cell> own, List<Coord2d> avoid, double avoidR) {
        int size = map.getSize();
        NPFMap.Cell[][] cells = map.getCells();
        int startExempt = (level == 0) ? 1 : ROOMY_EXEMPT;
        int goalExempt = (level == 0) ? -1 : ROOMY_EXEMPT;

        if (level < 2 && grow > 0) {
            boolean[][] seed = new boolean[size][size];
            for (int i = 0; i < size; i++)
                for (int j = 0; j < size; j++)
                    seed[i][j] = (cells[i][j].val == 1 || cells[i][j].val == 2) && (own == null || !own.test(cells[i][j]));

            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (!seed[i][j])
                        continue;
                    for (int di = -grow; di <= grow; di++)
                        for (int dj = -grow; dj <= grow; dj++)
                            block(cells, size, start, startExempt, goal, goalExempt, i + di, j + dj);
                }
            }
        }

        if (avoid != null) {
            synchronized (avoid) {
                if (!avoid.isEmpty()) {
                    for (int i = 0; i < size; i++) {
                        for (int j = 0; j < size; j++) {
                            Coord2d w = Utils.pfGridToWorld(cells[i][j].pos);
                            for (Coord2d spot : avoid) {
                                if (w.dist(spot) < avoidR) {
                                    block(cells, size, start, 1, null, -1, i, j);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void block(NPFMap.Cell[][] cells, int size, Coord start, int startExempt, Coord goal, int goalExempt,
                              int i, int j) {
        if (i < 0 || j < 0 || i >= size || j >= size)
            return;
        if (cells[i][j].val != 0)
            return;
        if (start != null && Math.abs(i - start.x) <= startExempt && Math.abs(j - start.y) <= startExempt)
            return;
        if (goal != null && goalExempt >= 0 && Math.abs(i - goal.x) <= goalExempt && Math.abs(j - goal.y) <= goalExempt)
            return;
        cells[i][j].val = 3;
    }
}
