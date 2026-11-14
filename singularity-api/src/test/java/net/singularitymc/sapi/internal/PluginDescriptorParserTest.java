package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.singularitymc.api.version.Version;
import net.singularitymc.sapi.permission.PermissionDefault;
import net.singularitymc.sapi.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

class PluginDescriptorParserTest {

    @Test
    void readsAFullDescriptor() throws Exception {
        PluginDescriptor d = PluginDescriptorParser.parse("""
                id = "exampleplugin"
                name = "Example Plugin"
                version = "1.2.0"
                main = "com.example.ExamplePlugin"
                api = ">=4.0.0"
                description = "Demo"
                authors = ["A", "B"]
                depends = ["economy"]
                softdepends = ["maps"]
                loadbefore = ["late"]

                [commands.hello]
                description = "Says hello"
                usage = "/hello [name]"
                aliases = ["hi", "Hey"]
                permission = "exampleplugin.hello"

                [permissions."exampleplugin.hello"]
                default = "true"
                """);
        assertEquals("exampleplugin", d.id());
        assertEquals(Version.parse("1.2.0"), d.version());
        assertTrue(d.api().matches(Version.parse("4.0.0")));
        assertEquals(List.of("A", "B"), d.authors());
        assertEquals(List.of("economy"), d.depends());
        var hello = d.commands().get("hello");
        assertEquals(List.of("hi", "hey"), hello.aliases());
        assertEquals("exampleplugin.hello", hello.permission());
        assertEquals(PermissionDefault.TRUE, d.permissions().get("exampleplugin.hello").def());
    }

    @Test
    void minimalDescriptorGetsDefaults() throws Exception {
        PluginDescriptor d = PluginDescriptorParser.parse("id = \"tiny\"\nversion = \"1\"\nmain = \"a.B\"\n");
        assertEquals("tiny", d.name());
        assertTrue(d.api().matches(Version.parse("99.0.0")));
        assertTrue(d.commands().isEmpty());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        PluginException e = assertThrows(PluginException.class, () -> PluginDescriptorParser.parse("""
                id = "Bad Id"
                main = "not a class"
                api = "?!"
                depends = "x y"
                colour = "red"

                [commands."two words"]

                [permissions.myplugin.hello]
                default = "true"
                """));
        String all = String.join("\n", e.problems());
        assertTrue(all.contains("'version' is missing"), all);
        assertTrue(all.contains("id 'Bad Id'"), all);
        assertTrue(all.contains("main 'not a class'"), all);
        assertTrue(all.contains("api '?!'"), all);
        assertTrue(all.contains("unknown key 'colour'"), all);
        assertTrue(all.contains("not a valid command name"), all);
        assertTrue(all.contains("nodes with dots need quotes"), all);
        assertTrue(e.problems().size() >= 7, all);
    }

    @Test
    void reservedIdsAndBadToml() {
        assertThrows(PluginException.class, () -> PluginDescriptorParser.parse("id = \"minecraft\"\nversion = \"1\"\nmain = \"a.B\""));
        PluginException e = assertThrows(PluginException.class, () -> PluginDescriptorParser.parse("id = "));
        assertTrue(e.getMessage().contains("not valid TOML"), e.getMessage());
    }
}
