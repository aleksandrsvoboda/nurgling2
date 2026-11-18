package nurgling.sessions.commander;

import haven.Widget;
import nurgling.NGameUI;
import nurgling.sessions.SessionManager;
import nurgling.sessions.squad.SquadHelpWindow;
import nurgling.widgets.NDraggableWidget;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Commander mode as a screen of its own, not a character's. While it is on (and this game UI is the one on screen),
 * everything that belongs to the character - belts, portrait, meters, action menus, chat, its windows - is hidden and
 * its hotkeys are off; left are the world, the minimap, the map window, the session tabs and the Commander panels.
 * Leaving the mode puts back exactly what was showing. Windows that open while the mode is on (a container a squad
 * member opened, a menu) stay: they are what the player is acting on.
 *
 * <p>Ticked last in the game UI's tick, so widgets that show themselves every tick are hidden again before anything
 * is drawn.
 */
public final class CommanderScreen {
    private final NGameUI gui;
    private final Set<Widget> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    /* Where the mode's widgets were before the Commander layout moved them, and the layout version applied. */
    private final java.util.Map<NDraggableWidget, CommanderLayout.Pos> own = new IdentityHashMap<>();
    private int layoutVersion = -1;
    private boolean on;

    public CommanderScreen(NGameUI gui) {
        this.gui = gui;
    }

    public boolean on() {
        return on;
    }

    /** The widgets hidden for the mode: their hotkeys are off too. */
    public Set<Widget> hidden() {
        return hidden;
    }

    /**
     * This game UI is about to come on screen (promoted in a switch): in Commander mode, hide the character's widgets
     * now. The switch can run before the session manager calls it active, so {@link #tick} could not tell yet.
     */
    public void enter() {
        if (!on && SessionManager.getInstance().squad().commander()) {
            on = true;
            hideAll();
        }
    }

    public void tick() {
        SessionManager sm = SessionManager.getInstance();
        /* Every session's character widgets stay hidden while the mode is on, also in the background: a session
         * switched away from is drawn one more frame, and putting its widgets back then flashes them. */
        boolean want = sm.squad().commander();
        if (want != on) {
            on = want;
            if (want)
                hideAll();
            else
                restore();
        } else if (on) {
            for (Widget w : hidden)
                if (w.visible)
                    w.hide();
            if (layoutVersion != CommanderLayout.version())
                applyLayout();
        }
    }

    private void hideAll() {
        hidden.clear();
        for (Widget w = gui.child; w != null; w = w.next) {
            if (keep(w) || !w.visible)
                continue;
            hidden.add(w);
            w.hide();
        }
        panels(true);
        applyLayout();
        if (gui.map != null)
            gui.setfocus(gui.map);
    }

    /**
     * The Commander panels' own wrappers: shown only in the mode. Hidden outside it they stay out of the normal drag
     * mode too (an empty frame there otherwise, saved into the character's layout when moved).
     */
    void panels(boolean show) {
        for (Widget w = gui.child; w != null; w = w.next) {
            if (w instanceof NDraggableWidget && ((NDraggableWidget) w).content instanceof HudPanel && w.visible != show) {
                if (show)
                    w.show();
                else
                    w.hide();
            }
        }
    }

    private void applyLayout() {
        layoutVersion = CommanderLayout.version();
        CommanderLayout.apply(gui, own, this::keep);
    }

    /**
     * Show the character's inventory on the Commander screen (from a character card). Its window is the character's
     * own, so it stays hidden otherwise; this one is let through until the mode ends.
     */
    public void showInventory() {
        haven.Window w = gui.getWindow("Inventory");
        if (w == null && gui.maininv != null)
            w = gui.maininv.getparent(haven.Window.class);
        if (w == null)
            return;
        /* The window can sit in a HUD wrapper: let the gui's own child through, and show the window in it. */
        Widget top = w;
        while (top.parent != null && top.parent != gui)
            top = top.parent;
        if (top.parent != gui)
            return;
        hidden.remove(top);
        if (!top.visible)
            top.show();
        if (!w.visible)
            w.show();
        top.raise();
        if (top == w)
            gui.fitwdg(w);
    }

    private void restore() {
        panels(false);
        CommanderLayout.restore(own);
        for (Widget w : hidden)
            if (w.parent != null)
                w.show();
        hidden.clear();
    }

    private boolean keep(Widget w) {
        /* The world, the map window, and the tools the commander needs: options (and drag mode with its window to end
         * it), the base planner. */
        if (w == gui.map || w == gui.mapfile || w == gui.opts || w == gui.guiinfo || w == gui.basePlanner)
            return true;
        if (w instanceof SquadHelpWindow)
            return true;
        if (w instanceof NDraggableWidget) {
            Widget c = ((NDraggableWidget) w).content;
            /* The Commander panels, and the action UI the commander uses on the selection: the regular bot menu and
             * the action menu (they act on the selected characters in this mode, and feed the quick bar). */
            if (c instanceof HudPanel || c instanceof nurgling.widgets.NBotsMenu || c instanceof nurgling.widgets.NMenuGridWdg
                || c instanceof haven.GameUI.MainMenu)
                return true;
        }
        /* The minimap's resizable frame. */
        if (gui.mmapw != null && gui.mmapw.parent == w)
            return true;
        /* A window that is not the character's own: nothing to tell them apart by but the mode starting with the
         * character's windows open, so all windows open now go, and new ones stay. */
        return false;
    }
}
