package net.singularitymc.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionParseException;
import net.singularitymc.api.version.VersionRange;
import org.junit.jupiter.api.Test;

class VersionRangeTest {
    private static boolean matches(String range, String version) {
        return VersionRange.parse(range).matches(Version.parse(version));
    }

    @Test
    void anyMatchesEverything() {
        assertTrue(matches("*", "0.0.1"));
        assertTrue(matches("", "99.0"));
        assertTrue(matches("x", "1.0"));
    }

    @Test
    void comparisonOperators() {
        assertTrue(matches(">=1.2.3", "1.2.3"));
        assertTrue(matches(">=1.2.3", "2.0"));
        assertFalse(matches(">=1.2.3", "1.2.2"));
        assertTrue(matches(">1.2.3", "1.2.4"));
        assertFalse(matches(">1.2.3", "1.2.3"));
        assertTrue(matches("<=2", "2.0.0"));
        assertFalse(matches("<2", "2.0.0"));
        assertTrue(matches("<2", "1.99.99"));
    }

    @Test
    void exactVersion() {
        assertTrue(matches("1.2.3", "1.2.3"));
        assertTrue(matches("=1.2", "1.2.0"));
        assertFalse(matches("1.2.3", "1.2.4"));
    }

    @Test
    void caretRanges() {
        assertTrue(matches("^1.2.3", "1.9.0"));
        assertFalse(matches("^1.2.3", "2.0.0"));
        assertFalse(matches("^1.2.3", "1.2.2"));
        assertTrue(matches("^0.2.3", "0.2.9"));
        assertFalse(matches("^0.2.3", "0.3.0"));
        assertTrue(matches("^0.0.3", "0.0.3"));
        assertFalse(matches("^0.0.3", "0.0.4"));
    }

    @Test
    void tildeRanges() {
        assertTrue(matches("~1.2.3", "1.2.9"));
        assertFalse(matches("~1.2.3", "1.3.0"));
        assertTrue(matches("~1", "1.9.9"));
        assertFalse(matches("~1", "2.0.0"));
    }

    @Test
    void wildcards() {
        assertTrue(matches("1.x", "1.9.9"));
        assertFalse(matches("1.x", "2.0.0"));
        assertTrue(matches("1.2.*", "1.2.7"));
        assertFalse(matches("1.2.*", "1.3.0"));
    }

    @Test
    void andAndOr() {
        assertTrue(matches(">=1.2 <2.0", "1.5"));
        assertFalse(matches(">=1.2 <2.0", "2.0"));
        assertTrue(matches(">= 1.2 < 2.0", "1.5"));
        assertTrue(matches("^1.0 || ^3.0", "3.4"));
        assertFalse(matches("^1.0 || ^3.0", "2.5"));
    }

    @Test
    void preReleasesSortBelowTheirRelease() {
        assertFalse(matches(">=1.0.0", "1.0.0-beta"));
        assertTrue(matches("<1.0.0", "1.0.0-beta"));
    }

    @Test
    void rejectsMalformedRanges() {
        assertThrows(VersionParseException.class, () -> VersionRange.parse(">=abc"));
        assertThrows(VersionParseException.class, () -> VersionRange.parse(">1.x"));
        assertThrows(VersionParseException.class, () -> VersionRange.parse("1.0 !! 2.0"));
    }
}
