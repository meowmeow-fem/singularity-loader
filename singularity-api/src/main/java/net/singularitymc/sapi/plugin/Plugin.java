package net.singularitymc.sapi.plugin;

/**
 * A loaded plugin. Plugins extend {@link JavaPlugin}; this interface is how other code sees them
 * ({@code Singularity.plugins().plugin("id").flatMap(PluginInfo::instance)}).
 */
public interface Plugin {
    PluginContext context();

    default PluginDescriptor descriptor() {
        return context().descriptor();
    }
}
