package nurgling.leveling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelAnalysisTest {
    private static final double E = 1e-6;

    /** Brute force, as the Land Survey window sums it. */
    private static double dig(float[] h, double t) {
        double s = 0;
        for(float v : h)
            s += Math.max(0, v - t);
        return(s);
    }

    private static double fill(float[] h, double t) {
        double s = 0;
        for(float v : h)
            s += Math.max(0, t - v);
        return(s);
    }

    @Test
    void sumsMatchBruteForceAtAnyHeight() {
        float[] h = {3, 7.5f, 1, 9, 4, 4, 12.25f, 0.5f};
        LevelAnalysis a = new LevelAnalysis(h);
        for(double t = -2; t <= 14; t += 0.37) {
            assertEquals(dig(h, t), a.dig(t), E, "dig at " + t);
            assertEquals(fill(h, t), a.fill(t), E, "fill at " + t);
            assertEquals(fill(h, t) - dig(h, t), a.net(t), E, "net at " + t);
        }
    }

    @Test
    void balancedIsTheMeanAndLeavesNothingOver() {
        float[] h = {1, 2, 3, 10};
        LevelAnalysis a = new LevelAnalysis(h);
        assertEquals(4, a.mean(), E);
        assertEquals(0, a.net(a.mean()), E);
    }

    @Test
    void leastWorkIsTheMedianAndMovesNoMoreThanBalanced() {
        float[] h = {1, 2, 3, 10, 11};
        LevelAnalysis a = new LevelAnalysis(h);
        assertEquals(3, a.median(), E);
        double work = a.dig(a.median()) + a.fill(a.median());
        assertTrue(work <= a.dig(a.mean()) + a.fill(a.mean()) + E);
        for(double t = 0; t <= 12; t += 0.25)
            assertTrue(work <= a.dig(t) + a.fill(t) + E, "median beaten at " + t);
        assertEquals(2.5, new LevelAnalysis(new float[] {1, 2, 3, 10}).median(), E);
    }

    @Test
    void budgetUsesTheStockpileExactly() {
        float[] h = {0, 2, 4, 6, 8, 10};
        LevelAnalysis a = new LevelAnalysis(h);
        double t = a.forNet(6);
        assertEquals(6, a.net(t), 1e-4);
        assertTrue(t > a.mean());
        assertEquals(a.mean(), a.forNet(0), 1e-4);
        /* More than filling everything to the top can take: clamp to the highest ground. */
        assertEquals(10, a.forNet(1000), E);
        assertEquals(0, a.forNet(-1000), E);
    }

    @Test
    void withinCountsCornersAlreadyLevel() {
        LevelAnalysis a = new LevelAnalysis(new float[] {4.9f, 5, 5.2f, 6, 3});
        assertEquals(3, a.within(5, 0.5));
    }

    @Test
    void emptyGroundIsEmpty() {
        LevelAnalysis a = new LevelAnalysis(new float[0]);
        assertTrue(a.empty());
        assertEquals(0, a.count());
    }
}
