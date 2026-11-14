package net.singularitymc.sapi.internal;

import net.singularitymc.sapi.plugin.PluginContext;

/** Hands a plugin its context while the loader runs its constructor ({@code JavaPlugin}'s constructor takes it). */
public final class PluginBootstrap {
    private static final ThreadLocal<PluginContext> PENDING = new ThreadLocal<>();

    private PluginBootstrap() {}

    static void prepare(PluginContext context) {
        PENDING.set(context);
    }

    static void clear() {
        PENDING.remove();
    }

    public static PluginContext take(Class<?> pluginClass) {
        PluginContext c = PENDING.get();
        if (c == null) {
            throw new IllegalStateException(pluginClass.getName() + " is a plugin: only the plugin loader creates it (list it as "
                    + "main in plugin.toml and put the jar into SingularityPlugins)");
        }
        PENDING.remove();
        return c;
    }
}
