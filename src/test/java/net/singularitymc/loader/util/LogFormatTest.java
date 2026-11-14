package net.singularitymc.loader.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogFormatTest {
    @Test
    void substitutesPlaceholdersInOrder() {
        assertEquals("a 1 b two", Log.format("a {} b {}", 1, "two"));
    }

    @Test
    void leavesExtraPlaceholdersAndArgumentsAlone() {
        assertEquals("only {}", Log.format("only {}"));
        assertEquals("one 1", Log.format("one {}", 1, 2));
    }

    @Test
    void appendsUnusedThrowableAsStackTrace() {
        String s = Log.format("failed: {}", "x", new IllegalStateException("boom"));
        assertTrue(s.startsWith("failed: x"));
        assertTrue(s.contains("IllegalStateException: boom"));
    }

    @Test
    void throwableWithPlaceholderIsPrintedInline() {
        assertEquals("failed: java.lang.RuntimeException: bad", Log.format("failed: {}", new RuntimeException("bad")));
    }
}
