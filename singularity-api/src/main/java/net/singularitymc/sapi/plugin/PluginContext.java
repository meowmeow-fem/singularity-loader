package net.singularitymc.sapi.plugin;

import java.nio.file.Path;
import net.singularitymc.sapi.ApiContext;

/** The {@link ApiContext} of a plugin, plus what the loader knows about it. */
public interface PluginContext extends ApiContext {
    PluginDescriptor descriptor();

    PluginState state();

    /** The plugin jar (or folder, for development). */
    Path file();

    @Override
    default String id() {
        return descriptor().id();
    }

    @Override
    default boolean isPlugin() {
        return true;
    }
}
