package net.singularitymc.sapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * The class loader of one plugin. Parent first: the game class loader (Minecraft, mods, the Singularity API), which
 * itself sends the loader and its API to the loader's class path. Then the plugin's own jar, then the plugins it
 * depends on. Plugin classes are not transformed by Mixin.
 */
public final class PluginClassLoader extends URLClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final String pluginId;
    private final List<PluginClassLoader> dependencies;

    public PluginClassLoader(String pluginId, URL source, ClassLoader parent, List<PluginClassLoader> dependencies) {
        super("plugin-" + pluginId, new URL[] {source}, parent);
        this.pluginId = pluginId;
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return super.loadClass(name, resolve);
        } catch (ClassNotFoundException notHere) {
            for (PluginClassLoader dep : dependencies) {
                try {
                    return dep.loadOwnClass(name);
                } catch (ClassNotFoundException ignored) {
                    // next dependency
                }
            }
            throw notHere;
        }
    }

    /** A class from this plugin's own jar only. */
    Class<?> loadOwnClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            return c != null ? c : findClass(name);
        }
    }

    /** A resource from this plugin's own jar only ({@code config.toml} defaults), or {@code null}. */
    InputStream ownResource(String name) {
        URL url = findResource(name);
        if (url == null) {
            return null;
        }
        try {
            var conn = url.openConnection();
            conn.setUseCaches(false); // the jar may be replaced while the server runs
            return conn.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "PluginClassLoader(" + pluginId + ")";
    }
}
