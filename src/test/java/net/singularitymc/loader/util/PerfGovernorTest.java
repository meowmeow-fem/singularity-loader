package net.singularitymc.loader.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerfGovernorTest {
    private static final long MS = 1_000_000L;
    private static final long GB = 1L << 30;

    /** Feeds ticks until one window closes. */
    private static void window(PerfGovernor g, double mspt) {
        window(g, mspt, 0, 0);
    }

    private static void window(PerfGovernor g, double mspt, long usedHeap, long maxHeap) {
        int before = g.windows();
        while (g.windows() == before) {
            g.tick((long) (mspt * MS), usedHeap, maxHeap);
        }
    }

    @Test
    void staysAtLevelZeroWhenTicksAreFast() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        for (int i = 0; i < 50; i++) window(g, 20);
        assertEquals(0, g.level());
        assertEquals(10, g.simulationDistance());
        assertEquals(10, g.viewDistance());
        assertEquals(1, g.spawnInterval());
        assertEquals(1, g.randomTickDivisor());
    }

    @Test
    void risesOneLevelWhenSlowThenWaitsOneWindow() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        window(g, 60);
        assertEquals(1, g.level());
        assertEquals(9, g.simulationDistance());
        assertEquals(10, g.viewDistance(), "the view distance goes last");
        assertEquals(2, g.spawnInterval());
        window(g, 60);
        assertEquals(1, g.level(), "the step just taken gets one window to take effect");
        window(g, 60);
        assertEquals(2, g.level());
    }

    @Test
    void risesTwoLevelsWhenVerySlowAndDoesNotWaitWhenFarBehind() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        window(g, 100);
        assertEquals(2, g.level());
        window(g, 200); // more than three times the target: no waiting
        assertEquals(4, g.level());
        assertEquals(6, g.simulationDistance());
        assertEquals(8, g.viewDistance());
    }

    @Test
    void aWindowEndsAfterFiveSecondsOfTickTimeOnALaggingServer() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        int ticks = 0;
        while (g.windows() == 0) {
            g.tick(500 * MS);
            ticks++;
        }
        assertEquals(10, ticks);
    }

    @Test
    void neverGoesBelowTheMinimumsOrPastTheLastLevel() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        for (int i = 0; i < 100; i++) window(g, 500);
        assertEquals(g.maxLevel(), g.level());
        assertEquals(3, g.simulationDistance());
        assertEquals(4, g.viewDistance());
        assertEquals(4, g.spawnInterval());
        assertEquals(3, g.randomTickDivisor());
    }

    @Test
    void minimumsAboveTheConfiguredDistancesAreIgnored() {
        PerfGovernor g = new PerfGovernor(45, 2, 3, 3, 4);
        for (int i = 0; i < 100; i++) window(g, 500);
        assertEquals(2, g.simulationDistance());
        assertEquals(3, g.viewDistance());
    }

    @Test
    void recoversSlowlyAndOnlyWhenClearlyUnderTheTarget() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        window(g, 100);
        assertEquals(2, g.level());
        for (int i = 0; i < 20; i++) window(g, 40); // under the target, but not by much: stay
        assertEquals(2, g.level());
        int windows = 0;
        while (g.level() == 2 && windows < 100) {
            window(g, 10);
            windows++;
        }
        assertEquals(1, g.level());
        assertEquals(PerfGovernor.CALM_WINDOWS, windows);
    }

    @Test
    void aDropThatDoesNotHoldMakesTheNextOneWaitLonger() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        window(g, 60);
        int first = 0;
        while (g.level() == 1) {
            window(g, 10);
            first++;
        }
        window(g, 60); // back up
        assertEquals(1, g.level());
        int second = 0;
        while (g.level() == 1) {
            window(g, 10);
            second++;
        }
        assertTrue(second > first, first + " then " + second);
    }

    @Test
    void aFullHeapRaisesTheLevelEvenWhenTicksAreFast() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        window(g, 10, (long) (1.8 * GB), 2 * GB); // 90 % still in use at the best moment of the window
        assertEquals(2, g.level());
        assertTrue(g.memoryPressure());
        assertEquals(89, g.lastHeapPercent()); // 1.8 GB of 2 GB, rounded down
        for (int i = 0; i < 30; i++) window(g, 10, (long) (1.6 * GB), 2 * GB); // 80 %: no longer too full, not roomy either
        assertEquals(2, g.level(), "does not drop while the heap is nearly full");
        for (int i = 0; i < PerfGovernor.CALM_WINDOWS; i++) window(g, 10, GB, 2 * GB);
        assertEquals(1, g.level());
    }

    @Test
    void heapUseIsTheLowestSeenInTheWindow() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        int before = g.windows();
        long[] used = {(long) (1.9 * GB), GB / 2, (long) (1.9 * GB)}; // garbage piles up between collections
        for (int i = 0; g.windows() == before; i++) {
            g.tick(10 * MS, used[i % 3], 2 * GB);
        }
        assertEquals(25, g.lastHeapPercent());
        assertFalse(g.memoryPressure());
        assertEquals(0, g.level());
    }

    @Test
    void reportsALevelChangeOnlyOnTheTickItHappens() {
        PerfGovernor g = new PerfGovernor(45, 10, 10, 3, 4);
        for (int i = 0; i < PerfGovernor.WINDOW - 1; i++) assertFalse(g.tick(40 * MS));
        assertFalse(g.tick(40 * MS));
        for (int i = 0; i < 83; i++) assertFalse(g.tick(60 * MS)); // 84 ticks of 60 ms reach 5 seconds
        assertTrue(g.tick(60 * MS));
        assertFalse(g.tick(60 * MS));
    }
}
