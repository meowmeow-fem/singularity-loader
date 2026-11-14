package net.singularitymc.sapi.internal;

import java.nio.file.Path;
import net.singularitymc.sapi.plugin.PluginContext;
import net.singularitymc.sapi.plugin.PluginDescriptor;
import net.singularitymc.sapi.plugin.PluginState;

final class PluginContextImpl extends ContextImpl implements PluginContext {
    private final PluginManagerImpl.Entry entry;

    PluginContextImpl(ApiRuntime runtime, PluginManagerImpl.Entry entry, Path dataFolder, PluginClassLoader loader) {
        super(runtime, entry.descriptor().id(), new LogAdapter(entry.descriptor().name()), dataFolder,
                () -> loader.ownResource("config.toml"));
        this.entry = entry;
    }

    @Override
    public PluginDescriptor descriptor() {
        return entry.descriptor();
    }

    @Override
    public PluginState state() {
        return entry.state();
    }

    @Override
    public Path file() {
        return entry.file();
    }

    @Override
    public String id() {
        return entry.descriptor().id();
    }

    @Override
    public boolean isPlugin() {
        return true;
    }
}
