package nurgling.actions;

import haven.*;
import nurgling.NGameUI;
import nurgling.NGob;
import nurgling.NHitBox;
import nurgling.NUtils;
import nurgling.tasks.NTask;
import nurgling.tasks.WaitBuildState;
import nurgling.tools.Finder;
import nurgling.tools.NAlias;
import nurgling.tools.NParser;
import nurgling.tools.VSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static haven.MCache.tilesz;
import static haven.OCache.posres;

/**
 * Adds a post to a milestone road: right-clicks the road's last milestone, presses Extend,
 * carries the placement to its spot and builds the new milestone there (5 stones). A
 * half-built milestone already standing there is finished instead. After SUCCESS, built()
 * is the new milestone, which is the road's new last post.
 *
 * The road doesn't run between the milestones themselves. The placement carries a
 * routeindicator overlay (loftar's Route sprite) whose line goes from the last milestone
 * plus srcoff to the new one plus dstoff turned by the new one's facing, and the game
 * refuses the post if that line crosses rock. dstoff is (33, 0): the road ends three tiles
 * in front of a milestone (measured 2026-10-21). So the caller names a tile on the open
 * line the road should follow (spot), the way along it and the side to stand on; the
 * milestone stands one tile out from spot and is turned so its road end falls back on the
 * line, behind spot. If the road's line or the milestone's tile is still rock, nothing is
 * placed and rockInTheWay() lists the tiles to dig before trying again.
 *
 * The build loop is copied from FrameTunneler.build(), which is itself a copy of
 * TunnelingBot's; check fixes against all three.
 */
public class ExtendRoad implements Action {

    public static final NAlias MILESTONES = new NAlias("gfx/terobjs/road/milestone");
    public static final int STONES_PER_POST = 5;

    private static final NAlias CONSTRUCTION = new NAlias("gfx/terobjs/consobj");
    private static final NAlias ROCK_TILES = new NAlias("rock", "tiles/cave");
    private static final String ROUTE_INDICATOR = "gfx/terobjs/road/routeindicator";
    private static final long WINDOW_WAIT_MS = 5_000;
    private static final long OVERLAY_WAIT_MS = 3_000;
    // Placing may still walk the last stretch before the construction window opens
    private static final long CONSTRUCTION_WAIT_MS = 15_000;
    private static final long BUILT_WAIT_MS = 5_000;

    private final Gob tail;
    private final Coord2d spot;
    private final Coord2d along;
    private final Coord2d outward;
    private final double fallbackAngle;
    private Gob built = null;
    private List<Coord> rockInTheWay = new ArrayList<>();

    /* spot is on the line the road should follow, along points ahead on it, outward to the
     * side the milestone stands on; fallbackAngle is the facing used if the placement carries
     * no route offsets. */
    public ExtendRoad(Gob tail, Coord2d spot, Coord2d along, Coord2d outward, double fallbackAngle) {
        this.tail = tail;
        this.spot = spot;
        this.along = along.norm();
        this.outward = outward.norm();
        this.fallbackAngle = fallbackAngle;
    }

    public Gob built() {
        return built;
    }

    /* After a failed run: the rock tiles on the road line or under the milestone, nearest
     * the last milestone first; empty if rock wasn't the problem. */
    public List<Coord> rockInTheWay() {
        return rockInTheWay;
    }

    /* Where a milestone stands, how it faces, and where its road then ends. */
    public static final class Siting {
        public final Coord2d pos, attach;
        public final double angle;

        Siting(Coord2d pos, Coord2d attach, Coord2d dstoff) {
            this.pos = pos;
            this.attach = attach;
            this.angle = Math.atan2(attach.y - pos.y, attach.x - pos.x) - Math.atan2(dstoff.y, dstoff.x);
        }
    }

    /* The milestone one tile out from spot, turned so its road end (dstoff turned by the
     * facing) lands on the line through spot, behind it. A road end shorter than a tile
     * lands on spot itself. */
    public static Siting site(Coord2d spot, Coord2d along, Coord2d outward, Coord2d dstoff) {
        double reach = dstoff.abs();
        double out = tilesz.x;
        if (reach <= out) {
            return new Siting(spot.add(outward.norm().mul(reach)), spot, dstoff);
        }
        double back = Math.sqrt(reach * reach - out * out);
        return new Siting(spot.add(outward.norm().mul(out)), spot.sub(along.norm().mul(back)), dstoff);
    }

    /* The tiles the straight line from a to b passes over, from a's end. */
    public static Set<Coord> lineTiles(Coord2d a, Coord2d b) {
        Set<Coord> tiles = new LinkedHashSet<>();
        int steps = Math.max(1, (int) Math.ceil(a.dist(b)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = a.x + (b.x - a.x) * t;
            double y = a.y + (b.y - a.y) * t;
            tiles.add(new Coord((int) Math.floor(x / tilesz.x), (int) Math.floor(y / tilesz.y)));
        }
        return tiles;
    }

    @Override
    public Results run(NGameUI gui) throws InterruptedException {
        NUtils.getDefaultCur();
        // A leftover placement would pass for the one Extend gives
        cancelPlacement(gui);
        rockInTheWay = new ArrayList<>();
        Set<Long> before = milestoneIds();

        Coord2d expected;
        Gob unfinished = Finder.findGob(spot.add(outward.mul(tilesz.x)), CONSTRUCTION, null, 1.5 * tilesz.x);
        Window cons;
        if (unfinished != null) {
            // An earlier run ran out of stones half way; finish that one
            expected = unfinished.rc;
            new PathFinder(unfinished).run(gui);
            Set<Window> open = windows(gui);
            NUtils.getUI().dropLastError();
            NUtils.rclickGob(unfinished);
            WaitNewWindow wait = new WaitNewWindow(gui, open, "Build", WINDOW_WAIT_MS);
            NUtils.addTask(wait);
            if (wait.window == null) {
                return Results.ERROR("Road: the unfinished milestone didn't open" + reason(wait.error));
            }
            cons = wait.window;
        } else {
            int stones = gui.getInventory().getItems(VSpec.getNamesInCategory("Stone")).size();
            if (stones < STONES_PER_POST) {
                return Results.ERROR("Road: need " + STONES_PER_POST + " stones for the next milestone, have " + stones);
            }

            Button extend = openExtend(gui, tail);
            if (extend == null) {
                return Results.ERROR("Road: can't reach the road's last milestone, or it has no Extend button");
            }
            Window info = windowOf(extend);

            NUtils.getUI().dropLastError();
            extend.click();
            WaitPlacing placing = new WaitPlacing(WINDOW_WAIT_MS);
            NUtils.addTask(placing);
            if (!placing.ready) {
                cancelPlacement(gui);
                closeWindow(gui, info);
                return Results.ERROR("Road: pressing Extend gave no milestone to place" + reason(placing.error));
            }

            ReadRouteOffsets offsets = new ReadRouteOffsets(OVERLAY_WAIT_MS);
            NUtils.addTask(offsets);
            Coord2d pos, attach;
            double angle;
            Coord2d from = tail.rc;
            if (offsets.dstoff != null) {
                Siting siting = site(spot, along, outward, offsets.dstoff);
                pos = siting.pos;
                angle = siting.angle;
                attach = siting.attach;
                from = tail.rc.add(offsets.srcoff);
            } else {
                pos = spot.add(outward.mul(tilesz.x));
                angle = fallbackAngle;
                attach = spot;
            }
            expected = pos;
            gui.map.placing.get().a = angle;

            // Dig first rather than have the game refuse the road
            Set<Coord> mustBeOpen = lineTiles(from, attach);
            mustBeOpen.add(pos.floor(tilesz));
            for (Coord tile : mustBeOpen) {
                if (isRock(gui, tile)) {
                    rockInTheWay.add(tile);
                }
            }
            if (!rockInTheWay.isEmpty()) {
                cancelPlacement(gui);
                closeWindow(gui, info);
                return Results.ERROR("Road: " + rockInTheWay.size() + " rock tiles in the way of the road");
            }

            // Carry the placement down the tunnel ourselves; the server only walks straight
            // lines. The milestone window stays open until the placement is down, in case
            // closing it ends the extension.
            NHitBox hitBox = gui.map.placing.get().ngob.hitBox;
            if (hitBox == null) {
                hitBox = new NHitBox(new Coord2d(-5.5, -5.5), new Coord2d(5.5, 5.5));
            }
            PathFinder pf = new PathFinder(NGob.getDummy(pos, angle, hitBox), true);
            pf.isHardMode = true;
            Results walked = pf.run(gui);
            if (!walked.IsSuccess()) {
                cancelPlacement(gui);
                closeWindow(gui, info);
                return Results.ERROR("Road: can't reach the spot for the next milestone");
            }
            if (gui.map.placing == null) {
                closeWindow(gui, info);
                return Results.ERROR("Road: the milestone placement was cancelled on the way");
            }

            Set<Window> open = windows(gui);
            NUtils.getUI().dropLastError();
            gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(angle * 32768 / Math.PI), 1, 0);
            WaitNewWindow wait = new WaitNewWindow(gui, open, "Build", CONSTRUCTION_WAIT_MS);
            NUtils.addTask(wait);
            closeWindow(gui, info);
            if (wait.window == null) {
                cancelPlacement(gui);
                return Results.ERROR("Road: the milestone can't be placed there" + reason(wait.error));
            }
            cons = wait.window;
        }

        Results result = build(gui, cons);
        if (!result.IsSuccess()) {
            return result;
        }

        NUtils.addTask(new NTask() {
            final long deadline = System.currentTimeMillis() + BUILT_WAIT_MS;

            @Override
            public boolean check() {
                for (Gob gob : Finder.findGobs(MILESTONES)) {
                    if (!before.contains(gob.id) && gob.rc.dist(expected) < 1.5 * tilesz.x) {
                        built = gob;
                        return true;
                    }
                }
                return System.currentTimeMillis() > deadline;
            }
        });
        if (built == null) {
            return Results.ERROR("Road: the new milestone didn't appear");
        }
        return Results.SUCCESS();
    }

    /* Walks to a milestone and right-clicks it; its Extend button, or null if it can't be
     * extended (not a trail end, or the trail ends in a sign). The window stays open. */
    public static Button openExtend(NGameUI gui, Gob milestone) throws InterruptedException {
        NUtils.getDefaultCur();
        new PathFinder(milestone).run(gui);
        // A milestone window left open would not count as the new one
        for (Window wnd : windows(gui)) {
            if (button(wnd, "Extend") != null || button(wnd, "Travel") != null) {
                closeWindow(gui, wnd);
            }
        }
        Set<Window> open = windows(gui);
        NUtils.getUI().dropLastError();
        NUtils.rclickGob(milestone);
        WaitNewWindow wait = new WaitNewWindow(gui, open, null, WINDOW_WAIT_MS);
        NUtils.addTask(wait);
        if (wait.window == null) {
            return null;
        }
        // The buttons may arrive a moment after the window
        final Button[] extend = {null};
        NUtils.addTask(new NTask() {
            final long deadline = System.currentTimeMillis() + 1_000;

            @Override
            public boolean check() {
                extend[0] = button(wait.window, "Extend");
                return extend[0] != null || System.currentTimeMillis() > deadline;
            }
        });
        if (extend[0] == null) {
            closeWindow(gui, wait.window);
        }
        return extend[0];
    }

    public static Window windowOf(Widget w) {
        while (w != null && !(w instanceof Window)) {
            w = w.parent;
        }
        return (Window) w;
    }

    public static void closeWindow(NGameUI gui, Window wnd) throws InterruptedException {
        if (wnd != null && gui.isWindowExist(wnd)) {
            new CloseTargetWindow(wnd).run(gui);
        }
    }

    /* Presses Build until the construction window closes, drinking in between. */
    private Results build(NGameUI gui, Window wnd) throws InterruptedException {
        while (gui.isWindowExist(wnd)) {
            NUtils.getUI().dropLastError();
            NUtils.startBuild(wnd);

            final boolean[] started = {false};
            NUtils.addTask(new NTask() {
                int count = 0;

                @Override
                public boolean check() {
                    started[0] = gui.prog != null;
                    return started[0] || !gui.isWindowExist(wnd) || count++ > 100;
                }
            });
            if (!started[0]) {
                if (!gui.isWindowExist(wnd)) {
                    break;
                }
                String err = NUtils.getUI().getLastError();
                closeWindow(gui, wnd);
                return Results.ERROR("Road: building the milestone didn't start" + reason(err)
                        + " - it needs " + STONES_PER_POST + " stones");
            }

            WaitBuildState wbs = new WaitBuildState();
            NUtils.addTask(wbs);

            if (wbs.getState() == WaitBuildState.State.TIMEFORDRINK) {
                if (!new Drink(0.9, false).run(gui).IsSuccess()) {
                    closeWindow(gui, wnd);
                    return Results.ERROR("Cannot drink");
                }
            } else if (wbs.getState() == WaitBuildState.State.DANGER) {
                closeWindow(gui, wnd);
                return Results.ERROR("Low energy");
            } else {
                // Finished; give the window a moment to close
                NUtils.addTask(new NTask() {
                    int count = 0;

                    @Override
                    public boolean check() {
                        return !gui.isWindowExist(wnd) || count++ > 100;
                    }
                });
            }
        }
        return Results.SUCCESS();
    }

    /* Right-click while placing is how the game cancels; drop the cursor here too in case
     * the server never answers. */
    private static void cancelPlacement(NGameUI gui) throws InterruptedException {
        if (gui.map.placing == null) {
            return;
        }
        Gob player = NUtils.player();
        if (player != null) {
            gui.map.wdgmsg("place", player.rc.floor(posres), 0, 3, 0);
            NUtils.addTask(new NTask() {
                final long deadline = System.currentTimeMillis() + 2_000;

                @Override
                public boolean check() {
                    return gui.map.placing == null || System.currentTimeMillis() > deadline;
                }
            });
        }
        if (gui.map.placing != null) {
            gui.map.placing.cancel();
            gui.map.placing = null;
        }
    }

    private static Set<Long> milestoneIds() {
        Set<Long> ids = new HashSet<>();
        for (Gob gob : Finder.findGobs(MILESTONES)) {
            ids.add(gob.id);
        }
        return ids;
    }

    private static Set<Window> windows(NGameUI gui) throws InterruptedException {
        Set<Window> found = new HashSet<>();
        NUtils.addTask(new NTask() {
            @Override
            public boolean check() {
                for (Widget w = gui.lchild; w != null; w = w.prev) {
                    if (w instanceof Window) {
                        found.add((Window) w);
                    }
                }
                return true;
            }
        });
        return found;
    }

    private static Button button(Window wnd, String text) {
        for (Button b : wnd.children(Button.class)) {
            if (b.text != null && text.equals(b.text.text)) {
                return b;
            }
        }
        return null;
    }

    private static String reason(String err) {
        return (err == null) ? "" : ": " + err;
    }

    /* A window that wasn't open before, holding a button with the given text (any window if
     * null); gives up on a server error or at the deadline. */
    private static class WaitNewWindow extends NTask {
        final NGameUI gui;
        final Set<Window> before;
        final String buttonText;
        final long deadline;
        Window window = null;
        String error = null;

        WaitNewWindow(NGameUI gui, Set<Window> before, String buttonText, long timeoutMs) {
            this.gui = gui;
            this.before = before;
            this.buttonText = buttonText;
            this.deadline = System.currentTimeMillis() + timeoutMs;
        }

        @Override
        public boolean check() {
            for (Widget w = gui.lchild; w != null; w = w.prev) {
                if (w instanceof Window && !before.contains(w)
                        && (buttonText == null || button((Window) w, buttonText) != null)) {
                    window = (Window) w;
                    return true;
                }
            }
            error = NUtils.getUI().getLastError();
            return error != null || System.currentTimeMillis() > deadline;
        }
    }

    private static boolean isRock(NGameUI gui, Coord tile) {
        Resource res = gui.ui.sess.glob.map.tilesetr(gui.ui.sess.glob.map.gettile(tile));
        return res != null && NParser.checkName(res.name, ROCK_TILES);
    }

    /* The placement's routeindicator: the road's line runs from gob src plus srcoff to the
     * placement plus dstoff turned by its facing, all in world units. The sprite's class comes
     * from the resource, so its public fields are read by name. */
    public static final class RouteOffsets {
        public final long src;
        public final Coord2d srcoff, dstoff;

        private RouteOffsets(long src, Coord2d srcoff, Coord2d dstoff) {
            this.src = src;
            this.srcoff = srcoff;
            this.dstoff = dstoff;
        }

        /* Null if the placement has no routeindicator (yet), or it can't be read. */
        public static RouteOffsets of(Gob plob) {
            for (Gob.Overlay ol : plob.ols) {
                if (ol.spr != null && ol.spr.res != null && ROUTE_INDICATOR.equals(ol.spr.res.name)) {
                    try {
                        Class<?> cl = ol.spr.getClass();
                        Coord3f so = (Coord3f) cl.getField("srcoff").get(ol.spr);
                        Coord3f dof = (Coord3f) cl.getField("dstoff").get(ol.spr);
                        long src = cl.getField("src").getLong(ol.spr);
                        return new RouteOffsets(src, Coord2d.of(so.x, so.y), Coord2d.of(dof.x, dof.y));
                    } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
                        System.out.println("ExtendRoad: can't read the route offsets: " + e);
                        return null;
                    }
                }
            }
            return null;
        }
    }

    private static class ReadRouteOffsets extends NTask {
        final long deadline;
        Coord2d srcoff = null, dstoff = null;

        ReadRouteOffsets(long timeoutMs) {
            this.deadline = System.currentTimeMillis() + timeoutMs;
        }

        @Override
        public boolean check() {
            MapView map = NUtils.getGameUI().map;
            if (map == null || map.placing == null || !map.placing.ready()) {
                return true;
            }
            RouteOffsets offsets = RouteOffsets.of(map.placing.get());
            if (offsets != null) {
                srcoff = offsets.srcoff;
                dstoff = offsets.dstoff;
                return true;
            }
            return System.currentTimeMillis() > deadline;
        }
    }

    private static class WaitPlacing extends NTask {
        final long deadline;
        boolean ready = false;
        String error = null;

        WaitPlacing(long timeoutMs) {
            this.deadline = System.currentTimeMillis() + timeoutMs;
        }

        @Override
        public boolean check() {
            MapView map = NUtils.getGameUI().map;
            ready = map != null && map.placing != null && map.placing.ready();
            if (ready) {
                return true;
            }
            error = NUtils.getUI().getLastError();
            return error != null || System.currentTimeMillis() > deadline;
        }
    }
}
