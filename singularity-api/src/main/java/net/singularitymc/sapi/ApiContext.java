package net.singularitymc.sapi;

import java.nio.file.Path;
import net.singularitymc.sapi.command.CommandRegistry;
import net.singularitymc.sapi.config.Config;
import net.singularitymc.sapi.event.Events;
import net.singularitymc.sapi.scheduler.Scheduler;
import net.singularitymc.sapi.server.Server;

/**
 * Everything one mod or plugin registers goes through its context, so it can be undone: when a plugin is disabled its
 * listeners are removed, its tasks cancelled and its commands hidden.
 */
public interface ApiContext {
    /** The mod or plugin id. */
    String id();

    /** {@code true} for a plugin, {@code false} for a mod. */
    boolean isPlugin();

    Logger logger();

    Events events();

    CommandRegistry commands();

    Scheduler scheduler();

    /** The data folder: {@code SingularityPlugins/<id>/} for a plugin, {@code config/<id>/} for a mod. Created on first use. */
    Path dataFolder();

    /** {@code config.toml} in the data folder, loaded on first use (see {@link Config}). */
    Config config();

    /** Same as {@link Singularity#server()}. */
    default Server server() {
        return Singularity.server();
    }
}
