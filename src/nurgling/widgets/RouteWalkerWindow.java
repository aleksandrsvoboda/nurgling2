package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.NGameUI;
import nurgling.NStyle;
import nurgling.NUtils;
import nurgling.actions.FollowRoute;
import nurgling.actions.RouteWalkControl;
import nurgling.i18n.L10n;
import nurgling.routes.ForagerPath;
import nurgling.routes.ForagerRouteStore;
import nurgling.routes.ForagerWaypoint;
import nurgling.sessions.BotExecutor;
import nurgling.widgets.bots.PathRecordable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a saved route without foraging: pick one of the Forager routes, see it drawn on the maps, and
 * walk it with {@link FollowRoute}. Opened from the route button under the minimap.
 *
 * Implementing {@link PathRecordable} is what puts the selected route on the minimap and the map window -
 * those renderers look for the first such window and draw whatever getCurrentLoadedPath() returns. This
 * window never records, so isRecording() is always false and map clicks keep walking the character.
 */
public class RouteWalkerWindow extends Window implements PathRecordable {

    private static final int W = UI.scale(216);
    private static final int GAP = UI.scale(5);
    private static final int ITEM_H = UI.scale(16);
    private static final int ROWS = 8;

    private final List<String> routes = new ArrayList<>();
    private final Listbox<String> list;
    private final Label detail;
    private final Label note;
    private final Label status;
    private final Button followBtn;
    private final Button pauseBtn;
    private final Button stopBtn;

    /** The selected route, loaded - what the map renderers draw and what Follow walks. */
    private ForagerPath loaded = null;
    /** Non-null only while a walk is running (or just finished and not yet cleaned up). */
    private RouteWalkControl control = null;

    public RouteWalkerWindow() {
        super(new Coord(W, UI.scale(220)), L10n.get("routewalker.title"));

        refreshRoutes();

        Widget prev = list = add(new RouteList(), Coord.z);
        prev = detail = add(new Label(L10n.get("routewalker.no_selection")), prev.pos("bl").add(0, GAP));
        prev = note = add(new Label(""), prev.pos("bl").add(0, UI.scale(2)));

        followBtn = add(new Button(W, L10n.get("routewalker.follow")) {
            @Override
            public void click() {
                super.click();
                startWalk();
            }
        }, prev.pos("bl").add(0, GAP));

        int halfW = (W - GAP) / 2;
        pauseBtn = add(new Button(halfW, L10n.get("routewalker.pause")) {
            @Override
            public void click() {
                super.click();
                togglePause();
            }
        }, followBtn.pos("bl").add(0, GAP));
        stopBtn = add(new Button(halfW, L10n.get("routewalker.stop")) {
            @Override
            public void click() {
                super.click();
                stopWalk();
            }
        }, followBtn.pos("bl").add(halfW + GAP, GAP));

        status = add(new Label(L10n.get("routewalker.idle")), pauseBtn.pos("bl").add(0, GAP));

        String last = (String) NConfig.get(NConfig.Key.routeWalkerLast);
        if (last != null && routes.contains(last)) {
            list.change(last);
        }
        updateButtons();
        pack();
    }

    /* ------------------------------------------------------------------ *
     *  Route list
     * ------------------------------------------------------------------ */

    private void refreshRoutes() {
        routes.clear();
        routes.addAll(ForagerRouteStore.listRouteNames());
    }

    private class RouteList extends Listbox<String> {
        RouteList() {
            // Listbox hangs its scrollbar off its right edge, outside its own size - leave room for it
            // inside the window, or a route list long enough to scroll draws its bar past the frame.
            super(W - UI.scale(15), ROWS, ITEM_H);
        }

        @Override
        protected int listitems() {
            return routes.size();
        }

        @Override
        protected String listitem(int i) {
            return routes.get(i);
        }

        @Override
        protected void drawbg(GOut g) {
            g.chcolor(NStyle.rowEven);
            g.frect(Coord.z, sz);
            g.chcolor();
        }

        @Override
        protected void drawsel(GOut g) {
            g.chcolor(NStyle.border.getRed(), NStyle.border.getGreen(), NStyle.border.getBlue(), 56);
            g.frect(Coord.z, g.sz());
            g.chcolor();
        }

        @Override
        protected void drawitem(GOut g, String item, int i) {
            if (i % 2 == 1) {
                g.chcolor(NStyle.rowOdd);
                g.frect(Coord.z, g.sz());
                g.chcolor();
            }
            g.text(item, new Coord(UI.scale(4), UI.scale(1)));
        }

        @Override
        public void change(String item) {
            super.change(item);
            select(item);
        }
    }

    /** Loads the picked route so the maps draw it, and describes it under the list. */
    private void select(String name) {
        if (name == null) {
            loaded = null;
            detail.settext(L10n.get("routewalker.no_selection"));
            note.settext("");
            updateButtons();
            return;
        }
        loaded = ForagerRouteStore.load(name);
        NConfig.set(NConfig.Key.routeWalkerLast, name);
        NConfig.needUpdate();
        detail.settext(describe(loaded));
        note.settext((loaded != null && !loaded.waypoints.isEmpty() && !onThisSegment(loaded))
                ? L10n.get("routewalker.elsewhere") : "");
        updateButtons();
    }

    /** "17 waypoints, 2 milestone legs", plus a warning when none of it resolves here. */
    private String describe(ForagerPath path) {
        if (path == null || path.waypoints.isEmpty()) {
            return L10n.get("routewalker.empty_route");
        }
        int milestoneLegs = 0;
        for (int i = 1; i < path.waypoints.size(); i++) {
            ForagerWaypoint from = path.waypoints.get(i - 1);
            ForagerWaypoint to = path.waypoints.get(i);
            if (from.milestoneHash != null && from.milestoneHash.equals(to.milestoneHash)) {
                milestoneLegs++;
            }
        }
        StringBuilder sb = new StringBuilder(L10n.get("routewalker.waypoints", path.waypoints.size()));
        if (milestoneLegs > 0) {
            sb.append(L10n.get("routewalker.milestone_legs", milestoneLegs));
        }
        return sb.toString();
    }

    /** Whether any waypoint resolves against the live session - routes recorded elsewhere draw nothing. */
    private boolean onThisSegment(ForagerPath path) {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null || gui.mmap == null || gui.mmap.sessloc == null) {
            return false;
        }
        for (ForagerWaypoint wp : path.waypoints) {
            if (wp.seg == gui.mmap.sessloc.seg.id) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ *
     *  Walking
     * ------------------------------------------------------------------ */

    private void startWalk() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null) {
            return;
        }
        if (walking()) {
            return;
        }
        if (loaded == null || loaded.waypoints.size() < 2) {
            gui.error(L10n.get("routewalker.need_two"));
            return;
        }
        if (gui.activeBotPath != null) {
            gui.error(L10n.get("routewalker.busy"));
            return;
        }
        RouteWalkControl ctl = new RouteWalkControl();
        Thread t = BotExecutor.runAsync("Route Walker", new FollowRoute(loaded, ctl));
        if (t == null) {
            return;
        }
        ctl.thread(t);
        control = ctl;
        updateButtons();
    }

    private void togglePause() {
        if (!walking()) {
            return;
        }
        control.setPaused(!control.isPaused());
        updateButtons();
    }

    private void stopWalk() {
        if (control == null) {
            return;
        }
        Thread t = control.thread();
        NGameUI gui = NUtils.getGameUI();
        // Same call the Bot Status strip's own X makes: interrupt the thread and drop its bar.
        if (t != null && gui != null && gui.biw != null) {
            gui.biw.removeObserve(t);
        } else if (t != null) {
            t.interrupt();
        }
        // A paused walk is parked in a task wait; the interrupt above wakes it, clearing the flag keeps it clean.
        control.setPaused(false);
        control = null;
        updateButtons();
    }

    private boolean walking() {
        return control != null && control.running();
    }

    private void updateButtons() {
        boolean running = walking();
        followBtn.disable(running || loaded == null);
        pauseBtn.disable(!running);
        stopBtn.disable(!running);
        pauseBtn.change(L10n.get(running && control.isPaused() ? "routewalker.resume" : "routewalker.pause"));
    }

    @Override
    public void tick(double dt) {
        super.tick(dt);
        if (control != null && !control.running()) {
            // Finished on its own, or was stopped from the Bot Status strip / panic key.
            control = null;
            updateButtons();
            status.settext(L10n.get("routewalker.idle"));
            return;
        }
        status.settext(statusText());
    }

    private String statusText() {
        if (control == null) {
            return L10n.get("routewalker.idle");
        }
        if (control.total() > 0 && control.waypoint() > 0) {
            return L10n.get(control.isPaused() ? "routewalker.status_paused_at" : "routewalker.status_at",
                    control.status(), control.waypoint(), control.total());
        }
        return control.status();
    }

    /* ------------------------------------------------------------------ *
     *  PathRecordable - drawing only, never recording
     * ------------------------------------------------------------------ */

    @Override
    public boolean isRecording() {
        return false;
    }

    @Override
    public void addWaypointToRecording(ForagerWaypoint wp) {
        /* Routes are edited in Forager Settings; this window only picks and walks them. */
    }

    @Override
    public ForagerPath getCurrentLoadedPath() {
        return loaded;
    }

    /* ------------------------------------------------------------------ *
     *  Window plumbing
     * ------------------------------------------------------------------ */

    @Override
    public void show() {
        // Routes may have been added or deleted in Forager Settings since this window was last open.
        refreshRoutes();
        if (loaded != null && !routes.contains(loaded.name)) {
            loaded = null;
            list.sel = null;
            detail.settext(L10n.get("routewalker.no_selection"));
            note.settext("");
            updateButtons();
        }
        super.show();
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        // Closing the window doesn't stop a running walk - the Bot Status strip owns it from there.
        if (msg.equals("close")) {
            hide();
        } else {
            super.wdgmsg(sender, msg, args);
        }
    }

    /** Toggle the window, creating it on first use. */
    public static void toggle() {
        NGameUI gui = NUtils.getGameUI();
        if (gui == null) {
            return;
        }
        if (gui.routeWalkerWindow != null) {
            if (gui.routeWalkerWindow.visible()) {
                gui.routeWalkerWindow.hide();
            } else {
                gui.routeWalkerWindow.show();
                gui.routeWalkerWindow.raise();
            }
        } else {
            gui.routeWalkerWindow = new RouteWalkerWindow();
            gui.add(gui.routeWalkerWindow, new Coord(150, 150));
            gui.routeWalkerWindow.show();
        }
    }
}
