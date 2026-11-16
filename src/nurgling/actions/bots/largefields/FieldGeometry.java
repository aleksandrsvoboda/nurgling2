package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Field geometry for the large-field farmers, free of any game state so it can be tested.
 *
 * Coordinates are "field tiles": tile (0,0) is the top-left tile of the top-left map grid the
 * field touches. A map grid is {@link #GRID} tiles square. Every {@link Area} here is
 * half-open (br exclusive), like the areas NArea stores per grid.
 */
public final class FieldGeometry {
    public static final int GRID = 100;

    private FieldGeometry() {}

    /**
     * Bounds of a field, given the grid-local rectangle it covers in each of its grids, keyed by
     * the grid's offset from the field's top-left grid. Null when there are no rectangles.
     */
    public static Area bounds(Map<Coord, Area> perGrid) {
        Coord ul = null, br = null;
        for (Map.Entry<Coord, Area> e : perGrid.entrySet()) {
            Coord off = e.getKey().mul(GRID);
            Coord a = e.getValue().ul.add(off);
            Coord b = e.getValue().br.add(off);
            ul = (ul == null) ? a : new Coord(Math.min(ul.x, a.x), Math.min(ul.y, a.y));
            br = (br == null) ? b : new Coord(Math.max(br.x, b.x), Math.max(br.y, b.y));
        }
        return (ul == null) ? null : new Area(ul, br);
    }

    /**
     * Cuts the field into blocks of at most bw x bh tiles and orders them as a serpentine walk:
     * column by column, the first column walked from the start side, each next one walked back.
     *
     * @param fromRight start with the rightmost column (the player is nearer the right edge)
     * @param fromBottom walk the first column bottom-up (the player is nearer the bottom edge)
     */
    public static List<Area> blocks(Area field, int bw, int bh, boolean fromRight, boolean fromBottom) {
        if (bw <= 0 || bh <= 0)
            throw new IllegalArgumentException("block size must be positive");
        List<Area> cols = new ArrayList<>();
        for (int x = field.ul.x; x < field.br.x; x += bw)
            cols.add(new Area(new Coord(x, field.ul.y), new Coord(Math.min(x + bw, field.br.x), field.br.y)));
        if (fromRight)
            java.util.Collections.reverse(cols);

        List<Area> res = new ArrayList<>();
        boolean up = fromBottom;
        for (Area col : cols) {
            List<Area> cells = new ArrayList<>();
            for (int y = col.ul.y; y < col.br.y; y += bh)
                cells.add(new Area(new Coord(col.ul.x, y), new Coord(col.br.x, Math.min(y + bh, col.br.y))));
            if (up)
                java.util.Collections.reverse(cells);
            res.addAll(cells);
            up = !up;
        }
        return res;
    }

    /** Centre tile of a half-open area. */
    public static Coord centre(Area a) {
        return new Coord((a.ul.x + a.br.x - 1) / 2, (a.ul.y + a.br.y - 1) / 2);
    }

    /** The grid offset (in grids) holding a field tile. */
    public static Coord gridOf(Coord fieldTile) {
        return new Coord(Math.floorDiv(fieldTile.x, GRID), Math.floorDiv(fieldTile.y, GRID));
    }

    /** Grid-local tile of a field tile. */
    public static Coord localOf(Coord fieldTile) {
        return new Coord(Math.floorMod(fieldTile.x, GRID), Math.floorMod(fieldTile.y, GRID));
    }

    /**
     * Strips of the given width across a half-open block, as inclusive tile rectangles, walked
     * left to right or right to left. Used for the 3-wide harvest and 2-wide seed passes.
     */
    public static List<Area> strips(Area block, int width, boolean fromRight) {
        List<Area> res = new ArrayList<>();
        for (int x = block.ul.x; x < block.br.x; x += width)
            res.add(new Area(new Coord(x, block.ul.y), new Coord(Math.min(x + width, block.br.x) - 1, block.br.y - 1)));
        if (fromRight)
            java.util.Collections.reverse(res);
        return res;
    }

    /**
     * Cells of an inclusive strip, h tiles tall, as inclusive rectangles, walked top-down or
     * bottom-up.
     */
    public static List<Area> cells(Area strip, int h, boolean up) {
        List<Area> res = new ArrayList<>();
        for (int y = strip.ul.y; y <= strip.br.y; y += h)
            res.add(new Area(new Coord(strip.ul.x, y), new Coord(strip.br.x, Math.min(y + h - 1, strip.br.y))));
        if (up)
            java.util.Collections.reverse(res);
        return res;
    }
}
