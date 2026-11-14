package net.singularitymc.sapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import net.singularitymc.sapi.ApiContext;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.command.CommandRegistry;
import net.singularitymc.sapi.config.Config;
import net.singularitymc.sapi.event.Events;
import net.singularitymc.sapi.scheduler.Scheduler;

/** The context of a mod; {@link PluginContextImpl} extends it for plugins. */
public class ContextImpl implements ApiContext {
    private final String id;
    private final Logger logger;
    private final EventsImpl events;
    private final CommandRegistry commands;
    private final Scheduler scheduler;
    private final Path dataFolder;
    private final Supplier<InputStream> configDefaults;
    private final ApiRuntime runtime;
    private volatile Config config;

    public ContextImpl(ApiRuntime runtime, String id, Logger logger, Path dataFolder, Supplier<InputStream> configDefaults) {
        this.runtime = runtime;
        this.id = id;
        this.logger = logger;
        this.events = new EventsImpl(id, logger);
        this.commands = runtime.commands().forOwner(id, logger);
        this.scheduler = runtime.scheduler().forOwner(id, logger);
        this.dataFolder = dataFolder;
        this.configDefaults = configDefaults;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isPlugin() {
        return false;
    }

    @Override public Logger logger() { return logger; }
    @Override public Events events() { return events; }
    @Override public CommandRegistry commands() { return commands; }
    @Override public Scheduler scheduler() { return scheduler; }

    @Override
    public Path dataFolder() {
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create " + dataFolder, e);
        }
        return dataFolder;
    }

    @Override
    public Config config() {
        Config c = config;
        if (c == null) {
            synchronized (this) {
                c = config;
                if (c == null) {
                    c = new ConfigImpl(dataFolder.resolve("config.toml"), configDefaults, logger);
                    config = c;
                }
            }
        }
        return c;
    }

    /** Undoes everything the owner registered: listeners, tasks, commands. */
    public void close() {
        events.close();
        runtime.scheduler().cancelOwner(id);
        runtime.commands().deactivateOwner(id);
    }

    public EventsImpl eventsImpl() {
        return events;
    }
}
