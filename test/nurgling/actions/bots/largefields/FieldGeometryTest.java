package nurgling.actions.bots.largefields;

import haven.Area;
import haven.Coord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FieldGeometryTest {

    private static Area a(int x0, int y0, int x1, int y1) {
        return new Area(new Coord(x0, y0), new Coord(x1, y1));
    }

    @Test
    void boundsAcrossFourGrids() {
        // A field from tile (80,90) to (130,150) as NArea.Space would split it.
        Map<Coord, Area> per = new HashMap<>();
        per.put(new Coord(0, 0), a(80, 90, 100, 100));
        per.put(new Coord(1, 0), a(0, 90, 30, 100));
        per.put(new Coord(0, 1), a(80, 0, 100, 50));
        per.put(new Coord(1, 1), a(0, 0, 30, 50));
        Area b = FieldGeometry.bounds(per);
        assertEquals(new Coord(80, 90), b.ul);
        assertEquals(new Coord(130, 150), b.br);
        assertNull(FieldGeometry.bounds(new HashMap<>()));
    }

    @Test
    void blocksCoverEveryTileOnceInSerpentine() {
        Area field = a(5, 7, 5 + 50, 7 + 30);
        List<Area> blocks = FieldGeometry.blocks(field, 24, 24, false, false);
        // 50 wide -> columns 24, 24, 2; 30 tall -> rows 24, 6.
        assertEquals(6, blocks.size());
        Set<Coord> seen = new HashSet<>();
        for (Area b : blocks)
            for (int x = b.ul.x; x < b.br.x; x++)
                for (int y = b.ul.y; y < b.br.y; y++)
                    assertTrue(seen.add(new Coord(x, y)), "tile covered twice");
        assertEquals(50 * 30, seen.size());
        // First column goes down, second goes up: neighbours in order share an edge.
        assertEquals(new Coord(5, 7), blocks.get(0).ul);
        assertEquals(new Coord(5, 31), blocks.get(1).ul);
        assertEquals(new Coord(29, 31), blocks.get(2).ul);
        assertEquals(new Coord(29, 7), blocks.get(3).ul);
    }

    @Test
    void blocksStartFromTheNearCorner() {
        Area field = a(0, 0, 48, 48);
        List<Area> blocks = FieldGeometry.blocks(field, 24, 24, true, true);
        assertEquals(new Coord(24, 24), blocks.get(0).ul);
        assertEquals(new Coord(24, 0), blocks.get(1).ul);
        assertEquals(new Coord(0, 0), blocks.get(2).ul);
        assertEquals(new Coord(0, 24), blocks.get(3).ul);
    }

    @Test
    void stripsAndCellsAreInclusiveAndComplete() {
        Area block = a(10, 20, 17, 25); // 7 x 5, half-open
        List<Area> strips = FieldGeometry.strips(block, 3, false);
        assertEquals(3, strips.size());
        assertEquals(a(10, 20, 12, 24).ul, strips.get(0).ul);
        assertEquals(new Coord(12, 24), strips.get(0).br);
        assertEquals(new Coord(16, 24), strips.get(2).br); // last strip is 1 wide
        assertEquals(new Coord(16, 20), FieldGeometry.strips(block, 3, true).get(0).ul);

        List<Area> cells = FieldGeometry.cells(strips.get(0), 2, false);
        assertEquals(3, cells.size());
        assertEquals(new Coord(12, 21), cells.get(0).br);
        assertEquals(new Coord(10, 24), cells.get(2).ul);
        assertEquals(new Coord(12, 24), cells.get(2).br);
        assertEquals(new Coord(10, 24), FieldGeometry.cells(strips.get(0), 2, true).get(0).ul);
    }

    @Test
    void gridAndLocalOfNegativeTiles() {
        assertEquals(new Coord(-1, 0), FieldGeometry.gridOf(new Coord(-1, 5)));
        assertEquals(new Coord(99, 5), FieldGeometry.localOf(new Coord(-1, 5)));
        assertEquals(new Coord(1, 2), FieldGeometry.gridOf(new Coord(100, 250)));
        assertEquals(new Coord(11, 12), FieldGeometry.centre(a(0, 0, 24, 25)));
    }
}
