package net.singularitymc.loader.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConfigTest {
    @Test
    void parsesLeniently() {
        var m = ServerConfig.parse("[gameplay & permissions]\nmotd = \"Hi # there\" # c\nmax_players = 20\nonline_mode = true\n# x = 1\n");
        assertEquals("Hi # there", m.get("motd"));
        assertEquals("20", m.get("max_players"));
        assertEquals("true", m.get("online_mode"));
        assertFalse(m.containsKey("x"));
    }

    @Test
    void mapsKeysToServerProperties() {
        Properties p = ServerConfig.of("max_players = 5\nrcon_port = 1\nquery_port = 2\n").toProperties();
        assertEquals("5", p.getProperty("max-players"));
        assertEquals("1", p.getProperty("rcon.port"));
        assertEquals("2", p.getProperty("query.port"));
    }

    @Test
    void createsFromTemplateWithGeneratedSecret(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("ServerConfig.toml");
        ServerConfig c = ServerConfig.loadOrCreate(f, null);
        assertTrue(Files.isRegularFile(f));
        assertEquals(40, c.get("management_server_secret", "").length());
        assertEquals(c.get("management_server_secret", ""), ServerConfig.loadOrCreate(f, null).get("management_server_secret", ""));
    }

    @Test
    void importsLegacyServerProperties(@TempDir Path dir) throws IOException {
        Path old = dir.resolve("server.properties");
        Files.writeString(old, "max-players=7\nmotd=Old\nonline-mode=false\n");
        ServerConfig c = ServerConfig.loadOrCreate(dir.resolve("ServerConfig.toml"), old);
        assertEquals("7", c.get("max_players", ""));
        assertEquals("Old", c.get("motd", ""));
        assertEquals("false", c.get("online_mode", ""));
    }

    @Test
    void eulaAnswerIsWrittenIntoTheHeaderedFile(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("ServerEula.txt");
        assertFalse(EulaGate.isAccepted(f));
        EulaGate.write(f, true);
        assertTrue(EulaGate.isAccepted(f));
        assertTrue(Files.readString(f).contains("Mojang"));
        EulaGate.write(f, false);
        assertFalse(EulaGate.isAccepted(f));
    }
}
