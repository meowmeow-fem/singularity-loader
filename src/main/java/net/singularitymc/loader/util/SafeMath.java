package net.singularitymc.loader.util;

/**
 * Overflow-free versions of the game's int midpoints, called by code {@code MidpointRewriter} rewrote
 * ({@code full_int_range}). Whenever {@code a + b} fits an int, the result is exactly the original's.
 */
public final class SafeMath {
    private SafeMath() {}

    /** {@code (a + b) / 2}, rounding towards zero like {@code idiv}. */
    public static int half(int a, int b) {
        return (int) (((long) a + b) / 2);
    }

    /** {@code (a + b) >> 1}, rounding down like {@code ishr}. */
    public static int halfFloor(int a, int b) {
        return (int) (((long) a + b) >> 1);
    }
}
