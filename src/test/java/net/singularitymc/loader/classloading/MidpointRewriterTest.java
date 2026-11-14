package net.singularitymc.loader.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.Test;

class MidpointRewriterTest {
    /** Compiled by javac into the very instructions the game uses. */
    public static final class Sample {
        public static int half(int a, int b) {
            return (a + b) / 2;
        }

        public static int halfFloor(int a, int b) {
            return (a + b) >> 1;
        }

        public static int other(int a, int b) {
            return (a + b) / 3;
        }
    }

    @Test
    void midpointsNoLongerOverflow() throws Throwable {
        Class<?> rewritten = load(MidpointRewriter.rewrite(bytes()));
        int a = 2_000_000_000, b = 2_000_000_010;
        assertEquals(2_000_000_005, (int) rewritten.getMethod("half", int.class, int.class).invoke(null, a, b));
        assertEquals(2_000_000_005, (int) rewritten.getMethod("halfFloor", int.class, int.class).invoke(null, a, b));
        assertTrue(Sample.half(a, b) < 0, "the original overflows");
    }

    @Test
    void sameResultsWhereNothingOverflows() throws Throwable {
        Class<?> rewritten = load(MidpointRewriter.rewrite(bytes()));
        int[][] cases = {{0, 0}, {1, 2}, {-1, -2}, {-7, 4}, {7, -4}, {-3, 0}, {1_000_000_000, 70}, {-1_000_000_000, -71}};
        for (int[] c : cases) {
            assertEquals(Sample.half(c[0], c[1]), (int) rewritten.getMethod("half", int.class, int.class).invoke(null, c[0], c[1]));
            assertEquals(Sample.halfFloor(c[0], c[1]), (int) rewritten.getMethod("halfFloor", int.class, int.class).invoke(null, c[0], c[1]));
            assertEquals(Sample.other(c[0], c[1]), (int) rewritten.getMethod("other", int.class, int.class).invoke(null, c[0], c[1]));
        }
    }

    @Test
    void classesWithoutMidpointsAreLeftAlone() throws IOException {
        byte[] plain;
        try (InputStream in = MidpointRewriter.class.getResourceAsStream("ClassTransformer.class")) {
            plain = in.readAllBytes();
        }
        assertFalse(MidpointRewriter.mayContainMidpoint(plain));
        assertSame(plain, MidpointRewriter.rewrite(plain));
        assertNotSame(bytes(), MidpointRewriter.rewrite(bytes()));
    }

    private static byte[] bytes() throws IOException {
        try (InputStream in = Sample.class.getResourceAsStream("MidpointRewriterTest$Sample.class")) {
            return in.readAllBytes();
        }
    }

    private static Class<?> load(byte[] b) throws IllegalAccessException {
        // A hidden class in this package keeps the name collision away from the original Sample.
        return MethodHandles.lookup().defineHiddenClass(b, true).lookupClass();
    }
}
