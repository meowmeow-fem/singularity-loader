package net.singularitymc.sapi.plugin;

import java.nio.file.Path;
import java.util.Optional;

/** What the loader knows about one plugin. */
public interface PluginInfo {
    PluginDescriptor descriptor();

    PluginState state();

    /** Why it failed, if it did. */
    Optional<String> error();

    Path file();

    /** The plugin object, once it has been created. */
    Optional<Plugin> instance();

    default String id() {
        return descriptor().id();
    }
}
