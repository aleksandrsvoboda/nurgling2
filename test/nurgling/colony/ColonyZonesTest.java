package nurgling.colony;

import haven.Area;
import haven.Coord;
import nurgling.db.dao.StorageItemDao;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Only stock inside a Construction Materials zone of the material's subtype counts. */
class ColonyZonesTest {
    private static final long GRID = 0x5100000001L;

    private static ColonyZones boardsAt(Area a) {
        Map<ColonyItems.Material, Map<Long, List<Area>>> z = new EnumMap<>(ColonyItems.Material.class);
        z.computeIfAbsent(ColonyItems.Material.BOARD, k -> new HashMap<>()).put(GRID, new ArrayList<>(List.of(a)));
        return ColonyZones.of(z);
    }

    /** Spot "(x, y)" in posres units of the tile's middle. */
    private static String at(int tx, int ty) {
        return "(" + (tx * 1024 + 512) + ", " + (ty * 1024 + 512) + ")";
    }

    @Test
    void tileOfStoredSpot() {
        assertEquals(Coord.of(10, 20), ColonyZones.tile(at(10, 20)));
        assertEquals(Coord.of(0, 0), ColonyZones.tile("(1023, 0)"));
        assertNull(ColonyZones.tile("bulk"));
    }

    @Test
    void insideOnlyItsOwnZone() {
        ColonyZones z = boardsAt(new Area(Coord.of(10, 10), Coord.of(15, 15)));
        assertTrue(z.has(ColonyItems.Material.BOARD));
        assertFalse(z.has(ColonyItems.Material.BLOCK));
        assertTrue(z.contains(ColonyItems.Material.BOARD, GRID, at(10, 10)));
        assertTrue(z.contains(ColonyItems.Material.BOARD, GRID, at(14, 14)));
        assertFalse(z.contains(ColonyItems.Material.BOARD, GRID, at(15, 14)), "br is exclusive");
        assertFalse(z.contains(ColonyItems.Material.BOARD, GRID + 1, at(12, 12)), "other grid");
        assertFalse(z.contains(ColonyItems.Material.BLOCK, GRID, at(12, 12)), "a board zone is no block zone");
    }

    @Test
    void countsOnlyZonedRowsAroundHome() {
        ColonyZones z = boardsAt(new Area(Coord.of(10, 10), Coord.of(15, 15)));
        String board = ColonyItems.names(ColonyItems.Material.BOARD).iterator().next();
        List<StorageItemDao.LocatedItem> rows = List.of(
            new StorageItemDao.LocatedItem(board, "a", GRID, at(12, 12)),
            new StorageItemDao.LocatedItem(board, "a", GRID, at(12, 12)),
            new StorageItemDao.LocatedItem(board, "b", GRID, at(40, 40)),     // a cupboard of good boards elsewhere
            new StorageItemDao.LocatedItem(board, "c", GRID + 1, at(12, 12))); // not around home
        Map<ColonyItems.Material, Integer> c = ColonyStorage.count(rows, Set.of(GRID), z);
        assertEquals(2, c.get(ColonyItems.Material.BOARD));
    }

    @Test
    void pileRowsCarryTheGeneralKind() {
        assertTrue(ColonyStorage.counts(ColonyItems.Material.BOARD, "Board"));
        assertTrue(ColonyStorage.counts(ColonyItems.Material.BLOCK, "Block of Wood"));
        assertTrue(ColonyStorage.counts(ColonyItems.Material.STONE, "Stone"));
        assertFalse(ColonyStorage.counts(ColonyItems.Material.STONE, "Stone Axe"));
        assertFalse(ColonyStorage.counts(ColonyItems.Material.BOARD, "Stone"));
    }
}
