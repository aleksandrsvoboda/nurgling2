package nurgling.actions.bots;

import haven.*;
import nurgling.*;
import nurgling.actions.*;
import nurgling.overlays.NMiningSupport;
import nurgling.tasks.*;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.widgets.TunnelingDialog.Direction;
import nurgling.widgets.TunnelingDialog.SupportType;
import nurgling.widgets.TunnelingDialog.TunnelSide;

import java.util.*;

import static haven.MCache.tilesz;
import static haven.OCache.posres;

/**
 * Digs with tunnel frames (Timber, Reinforced and Stone Arch Tunnel), started from
 * the Tunneling Bot's dialog. A frame is a doorway the corridor runs through, and it
 * props a width x length rectangle ahead of it, so the loop is: find the first row the
 * existing supports don't prop, stand a frame on the line behind it, mine what it
 * props, repeat.
 *
 * mineTileIfNeeded, handleBumlings and the build wait are copied from TunnelingBot;
 * check fixes against both copies.
 */
public class FrameTunneler implements Action {

    private static final NAlias ALL_SUPPORTS = new NAlias(
            "minebeam", "column", "towercap", "ladder", "minesupport", "naturalminesupport",
            "timbertunnel", "reinforcedtunnel", "stonearchtunnel"
    );

    private static final NAlias MINEABLE_TILES = new NAlias("rock", "tiles/cave");

    // How far ahead to look for the end of the propped ground
    private static final int MAX_SCAN_ROWS = 64;

    private final Direction direction;
    private final SupportType frameType;
    private final TunnelSide extraSide;
    private final boolean centreLine;
    private final int width, length;
    private final NAlias frameAlias;

    // Tile steps along the dig direction, and across it (East digging N/S, South digging E/W)
    private final Coord fwd, lat;
    private final double angle;

    // Lateral coordinates of the frame's columns and of the columns we dig, centre first
    private int centreCol;
    private int[] frameCols, digCols;

    public FrameTunneler(Direction direction, SupportType frameType, TunnelSide extraSide, boolean centreLine) {
        this.direction = direction;
        this.frameType = frameType;
        this.extraSide = extraSide;
        this.centreLine = centreLine;
        NMiningSupport.Spec spec = frameType.frameSpec();
        this.width = spec.widthTiles;
        this.length = spec.lengthTiles;
        this.frameAlias = new NAlias(frameType.resourcePath);
        this.fwd = new Coord(direction.dx, direction.dy);
        this.lat = new Coord(Math.abs(direction.dy), Math.abs(direction.dx));
        // Facing as NMiningSupport.computeRect reads it: a=0 is +X, turning towards +Y
        switch (direction) {
            case EAST: angle = 0; break;
            case SOUTH: angle = Math.PI / 2; break;
            case WEST: angle = Math.PI; break;
            default: angle = 3 * Math.PI / 2; break;
        }
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        Gob player = NUtils.player();
        if (player == null) {
            return Results.ERROR("Player not found");
        }

        Gob start = findNearestSupport(player);
        if (start == null) {
            return Results.ERROR("No mine support found nearby. Stand near a support to start.");
        }

        Coord playerTile = player.rc.div(tilesz).floor();
        if (!coverage().isSafe(playerTile)) {
            return Results.ERROR("Stand inside a support's coverage to start.");
        }

        setUpCorridor(start, playerTile);
        gui.msg("Tunneling with " + frameType.menuName + ": " + direction.name +
                (centreLine && width > 1 ? ", centre line only" : ""));

        int row = rowOf(playerTile);
        while (true) {
            int frontier = findFrontier(row);
            if (frontier < 0) {
                return Results.ERROR("Couldn't find the end of the propped ground ahead");
            }

            // Mine the propped stretch up to the frontier
            Results mined = mineRows(gui, row, frontier - 1, digCols);
            if (!mined.IsSuccess()) {
                return mined;
            }

            // The frame stands on the row behind the frontier, across its full width
            mined = mineRows(gui, frontier - 1, frontier - 1, frameCols);
            if (!mined.IsSuccess()) {
                return mined;
            }

            String blocked = blockedAhead(gui, frontier);
            if (blocked != null) {
                gui.msg("Tunneling stopped: " + blocked);
                return Results.SUCCESS();
            }

            Results placed = placeFrame(gui, frontier);
            if (!placed.IsSuccess()) {
                return placed;
            }

            // Never mine on the assumption that the frame props what it should
            if (findFrontier(frontier) <= frontier) {
                return Results.ERROR("The new " + frameType.menuName + " doesn't prop the ground ahead; stopping");
            }

            row = frontier;
        }
    }

    // --- Corridor geometry ---

    private int rowOf(Coord tile) {
        return tile.x * fwd.x + tile.y * fwd.y;
    }

    private int latOf(Coord tile) {
        return tile.x * lat.x + tile.y * lat.y;
    }

    private Coord tileAt(int row, int col) {
        return fwd.mul(row).add(lat.mul(col));
    }

    private Coord2d tileCenter(Coord tile) {
        return tile.mul(tilesz).add(tilesz.div(2));
    }

    /* Continue the line of a same-type frame facing our way when the player stands in
     * it; otherwise line the corridor up on the player's tile. */
    private void setUpCorridor(Gob start, Coord playerTile) {
        int playerLat = latOf(playerTile);
        int[] cols = null;

        if (NParser.checkName(start.ngob.name, frameAlias) && facingOf(start).equals(fwd)) {
            NMiningSupport.Mask mask = maskOf(start);
            if (mask != null) {
                TreeSet<Integer> lats = new TreeSet<>();
                for (Coord tile : tilesOf(mask)) {
                    lats.add(latOf(tile));
                }
                if (lats.contains(playerLat)) {
                    cols = lats.stream().mapToInt(Integer::intValue).toArray();
                }
            }
        }

        if (cols == null) {
            if (width == 1) {
                cols = new int[]{playerLat};
            } else if (width == 2) {
                boolean positive = (extraSide == TunnelSide.EAST || extraSide == TunnelSide.SOUTH);
                cols = new int[]{playerLat, playerLat + (positive ? 1 : -1)};
            } else {
                cols = new int[]{playerLat - 1, playerLat, playerLat + 1};
            }
        }

        // Odd widths dig out from the middle; a 2-wide frame from the player's column
        int[] sorted = cols.clone();
        Arrays.sort(sorted);
        centreCol = (sorted.length % 2 == 1) ? sorted[sorted.length / 2] : playerLat;
        if (Arrays.stream(sorted).noneMatch(c -> c == centreCol)) {
            centreCol = sorted[0];
        }

        frameCols = Arrays.stream(sorted).boxed()
                .sorted(Comparator.comparingInt(c -> Math.abs(c - centreCol)))
                .mapToInt(Integer::intValue).toArray();
        digCols = centreLine ? new int[]{centreCol} : frameCols;
    }

    private static Coord facingOf(Gob gob) {
        Coord2d v = Coord2d.of(1, 0).rot(gob.a);
        if (Math.abs(v.x) >= Math.abs(v.y)) {
            return new Coord(v.x >= 0 ? 1 : -1, 0);
        }
        return new Coord(0, v.y >= 0 ? 1 : -1);
    }

    /* First row, from fromRow on, where some frame column isn't propped. */
    private int findFrontier(int fromRow) {
        Coverage cov = coverage();
        for (int r = fromRow; r < fromRow + MAX_SCAN_ROWS; r++) {
            for (int c : frameCols) {
                if (!cov.covered(tileAt(r, c))) {
                    return r;
                }
            }
        }
        return -1;
    }

    /* The tile the frame's preview must sit on so that it props exactly the wanted
     * rectangle. Searched with the overlay's own geometry instead of re-deriving the
     * parity rules for even widths and negative directions. */
    private Coord solveOrigin(int frontier) {
        Set<Coord> wanted = new HashSet<>();
        for (int r = frontier; r < frontier + length; r++) {
            for (int c : frameCols) {
                wanted.add(tileAt(r, c));
            }
        }
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                Coord origin = tileAt(frontier + dr, centreCol + dc);
                NMiningSupport.Mask mask = NMiningSupport.computeRect(frameAnchor(origin), angle, width, length, 0);
                if (new HashSet<>(tilesOf(mask)).equals(wanted)) {
                    return origin;
                }
            }
        }
        return null;
    }

    /* A frame stands on the tile edge that is its line, between its posts: on the
     * origin tile's near edge along the dig axis, and across it on the tile's centre
     * for odd widths or its near edge for even ones. */
    private Coord2d frameAnchor(Coord origin) {
        Coord2d anchor = origin.mul(tilesz);
        if (width % 2 == 1) {
            anchor = anchor.add(Coord2d.of(lat).mul(tilesz.x / 2));
        }
        return anchor;
    }

    // --- Support coverage ---

    /* Tiles propped by any support, and those whose support is about to fail. */
    private static final class Coverage {
        final Set<Coord> healthy = new HashSet<>();
        final Set<Coord> weak = new HashSet<>();

        boolean covered(Coord tile) {
            return healthy.contains(tile) || weak.contains(tile);
        }

        boolean isSafe(Coord tile) {
            return healthy.contains(tile) && !weak.contains(tile);
        }
    }

    private Coverage coverage() {
        Coverage cov = new Coverage();
        for (Gob support : Finder.findGobs(ALL_SUPPORTS)) {
            NMiningSupport.Mask mask = maskOf(support);
            if (mask == null) {
                continue;
            }
            GobHealth health = support.getattr(GobHealth.class);
            Set<Coord> into = (health != null && health.hp <= 0.25) ? cov.weak : cov.healthy;
            into.addAll(tilesOf(mask));
        }
        return cov;
    }

    /* Built tunnels are recomputed from their facing here: the overlay works out a
     * built support's mask once, possibly before gob.a has arrived. */
    private static NMiningSupport.Mask maskOf(Gob support) {
        if (support.id < 0 || support.ngob == null) {
            return null;
        }
        NMiningSupport.Spec spec = NMiningSupport.specFor(support.ngob.name);
        if (spec == null) {
            return null;
        }
        if (spec.isRect()) {
            return NMiningSupport.computeRect(support.rc, support.a, spec.widthTiles, spec.lengthTiles, 1);
        }
        for (Gob.Overlay ol : support.ols) {
            if (ol.spr instanceof NMiningSupport) {
                return ((NMiningSupport) ol.spr).getMask();
            }
        }
        return null;
    }

    private static List<Coord> tilesOf(NMiningSupport.Mask mask) {
        List<Coord> tiles = new ArrayList<>();
        if (mask == null || mask.data == null) {
            return tiles;
        }
        for (int i = 0; i < mask.data.length; i++) {
            for (int j = 0; j < mask.data[i].length; j++) {
                if (mask.data[i][j]) {
                    tiles.add(new Coord(mask.begin.x + i, mask.begin.y + j));
                }
            }
        }
        return tiles;
    }

    private Gob findNearestSupport(Gob player) {
        Gob nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Gob support : Finder.findGobs(ALL_SUPPORTS)) {
            if (maskOf(support) == null) {
                continue;
            }
            double dist = support.rc.dist(player.rc);
            if (dist < minDist) {
                minDist = dist;
                nearest = support;
            }
        }
        return nearest;
    }

    // --- Mining ---

    /* Mines the given columns of rows fromRow..toRow, nearest row first and centre
     * column first, so every tile borders ground that is already open. */
    private Results mineRows(NGameUI gui, int fromRow, int toRow, int[] cols) throws InterruptedException {
        for (int r = fromRow; r <= toRow; r++) {
            for (int c : cols) {
                Results result = mineTileIfNeeded(gui, tileAt(r, c));
                if (!result.IsSuccess()) {
                    return result;
                }
            }
        }
        return Results.SUCCESS();
    }

    private Results mineTileIfNeeded(NGameUI gui, Coord tilePos) throws InterruptedException {
        if (!needsMining(gui, tilePos)) {
            return Results.SUCCESS();
        }

        if (!isSafeToMine(tilePos)) {
            return Results.ERROR("Unsafe to mine - check support health or loose rocks");
        }

        Coord2d worldPos = tileCenter(tilePos);

        PathFinder pf = new PathFinder(NGob.getDummy(worldPos, 0,
                new NHitBox(new Coord2d(-5.5, -5.5), new Coord2d(5.5, 5.5))), true);
        pf.isHardMode = true;
        pf.run(gui);

        new RestoreResources().run(gui);

        Resource resBefore = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));

        while (needsMining(gui, tilePos)) {
            handleBumlings(gui);

            NUtils.mine(worldPos);
            gui.map.wdgmsg("sel", tilePos, tilePos, 0);

            if (NUtils.getStamina() > 0.4) {
                Resource finalResBefore = resBefore;
                NUtils.addTask(new NTask() {
                    @Override
                    public boolean check() {
                        Resource current = gui.ui.sess.glob.map.tilesetr(
                                gui.ui.sess.glob.map.gettile(tilePos));
                        return current != finalResBefore;
                    }
                });
            }

            resBefore = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));

            if (!new RestoreResources().run(gui).IsSuccess()) {
                return Results.ERROR("Cannot restore resources");
            }
        }

        NUtils.getDefaultCur();
        return Results.SUCCESS();
    }

    private boolean needsMining(NGameUI gui, Coord tilePos) {
        Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tilePos));
        if (res == null) {
            return false;
        }
        return NParser.checkName(res.name, MINEABLE_TILES);
    }

    private boolean isSafeToMine(Coord tilePos) throws InterruptedException {
        Gob looserock = Finder.findGob(new NAlias("looserock"));
        if (looserock != null && looserock.rc.dist(tileCenter(tilePos)) < 93.5) {
            return false;
        }
        return coverage().isSafe(tilePos);
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

    /* Why the next frame's rectangle can't be dug, or null if it can. */
    private String blockedAhead(NGameUI gui, int frontier) throws InterruptedException {
        for (int r = frontier; r < frontier + length; r++) {
            for (int c : frameCols) {
                Coord tile = tileAt(r, c);
                Resource res;
                try {
                    res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tile));
                } catch (Loading e) {
                    res = null;
                }
                if (res == null) {
                    return "the map ahead isn't loaded";
                }
                Gob obstacle = Finder.findGob(tile);
                if (obstacle != null && obstacle.ngob != null && obstacle.ngob.name != null
                        && !NParser.isIt(obstacle, ALL_SUPPORTS)
                        && !NParser.checkName(obstacle.ngob.name, new NAlias("bumlings"))) {
                    return "obstacle ahead: " + obstacle.ngob.name;
                }
            }
        }
        return null;
    }

    // --- Building ---

    private Results placeFrame(NGameUI gui, int frontier) throws InterruptedException {
        Coord origin = solveOrigin(frontier);
        if (origin == null) {
            return Results.ERROR("Can't line the " + frameType.menuName + " up with the corridor");
        }
        Coord2d pos = frameAnchor(origin);

        // Stand on the frame line, in the corridor
        new PathFinder(tileCenter(tileAt(frontier - 1, centreCol))).run(gui);

        Set<Long> existing = new HashSet<>();
        for (Gob frame : Finder.findGobs(frameAlias)) {
            existing.add(frame.id);
        }

        boolean menuActivated = false;
        for (MenuGrid.Pagina pag : gui.menu.paginae) {
            if (pag.button() != null && pag.button().name().equals(frameType.menuName)) {
                pag.button().use(new MenuGrid.Interaction(1, 0));
                menuActivated = true;
                break;
            }
        }
        if (!menuActivated) {
            return Results.ERROR("Cannot find " + frameType.menuName + " in build menu");
        }

        NUtils.addTask(new WaitPlob());
        Loader.Future<MapView.Plob> placing = gui.map.placing;
        if (placing != null && placing.ready()) {
            // Turn the preview too, so the coverage overlay shows what is being built
            placing.get().a = angle;
        }

        gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(angle * 32768 / Math.PI), 1, 0);

        Results built = build(gui);
        if (!built.IsSuccess()) {
            return built;
        }

        final Gob[] newFrame = {null};
        NUtils.addTask(new NTask() {
            int count = 0;

            @Override
            public boolean check() {
                for (Gob frame : Finder.findGobs(frameAlias)) {
                    if (!existing.contains(frame.id) && frame.rc.dist(pos) < 2 * tilesz.x) {
                        newFrame[0] = frame;
                        return true;
                    }
                }
                return count++ > 50;
            }
        });

        if (newFrame[0] == null) {
            return Results.ERROR("Failed to build " + frameType.menuName + " - check if you have required resources");
        }

        gui.msg(frameType.menuName + " placed");
        return Results.SUCCESS();
    }

    /* Presses Build until the construction window closes, drinking in between. */
    private Results build(NGameUI gui) throws InterruptedException {
        String windowName = frameType.menuName;
        NUtils.addTask(new NTask() {
            int count = 0;

            @Override
            public boolean check() {
                return gui.getWindow(windowName) != null || count++ > 100;
            }
        });
        if (gui.getWindow(windowName) == null) {
            return Results.ERROR("Construction window did not open - the " + windowName + " can't be placed here");
        }

        while (gui.getWindow(windowName) != null) {
            NUtils.startBuild(gui.getWindow(windowName));

            final boolean[] started = {false};
            NUtils.addTask(new NTask() {
                int count = 0;

                @Override
                public boolean check() {
                    started[0] = gui.prog != null;
                    return started[0] || gui.getWindow(windowName) == null || count++ > 100;
                }
            });
            if (!started[0]) {
                if (gui.getWindow(windowName) == null) {
                    break;
                }
                return Results.ERROR("Building the " + windowName + " didn't start - check if you have required resources");
            }

            WaitBuildState wbs = new WaitBuildState();
            NUtils.addTask(wbs);

            if (wbs.getState() == WaitBuildState.State.TIMEFORDRINK) {
                if (!new Drink(0.9, false).run(gui).IsSuccess()) {
                    return Results.ERROR("Cannot drink");
                }
            } else if (wbs.getState() == WaitBuildState.State.DANGER) {
                return Results.ERROR("Low energy");
            } else {
                // Finished; give the window a moment to close
                NUtils.addTask(new NTask() {
                    int count = 0;

                    @Override
                    public boolean check() {
                        return gui.getWindow(windowName) == null || count++ > 100;
                    }
                });
            }
        }
        return Results.SUCCESS();
    }
}
