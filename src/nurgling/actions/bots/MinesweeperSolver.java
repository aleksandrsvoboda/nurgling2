package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.overlays.NMiningNumber;
import nurgling.overlays.NMiningSupport;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;

import java.util.*;

import static haven.MCache.tilesz;

/**
 * Minesweeper-style constraint solver for mining.
 * Tracks tile states and deduces which tiles are safe or dangerous
 * based on revealed numbers (like classic Minesweeper).
 */
public class MinesweeperSolver {

    public enum TileState {
        UNKNOWN,    // Unmined, not yet determined
        SAFE,       // Deduced safe to mine
        DANGER,     // Deduced mine — will cause collapse
        REVEALED,   // Mined, shows a number
        SUPPORTED,  // Within support coverage — always safe
        WALL        // Not mineable (not rock/cave)
    }

    private static final NAlias MINEABLE_TILES = new NAlias("rock", "tiles/cave");
    private static final NAlias ALL_SUPPORTS = new NAlias(
            "minebeam", "column", "towercap", "ladder", "minesupport", "naturalminesupport"
    );

    // 8-directional neighbor offsets
    private static final int[][] NEIGHBORS = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0},           {1, 0},
            {-1, 1},  {0, 1},  {1, 1}
    };

    private final Map<Long, TileState> states = new HashMap<>();
    private final Map<Long, Integer> numbers = new HashMap<>();
    private final NGameUI gui;

    public MinesweeperSolver(NGameUI gui) {
        this.gui = gui;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyY(long key) {
        return (int) key;
    }

    public TileState getState(Coord tile) {
        return states.getOrDefault(key(tile.x, tile.y), TileState.UNKNOWN);
    }

    public int getNumber(Coord tile) {
        return numbers.getOrDefault(key(tile.x, tile.y), -1);
    }

    /**
     * Initialize/refresh the solver grid around a center position.
     * Scans support coverage and tile types to pre-populate states.
     */
    public void refresh(Coord center, int radius) {
        // Mark tiles within support coverage as SUPPORTED
        refreshSupportCoverage(center, radius);

        // Mark non-mineable tiles as WALL, unknown mineable tiles stay UNKNOWN
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int y = center.y - radius; y <= center.y + radius; y++) {
                long k = key(x, y);
                TileState current = states.get(k);
                // Don't overwrite REVEALED, SAFE, DANGER, or SUPPORTED
                if (current == TileState.REVEALED || current == TileState.SAFE ||
                        current == TileState.DANGER || current == TileState.SUPPORTED) {
                    continue;
                }

                Coord tilePos = new Coord(x, y);
                if (!isTileMineable(tilePos)) {
                    states.put(k, TileState.WALL);
                }
            }
        }

        // Scan for existing minesweeper numbers on gobs
        scanMinesweeperNumbers();
    }

    /**
     * Scan all nearby gobs for NMiningNumber overlays and record their values.
     */
    public void scanMinesweeperNumbers() {
        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                Gob.Overlay ol = gob.findol(NMiningNumber.class);
                if (ol != null && ol.spr instanceof NMiningNumber) {
                    NMiningNumber nmn = (NMiningNumber) ol.spr;
                    Coord tile = gob.rc.div(tilesz).floor();
                    long k = key(tile.x, tile.y);
                    states.put(k, TileState.REVEALED);
                    numbers.put(k, nmn.val);
                }
            }
        }
    }

    /**
     * Record that a tile was mined and revealed a number.
     * A number of 0 means no Cavein effect (fully safe area).
     */
    public void reveal(Coord tile, int number) {
        long k = key(tile.x, tile.y);
        states.put(k, TileState.REVEALED);
        numbers.put(k, number);
    }

    /**
     * Mark a tile that was mined but showed no minesweeper number.
     * No number means 0 adjacent dangers — all neighbors are safe.
     * This is like clicking a blank tile in minesweeper.
     */
    public void markMined(Coord tile) {
        long k = key(tile.x, tile.y);
        TileState current = states.get(k);
        if (current != TileState.REVEALED) {
            states.put(k, TileState.REVEALED);
            numbers.put(k, 0);
        }
    }

    /**
     * Run the constraint solver. Returns the set of tiles newly deduced as SAFE.
     */
    public Set<Coord> solve() {
        Set<Coord> newSafe = new HashSet<>();
        boolean changed = true;

        while (changed) {
            changed = false;

            for (Map.Entry<Long, TileState> entry : new ArrayList<>(states.entrySet())) {
                if (entry.getValue() != TileState.REVEALED) continue;

                long k = entry.getKey();
                int x = keyX(k);
                int y = keyY(k);
                int number = numbers.getOrDefault(k, 0);

                List<Long> unknowns = new ArrayList<>();
                int dangerCount = 0;

                for (int[] d : NEIGHBORS) {
                    long nk = key(x + d[0], y + d[1]);
                    TileState ns = states.getOrDefault(nk, TileState.UNKNOWN);
                    if (ns == TileState.DANGER) {
                        dangerCount++;
                    } else if (ns == TileState.UNKNOWN) {
                        unknowns.add(nk);
                    }
                    // SAFE, SUPPORTED, WALL, REVEALED are not dangers and not unknown
                }

                // Rule 1: All mines accounted for — remaining unknowns are safe
                if (dangerCount == number && !unknowns.isEmpty()) {
                    for (long uk : unknowns) {
                        states.put(uk, TileState.SAFE);
                        newSafe.add(new Coord(keyX(uk), keyY(uk)));
                        changed = true;
                    }
                }

                // Rule 2: All unknowns must be mines
                if (dangerCount + unknowns.size() == number && !unknowns.isEmpty()) {
                    for (long uk : unknowns) {
                        states.put(uk, TileState.DANGER);
                        changed = true;
                    }
                }
            }

            // Subset/overlap analysis between pairs of revealed tiles
            if (!changed) {
                changed = solveSubsets();
            }
        }

        return newSafe;
    }

    /**
     * Advanced solver: compare constraints between pairs of numbered tiles
     * that share unknown neighbors.
     */
    private boolean solveSubsets() {
        boolean changed = false;

        // Collect all revealed tiles with their constraint info
        List<ConstraintInfo> constraints = new ArrayList<>();
        for (Map.Entry<Long, TileState> entry : states.entrySet()) {
            if (entry.getValue() != TileState.REVEALED) continue;

            long k = entry.getKey();
            int x = keyX(k);
            int y = keyY(k);
            int number = numbers.getOrDefault(k, 0);

            Set<Long> unknowns = new HashSet<>();
            int dangerCount = 0;

            for (int[] d : NEIGHBORS) {
                long nk = key(x + d[0], y + d[1]);
                TileState ns = states.getOrDefault(nk, TileState.UNKNOWN);
                if (ns == TileState.DANGER) dangerCount++;
                else if (ns == TileState.UNKNOWN) unknowns.add(nk);
            }

            int remaining = number - dangerCount;
            if (!unknowns.isEmpty() && remaining >= 0) {
                constraints.add(new ConstraintInfo(unknowns, remaining));
            }
        }

        // Compare pairs: if one's unknowns are a subset of another's
        for (int i = 0; i < constraints.size(); i++) {
            for (int j = 0; j < constraints.size(); j++) {
                if (i == j) continue;

                ConstraintInfo a = constraints.get(i);
                ConstraintInfo b = constraints.get(j);

                // Check if A's unknowns are a subset of B's unknowns
                if (b.unknowns.containsAll(a.unknowns)) {
                    // The tiles in B but not in A must contain (b.remaining - a.remaining) dangers
                    Set<Long> diff = new HashSet<>(b.unknowns);
                    diff.removeAll(a.unknowns);
                    int diffDangers = b.remaining - a.remaining;

                    if (diff.isEmpty()) continue;

                    // If diffDangers == 0, all diff tiles are safe
                    if (diffDangers == 0) {
                        for (long dk : diff) {
                            if (states.getOrDefault(dk, TileState.UNKNOWN) == TileState.UNKNOWN) {
                                states.put(dk, TileState.SAFE);
                                changed = true;
                            }
                        }
                    }

                    // If diffDangers == diff.size(), all diff tiles are dangers
                    if (diffDangers == diff.size()) {
                        for (long dk : diff) {
                            if (states.getOrDefault(dk, TileState.UNKNOWN) == TileState.UNKNOWN) {
                                states.put(dk, TileState.DANGER);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        return changed;
    }

    /**
     * Get all tiles currently deduced as SAFE that are mineable.
     */
    public List<Coord> getSafeTiles() {
        List<Coord> result = new ArrayList<>();
        for (Map.Entry<Long, TileState> entry : states.entrySet()) {
            if (entry.getValue() == TileState.SAFE) {
                result.add(new Coord(keyX(entry.getKey()), keyY(entry.getKey())));
            }
        }
        return result;
    }

    /**
     * Get all tiles within support coverage that are mineable.
     */
    public List<Coord> getSupportedMineableTiles() {
        List<Coord> result = new ArrayList<>();
        for (Map.Entry<Long, TileState> entry : states.entrySet()) {
            if (entry.getValue() == TileState.SUPPORTED) {
                Coord tile = new Coord(keyX(entry.getKey()), keyY(entry.getKey()));
                if (isTileMineable(tile)) {
                    result.add(tile);
                }
            }
        }
        return result;
    }

    /**
     * Check if a tile is mineable (rock or cave tile type).
     */
    private boolean isTileMineable(Coord tilePos) {
        try {
            Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));
            if (res == null) return false;
            return NParser.checkName(res.name, MINEABLE_TILES);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a tile is within support coverage.
     */
    public boolean isTileSupported(Coord tilePos) {
        TileState state = getState(tilePos);
        return state == TileState.SUPPORTED;
    }

    /**
     * Check if a tile is adjacent to open space (already-mined, walkable area).
     * A tile is adjacent to open space if at least one of its 4 cardinal neighbors
     * is not mineable (i.e., it's already been mined into open ground).
     */
    public boolean isAdjacentToOpenSpace(Coord tilePos) {
        int[][] cardinals = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        for (int[] d : cardinals) {
            Coord neighbor = new Coord(tilePos.x + d[0], tilePos.y + d[1]);
            if (!isTileMineable(neighbor)) {
                // Neighbor is open ground — this tile is on the mining frontier
                return true;
            }
        }
        return false;
    }

    /**
     * Check if an UNKNOWN tile is adjacent to any REVEALED tile with number > 0.
     * Such tiles are potential dangers — the minesweeper numbers warn about them.
     * Only tiles NOT adjacent to any numbered tile are safe to mine speculatively.
     */
    public boolean isAdjacentToNumberedTile(Coord tilePos) {
        for (int[] d : NEIGHBORS) {
            Coord neighbor = new Coord(tilePos.x + d[0], tilePos.y + d[1]);
            long nk = key(neighbor.x, neighbor.y);
            if (states.getOrDefault(nk, TileState.UNKNOWN) == TileState.REVEALED) {
                int num = numbers.getOrDefault(nk, 0);
                if (num > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Refresh support coverage data from all nearby mining supports.
     */
    private void refreshSupportCoverage(Coord center, int radius) {
        ArrayList<Gob> supports = Finder.findGobs(ALL_SUPPORTS);
        for (Gob support : supports) {
            Gob.Overlay ol = support.findol(NMiningSupport.class);
            if (ol == null || !(ol.spr instanceof NMiningSupport)) continue;

            NMiningSupport nms = (NMiningSupport) ol.spr;
            boolean[][] data = nms.getData();
            if (data == null) continue;

            for (int i = 0; i < data.length; i++) {
                for (int j = 0; j < data[i].length; j++) {
                    if (data[i][j]) {
                        int tx = nms.begin.x + i;
                        int ty = nms.begin.y + j;
                        long k = key(tx, ty);
                        TileState current = states.getOrDefault(k, TileState.UNKNOWN);
                        // Only mark as SUPPORTED if not already REVEALED
                        if (current == TileState.UNKNOWN || current == TileState.SAFE) {
                            states.put(k, TileState.SUPPORTED);
                        }
                    }
                }
            }
        }
    }

    /**
     * Read the minesweeper number from a gob at the given tile position.
     * Returns -1 if no number found.
     */
    public int readNumberAtTile(Coord tilePos) {
        Coord2d worldPos = new Coord2d(
                tilePos.x * tilesz.x + tilesz.x / 2,
                tilePos.y * tilesz.y + tilesz.y / 2
        );

        synchronized (gui.ui.sess.glob.oc) {
            for (Gob gob : gui.ui.sess.glob.oc) {
                if (gob.rc.dist(worldPos) < tilesz.x) {
                    Gob.Overlay ol = gob.findol(NMiningNumber.class);
                    if (ol != null && ol.spr instanceof NMiningNumber) {
                        return ((NMiningNumber) ol.spr).val;
                    }
                }
            }
        }
        return -1;
    }

    private static class ConstraintInfo {
        final Set<Long> unknowns;
        final int remaining;

        ConstraintInfo(Set<Long> unknowns, int remaining) {
            this.unknowns = unknowns;
            this.remaining = remaining;
        }
    }
}
