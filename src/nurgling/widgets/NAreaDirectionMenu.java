package nurgling.widgets;

import haven.*;
import nurgling.NConfig;
import nurgling.areas.NArea;
import nurgling.areas.PileFillDirection;

import java.awt.Color;

/**
 * Compact cross of arrows for picking a zone's fill direction.
 *
 * The centre cell is {@link PileFillDirection#DEFAULT} - "however this zone has
 * always behaved" - so a user can back out of a choice without having to guess
 * which of the four was the original.
 *
 * The glyph-to-direction mapping is deliberate and not derivable from the names:
 * Haven's world axes are rotated against the screen, so the arrow that reads as
 * "up" corresponds to walking rows in ascending world y. It lives in one place
 * ({@link #directionForGlyph}) so it can be corrected in one edit.
 */
public class NAreaDirectionMenu extends Widget {
    private static final int CELL = UI.scale(28);
    private static final Color BACKGROUND = new Color(35, 45, 45, 230);
    private static final Color SELECTED = new Color(112, 82, 32, 255);
    private static final Color BORDER = new Color(170, 125, 55, 255);

    private final NArea area;
    private UI.Grab mouseGrab;
    private UI.Grab keyGrab;

    public NAreaDirectionMenu(NArea area) {
        super(new Coord(CELL * 3, CELL * 3));
        this.area = area;

        add(new DirectionButton("↑", directionForGlyph("↑")), new Coord(CELL, 0));
        add(new DirectionButton("←", directionForGlyph("←")), new Coord(0, CELL));
        add(new DirectionButton("◦", directionForGlyph("◦")), new Coord(CELL, CELL));
        add(new DirectionButton("→", directionForGlyph("→")), new Coord(CELL * 2, CELL));
        add(new DirectionButton("↓", directionForGlyph("↓")), new Coord(CELL, CELL * 2));
    }

    static PileFillDirection directionForGlyph(String glyph) {
        switch (glyph) {
            case "↑": return PileFillDirection.TOP_TO_BOTTOM;
            case "↓": return PileFillDirection.BOTTOM_TO_TOP;
            case "←": return PileFillDirection.RIGHT_TO_LEFT;
            case "→": return PileFillDirection.LEFT_TO_RIGHT;
            case "◦": return PileFillDirection.DEFAULT;
            default: throw new IllegalArgumentException(glyph);
        }
    }

    static boolean apply(NArea area, PileFillDirection direction) {
        return area.setPileFillDirection(direction);
    }

    @Override
    protected void added() {
        mouseGrab = ui.grabmouse(this);
        keyGrab = ui.grabkeys(this);
    }

    @Override
    public void destroy() {
        if (mouseGrab != null) mouseGrab.remove();
        if (keyGrab != null) keyGrab.remove();
        super.destroy();
    }

    @Override
    public void draw(GOut g) {
        g.chcolor(BACKGROUND);
        g.frect(Coord.z, sz);
        g.chcolor(BORDER);
        g.rect(Coord.z, sz.sub(1, 1));
        g.chcolor();
        super.draw(g);
    }

    @Override
    public boolean mousedown(MouseDownEvent ev) {
        if (!ev.propagate(this)) close();
        return true;
    }

    @Override
    public boolean keydown(KeyDownEvent ev) {
        if (key_esc.match(ev)) {
            close();
            return true;
        }
        return false;
    }

    private void choose(PileFillDirection direction) {
        if (apply(area, direction)) NConfig.needAreasUpdate();
        close();
    }

    private void close() {
        ui.destroy(this);
    }

    private class DirectionButton extends Widget {
        private final PileFillDirection direction;
        private final Text arrow;

        private DirectionButton(String glyph, PileFillDirection direction) {
            super(new Coord(CELL, CELL));
            this.direction = direction;
            this.arrow = Text.render(glyph, Color.WHITE);
        }

        @Override
        public void draw(GOut g) {
            if (area.pileFillDirection == direction) {
                g.chcolor(SELECTED);
                g.frect(Coord.z, sz);
                g.chcolor();
            }
            g.image(arrow.tex(), sz.sub(arrow.sz()).div(2));
            super.draw(g);
        }

        @Override
        public boolean mousedown(MouseDownEvent ev) {
            if (ev.b == 1) choose(direction);
            return true;
        }
    }
}
