package net.singularitymc.sapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.plugin.PluginInfo;
import net.singularitymc.sapi.plugin.PluginState;
import net.singularitymc.sapi.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginManagerImplTest {
    @TempDir
    Path game;

    static final class FakeBackend implements Backend {
        @Override public Server server() { throw new IllegalStateException("no server in this test"); }
        @Override public boolean isServerRunning() { return false; }
        @Override public boolean isServerThread() { return true; }
    }

    @BeforeEach
    void reset() {
        TestPlugins.CALLS.clear();
    }

    private static void jar(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private static String toml(String id, Class<?> main, String extra) {
        return "id = \"" + id + "\"\nname = \"" + id + "\"\nversion = \"1.0.0\"\nmain = \"" + main.getName() + "\"\napi = \">=4.0.0\"\n" + extra;
    }

    @Test
    void fullLifecycle() throws Exception {
        Path plugins = game.resolve("SingularityPlugins");
        jar(plugins.resolve("good.jar"), Map.of(
                "plugin.toml", toml("good", TestPlugins.Good.class, "[commands.hello]\naliases = [\"hi\"]\n"),
                "config.toml", "greeting = \"from the jar\"\n"));
        jar(plugins.resolve("failing.jar"), Map.of("plugin.toml", toml("failing", TestPlugins.FailsOnEnable.class, "")));
        jar(plugins.resolve("dependent.jar"), Map.of("plugin.toml", toml("dependent", TestPlugins.Dependent.class, "depends = [\"failing\"]\n")));
        jar(plugins.resolve("notaplugin.jar"), Map.of("plugin.toml", toml("notaplugin", TestPlugins.NotAPlugin.class, "")));
        jar(plugins.resolve("mod.jar"), Map.of("singularity.mod.json", "{}"));
        jar(plugins.resolve("future.jar"), Map.of("plugin.toml", toml("future", TestPlugins.Good.class, "").replace(">=4.0.0", ">=9.0.0")));

        ApiRuntime rt = new ApiRuntime(new FakeBackend(), game, plugins, List.of(), getClass().getClassLoader());
        PluginManagerImpl pm = rt.pluginManager();
        pm.loadAll();
        assertThrows(IllegalStateException.class, pm::loadAll);

        assertEquals(PluginState.LOADED, state(pm, "good"));
        assertEquals(PluginState.FAILED, state(pm, "notaplugin"));
        assertTrue(pm.plugin("notaplugin").flatMap(PluginInfo::error).orElse("").contains("does not extend"));
        assertEquals(PluginState.FAILED, state(pm, "future"));
        assertTrue(pm.problems().stream().anyMatch(p -> p.contains("mod.jar") && p.contains("SingularityMods")), pm.problems().toString());
        assertTrue(TestPlugins.CALLS.contains("good.load:from the jar"), TestPlugins.CALLS.toString());
        assertTrue(Files.isRegularFile(plugins.resolve("good").resolve("config.toml")), "config.toml copied into the data folder");

        pm.enableAll();
        assertEquals(PluginState.ENABLED, state(pm, "good"));
        assertEquals(PluginState.FAILED, state(pm, "failing"));
        assertEquals(PluginState.FAILED, state(pm, "dependent"));
        assertTrue(pm.plugin("dependent").flatMap(PluginInfo::error).orElse("").contains("'failing'"));

        // the declared command got its executor; the plugin's timer posts an event its listener sees
        CommandRegistryImpl.Entry hello = rt.commands().entries().stream().filter(e -> e.spec().name().equals("hello")).findFirst().orElseThrow();
        Recorder r = new Recorder();
        assertEquals(1, CommandRegistryImpl.dispatch(hello, r, "hi", "there"));
        assertEquals(List.of("hello there"), r.messages);
        rt.scheduler().tick();
        rt.scheduler().tick();
        assertEquals(2, TestPlugins.CALLS.stream().filter("good.ping:good"::equals).count(), TestPlugins.CALLS.toString());
        assertTrue(TestPlugins.CALLS.stream().noneMatch(c -> c.contains("failing plugin still listening")),
                "a plugin that failed in onEnable has its listeners removed");

        pm.disableAll();
        assertEquals(PluginState.DISABLED, state(pm, "good"));
        assertTrue(TestPlugins.CALLS.contains("good.disable"));
        assertTrue(TestPlugins.CALLS.stream().noneMatch(c -> c.contains("failing plugin was disabled")));
        int pings = (int) TestPlugins.CALLS.stream().filter("good.ping:good"::equals).count();
        rt.scheduler().tick();
        rt.scheduler().tick();
        assertEquals(pings, TestPlugins.CALLS.stream().filter("good.ping:good"::equals).count(), "tasks are cancelled on disable");
        assertEquals(0, CommandRegistryImpl.dispatch(hello, r, "hello", ""));
        assertTrue(BuiltinCommands.list(pm.plugins()).startsWith("Plugins (5): "), BuiltinCommands.list(pm.plugins()));
    }

    @Test
    void developmentFolderAndNoPlugins() throws Exception {
        ApiRuntime empty = new ApiRuntime(new FakeBackend(), game, game.resolve("none"), List.of(), getClass().getClassLoader());
        empty.pluginManager().loadAll();
        assertTrue(empty.pluginManager().plugins().isEmpty());

        Path dev = game.resolve("dev-plugin");
        Files.createDirectories(dev);
        Files.writeString(dev.resolve("plugin.toml"), toml("dev", TestPlugins.Good.class, "[commands.hello]\n"));
        ApiRuntime rt = new ApiRuntime(new FakeBackend(), game, game.resolve("SingularityPlugins"), List.of(dev), getClass().getClassLoader());
        rt.pluginManager().loadAll();
        assertEquals(PluginState.LOADED, state(rt.pluginManager(), "dev"));
        rt.pluginManager().disableAll();
        assertEquals(PluginState.DISABLED, state(rt.pluginManager(), "dev"));
    }

    @Test
    void pluginObjectsCannotBeCreatedByHand() {
        assertThrows(IllegalStateException.class, TestPlugins.Good::new);
    }

    private static PluginState state(PluginManagerImpl pm, String id) {
        return pm.plugin(id).map(PluginInfo::state).orElseThrow(() -> new AssertionError("no plugin " + id + " in " + pm.plugins()));
    }

    static final class Recorder implements CommandSender {
        final java.util.List<String> messages = new java.util.ArrayList<>();
        @Override public String name() { return "Recorder"; }
        @Override public void sendMessage(String message) { messages.add(message); }
        @Override public boolean hasPermission(String node) { return true; }
        @Override public boolean isOp() { return true; }
        @Override public boolean isConsole() { return true; }
    }
}
