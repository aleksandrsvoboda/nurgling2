package nurgling.sessions.commander;

import haven.Coord;
import haven.UI;
import nurgling.NGameUI;
import nurgling.widgets.NDraggableWidget;

/**
 * The Commander screen on a game UI: theater bar at the top, character cards along the bottom, the order panel at the
 * bottom right and the alert feed on the left. Every game UI gets its own (a switch shows the new session's), each
 * piece hides itself outside Commander mode, and each is a draggable HUD widget like the rest, placed here on first use.
 */
public final class CommanderHud {
    private CommanderHud() {}

    public static void install(NGameUI gui) {
        /* Top right, left of the minimap. */
        HudPanel.install(gui, new Roster(), Roster.DRAG_NAME, w ->
            w.target_c = new Coord(gui.sz.x - UI.scale(460) - w.sz.x, UI.scale(10)));
        HudPanel.install(gui, new CommandCard(), CommandCard.DRAG_NAME, w ->
            w.target_c = new Coord(gui.sz.x - CommandCard.W - NDraggableWidget.delta.x - UI.scale(20), gui.sz.y - UI.scale(480)));
        /* Where the character's belts are outside the mode (they are hidden in it). */
        HudPanel.install(gui, new QuickBar(), QuickBar.DRAG_NAME, w ->
            w.target_c = new Coord((gui.sz.x - w.sz.x) / 2, gui.sz.y - w.sz.y - UI.scale(40)));
        HudPanel.install(gui, new AlertFeed(), AlertFeed.DRAG_NAME, w ->
            w.target_c = new Coord(UI.scale(70), UI.scale(280)));
    }
}
