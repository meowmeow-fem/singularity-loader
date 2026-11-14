package net.singularitymc.sapi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TomlTest {

    @Test
    void readsValuesTablesAndComments() {
        Map<String, Object> d = Toml.parse("""
                # comment
                id = "x"   # trailing comment
                count = 1_000
                hex = 0xff
                ratio = 2.5
                big = 35184372088832
                on = true
                list = ["a", 'b', ]
                nested = [[1, 2], [3]]
                inline = { a = 1, b = "two" }
                dotted.key = "v"

                [commands.hello]
                aliases = ["hi"]

                [permissions."a.b.c"]
                default = "op"
                """);
        assertEquals("x", d.get("id"));
        assertEquals(1000L, d.get("count"));
        assertEquals(255L, d.get("hex"));
        assertEquals(2.5, d.get("ratio"));
        assertEquals(35184372088832L, d.get("big"));
        assertEquals(true, d.get("on"));
        assertEquals(List.of("a", "b"), d.get("list"));
        assertEquals(List.of(List.of(1L, 2L), List.of(3L)), d.get("nested"));
        assertEquals(Map.of("a", 1L, "b", "two"), d.get("inline"));
        assertEquals(Map.of("key", "v"), d.get("dotted"));
        assertEquals(Map.of("hello", Map.of("aliases", List.of("hi"))), d.get("commands"));
        assertEquals(Map.of("a.b.c", Map.of("default", "op")), d.get("permissions"));
    }

    @Test
    void readsStrings() {
        Map<String, Object> d = Toml.parse("""
                a = "tab\\there \\"q\\" \\u00e9"
                b = 'C:\\path\\no\\escapes'
                c = \"\"\"
                line one
                line two\"\"\"
                e = '''
                raw \\n'''
                """);
        assertEquals("tab\there \"q\" \u00e9", d.get("a"));
        assertEquals("C:\\path\\no\\escapes", d.get("b"));
        assertEquals("line one\nline two", d.get("c"));
        assertEquals("raw \\n", d.get("e"));
    }

    @Test
    void arraysOfTables() {
        Map<String, Object> d = Toml.parse("""
                [[kit]]
                name = "a"
                [[kit]]
                name = "b"
                """);
        assertEquals(List.of(Map.of("name", "a"), Map.of("name", "b")), d.get("kit"));
    }

    @Test
    void errorsNameTheLine() {
        TomlException e = assertThrows(TomlException.class, () -> Toml.parse("a = 1\nb = \n"));
        assertEquals(2, e.line());
        assertThrows(TomlException.class, () -> Toml.parse("a = 1\na = 2"));
        assertThrows(TomlException.class, () -> Toml.parse("a = unquoted"));
        assertThrows(TomlException.class, () -> Toml.parse("a = \"open"));
        assertThrows(TomlException.class, () -> Toml.parse("[t]\nx=1\n[t]\ny=2"));
        assertThrows(TomlException.class, () -> Toml.parse("a = 1 b = 2"));
    }

    @Test
    void writeThenReadGivesTheSameDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "Say \"hi\"\n");
        doc.put("x", 35184372088832L);
        doc.put("f", 1.0);
        doc.put("list", List.of("a", "b"));
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("on", true);
        table.put("a.b", "dotted key");
        doc.put("section", table);
        doc.put("rows", List.of(Map.of("n", 1L), Map.of("n", 2L)));
        String text = Toml.write(doc);
        assertTrue(text.contains("[section]"), text);
        assertEquals(doc, Toml.parse(text));
    }
}
