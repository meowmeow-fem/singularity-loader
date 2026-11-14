package net.singularitymc.loader.game;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameCompatTest {
    @Test
    void everyUnobfuscatedReleaseIsSupported() {
        for (String v : new String[] {"26.1", "26.1.1", "26.1.2", "26.2", "26.3"}) {
            assertNull(GameCompat.versionWarning(v), v);
        }
    }

    @Test
    void obfuscatedReleasesAreOlder() {
        String w = GameCompat.versionWarning("1.21.11");
        assertNotNull(w);
        assertTrue(w.contains("older than 26.1"), w);
    }

    @Test
    void newerVersionsAreUntested() {
        String w = GameCompat.versionWarning("26.4");
        assertNotNull(w);
        assertTrue(w.contains("newer than"), w);
    }
}
