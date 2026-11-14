package net.singularitymc.sapi.internal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.plugin.JavaPlugin;

/** Plugin classes for {@link PluginManagerImplTest}; they record their lifecycle calls. */
public final class TestPlugins {
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    private TestPlugins() {}

    /** A plugin event, posted by {@link Good} in its timer. */
    public record Ping(String from) {}

    public static final class Good extends JavaPlugin {
        @Override
        public void onLoad() {
            CALLS.add(context().id() + ".load:" + config().getString("greeting", "none"));
        }

        @Override
        public void onEnable() {
            CALLS.add(context().id() + ".enable");
            command("hello").setExecutor((sender, label, args) -> {
                sender.sendMessage("hello " + String.join(" ", args));
                return true;
            });
            commands().register(CommandSpec.of("extra"), (s, l, a) -> true);
            events().listen(Ping.class, p -> CALLS.add(context().id() + ".ping:" + p.from()));
            scheduler().runTimer(1, 1, () -> events().post(new Ping(context().id())));
        }

        @Override
        public void onDisable() {
            CALLS.add(context().id() + ".disable");
        }
    }

    public static final class FailsOnEnable extends JavaPlugin {
        @Override
        public void onEnable() {
            events().listen(Ping.class, p -> CALLS.add("failing plugin still listening"));
            throw new IllegalStateException("broken on purpose (expected in this test)");
        }

        @Override
        public void onDisable() {
            CALLS.add("failing plugin was disabled");
        }
    }

    /** Depends on the failing one. */
    public static final class Dependent extends JavaPlugin {
        @Override
        public void onEnable() {
            CALLS.add("dependent.enable");
        }
    }

    /** Not a JavaPlugin. */
    public static final class NotAPlugin {}
}
