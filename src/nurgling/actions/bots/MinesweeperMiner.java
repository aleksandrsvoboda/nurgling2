package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.overlays.NMiningNumber;
import nurgling.widgets.TunnelingDialog.Direction;

import java.util.*;

import static haven.MCache.tilesz;
import static haven.OCache.posres;

/**
 * Minesweeper-guided mining bot.
 * Mines in a general direction using minesweeper constraint solving
 * to determine safe tiles. Stops when no provably-safe tiles remain.
 */
public class MinesweeperMiner implements Action {

    private static final NAlias MINEABLE_TILES = new NAlias("rock", "tiles/cave");
    private static final NAlias ALL_SUPPORTS = new NAlias(
            "minebeam", "column", "towercap", "ladder", "minesupport", "naturalminesupport"
    );
    private static final int SOLVER_RADIUS = 30;

    private final Direction direction;
    private final int maxLateral;
    private final boolean recordStones;

    public MinesweeperMiner(Direction direction, int maxLateral) {
        this(direction, maxLateral, true);
    }

    public MinesweeperMiner(Direction direction, int maxLateral, boolean recordStones) {
        this.direction = direction;
        this.maxLateral = maxLateral;
        this.recordStones = recordStones;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        gui.msg("Minesweeper Miner: Direction=" + direction.name + ", Max lateral=" + maxLateral);

        // Initialize solver
        MinesweeperSolver solver = new MinesweeperSolver(gui);
        Gob player = NUtils.player();
        if (player == null) return Results.ERROR("No player found");

        Coord startTile = player.rc.div(tilesz).floor();

        // Track the starting lateral position for deviation limit
        int startLateral = getLateral(startTile, direction);

        // Main mining loop
        int tilesMinedTotal = 0;

        while (true) {
            player = NUtils.player();
            if (player == null) return Results.ERROR("Lost player reference");

            Coord playerTile = player.rc.div(tilesz).floor();

            // Refresh solver with current state and run deductions
            solver.refresh(playerTile, SOLVER_RADIUS);
            solver.solve();

            // Find the closest safe mineable tile to the player
            Coord target = findNextTile(solver, playerTile, direction, startLateral, maxLateral);
            if (target == null) {
                gui.msg("Minesweeper Miner: No more safe tiles. Mined " + tilesMinedTotal + " tiles total.");
                break;
            }

            Results result = mineTile(gui, solver, target);
            if (!result.IsSuccess()) {
                gui.msg("Mining failed at " + target);
                return result;
            }
            tilesMinedTotal++;
            handleBumlings(gui);
        }

        return Results.SUCCESS();
    }

    /**
     * Find the next tile to mine. Searches all tiles near the player for the closest
     * safe, mineable tile adjacent to open space.
     *
     * Uses BFS through walkable/open tiles to find reachable mineable tiles,
     * then picks the best one. This allows navigating around danger zones —
     * e.g., going east then north to get around a danger to the direct north.
     *
     * Prefers SAFE/SUPPORTED over UNKNOWN. Never mines UNKNOWN adjacent to numbers.
     */
    private Coord findNextTile(MinesweeperSolver solver, Coord playerTile,
                                Direction direction, int startLateral, int maxLateral) {
        // BFS from player through open (non-mineable) tiles to find all reachable
        // mineable tiles on the frontier (adjacent to open space we can walk to).
        Set<Long> visited = new HashSet<>();
        Queue<Coord> queue = new LinkedList<>();
        queue.add(playerTile);
        visited.add(tileKey(playerTile));

        Coord best = null;
        int bestScore = Integer.MIN_VALUE;
        int candidates = 0;
        int rejectedDanger = 0;

        int startForward = getForward(playerTile, direction);
        int[][] cardinals = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

        while (!queue.isEmpty()) {
            Coord current = queue.poll();

            // Limit BFS distance to prevent loading distant map chunks
            int bfsDist = Math.abs(current.x - playerTile.x) + Math.abs(current.y - playerTile.y);
            if (bfsDist > SOLVER_RADIUS) continue;

            for (int[] d : cardinals) {
                Coord neighbor = new Coord(current.x + d[0], current.y + d[1]);
                long nk = tileKey(neighbor);
                if (visited.contains(nk)) continue;
                visited.add(nk);

                int lateral = getLateral(neighbor, direction);

                // If neighbor is open (not mineable), add to BFS — we can walk through it
                if (!isTileMineable(NUtils.getGameUI(), neighbor)) {
                    queue.add(neighbor);
                    continue;
                }

                // Neighbor is mineable — it's a candidate to mine
                if (Math.abs(lateral - startLateral) > maxLateral) continue;

                MinesweeperSolver.TileState state = solver.getState(neighbor);
                if (state == MinesweeperSolver.TileState.DANGER) { rejectedDanger++; continue; }

                // UNKNOWN tiles adjacent to numbered tiles are potential mines — never mine them
                if (state != MinesweeperSolver.TileState.SUPPORTED &&
                    state != MinesweeperSolver.TileState.SAFE) {
                    if (solver.isAdjacentToNumberedTile(neighbor)) continue;
                }

                candidates++;

                int forward = getForward(neighbor, direction) - startForward;

                // Don't go more than 10 tiles backwards
                if (forward < -10) continue;

                int dist = Math.abs(neighbor.x - playerTile.x) + Math.abs(neighbor.y - playerTile.y);
                int lateralDev = Math.abs(lateral - startLateral);

                // SAFE/SUPPORTED get a bonus worth ~0.5 tiles of forward progress.
                // At equal forward distance, safe tiles win. But an unknown tile
                // further forward (like rock at the far end of an open area) wins
                // over a safe side-wall tile nearby.
                int safetyBonus = 0;
                if (state == MinesweeperSolver.TileState.SUPPORTED ||
                    state == MinesweeperSolver.TileState.SAFE) {
                    safetyBonus = 5000;
                }

                int score = forward * 10000 + safetyBonus - dist * 10 - lateralDev;

                if (score > bestScore) {
                    bestScore = score;
                    best = neighbor;
                }
            }
        }

        return best;
    }

    private static long tileKey(Coord c) {
        return ((long) c.x << 32) | (c.y & 0xFFFFFFFFL);
    }

    /**
     * Mine a single tile and update the solver with results.
     */
    private Results mineTile(NGameUI gui, MinesweeperSolver solver, Coord tilePos)
            throws InterruptedException {
        if (!isTileMineable(gui, tilePos)) {
            solver.markMined(tilePos);
            return Results.SUCCESS();
        }

        // Check for loose rocks
        Gob looserock = Finder.findGob(new NAlias("looserock"));
        if (looserock != null && looserock.rc.dist(NUtils.player().rc) < 93.5) {
            return Results.ERROR("Loose rock detected — unsafe to continue");
        }

        // Check support health
        if (!checkSupportHealth(tilePos)) {
            return Results.ERROR("Nearby support damaged — unsafe to continue");
        }

        Coord2d worldPos = tileCenter(tilePos);

        // Ensure cursor is in default state before pathfinding
        NUtils.getDefaultCur();

        // Navigate to tile
        PathFinder pf = new PathFinder(NGob.getDummy(worldPos, 0,
                new NHitBox(new Coord2d(-5.5, -5.5), new Coord2d(5.5, 5.5))), true);
        pf.isHardMode = true;
        pf.run(gui);

        // Restore resources
        new RestoreResources().run(gui);

        // Mine the tile — same pattern as TunnelingBot.mineTileIfNeeded
        Resource resBefore = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));

        // Record stone type before mining changes the tile
        if (recordStones && resBefore != null && resBefore.name.startsWith("gfx/tiles/rocks/")) {
            if (gui.stoneLocationService != null) {
                gui.stoneLocationService.saveStoneLocation(resBefore.name, worldPos);
            }
        }

        while (isTileMineable(gui, tilePos)) {
            handleBumlings(gui);

            NUtils.mine(worldPos);
            gui.map.wdgmsg("sel", tilePos, tilePos, 0);

            if (NUtils.getStamina() > 0.4) {
                Resource finalResBefore = resBefore;
                Coord finalTilePos = tilePos;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        Resource current = gui.ui.sess.glob.map.tilesetr(
                                gui.ui.sess.glob.map.gettile(finalTilePos));
                        return current != finalResBefore;
                    }
                });
            }

            // Force right-click to cancel mining selection state,
            // then wait for cursor to become arrow.
            // getDefaultCur() alone isn't enough — cursor may already be "arw"
            // but the tile selection from wdgmsg("sel") stays active,
            // which makes subsequent movement clicks get swallowed.
            gui.map.wdgmsg("click", Coord.z, NUtils.player().rc.floor(posres), 3, 0);
            NUtils.getUI().core.addTask(new GetCurs("arw"));

            resBefore = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));

            if (!new RestoreResources().run(gui).IsSuccess()) {
                return Results.ERROR("Cannot restore resources");
            }
        }

        // Wait for server to send minesweeper numbers (they arrive as Cavein overlays)
        Coord scanTile = tilePos;
        NUtils.addTask(new NTask() {
            int ticks = 0;
            @Override
            public boolean check() {
                if (solver.readNumberAtTile(scanTile) >= 0) return true;
                return ticks++ > 15;
            }
        });

        // Scan for new minesweeper numbers around the mined tile
        solver.scanMinesweeperNumbers();

        // Check if this tile got a number
        int number = solver.readNumberAtTile(tilePos);
        if (number >= 0) {
            solver.reveal(tilePos, number);
        } else {
            solver.markMined(tilePos);
        }

        // Also scan adjacent tiles for new numbers
        for (int[] d : new int[][]{{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}}) {
            Coord adj = new Coord(tilePos.x + d[0], tilePos.y + d[1]);
            int adjNum = solver.readNumberAtTile(adj);
            if (adjNum >= 0) {
                solver.reveal(adj, adjNum);
            }
        }

        // Run solver with new data
        solver.solve();

        return Results.SUCCESS();
    }

    private boolean checkSupportHealth(Coord tilePos) {
        Coord2d worldPos = tileCenter(tilePos);
        ArrayList<Gob> supports = Finder.findGobs(ALL_SUPPORTS);
        for (Gob support : supports) {
            double dist = support.rc.dist(worldPos);
            if (dist <= 150) {
                GobHealth health = support.getattr(GobHealth.class);
                if (health != null && health.hp <= 0.25) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isTileMineable(NGameUI gui, Coord tilePos) {
        try {
            Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));
            if (res == null) return false;
            return NParser.checkName(res.name, MINEABLE_TILES);
        } catch (Exception e) {
            return false;
        }
    }

    private Coord2d tileCenter(Coord tile) {
        return new Coord2d(tile.x * tilesz.x + tilesz.x / 2, tile.y * tilesz.y + tilesz.y / 2);
    }

    /**
     * Get the "forward" coordinate component for a direction.
     * For NORTH/SOUTH, forward is Y (negated for north).
     * For EAST/WEST, forward is X (negated for west).
     */
    private int getForward(Coord tile, Direction dir) {
        switch (dir) {
            case NORTH: return -tile.y;
            case SOUTH: return tile.y;
            case EAST: return tile.x;
            case WEST: return -tile.x;
            default: return 0;
        }
    }

    /**
     * Get the "lateral" coordinate component for a direction.
     * Perpendicular to the forward axis.
     */
    private int getLateral(Coord tile, Direction dir) {
        switch (dir) {
            case NORTH:
            case SOUTH: return tile.x;
            case EAST:
            case WEST: return tile.y;
            default: return 0;
        }
    }

    private void handleBumlings(NGameUI gui) throws InterruptedException {
        Gob bumling = Finder.findGob(new NAlias("bumlings"));

        if (bumling != null && bumling.rc.dist(NUtils.player().rc) <= 20) {
            new PathFinder(bumling).run(gui);

            int maxAttempts = 10;
            int attempts = 0;

            while (bumling != null && Finder.findGob(bumling.id) != null && attempts < maxAttempts) {
                attempts++;

                if (NUtils.getGameUI().vhand != null) {
                    NUtils.drop(NUtils.getGameUI().vhand);
                }

                new SelectFlowerAction("Chip stone", bumling).run(gui);

                WaitChipperState wcs = new WaitChipperState(bumling, true);
                NUtils.getUI().core.addTask(wcs);

                switch (wcs.getState()) {
                    case BUMLINGNOTFOUND:
                        bumling = null;
                        break;
                    case BUMLINGFORDRINK:
                        new RestoreResources().run(gui);
                        bumling = Finder.findGob(bumling.id);
                        break;
                    case DANGER:
                        gui.msg("Warning: Low energy while chipping stones");
                        return;
                }

                if (bumling != null) {
                    bumling = Finder.findGob(bumling.id);
                }
            }
        }
    }

}
