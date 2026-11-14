package net.singularitymc.loader.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.singularitymc.api.Environment;
import org.junit.jupiter.api.Test;

class ModResolverTest {
    private static final List<ModContainerImpl> BUILTINS = BuiltinMods.create("0.1.0", Path.of("loader.jar"), "26.3", Path.of("server.jar"));

    private static ModCandidate mod(String file, String json) throws Exception {
        return new ModCandidate(Path.of(file), ModMetadataParser.parse(new StringReader(json), file));
    }

    private static String json(String id, String version, String extra) {
        return "{\"id\":\"" + id + "\",\"version\":\"" + version + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static List<String> ids(List<ModContainerImpl> mods) {
        List<String> out = new ArrayList<>();
        mods.forEach(m -> out.add(m.getId()));
        return out;
    }

    @Test
    void ordersDependenciesFirst() throws Exception {
        var result = ModResolver.resolve(List.of(
                mod("a.jar", json("aaa", "1.0", "\"depends\":{\"zzz\":\"*\"}")),
                mod("z.jar", json("zzz", "1.0", "\"depends\":{\"mmm\":\"*\"}")),
                mod("m.jar", json("mmm", "1.0", ""))), BUILTINS, Environment.SERVER);
        assertEquals(List.of("mmm", "zzz", "aaa"), ids(result));
    }

    @Test
    void independentModsAreOrderedById() throws Exception {
        var result = ModResolver.resolve(List.of(
                mod("b.jar", json("bbb", "1.0", "")), mod("a.jar", json("aaa", "1.0", ""))), BUILTINS, Environment.SERVER);
        assertEquals(List.of("aaa", "bbb"), ids(result));
    }

    @Test
    void dependsOnBuiltins() throws Exception {
        ModResolver.resolve(List.of(mod("a.jar", json("aaa", "1.0",
                "\"depends\":{\"minecraft\":\">=26.1\",\"singularity-loader\":\"^0.1\",\"java\":\">=21\"}"))), BUILTINS, Environment.SERVER);
    }

    @Test
    void reportsEveryProblemAtOnce() throws Exception {
        var e = assertThrows(ModResolutionException.class, () -> ModResolver.resolve(List.of(
                mod("a.jar", json("aaa", "1.0", "\"depends\":{\"missing\":\"*\",\"minecraft\":\">=99\"}")),
                mod("b.jar", json("bbb", "1.0", "\"breaks\":{\"aaa\":\"*\"}"))), BUILTINS, Environment.SERVER));
        assertEquals(3, e.getProblems().size());
        assertTrue(e.getMessage().contains("'missing'"));
        assertTrue(e.getMessage().contains("26.3"));
        assertTrue(e.getMessage().contains("breaks"));
    }

    @Test
    void softRelationsDoNotFail() throws Exception {
        var result = ModResolver.resolve(List.of(mod("a.jar", json("aaa", "1.0",
                "\"recommends\":{\"nothere\":\"*\"},\"suggests\":{\"alsonot\":\"*\"},\"conflicts\":{\"minecraft\":\"*\"}"))), BUILTINS, Environment.SERVER);
        assertEquals(List.of("aaa"), ids(result));
    }

    @Test
    void detectsCycles() throws Exception {
        var e = assertThrows(ModResolutionException.class, () -> ModResolver.resolve(List.of(
                mod("a.jar", json("aaa", "1.0", "\"depends\":{\"bbb\":\"*\"}")),
                mod("b.jar", json("bbb", "1.0", "\"depends\":{\"aaa\":\"*\"}"))), BUILTINS, Environment.SERVER));
        assertTrue(e.getMessage().contains("aaa, bbb"));
    }

    @Test
    void newestDuplicateWins() throws Exception {
        var result = ModResolver.resolve(List.of(
                mod("old.jar", json("dup", "1.0.0", "")), mod("new.jar", json("dup", "1.1.0", ""))), BUILTINS, Environment.SERVER);
        assertEquals(1, result.size());
        assertEquals("new.jar", result.get(0).getOrigin().getFileName().toString());
    }

    @Test
    void skipsModsForOtherEnvironments() throws Exception {
        var result = ModResolver.resolve(List.of(
                mod("c.jar", json("clientonly", "1.0", "\"environment\":\"client\"")),
                mod("s.jar", json("serverside", "1.0", "\"environment\":\"server\""))), BUILTINS, Environment.SERVER);
        assertEquals(List.of("serverside"), ids(result));
    }
}
