package net.singularitymc.loader.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LaunchOptionsTest {
    @Test
    void passesUnknownArgumentsToTheGame() throws Exception {
        LaunchOptions o = LaunchOptions.parse(new String[] {"nogui", "--port", "25566"});
        assertEquals(List.of("nogui", "--port", "25566"), o.gameArgs);
        assertEquals(null, o.serverJar);
        assertEquals(null, o.gameVersion);
        assertTrue(o.modsDir.endsWith("SingularityMods"));
    }

    @Test
    void parsesLoaderOptions() throws Exception {
        LaunchOptions o = LaunchOptions.parse(new String[] {"--singularity-debug", "--singularity-dump",
                "--singularity-server-jar=my.jar", "--singularity-add-mod", "dev/mod", "nogui"});
        assertTrue(o.debug);
        assertTrue(o.dump);
        assertTrue(o.serverJar.endsWith("my.jar"));
        assertEquals(1, o.extraMods.size());
        assertTrue(o.extraMods.get(0).endsWith("mod"));
        assertEquals(List.of("nogui"), o.gameArgs);
    }

    @Test
    void parsesDownloadOptions() throws Exception {
        LaunchOptions o = LaunchOptions.parse(new String[] {"--singularity-game-version=26.4", "--singularity-offline",
                "--singularity-server-url", "https://example.com/server.jar"});
        assertEquals("26.4", o.gameVersion);
        assertTrue(o.offline);
        assertTrue(!o.setup);
        assertEquals("https://example.com/server.jar", o.serverUrl);
        assertTrue(o.gameArgs.isEmpty());
    }

    @Test
    void parsesSetup() throws Exception {
        assertTrue(LaunchOptions.parse(new String[] {"--singularity-setup"}).setup);
    }

    @Test
    void rejectsUnknownLoaderOptionsAndMissingValues() {
        assertThrows(LaunchException.class, () -> LaunchOptions.parse(new String[] {"--singularity-nope"}));
        assertThrows(LaunchException.class, () -> LaunchOptions.parse(new String[] {"--singularity-server-jar"}));
    }
}
