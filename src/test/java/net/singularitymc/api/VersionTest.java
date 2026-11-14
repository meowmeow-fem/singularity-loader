package net.singularitymc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionParseException;
import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void parsesPlainAndSemanticVersions() {
        Version v = Version.parse("1.2.3-beta.1+build.5");
        assertEquals(1, v.getMajor());
        assertEquals(2, v.getMinor());
        assertEquals(3, v.getPatch());
        assertTrue(v.isPreRelease());
        assertEquals("build.5", v.getBuildMetadata());
        assertEquals("1.2.3-beta.1+build.5", v.toString());
        assertEquals(26, Version.parse("26.3").getMajor());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(VersionParseException.class, () -> Version.parse("abc"));
        assertThrows(VersionParseException.class, () -> Version.parse("1..2"));
        assertThrows(VersionParseException.class, () -> Version.parse(""));
        assertThrows(VersionParseException.class, () -> Version.parse("1.2.3-"));
    }

    @Test
    void missingComponentsCountAsZero() {
        assertEquals(Version.parse("1.2"), Version.parse("1.2.0"));
        assertEquals(Version.parse("1.2").hashCode(), Version.parse("1.2.0").hashCode());
        assertTrue(Version.parse("1.2.1").compareTo(Version.parse("1.2")) > 0);
    }

    @Test
    void followsSemverPrecedence() {
        assertTrue(Version.parse("1.0.0-alpha").compareTo(Version.parse("1.0.0")) < 0);
        assertTrue(Version.parse("1.0.0-alpha").compareTo(Version.parse("1.0.0-alpha.1")) < 0);
        assertTrue(Version.parse("1.0.0-alpha.1").compareTo(Version.parse("1.0.0-alpha.beta")) < 0);
        assertTrue(Version.parse("1.0.0-beta.2").compareTo(Version.parse("1.0.0-beta.11")) < 0);
        assertTrue(Version.parse("1.0.0-rc.1").compareTo(Version.parse("1.0.0")) < 0);
        assertTrue(Version.parse("2.0.0").compareTo(Version.parse("10.0.0")) < 0);
    }

    @Test
    void ignoresBuildMetadataWhenComparing() {
        assertEquals(Version.parse("1.0.0+a"), Version.parse("1.0.0+b"));
    }

    @Test
    void lenientParsingNeverThrows() {
        Version snapshot = Version.parseLenient("26w14a");
        assertEquals("26w14a", snapshot.toString());
        assertFalse(snapshot.isPreRelease());
        assertEquals(0, snapshot.getMajor());
        assertEquals(26, Version.parseLenient("26.3").getMajor());
    }
}
