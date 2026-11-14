package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.singularitymc.api.version.Version;
import net.singularitymc.sapi.internal.PluginDiscovery.Candidate;
import org.junit.jupiter.api.Test;

class PluginResolverTest {
    private static final Version API = Version.parse("4.0.0");

    private static Candidate plugin(String id, String extra) throws PluginException {
        return plugin(id, extra, id + ".jar");
    }

    private static Candidate plugin(String id, String extra, String file) throws PluginException {
        return new Candidate(Path.of(file), PluginDescriptorParser.parse(
                "id = \"" + id + "\"\nversion = \"1.0.0\"\nmain = \"x.Main\"\n" + extra));
    }

    private static List<String> ids(PluginResolver.Result r) {
        List<String> out = new ArrayList<>();
        r.order().forEach(c -> out.add(c.descriptor().id()));
        return out;
    }

    @Test
    void dependenciesComeFirst() throws Exception {
        PluginResolver.Result r = PluginResolver.resolve(List.of(
                plugin("shop", "depends = [\"economy\"]\nsoftdepends = [\"maps\", \"missing\"]"),
                plugin("maps", ""),
                plugin("economy", ""),
                plugin("alpha", "loadbefore = [\"economy\"]")), API);
        assertEquals(List.of("alpha", "economy", "maps", "shop"), ids(r));
        assertTrue(r.failed().isEmpty());
    }

    @Test
    void missingDependencyFailsTheWholeChain() throws Exception {
        PluginResolver.Result r = PluginResolver.resolve(List.of(
                plugin("aa", "depends = [\"bb\"]"),
                plugin("bb", "depends = [\"gone\"]"),
                plugin("cc", "")), API);
        assertEquals(List.of("cc"), ids(r));
        assertEquals(2, r.failed().size());
        assertTrue(r.failed().values().stream().anyMatch(s -> s.contains("'gone', which is not installed")));
        assertTrue(r.failed().values().stream().anyMatch(s -> s.contains("'bb', which could not be loaded")));
    }

    @Test
    void apiVersionDuplicatesAndCycles() throws Exception {
        PluginResolver.Result r = PluginResolver.resolve(List.of(
                plugin("future", "api = \">=5.0.0\""),
                plugin("dup", ""),
                plugin("dup", "", "dup-copy.jar"),
                plugin("xx", "depends = [\"yy\"]"),
                plugin("yy", "depends = [\"xx\"]"),
                plugin("ok", "")), API);
        assertEquals(List.of("dup", "ok"), ids(r));
        String why = String.join("\n", r.failed().values());
        assertTrue(why.contains("needs Singularity API >=5.0.0"), why);
        assertTrue(why.contains("also used by"), why);
        assertTrue(why.contains("dependency cycle between xx, yy"), why);
    }
}
