package net.singularitymc.loader.util;

/**
 * Adaptive performance (4.2): watches how long ticks take and picks a load level. Level 0 is the server as
 * configured; every level above trades simulated and sent world for tick time (see {@link #simulationDistance},
 * {@link #viewDistance}, {@link #spawnInterval}, {@link #randomTickDivisor}). Pure logic, no Minecraft types: the
 * patches feed it tick times and apply what it decides.
 *
 * <p>It reacts fast and recovers slowly: one window (5 seconds) over the target raises the level, two levels at once
 * if ticks take more than twice the target or the heap is too full; after a step it waits one window for the step to
 * take effect, unless ticks take more than three times the target. The level drops again only after
 * {@link #CALM_WINDOWS} windows in a row well under the target (60 %), and waits longer each time a drop did not hold.
 */
public final class PerfGovernor {
    /** Ticks per decision. */
    public static final int WINDOW = 100;
    /** Calm windows in a row before the level drops (6 x 5 s). */
    public static final int CALM_WINDOWS = 6;
    /** Heap still this full after a collection counts as memory pressure. */
    public static final int HEAP_FULL_PERCENT = 85;

    private final double targetNanos;
    private final int configuredSimulation, configuredView, minSimulation, minView;
    private final int maxLevel;

    private int level;
    private int ticks;
    private long sum;
    private int calm;
    /** After a drop made things worse again, wait longer before the next try. */
    private int calmNeeded = CALM_WINDOWS;
    private boolean dropped;
    private int settle;
    private double lastMean;
    private int windows;
    private long minUsed = Long.MAX_VALUE;
    private int heapPercent;
    private boolean memoryPressure;

    public PerfGovernor(double targetMspt, int configuredSimulation, int configuredView, int minSimulation, int minView) {
        this.targetNanos = targetMspt * 1_000_000.0;
        this.configuredSimulation = configuredSimulation;
        this.configuredView = configuredView;
        this.minSimulation = Math.min(minSimulation, configuredSimulation);
        this.minView = Math.min(minView, configuredView);
        // enough levels to reach both minimums (the view distance starts to shrink two levels after the simulation distance)
        this.maxLevel = Math.max(configuredSimulation - this.minSimulation, configuredView - this.minView + 2) + 2;
    }

    /**
     * Feeds one tick's duration.
     *
     * @return true if the level changed with this tick
     */
    public boolean tick(long nanos) {
        return tick(nanos, 0, 0);
    }

    /**
     * Feeds one tick's duration and the heap in use right now. The lowest heap use seen in a window is about what
     * is left after a garbage collection; if that is {@link #HEAP_FULL_PERCENT} % of the maximum or more, the server
     * holds more world than fits (mostly loaded chunks) and the level rises whatever the tick times are.
     *
     * @return true if the level changed with this tick
     */
    public boolean tick(long nanos, long usedHeap, long maxHeap) {
        sum += nanos;
        if (maxHeap > 0) {
            minUsed = Math.min(minUsed, usedHeap);
        }
        // a window is 100 ticks, or 5 seconds of tick time on a server that is far behind
        if (++ticks < WINDOW && sum < 5_000_000_000L) {
            return false;
        }
        double mean = (double) sum / ticks;
        ticks = 0;
        sum = 0;
        lastMean = mean;
        windows++;
        heapPercent = maxHeap > 0 && minUsed != Long.MAX_VALUE ? (int) (100 * minUsed / maxHeap) : 0;
        minUsed = Long.MAX_VALUE;
        memoryPressure = heapPercent >= HEAP_FULL_PERCENT;
        int before = level;
        if (mean > targetNanos || memoryPressure) {
            if (settle > 0 && mean <= 3 * targetNanos) {
                settle--; // the last step is still taking effect (chunks unload over several seconds)
            } else if (level < maxLevel) {
                level = Math.min(maxLevel, level + (mean > 2 * targetNanos || memoryPressure ? 2 : 1));
                settle = 1;
                if (dropped) {
                    // the last drop did not hold: be more patient before the next one (up to 2 minutes)
                    calmNeeded = Math.min(4 * CALM_WINDOWS, calmNeeded + 2);
                    dropped = false;
                }
            }
            calm = 0;
        } else if (mean < targetNanos * 0.6 && heapPercent < HEAP_FULL_PERCENT - 15 && level > 0) {
            settle = 0;
            if (++calm >= calmNeeded) {
                level--;
                calm = 0;
                dropped = true;
            }
        } else {
            settle = 0;
            calm = 0;
        }
        return level != before;
    }

    /** Windows closed so far. */
    public int windows() {
        return windows;
    }

    /** Lowest heap use of the last window, in percent of the maximum heap (0 if unknown). */
    public int lastHeapPercent() {
        return heapPercent;
    }

    /** True if the last window ended with the heap too full. */
    public boolean memoryPressure() {
        return memoryPressure;
    }

    public int level() {
        return level;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** Mean tick time of the last full window, in milliseconds. */
    public double lastMeanMspt() {
        return lastMean / 1_000_000.0;
    }

    /** One chunk less per level, down to the minimum. */
    public int simulationDistance() {
        return Math.max(minSimulation, configuredSimulation - level);
    }

    /** Starts to shrink at level 3: what players see goes last. */
    public int viewDistance() {
        return Math.max(minView, configuredView - Math.max(0, level - 2));
    }

    /** Natural mob spawning runs every this many ticks: 1, then 2 from level 1, 4 from level 4. */
    public int spawnInterval() {
        return spawnInterval(level);
    }

    static int spawnInterval(int level) {
        return level >= 4 ? 4 : level >= 1 ? 2 : 1;
    }

    /** Random ticks (crop growth, leaf decay, ...) are thinned from level 5: every 2nd chunk tick, then every 3rd. */
    public int randomTickDivisor() {
        return level >= 7 ? 3 : level >= 5 ? 2 : 1;
    }
}
