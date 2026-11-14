package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigImplTest {
    @TempDir
    Path dir;

    @Test
    void defaultsAreCopiedThenReadAndSaved() throws Exception {
        Path file = dir.resolve("p").resolve("config.toml");
        String defaults = "# keep me\ngreeting = \"Hi\"\nlimit = 5\n[messages]\njoin = \"Welcome\"\nworlds = [\"a\", \"b\"]\n";
        ConfigImpl c = new ConfigImpl(file, () -> new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)), new LogAdapter("t"));
        assertEquals("Hi", c.getString("greeting", "x"));
        assertTrue(Files.readString(file).contains("# keep me"), "the defaults are copied as they are, comments included");
        assertEquals(5, c.getInt("limit", 0));
        assertEquals(5.0, c.getDouble("limit", 0));
        assertEquals("5", c.getString("limit", null));
        assertEquals("Welcome", c.getString("messages.join", null));
        assertEquals(List.of("a", "b"), c.getStringList("messages.worlds"));
        assertEquals(Set.of("join", "worlds"), c.keys("messages"));
        assertEquals(7, c.getInt("missing", 7));
        assertFalse(c.getBoolean("greeting", false));

        c.set("messages.leave", "Bye");
        c.set("far", 35_184_372_088_832L);
        c.addDefault("greeting", "ignored");
        c.addDefault("new.flag", true);
        c.set("limit", null);
        c.save();

        ConfigImpl again = new ConfigImpl(file, null, new LogAdapter("t"));
        assertEquals("Bye", again.getString("messages.leave", null));
        assertEquals(35_184_372_088_832L, again.getLong("far", 0));
        assertEquals("Hi", again.getString("greeting", null));
        assertTrue(again.getBoolean("new.flag", false));
        assertFalse(again.contains("limit"));
    }

    @Test
    void aBrokenFileIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, "this is = not toml =");
        ConfigImpl c = new ConfigImpl(file, null, new LogAdapter("t"));
        assertEquals("fallback", c.getString("x", "fallback"));
        c.set("x", "y");
        c.save();
        assertEquals("this is = not toml =", Files.readString(file));
    }
}
