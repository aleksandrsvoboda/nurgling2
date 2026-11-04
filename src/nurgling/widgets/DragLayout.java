package nurgling.widgets;

import haven.Area;
import haven.Coord;

/**
 * Placement rules for drag-mode widgets, in terms of the box the player actually sees.
 *
 * A drag-mode widget is a frame with its content inset in it. Keeping it on screen acts on the
 * content box, so the content can sit flush against a screen edge; the frame may hang off screen
 * by its inset.
 *
 * Positions are frame origins (what gets saved); {@code box} is the content box in frame coordinates.
 */
public final class DragLayout {
    private DragLayout() {}

    /** The frame position closest to {@code frame} that keeps the whole content box on screen (top-left wins if it can't fit). */
    public static Coord clamp(Coord frame, Area box, Coord screen) {
        int x = Math.max(Math.min(frame.x, screen.x - box.br.x), -box.ul.x);
        int y = Math.max(Math.min(frame.y, screen.y - box.br.y), -box.ul.y);
        return new Coord(x, y);
    }
}
