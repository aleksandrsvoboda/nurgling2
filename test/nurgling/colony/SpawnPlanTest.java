package nurgling.colony;

import haven.Coord2d;
import nurgling.sessions.squad.WorldRef;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SpawnPlanTest {

    private static ColonistReport rep(String name, int lp, Set<String> skills, Map<ColonyItems.Tool, String> tools,
                                      Map<ColonyItems.Material, Integer> mats) {
        Map<String, Integer> buy = new HashMap<>();
        for (Map.Entry<String, Integer> e : SpawnPlan.CARPENTRY_CHAIN.entrySet())
            if (!skills.contains(e.getKey()))
                buy.put(e.getKey(), e.getValue());
        return new ColonistReport("s-" + name, "g|" + name, name, "acc-" + name, null, skills, buy, lp, tools, mats, "g", "World 1", 10, 0);
    }

    private static Set<String> skills(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    private static Map<ColonyItems.Material, Integer> mats(Object... kv) {
        Map<ColonyItems.Material, Integer> m = new EnumMap<>(ColonyItems.Material.class);
        for (int i = 0; i < kv.length; i += 2)
            m.put((ColonyItems.Material) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    private static SpawnPlan.Row row(List<SpawnPlan.Row> rows, String key) {
        for (SpawnPlan.Row r : rows)
            if (r.key.equals(key))
                return r;
        return null;
    }

    private static final Map<ColonyItems.Tool, String> NONE = new EnumMap<>(ColonyItems.Tool.class);

    @Test
    void nobodyHomeIsBlocked() {
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.emptyList(), null, Collections.singleton("alt"), Collections.emptySet());
        assertEquals(1, rows.size());
        assertEquals(SpawnPlan.State.BLOCKED, rows.get(0).state);
    }

    @Test
    void enoughCarriedPlusStoredMeansNoGathering() {
        ColonistReport a = rep("A", 0, skills("crp", "lumber"), NONE, mats(ColonyItems.Material.STONE, 6));
        Map<ColonyItems.Material, Integer> storage = mats(ColonyItems.Material.STONE, 4, ColonyItems.Material.CLAY, 10,
                                                          ColonyItems.Material.BLOCK, 20, ColonyItems.Material.BOARD, 10);
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.singletonList(new SpawnPlan.Member(a, true, false)), storage,
                                                  Collections.singleton("alt"), Collections.emptySet());
        assertEquals(SpawnPlan.State.OK, row(rows, "colony.plan.mat.stone").state);
        assertNull(row(rows, "colony.plan.chip"), "no gathering when carried + stored cover the need");
        assertEquals(SpawnPlan.State.OK, row(rows, "colony.plan.builder").state);
        assertEquals("A", row(rows, "colony.plan.builder").args[0]);
    }

    @Test
    void gathersOnlyWhatIsMissing() {
        ColonistReport a = rep("A", 0, skills("crp"), NONE, mats(ColonyItems.Material.STONE, 3));
        Map<ColonyItems.Material, Integer> storage = mats(ColonyItems.Material.STONE, 3);
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.singletonList(new SpawnPlan.Member(a, true, false)), storage,
                                                  Collections.singleton("alt"), Collections.emptySet());
        assertEquals(4, row(rows, "colony.plan.chip").args[0]);
        assertEquals(10, row(rows, "colony.plan.dig").args[0]);
    }

    @Test
    void learnerClosestToCarpentryIsPicked() {
        /* B needs all 375 and has 100; C knows Foraging and Lumberjacking (200 left) and has 150: gap 50 beats 275. */
        ColonistReport b = rep("B", 100, skills(), NONE, mats());
        ColonistReport c = rep("C", 150, skills("forage", "lumber"), NONE, mats());
        List<SpawnPlan.Member> crew = Arrays.asList(new SpawnPlan.Member(b, true, true), new SpawnPlan.Member(c, true, true));
        List<SpawnPlan.Row> rows = SpawnPlan.plan(crew, null, Collections.singleton("alt"), Collections.emptySet());
        SpawnPlan.Row r = row(rows, "colony.plan.builder.learn");
        assertNotNull(r);
        assertEquals("C", r.args[0]);
        assertEquals(200, r.args[1]);
        assertEquals(50, r.args[2]);
    }

    @Test
    void nobodyMayLearnIsBlocked() {
        ColonistReport b = rep("B", 1000, skills(), NONE, mats());
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.singletonList(new SpawnPlan.Member(b, true, false)), null,
                                                  Collections.singleton("alt"), Collections.emptySet());
        assertEquals(SpawnPlan.State.BLOCKED, row(rows, "colony.plan.nolearner").state);
    }

    @Test
    void boardsWithoutSawPlanBoneSawAndHunt() {
        ColonistReport a = rep("A", 0, skills("crp", "lumber"), NONE, mats());
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.singletonList(new SpawnPlan.Member(a, true, false)), null,
                                                  Collections.singleton("alt"), Collections.emptySet());
        assertNotNull(row(rows, "colony.plan.craft.saw"));
        assertNotNull(row(rows, "colony.plan.ing.bone.hunt"));
        assertNotNull(row(rows, "colony.plan.craft.axe"));
    }

    @Test
    void sawHolderCoversBoards() {
        Map<ColonyItems.Tool, String> saw = new EnumMap<>(ColonyItems.Tool.class);
        saw.put(ColonyItems.Tool.SAW, "Metal Saw");
        ColonistReport a = rep("A", 0, skills("crp", "lumber"), saw, mats());
        List<SpawnPlan.Row> rows = SpawnPlan.plan(Collections.singletonList(new SpawnPlan.Member(a, true, false)), null,
                                                  Collections.singleton("alt"), Collections.emptySet());
        assertNull(row(rows, "colony.plan.craft.saw"));
        SpawnPlan.Row has = row(rows, "colony.plan.tool.has");
        assertNotNull(has);
    }

    @Test
    void accounts() {
        ColonistReport a = rep("A", 0, skills("crp"), NONE, mats());
        List<SpawnPlan.Member> crew = Collections.singletonList(new SpawnPlan.Member(a, true, false));
        assertEquals(SpawnPlan.State.BLOCKED,
                     row(SpawnPlan.plan(crew, null, Collections.emptyList(), Collections.emptySet()), "colony.plan.account.none").state);
        assertEquals(SpawnPlan.State.BLOCKED,
                     row(SpawnPlan.plan(crew, null, Collections.singleton("alt"), Collections.singleton("alt")), "colony.plan.account.online").state);
        assertEquals("alt2", row(SpawnPlan.plan(crew, null, Arrays.asList("alt", "alt2"), Collections.singleton("alt")),
                                 "colony.plan.account").args[0]);
    }

    @Test
    void nextNameCountsUp() {
        assertEquals("Colonist2", ColonyConfig.following("Colonist1"));
        assertEquals("Colonist10", ColonyConfig.following("Colonist9"));
        assertEquals("Bob2", ColonyConfig.following("Bob"));
    }

    @Test
    void geoSameSegmentAndGrid() {
        WorldRef home = new WorldRef(5, new Coord2d(0, 0), 7, new Coord2d(0, 0));
        WorldRef near = new WorldRef(-1, null, 7, new Coord2d(11 * 150, 0));
        WorldRef far = new WorldRef(-1, null, 7, new Coord2d(11 * 250, 0));
        WorldRef other = new WorldRef(-1, null, 8, new Coord2d(0, 0));
        assertEquals(150, ColonyGeo.tiles(home, near), 0.01);
        assertTrue(ColonyGeo.near(home, near, 2));
        assertFalse(ColonyGeo.near(home, far, 2));
        assertEquals(-1, ColonyGeo.tiles(home, other), 0.0);
        WorldRef sameGrid = new WorldRef(5, new Coord2d(33, 44), 9, null);
        assertEquals(5, ColonyGeo.tiles(home, sameGrid), 0.01);
    }
}
