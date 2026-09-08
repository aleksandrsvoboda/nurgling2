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

    private static final int[][] CARDINALS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

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

    public void putState(int x, int y, TileState state) {
        states.put(key(x, y), state);
    }

    public void putNumber(int x, int y, int number) {
        numbers.put(key(x, y), number);
    }

    public List<Coord> dangerTiles() {
        return tilesWithState(TileState.DANGER);
    }

    public List<Coord> safeTiles() {
        return tilesWithState(TileState.SAFE);
    }

    private List<Coord> tilesWithState(TileState wanted) {
        List<Coord> tiles = new ArrayList<>();
        for (Map.Entry<Long, TileState> entry : states.entrySet()) {
            if (entry.getValue() == wanted) {
                tiles.add(new Coord(keyX(entry.getKey()), keyY(entry.getKey())));
            }
        }
        return tiles;
    }

    /**
     * Mined floor without a dust number is a minesweeper 0, which is the strongest
     * constraint there is — every neighbour of it is safe. {@link #refresh} cannot tell
     * those apart from bedrock and marks them WALL because they are no longer mineable,
     * so this promotes any WALL adjacent to a REVEALED tile back to a revealed zero.
     */
    public void promoteAdjacentBlanks() {
        List<Long> blanks = new ArrayList<>();
        for (Map.Entry<Long, TileState> entry : states.entrySet()) {
            if (entry.getValue() != TileState.REVEALED) {
                continue;
            }
            int x = keyX(entry.getKey());
            int y = keyY(entry.getKey());
            for (int[] d : NEIGHBORS) {
                long nk = key(x + d[0], y + d[1]);
                if (states.getOrDefault(nk, TileState.UNKNOWN) == TileState.WALL) {
                    blanks.add(nk);
                }
            }
        }
        for (long nk : blanks) {
            if (states.get(nk) == TileState.WALL) {
                states.put(nk, TileState.REVEALED);
                if (!numbers.containsKey(nk)) {
                    numbers.put(nk, 0);
                }
            }
        }
    }

    /**
     * Initialize/refresh the solver grid around a center position.
     * Prunes old entries far from the player to prevent unbounded growth.
     */
    public void refresh(Coord center, int radius) {
        // Prune entries far from the player (2x radius)
        int pruneRadius = radius * 2;
        states.entrySet().removeIf(e -> {
            int x = keyX(e.getKey());
            int y = keyY(e.getKey());
            return Math.abs(x - center.x) > pruneRadius || Math.abs(y - center.y) > pruneRadius;
        });
        numbers.entrySet().removeIf(e -> {
            int x = keyX(e.getKey());
            int y = keyY(e.getKey());
            return Math.abs(x - center.x) > pruneRadius || Math.abs(y - center.y) > pruneRadius;
        });

        // Mark tiles within support coverage as SUPPORTED
        refreshSupportCoverage(center, radius);

        // Only check tiles we don't already know about.
        // Skipping known tiles (including WALL) avoids thousands of
        // redundant MCache.gettile() calls per iteration.
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int y = center.y - radius; y <= center.y + radius; y++) {
                long k = key(x, y);
                if (states.containsKey(k)) continue;

                if (!isTileMineable(x, y)) {
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
                /* Not Gob.findol(Class): it dereferences ol.spr without a null check, and a
                 * gob here can still be carrying an overlay whose sprite has not resolved. */
                for (Gob.Overlay ol : gob.ols) {
                    if (ol.spr instanceof NMiningNumber) {
                        NMiningNumber nmn = (NMiningNumber) ol.spr;
                        Coord tile = gob.rc.div(tilesz).floor();
                        long k = key(tile.x, tile.y);
                        states.put(k, TileState.REVEALED);
                        numbers.put(k, nmn.val);
                    }
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
     * Run the constraint solver. Deduces SAFE and DANGER tiles from revealed numbers.
     * Collects changes separately to avoid copying the full states map each iteration.
     */
    public void solve() {
        boolean changed = true;

        while (changed) {
            changed = false;

            // Collect changes to apply after iteration (avoids ConcurrentModificationException
            // and avoids expensive new ArrayList<>(states.entrySet()) copies)
            Map<Long, TileState> pending = new HashMap<>();

            for (Map.Entry<Long, TileState> entry : states.entrySet()) {
                if (entry.getValue() != TileState.REVEALED) continue;

                long k = entry.getKey();
                int x = keyX(k);
                int y = keyY(k);
                int number = numbers.getOrDefault(k, 0);

                int unknownCount = 0;
                int dangerCount = 0;
                long[] neighborKeys = new long[8];
                TileState[] neighborStates = new TileState[8];

                for (int di = 0; di < NEIGHBORS.length; di++) {
                    long nk = key(x + NEIGHBORS[di][0], y + NEIGHBORS[di][1]);
                    neighborKeys[di] = nk;
                    // Check pending changes first, then states
                    TileState ns = pending.getOrDefault(nk, states.getOrDefault(nk, TileState.UNKNOWN));
                    neighborStates[di] = ns;
                    if (ns == TileState.DANGER) dangerCount++;
                    else if (ns == TileState.UNKNOWN) unknownCount++;
                }

                // Rule 1: All mines accounted for — remaining unknowns are safe
                if (dangerCount == number && unknownCount > 0) {
                    for (int di = 0; di < 8; di++) {
                        if (neighborStates[di] == TileState.UNKNOWN) {
                            pending.put(neighborKeys[di], TileState.SAFE);
                            changed = true;
                        }
                    }
                }

                // Rule 2: All unknowns must be mines
                if (dangerCount + unknownCount == number && unknownCount > 0) {
                    for (int di = 0; di < 8; di++) {
                        if (neighborStates[di] == TileState.UNKNOWN) {
                            pending.put(neighborKeys[di], TileState.DANGER);
                            changed = true;
                        }
                    }
                }
            }

            // Apply pending changes
            states.putAll(pending);

            // Subset/overlap analysis between pairs of revealed tiles
            if (!changed) {
                changed = solveSubsets();
            }
        }
    }

    /**
     * Advanced solver: compare constraints between pairs of numbered tiles
     * that share unknown neighbors.
     */
    private boolean solveSubsets() {
        boolean changed = false;

        // Collect only revealed tiles that have remaining unknowns (active constraints)
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
        Map<Long, TileState> pending = new HashMap<>();
        for (int i = 0; i < constraints.size(); i++) {
            for (int j = 0; j < constraints.size(); j++) {
                if (i == j) continue;

                ConstraintInfo a = constraints.get(i);
                ConstraintInfo b = constraints.get(j);

                if (b.unknowns.containsAll(a.unknowns)) {
                    Set<Long> diff = new HashSet<>(b.unknowns);
                    diff.removeAll(a.unknowns);
                    int diffDangers = b.remaining - a.remaining;

                    if (diff.isEmpty()) continue;

                    if (diffDangers == 0) {
                        for (long dk : diff) {
                            if (states.getOrDefault(dk, TileState.UNKNOWN) == TileState.UNKNOWN) {
                                pending.put(dk, TileState.SAFE);
                                changed = true;
                            }
                        }
                    }

                    if (diffDangers == diff.size()) {
                        for (long dk : diff) {
                            if (states.getOrDefault(dk, TileState.UNKNOWN) == TileState.UNKNOWN) {
                                pending.put(dk, TileState.DANGER);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        states.putAll(pending);
        return changed;
    }

    /**
     * Whether a tile is mineable (rock or cave tile type).
     *
     * <p>Returns {@code null} when the answer is not knowable yet — no session, the grid
     * has not loaded, or the tileset resource is still resolving. Callers that watch for
     * a tile changing from mineable to mined need that distinction: treating "still
     * loading" as "not mineable" would report a phantom mining event on every grid load.
     */
    public Boolean mineableOrUnknown(int x, int y) {
        if (gui == null || gui.ui == null || gui.ui.sess == null) {
            return null;
        }
        try {
            Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(new Coord(x, y)));
            if (res == null) {
                return null;
            }
            return NParser.checkName(res.name, MINEABLE_TILES);
        } catch (Loading e) {
            return null;
        } catch (ArrayIndexOutOfBoundsException e) {
            /* MCache.tilesetr indexes its set table unguarded, so a tile id from a grid
             * whose tileset table has not caught up yet lands out of range. */
            return null;
        }
    }

    private boolean isTileMineable(int x, int y) {
        return Boolean.TRUE.equals(mineableOrUnknown(x, y));
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
     * Uses the states map when possible to avoid MCache hits.
     */
    public boolean isAdjacentToOpenSpace(Coord tilePos) {
        for (int[] d : CARDINALS) {
            int nx = tilePos.x + d[0];
            int ny = tilePos.y + d[1];
            long nk = key(nx, ny);
            TileState ns = states.get(nk);
            // WALL or REVEALED means it's already open/mined ground
            if (ns == TileState.WALL || ns == TileState.REVEALED) {
                return true;
            }
            // If not in states, check the map directly
            if (ns == null && !isTileMineable(nx, ny)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if an UNKNOWN tile is adjacent to any REVEALED tile with number > 0.
     */
    public boolean isAdjacentToNumberedTile(Coord tilePos) {
        for (int[] d : NEIGHBORS) {
            long nk = key(tilePos.x + d[0], tilePos.y + d[1]);
            if (states.getOrDefault(nk, TileState.UNKNOWN) == TileState.REVEALED) {
                if (numbers.getOrDefault(nk, 0) > 0) {
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
                        /* Support coverage outranks every deduction: a tile the solver
                         * called DANGER is still safe to mine once it is propped. Only
                         * hard facts about the tile itself — already mined (REVEALED) or
                         * not mineable at all (WALL) — survive. */
                        if (current != TileState.REVEALED && current != TileState.WALL) {
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
                    for (Gob.Overlay ol : gob.ols) {
                        if (ol.spr instanceof NMiningNumber) {
                            return ((NMiningNumber) ol.spr).val;
                        }
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
