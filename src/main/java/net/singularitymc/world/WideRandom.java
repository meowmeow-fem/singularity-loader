package net.singularitymc.world;

import net.minecraft.util.RandomSource;

/**
 * experimental_long_positions, 3.5.8: long twins of {@link RandomSource}'s ranged ints, for widened code (see
 * {@code PositionWidening.TWINS}). Generation picks random positions between two coordinates (a tree's box, a
 * feature's spread); the int versions would hand the result back as a narrowed int to be carried, which cannot tell a
 * block from a chunk coordinate where their low 32 bits coincide. The random draw is the same: only the span is an
 * int, and it is small.
 */
public final class WideRandom {
    private WideRandom() {
    }

    /** {@code RandomSource.nextIntBetweenInclusive(min, max)}. */
    public static long nextBetweenInclusive(RandomSource random, long min, long max) {
        return min + random.nextInt((int) (max - min + 1));
    }

    /** {@code RandomSource.nextInt(origin, bound)}. */
    public static long nextBetween(RandomSource random, long origin, long bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound - origin is non positive");
        }
        return origin + random.nextInt((int) (bound - origin));
    }
}
