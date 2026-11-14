package net.singularitymc.loader.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import net.singularitymc.api.mod.ModDependency;
import net.singularitymc.api.mod.ModEnvironment;
import org.junit.jupiter.api.Test;

class ModMetadataParserTest {
    static ModMetadataImpl parse(String json) throws ModMetadataException {
        return ModMetadataParser.parse(new StringReader(json), "test.jar");
    }

    @Test
    void minimalMetadata() throws Exception {
        ModMetadataImpl m = parse("{\"id\":\"mymod\",\"version\":\"1.0.0\"}");
        assertEquals("mymod", m.getId());
        assertEquals("mymod", m.getName());
        assertEquals("", m.getDescription());
        assertEquals(ModEnvironment.UNIVERSAL, m.getEnvironment());
        assertTrue(m.getEntrypoints().isEmpty());
        assertTrue(m.getDependencies().isEmpty());
    }

    @Test
    void fullMetadata() throws Exception {
        ModMetadataImpl m = parse("""
                {
                  "schemaVersion": 1, "id": "fullmod", "version": "2.1.0-beta.1", "name": "Full Mod",
                  "description": "d", "authors": ["Ann", {"name": "Bob", "contact": {}}],
                  "license": ["MIT", "Apache-2.0"], "contact": {"homepage": "https://x"},
                  "icon": "icon.png", "environment": "server",
                  "entrypoints": {"main": ["a.B", "a.C"], "preLaunch": "a.D"},
                  "mixins": ["a.mixins.json", {"config": "b.mixins.json", "environment": "client"}],
                  "depends": {"minecraft": ">=26.1", "other": ["^1.0", "^2.0"]},
                  "breaks": {"bad": "*"},
                  "custom": {"n": 3, "list": [1, "x"], "nested": {"k": true}}
                }""");
        assertEquals("Full Mod", m.getName());
        assertEquals(List.of("Ann", "Bob"), m.getAuthors());
        assertEquals(List.of("MIT", "Apache-2.0"), m.getLicense());
        assertEquals(ModEnvironment.SERVER, m.getEnvironment());
        assertEquals(List.of("a.B", "a.C"), m.getEntrypoints().get("main"));
        assertEquals(List.of("a.D"), m.getEntrypoints().get("preLaunch"));
        assertEquals(2, m.getMixinConfigs().size());
        assertEquals(ModEnvironment.CLIENT, m.getMixinConfigs().get(1).environment());
        assertEquals(3, m.getDependencies().size());
        assertTrue(m.getDependencies().stream().anyMatch(d -> d.kind() == ModDependency.Kind.BREAKS && d.modId().equals("bad")));
        assertEquals(true, ((java.util.Map<?, ?>) m.getCustomValues().get("nested")).get("k"));
        assertEquals("icon.png", m.getIcon().orElseThrow());
    }

    @Test
    void rejectsInvalidInput() {
        assertThrows(ModMetadataException.class, () -> parse("not json"));
        assertThrows(ModMetadataException.class, () -> parse("[]"));
        assertThrows(ModMetadataException.class, () -> parse("{\"version\":\"1.0\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"x\",\"version\":\"1.0\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"Bad_Id\",\"version\":\"1.0\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"minecraft\",\"version\":\"1.0\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"okmod\",\"version\":\"nope\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"okmod\",\"version\":\"1.0\",\"schemaVersion\":2}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"okmod\",\"version\":\"1.0\",\"environment\":\"moon\"}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"okmod\",\"version\":\"1.0\",\"depends\":{\"okmod\":\"*\"}}"));
        assertThrows(ModMetadataException.class, () -> parse("{\"id\":\"okmod\",\"version\":\"1.0\",\"depends\":{\"x\":\">=bad\"}}"));
    }

    @Test
    void errorMessagesNameTheFile() {
        ModMetadataException e = assertThrows(ModMetadataException.class, () -> parse("{\"version\":\"1.0\"}"));
        assertTrue(e.getMessage().contains("test.jar"));
        assertTrue(e.getMessage().contains("\"id\""));
    }
}
