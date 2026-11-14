package net.singularitymc.sapi.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The plugins of the server, in load order. */
public interface PluginManager {
    List<PluginInfo> plugins();

    Optional<PluginInfo> plugin(String id);

    default boolean isEnabled(String id) {
        return plugin(id).map(p -> p.state() == PluginState.ENABLED).orElse(false);
    }

    /** {@code SingularityPlugins/}. */
    Path pluginsFolder();

    /** Problems found while reading plugins that never got a {@link PluginInfo} (unreadable jars, bad descriptors). */
    List<String> problems();
}
