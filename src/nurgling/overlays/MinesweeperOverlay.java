package nurgling.overlays;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NUI;
import nurgling.NUtils;
import nurgling.actions.bots.MinesweeperSolver;
import nurgling.conf.NMiningOverlayMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static haven.MCache.tilesz;

/**
 * Draws what is known about a mine and remembers it across relogs.
 *
 * <p>Two things are shown. Cave-in dust numbers keep their white glyph after the game has
 * forgotten the gob, restored as virtual gobs from {@link NMiningOverlayMemory}. And walls
 * next to a tile that was mined with <em>no</em> dust get a green dot: no dust is a
 * minesweeper zero, so every neighbour of it is safe to mine.
 *
 * <p>Nothing here draws danger. The solver's deductions are not rendered, so this only
 * reports facts the player has already uncovered rather than guessing at hidden ones.
 */
public class MinesweeperOverlay {

    private static final double UPDATE_INTERVAL = 0.3;
    /* How long a freshly mined tile is given to produce its dust gob before we conclude
     * it produced none. */
    private static final double DUST_WAIT = 0.8;
    /** Tiles around the player that are drawn and persisted. */
    private static final int RADIUS = 50;
    /** Tiles around the player watched for a wall turning into floor. */
    private static final int MINE_WATCH_RADIUS = 8;
    /** Re-probe for rock this often even when standing still, to catch a grid loading in. */
    private static final double PROBE_INTERVAL = 1.0;

    private static final int[][] NEIGHBORS = {
            {-1, -1}, {0, -1}, {1, -1},
            {-1, 0}, {1, 0},
            {-1, 1}, {0, 1}, {1, 1}
    };

    private MinesweeperSolver solver;
    private NGameUI solverGui;
    private final Map<Long, Gob> greenMarkers = new HashMap<>();
    private final Map<Long, Gob> numberMarkers = new HashMap<>();
    private final Map<Long, Boolean> prevMineable = new HashMap<>();
    private final Map<Long, Double> pendingBlanks = new HashMap<>();
    private final Set<Long> confirmedBlanks = new HashSet<>();
    private NMiningOverlayMemory memory;
    private String memUser;
    private String memChr;
    private final TimedSnapshot<NumberSnapshot> numberSnapshots = newNumberSnapshotCache();

    /* Scan gating. The watch window is only worth walking when rock could be in it, which
     * is true exactly when the last walk found some or when the player has moved since. */
    private Coord lastScanTile = null;
    private boolean sawMineable = false;
    private double sinceProbe = PROBE_INTERVAL;

    /* Set from any thread, acted on by tick(). Everything else here is touched only by the
     * UI thread, and a bot asking for a redraw must not reach into it directly. */
    private volatile boolean restoreRequested = false;

    static final class SnapshotUpdate<T> {
        final T value;
        final boolean refreshed;

        SnapshotUpdate(T value, boolean refreshed) {
            this.value = value;
            this.refreshed = refreshed;
        }
    }

    static final class TimedSnapshot<T> {
        private final double interval;
        private double age;
        private T value;

        TimedSnapshot(double interval) {
            this.interval = interval;
            this.age = interval;
        }

        SnapshotUpdate<T> update(double dt, Supplier<T> capture) {
            age += dt;
            if (value == null || age >= interval) {
                value = capture.get();
                age = 0;
                return new SnapshotUpdate<>(value, true);
            }
            return new SnapshotUpdate<>(value, false);
        }

        void clear() {
            value = null;
            age = interval;
        }
    }

    static <T> TimedSnapshot<T> newNumberSnapshotCache() {
        return new TimedSnapshot<>(UPDATE_INTERVAL);
    }

    private static final class NumberEntry {
        final Coord tile;
        final int value;
        final boolean virtual;

        NumberEntry(Coord tile, int value, boolean virtual) {
            this.tile = tile;
            this.value = value;
            this.virtual = virtual;
        }
    }

    /** One pass over the object cache, shared by every consumer for {@link #UPDATE_INTERVAL}. */
    private static final class NumberSnapshot {
        final List<NumberEntry> entries;
        /** Tiles whose number comes from a real gob, so no restored copy is wanted there. */
        final Set<Long> liveTiles;

        NumberSnapshot(List<NumberEntry> entries, Set<Long> liveTiles) {
            this.entries = entries;
            this.liveTiles = liveTiles;
        }

        static NumberSnapshot capture(NGameUI gui) {
            List<NumberEntry> entries = new ArrayList<>();
            Set<Long> liveTiles = new HashSet<>();
            synchronized (gui.ui.sess.glob.oc) {
                for (Gob gob : gui.ui.sess.glob.oc) {
                    for (Gob.Overlay ol : gob.ols) {
                        if (ol.spr instanceof NMiningNumber) {
                            NMiningNumber number = (NMiningNumber) ol.spr;
                            Coord tile = gob.rc.div(tilesz).floor();
                            entries.add(new NumberEntry(tile, number.val, gob.virtual));
                            if (!gob.virtual) {
                                liveTiles.add(key(tile.x, tile.y));
                            }
                        }
                    }
                }
            }
            return new NumberSnapshot(entries, liveTiles);
        }

        void applyTo(MinesweeperSolver solver) {
            for (NumberEntry entry : entries) {
                solver.reveal(entry.tile, entry.value);
            }
        }
    }

    static Set<Coord> greenFromFreshBlanks(Iterable<Coord> blanks, Set<Coord> mineable) {
        Set<Coord> green = new HashSet<>();
        for (Coord blank : blanks) {
            for (int[] d : NEIGHBORS) {
                Coord n = new Coord(blank.x + d[0], blank.y + d[1]);
                if (mineable.contains(n)) {
                    green.add(n);
                }
            }
        }
        return green;
    }

    static Coord snapshotPlayerTile(Supplier<Coord2d> playerPosition) {
        Coord2d position = playerPosition.get();
        return position == null ? null : position.div(tilesz).floor();
    }

    public void tick(double dt) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.ui == null || gui.ui.sess == null || gui.map == null) {
            return;
        }
        if (!Boolean.TRUE.equals(NConfig.get(NConfig.Key.minesweeperol))) {
            restoreRequested = false;
            if (solver != null) {
                clear(gui);
                reset();
            }
            return;
        }
        Coord playerTile = snapshotPlayerTile(() -> {
            Gob player = gui.map.player();
            return player == null ? null : player.rc;
        });
        if (playerTile == null) {
            return;
        }
        if (solver == null || solverGui != gui) {
            clear(gui);
            reset();
            solver = new MinesweeperSolver(gui);
            solverGui = gui;
            reloadMemory(gui);
            restoreRequested = false;
        } else if (restoreRequested) {
            restoreRequested = false;
            runRestore(gui, playerTile);
        }

        SnapshotUpdate<NumberSnapshot> snapshotUpdate = numberSnapshots.update(
                dt, () -> NumberSnapshot.capture(gui));
        NumberSnapshot snapshot = snapshotUpdate.value;
        if (snapshotUpdate.refreshed) {
            snapshot.applyTo(solver);
        }
        observeMinedTiles(playerTile, dt);

        if (snapshotUpdate.refreshed) {
            persistLiveNumbers(gui, snapshot);
            persistGreens(gui);
            restoreRememberedNumbers(gui, playerTile, snapshot);
            if (memory != null) {
                memory.maybeFlush(System.currentTimeMillis());
            }
        }
        sync(gui, playerTile);
    }

    /**
     * Ask for this character's memory to be re-read and redrawn on the next tick.
     *
     * <p>Safe to call from a bot thread: the work itself is deferred to {@link #tick}, which
     * is the only thing that touches the marker state.
     */
    public void requestRestore() {
        restoreRequested = true;
    }

    /** Re-read memory and redraw everything now. UI thread only. */
    private void runRestore(NGameUI gui, Coord playerTile) {
        numberSnapshots.clear();
        NumberSnapshot snapshot = NumberSnapshot.capture(gui);
        reloadMemory(gui, snapshot);
        snapshot.applyTo(solver);
        restoreRememberedNumbers(gui, playerTile, snapshot);
        sync(gui, playerTile);
        if (memory != null) {
            memory.maybeFlush(System.currentTimeMillis());
        }
    }

    private void reloadMemory(NGameUI gui) {
        reloadMemory(gui, NumberSnapshot.capture(gui));
    }

    /** Rebind to this character's stored memory and fold what is on screen back into it. */
    private void reloadMemory(NGameUI gui, NumberSnapshot snapshot) {
        if (solver == null || solverGui != gui) {
            solver = new MinesweeperSolver(gui);
            solverGui = gui;
        }
        memory = null;
        memUser = null;
        memChr = null;
        resolveMemory(gui);
        persistLiveNumbers(gui, snapshot);
        persistGreens(gui);
    }

    private void reset() {
        solver = null;
        solverGui = null;
        prevMineable.clear();
        pendingBlanks.clear();
        confirmedBlanks.clear();
        numberSnapshots.clear();
        lastScanTile = null;
        sawMineable = false;
        sinceProbe = PROBE_INTERVAL;
    }

    /**
     * Watch the tiles around the player for a wall becoming floor, and decide whether the
     * tile that changed produced dust. A tile that stays numberless past {@link #DUST_WAIT}
     * is a confirmed blank, which is what makes its neighbours green.
     */
    private void observeMinedTiles(Coord playerTile, double dt) {
        sinceProbe += dt;
        boolean moved = !playerTile.equals(lastScanTile);
        /* No rock in the window last time and the player has not moved means nothing in it
         * can have changed, so the walk is skipped rather than repeated every frame. */
        if (moved || sawMineable || sinceProbe >= PROBE_INTERVAL) {
            scanWatchWindow(playerTile);
            lastScanTile = playerTile;
            sinceProbe = 0;
        }

        Iterator<Map.Entry<Long, Double>> pending = pendingBlanks.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Long, Double> e = pending.next();
            Coord tile = new Coord(keyX(e.getKey()), keyY(e.getKey()));
            int number = solver.getNumber(tile);
            if (number > 0) {
                confirmedBlanks.remove(e.getKey());
                pending.remove();
                continue;
            }
            double wait = e.getValue() + dt;
            if (number == 0 || wait >= DUST_WAIT) {
                confirmedBlanks.add(e.getKey());
                pending.remove();
            } else {
                e.setValue(wait);
            }
        }

        int prune = RADIUS * 2;
        confirmedBlanks.removeIf(k ->
                Math.abs(keyX(k) - playerTile.x) > prune || Math.abs(keyY(k) - playerTile.y) > prune);
        prevMineable.entrySet().removeIf(e ->
                Math.abs(keyX(e.getKey()) - playerTile.x) > prune || Math.abs(keyY(e.getKey()) - playerTile.y) > prune);
    }

    private void scanWatchWindow(Coord playerTile) {
        boolean any = false;
        for (int x = playerTile.x - MINE_WATCH_RADIUS; x <= playerTile.x + MINE_WATCH_RADIUS; x++) {
            for (int y = playerTile.y - MINE_WATCH_RADIUS; y <= playerTile.y + MINE_WATCH_RADIUS; y++) {
                Boolean cur = solver.mineableOrUnknown(x, y);
                if (cur == null) {
                    continue;
                }
                if (cur) {
                    any = true;
                }
                long k = key(x, y);
                Boolean prev = prevMineable.put(k, cur);
                if (Boolean.TRUE.equals(prev) && Boolean.FALSE.equals(cur)) {
                    pendingBlanks.put(k, 0.0);
                    confirmedBlanks.remove(k);
                }
            }
        }
        sawMineable = any;
    }

    private NMiningOverlayMemory resolveMemory(NGameUI gui) {
        if (gui == null || !(gui.ui instanceof NUI) || gui.getCharInfo() == null) {
            return memory;
        }
        NUI.NSessInfo sess = ((NUI) gui.ui).sessInfo;
        if (sess == null || sess.username == null) {
            return memory;
        }
        String chrid = gui.getCharInfo().chrid;
        if (chrid == null) {
            return memory;
        }
        if (memory == null || !sess.username.equals(memUser) || !chrid.equals(memChr)) {
            memory = NMiningOverlayMemory.get(sess.username, chrid);
            memUser = sess.username;
            memChr = chrid;
        }
        return memory;
    }

    private MCache mapOf(NGameUI gui) {
        return gui.ui.sess.glob.map;
    }

    private void persistLiveNumbers(NGameUI gui, NumberSnapshot snapshot) {
        NMiningOverlayMemory mem = resolveMemory(gui);
        if (mem == null) {
            return;
        }
        MCache map = mapOf(gui);
        for (NumberEntry entry : snapshot.entries) {
            if (entry.virtual) {
                continue;
            }
            NMiningOverlayMemory.TileRef ref = NMiningOverlayMemory.ofWorld(map, entry.tile);
            if (ref != null) {
                mem.putNumber(ref, entry.value);
            }
        }
    }

    private void persistGreens(NGameUI gui) {
        NMiningOverlayMemory mem = resolveMemory(gui);
        if (mem == null) {
            return;
        }
        MCache map = mapOf(gui);
        Set<Coord> blanks = new HashSet<>();
        Set<Coord> mineable = new HashSet<>();
        collectBlankNeighbors(blanks, mineable);
        for (Coord tile : greenFromFreshBlanks(blanks, mineable)) {
            NMiningOverlayMemory.TileRef ref = NMiningOverlayMemory.ofWorld(map, tile);
            if (ref != null) {
                mem.putGreen(ref);
            }
        }
    }

    /**
     * Put a virtual number gob back on any remembered tile the game is not already drawing
     * one for, and take down the ones that are no longer wanted.
     */
    private void restoreRememberedNumbers(NGameUI gui, Coord playerTile, NumberSnapshot snapshot) {
        NMiningOverlayMemory mem = resolveMemory(gui);
        if (mem == null) {
            return;
        }
        MCache map = mapOf(gui);
        OCache oc = gui.ui.sess.glob.oc;
        Set<Long> liveNumberTiles = snapshot.liveTiles;
        Set<Long> wanted = new HashSet<>();
        for (Map.Entry<NMiningOverlayMemory.TileRef, Integer> e : mem.numbers().entrySet()) {
            Coord tile = NMiningOverlayMemory.toWorld(map, e.getKey());
            if (tile == null) {
                continue;
            }
            if (Math.abs(tile.x - playerTile.x) > RADIUS || Math.abs(tile.y - playerTile.y) > RADIUS) {
                continue;
            }
            /* A zero never had a glyph of its own, so there is nothing to put back. */
            if (e.getValue() <= 0) {
                continue;
            }
            long k = key(tile.x, tile.y);
            wanted.add(k);
            if (liveNumberTiles.contains(k)) {
                Gob dummy = numberMarkers.remove(k);
                if (dummy != null) {
                    removeGob(oc, dummy);
                }
                continue;
            }
            Gob dummy = numberMarkers.get(k);
            if (dummy == null || oc.getgob(dummy.id) == null || dummy.findol(NMiningNumber.class) == null) {
                if (dummy != null) {
                    removeGob(oc, dummy);
                }
                numberMarkers.put(k, createNumber(oc, tile, e.getValue()));
            }
        }
        Iterator<Map.Entry<Long, Gob>> it = numberMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Gob> e = it.next();
            if (!wanted.contains(e.getKey()) || liveNumberTiles.contains(e.getKey())) {
                removeGob(oc, e.getValue());
                it.remove();
            }
        }
    }

    private void collectBlankNeighbors(Set<Coord> blanks, Set<Coord> mineable) {
        for (long k : confirmedBlanks) {
            blanks.add(new Coord(keyX(k), keyY(k)));
            int x = keyX(k);
            int y = keyY(k);
            for (int[] d : NEIGHBORS) {
                int nx = x + d[0];
                int ny = y + d[1];
                if (Boolean.TRUE.equals(solver.mineableOrUnknown(nx, ny))) {
                    mineable.add(new Coord(nx, ny));
                }
            }
        }
    }

    private void sync(NGameUI gui, Coord playerTile) {
        OCache oc = gui.ui.sess.glob.oc;
        Set<Long> wanted = new HashSet<>();

        Set<Coord> blanks = new HashSet<>();
        Set<Coord> mineable = new HashSet<>();
        collectBlankNeighbors(blanks, mineable);
        for (Coord tile : greenFromFreshBlanks(blanks, mineable)) {
            wanted.add(key(tile.x, tile.y));
        }
        rememberedGreens(gui, wanted, playerTile);

        for (long k : wanted) {
            Gob dummy = greenMarkers.get(k);
            if (dummy == null || oc.getgob(dummy.id) == null) {
                if (dummy != null) {
                    removeGob(oc, dummy);
                }
                greenMarkers.put(k, createGreen(oc, new Coord(keyX(k), keyY(k))));
            }
        }
        Iterator<Map.Entry<Long, Gob>> it = greenMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Gob> e = it.next();
            if (!wanted.contains(e.getKey())) {
                removeGob(oc, e.getValue());
                it.remove();
            }
        }
    }

    private void rememberedGreens(NGameUI gui, Set<Long> wanted, Coord playerTile) {
        NMiningOverlayMemory mem = resolveMemory(gui);
        if (mem == null) {
            return;
        }
        MCache map = mapOf(gui);
        for (NMiningOverlayMemory.TileRef ref : mem.greens()) {
            Coord tile = NMiningOverlayMemory.toWorld(map, ref);
            if (tile == null) {
                continue;
            }
            if (Math.abs(tile.x - playerTile.x) > RADIUS || Math.abs(tile.y - playerTile.y) > RADIUS) {
                continue;
            }
            /* Once it has been mined out it is floor, not a safe wall — forget it. */
            if (Boolean.FALSE.equals(solver.mineableOrUnknown(tile.x, tile.y))) {
                mem.removeGreen(ref);
                continue;
            }
            wanted.add(key(tile.x, tile.y));
        }
    }

    private static Gob createGreen(OCache oc, Coord tile) {
        OCache.Virtual created = virtualAt(oc, tile);
        created.addol(new Gob.Overlay(created, new NMiningSafeOverlay(created)), false);
        oc.add(created);
        return created;
    }

    private static Gob createNumber(OCache oc, Coord tile, int val) {
        OCache.Virtual created = virtualAt(oc, tile);
        created.addol(new Gob.Overlay(created, new NMiningNumber(created, val)), false);
        oc.add(created);
        return created;
    }

    private static OCache.Virtual virtualAt(OCache oc, Coord tile) {
        Coord2d pos = new Coord2d((tile.x + 0.5) * tilesz.x, (tile.y + 0.5) * tilesz.y);
        OCache.Virtual created = oc.new Virtual(pos, 0);
        created.virtual = true;
        return created;
    }

    private void clear(NGameUI gui) {
        OCache oc = gui.ui.sess.glob.oc;
        for (Gob dummy : greenMarkers.values()) {
            removeGob(oc, dummy);
        }
        greenMarkers.clear();
        for (Gob dummy : numberMarkers.values()) {
            removeGob(oc, dummy);
        }
        numberMarkers.clear();
    }

    private static void removeGob(OCache oc, Gob dummy) {
        if (dummy != null && oc.getgob(dummy.id) != null) {
            oc.remove(dummy);
        }
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
}
