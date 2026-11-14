package net.singularitymc.servermanager;

/**
 * Remembers when the last ticks started, to tell the tick rate. The plugin API reports one event per tick and not
 * how long the tick worked, so this measures ticks per second and the gaps between ticks, not the work per tick.
 */
final class TickClock {

    /** Five minutes at 20 ticks a second. */
    private static final int SIZE = 6000;

    private final long[] starts = new long[SIZE];
    private long ticks;

    void tick(long nanoTime) {
        starts[(int) (ticks++ % SIZE)] = nanoTime;
    }

    long ticks() {
        return ticks;
    }

    /** Ticks per second over the last {@code seconds} (or over what has been seen so far); at most 20. */
    double tps(int seconds) {
        long have = Math.min(ticks, SIZE);
        if (have < 2) {
            return 20.0;
        }
        long newest = at(0);
        long window = seconds * 1_000_000_000L;
        // walk back until the window is covered
        int back = 1;
        while (back < have - 1 && newest - at(back) < window) {
            back++;
        }
        long span = newest - at(back);
        return span <= 0 ? 20.0 : Math.min(20.0, back * 1e9 / span);
    }

    /** The longest time between two tick starts within the last {@code seconds}, in milliseconds. */
    double worstGapMillis(int seconds) {
        long have = Math.min(ticks, SIZE);
        long newest = at(0);
        long window = seconds * 1_000_000_000L;
        long worst = 0;
        for (int back = 1; back < have && newest - at(back - 1) < window; back++) {
            worst = Math.max(worst, at(back - 1) - at(back));
        }
        return worst / 1e6;
    }

    /** Start of the tick {@code back} ticks ago (0 = the latest). */
    private long at(int back) {
        return starts[(int) ((ticks - 1 - back) % SIZE)];
    }
}
