package net.singularitymc.sapi.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.plugin.JavaPlugin;
import net.singularitymc.sapi.plugin.Plugin;
import net.singularitymc.sapi.plugin.PluginDescriptor;
import net.singularitymc.sapi.plugin.PluginInfo;
import net.singularitymc.sapi.plugin.PluginManager;
import net.singularitymc.sapi.plugin.PluginState;

/**
 * Loads the plugins of {@code SingularityPlugins/} and drives their lifecycle: {@link #loadAll} (server starting),
 * {@link #enableAll} (server started), {@link #disableAll} (server stopping). A plugin that fails is skipped; the
 * server keeps running.
 */
public final class PluginManagerImpl implements PluginManager {
    private final ApiRuntime runtime;
    private final Path folder;
    private final List<Path> extra;
    private final ClassLoader parent;
    private final Logger log;
    private final Map<String, Entry> entries = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    private boolean loaded;

    public PluginManagerImpl(ApiRuntime runtime, Path folder, List<Path> extra, ClassLoader parent, Logger log) {
        this.runtime = runtime;
        this.folder = folder;
        this.extra = List.copyOf(extra);
        this.parent = parent;
        this.log = log;
    }

    @Override
    public List<PluginInfo> plugins() {
        synchronized (entries) {
            return List.copyOf(entries.values());
        }
    }

    @Override
    public Optional<PluginInfo> plugin(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public Path pluginsFolder() {
        return folder;
    }

    @Override
    public List<String> problems() {
        synchronized (problems) {
            return List.copyOf(problems);
        }
    }

    /** Finds, checks, orders and loads the plugins ({@code onLoad}). Call once. */
    public synchronized void loadAll() {
        if (loaded) {
            throw new IllegalStateException("plugins are already loaded");
        }
        loaded = true;
        PluginDiscovery.Result found = PluginDiscovery.discover(folder, extra);
        problems.addAll(found.problems());
        PluginResolver.Result resolved = PluginResolver.resolve(found.candidates(), ApiRuntime.apiVersion());
        List<Entry> order = new ArrayList<>();
        for (PluginDiscovery.Candidate c : resolved.order()) {
            Entry e = new Entry(c.descriptor(), c.file());
            entries.put(e.id(), e);
            order.add(e);
        }
        // failed ones get an entry too, so /plugins shows them (a second jar with a taken id only gets a problem line)
        for (Map.Entry<PluginDiscovery.Candidate, String> f : resolved.failed().entrySet()) {
            if (!entries.containsKey(f.getKey().descriptor().id())) {
                Entry e = new Entry(f.getKey().descriptor(), f.getKey().file());
                e.fail(f.getValue());
                entries.put(e.id(), e);
            } else {
                problems.add(f.getKey().file().getFileName() + ": " + f.getValue());
            }
        }
        for (String p : found.problems()) {
            log.error("Plugin not loaded: {}", p);
        }
        for (Map.Entry<PluginDiscovery.Candidate, String> f : resolved.failed().entrySet()) {
            log.error("Plugin {} not loaded: {}", f.getKey().descriptor().id(), f.getValue());
        }
        if (order.isEmpty()) {
            log.info("No plugins loaded (put plugin jars into {})", folder);
            return;
        }
        log.info("Loading {} plugin{}:", order.size(), order.size() == 1 ? "" : "s");
        for (Entry e : order) {
            log.info("  - {}", e.descriptor);
        }
        for (Entry e : order) {
            load(e);
        }
    }

    private void load(Entry e) {
        PluginDescriptor d = e.descriptor;
        for (String dep : d.depends()) {
            Entry de = entries.get(dep);
            if (de == null || de.state == PluginState.FAILED) {
                e.fail("needs plugin '" + dep + "', which could not be loaded");
                log.error("Plugin {} not loaded: {}", d.id(), e.error);
                return;
            }
        }
        List<PluginClassLoader> deps = new ArrayList<>();
        for (String dep : concat(d.depends(), d.softDepends())) {
            Entry de = entries.get(dep);
            if (de != null && de.classLoader != null) {
                deps.add(de.classLoader);
            }
        }
        try {
            e.classLoader = new PluginClassLoader(d.id(), e.file.toUri().toURL(), parent, deps);
        } catch (MalformedURLException ex) {
            e.fail("bad path " + e.file);
            return;
        }
        for (PluginDescriptor.Permission p : d.permissions().values()) {
            runtime.permissions().register(p.node(), p.def(), p.description());
        }
        PluginContextImpl context = new PluginContextImpl(runtime, e, runtime.pluginDataFolder(d.id()), e.classLoader);
        e.context = context;
        for (CommandSpec spec : d.commands().values()) {
            context.commands().register(spec, null);
        }
        Plugin instance;
        try {
            Class<?> main = Class.forName(d.main(), true, e.classLoader);
            if (!JavaPlugin.class.isAssignableFrom(main)) {
                throw new IllegalStateException(d.main() + " does not extend " + JavaPlugin.class.getName());
            }
            Constructor<?> ctor = main.getDeclaredConstructor();
            PluginBootstrap.prepare(context);
            try {
                instance = (Plugin) ctor.newInstance();
            } finally {
                PluginBootstrap.clear();
            }
        } catch (ClassNotFoundException ex) {
            fail(e, "main class " + d.main() + " is not in the jar", null);
            return;
        } catch (NoSuchMethodException ex) {
            fail(e, d.main() + " needs a public constructor without parameters", null);
            return;
        } catch (InvocationTargetException ex) {
            fail(e, "its constructor threw " + ex.getCause(), ex.getCause());
            return;
        } catch (Throwable ex) {
            fail(e, "could not be created: " + ex, ex);
            return;
        }
        e.instance = instance;
        try {
            ((JavaPlugin) instance).onLoad();
        } catch (Throwable ex) {
            fail(e, "onLoad threw " + ex, ex);
            return;
        }
        e.state = PluginState.LOADED;
    }

    /** {@code onEnable} of every loaded plugin, in load order. Call on the server thread once the worlds are loaded. */
    public void enableAll() {
        for (Entry e : snapshot()) {
            if (e.state != PluginState.LOADED) {
                continue;
            }
            String missing = null;
            for (String dep : e.descriptor.depends()) {
                Entry de = entries.get(dep);
                if (de == null || de.state != PluginState.ENABLED) {
                    missing = dep;
                    break;
                }
            }
            if (missing != null) {
                fail(e, "needs plugin '" + missing + "', which could not be enabled", null);
                continue;
            }
            try {
                ((JavaPlugin) e.instance).onEnable();
                e.state = PluginState.ENABLED;
                log.info("Enabled {}", e.descriptor);
            } catch (Throwable ex) {
                fail(e, "onEnable threw " + ex, ex);
            }
        }
    }

    /** {@code onDisable} of every enabled plugin, in reverse order; then everything they registered is removed. */
    public void disableAll() {
        List<Entry> list = snapshot();
        Collections.reverse(list);
        for (Entry e : list) {
            if (e.state == PluginState.ENABLED) {
                try {
                    ((JavaPlugin) e.instance).onDisable();
                } catch (Throwable ex) {
                    log.error("onDisable of " + e.id() + " failed", ex);
                }
                log.info("Disabled {}", e.descriptor);
            }
            if (e.state == PluginState.ENABLED || e.state == PluginState.LOADED) {
                e.state = PluginState.DISABLED;
                if (e.context != null) {
                    e.context.close();
                }
            }
        }
        // the server is stopping: release the jars (on Windows an open jar cannot be replaced or deleted)
        for (Entry e : list) {
            if (e.classLoader != null) {
                try {
                    e.classLoader.close();
                } catch (java.io.IOException ignored) {
                    // nothing left to do at shutdown
                }
            }
        }
    }

    private void fail(Entry e, String why, Throwable t) {
        e.fail(why);
        if (t != null) {
            log.error("Plugin " + e.id() + " failed: " + why, t);
        } else {
            log.error("Plugin {} failed: {}", e.id(), why);
        }
        if (e.context != null) {
            e.context.close();
        }
    }

    private List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries.values());
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** One plugin. */
    static final class Entry implements PluginInfo {
        final PluginDescriptor descriptor;
        final Path file;
        volatile PluginState state = PluginState.DISCOVERED;
        volatile String error;
        volatile Plugin instance;
        PluginClassLoader classLoader;
        PluginContextImpl context;

        Entry(PluginDescriptor descriptor, Path file) {
            this.descriptor = descriptor;
            this.file = file;
        }

        void fail(String why) {
            state = PluginState.FAILED;
            error = why;
        }

        @Override public PluginDescriptor descriptor() { return descriptor; }
        @Override public PluginState state() { return state; }
        @Override public Optional<String> error() { return Optional.ofNullable(error); }
        @Override public Path file() { return file; }
        @Override public Optional<Plugin> instance() { return Optional.ofNullable(instance); }
    }
}
