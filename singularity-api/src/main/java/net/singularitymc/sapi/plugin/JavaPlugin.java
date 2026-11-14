package net.singularitymc.sapi.plugin;

import java.nio.file.Path;
import java.util.Optional;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.Singularity;
import net.singularitymc.sapi.command.CommandRegistry;
import net.singularitymc.sapi.command.RegisteredCommand;
import net.singularitymc.sapi.config.Config;
import net.singularitymc.sapi.event.Events;
import net.singularitymc.sapi.internal.PluginBootstrap;
import net.singularitymc.sapi.scheduler.Scheduler;
import net.singularitymc.sapi.server.Server;

/**
 * Base class of every plugin: the class named by {@code main} in {@code plugin.toml}. It needs a public constructor
 * without parameters. The loader creates one instance and calls:
 *
 * <ol>
 *   <li>{@link #onLoad()} while the server starts, before the worlds load. No players or worlds yet; read the config,
 *       register listeners and commands here or in {@code onEnable}.</li>
 *   <li>{@link #onEnable()} once the server has started (worlds loaded), on the server thread.</li>
 *   <li>{@link #onDisable()} when the server stops (reverse order). Afterwards its listeners, tasks and commands are
 *       removed automatically.</li>
 * </ol>
 *
 * If {@code onLoad} or {@code onEnable} throws, the plugin is marked failed, its registrations are undone and the
 * server keeps running.
 */
public abstract class JavaPlugin implements Plugin {
    private final PluginContext context;

    protected JavaPlugin() {
        this.context = PluginBootstrap.take(getClass());
    }

    public void onLoad() {}

    public void onEnable() {}

    public void onDisable() {}

    @Override
    public final PluginContext context() {
        return context;
    }

    public final Logger logger() {
        return context.logger();
    }

    public final Events events() {
        return context.events();
    }

    public final CommandRegistry commands() {
        return context.commands();
    }

    /** A command declared in {@code plugin.toml} (or registered earlier), to set its executor. */
    public final RegisteredCommand command(String name) {
        Optional<RegisteredCommand> c = context.commands().get(name);
        return c.orElseThrow(() -> new IllegalArgumentException("Command '" + name + "' is not declared in plugin.toml of "
                + context.id()));
    }

    public final Scheduler scheduler() {
        return context.scheduler();
    }

    public final Config config() {
        return context.config();
    }

    public final Path dataFolder() {
        return context.dataFolder();
    }

    /** Same as {@link Singularity#server()}: only from {@code onEnable} on. */
    public final Server server() {
        return Singularity.server();
    }
}
