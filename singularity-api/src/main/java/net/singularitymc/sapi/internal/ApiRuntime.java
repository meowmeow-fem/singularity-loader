package net.singularitymc.sapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.singularitymc.api.version.Version;
import net.singularitymc.sapi.ApiContext;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.permission.Permissions;
import net.singularitymc.sapi.plugin.PluginManager;
import net.singularitymc.sapi.server.Server;

/** The one instance behind {@link net.singularitymc.sapi.Singularity}. No Minecraft types (see {@link Backend}). */
public final class ApiRuntime {
    private static volatile ApiRuntime instance;
    private static final Version API_VERSION = readVersion();

    private final Backend backend;
    private final Path gameDir;
    private final Logger log = new LogAdapter("Singularity API");
    private final PermissionsImpl permissions = new PermissionsImpl();
    private final CommandRegistryImpl commands = new CommandRegistryImpl();
    private final SchedulerImpl scheduler = new SchedulerImpl();
    private final Map<String, ContextImpl> modContexts = new ConcurrentHashMap<>();
    private final PluginManagerImpl plugins;

    /**
     * @param gameDir      the server folder
     * @param pluginsDir   {@code SingularityPlugins/}
     * @param extraPlugins more plugin jars / folders (development)
     * @param parent       the class loader plugins see first (the game class loader)
     */
    public ApiRuntime(Backend backend, Path gameDir, Path pluginsDir, List<Path> extraPlugins, ClassLoader parent) {
        this.backend = backend;
        this.gameDir = gameDir;
        this.plugins = new PluginManagerImpl(this, pluginsDir, extraPlugins, parent, log);
    }

    public static ApiRuntime get() {
        ApiRuntime r = instance;
        if (r == null) {
            throw new IllegalStateException("The Singularity API is not running: put singularity-api-<version>.jar into "
                    + "SingularityMods (or call it only after the server's mods have started)");
        }
        return r;
    }

    public static boolean isInstalled() {
        return instance != null;
    }

    public static void install(ApiRuntime runtime) {
        instance = runtime;
    }

    public static Version apiVersion() {
        return API_VERSION;
    }

    private static Version readVersion() {
        Properties p = new Properties();
        try (InputStream in = ApiRuntime.class.getResourceAsStream("api.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // default below
        }
        String v = p.getProperty("version", "0.0.0").trim();
        return Version.parseLenient(v.startsWith("${") ? "0.0.0" : v);
    }

    public Server server() {
        return backend.server();
    }

    public boolean isServerRunning() {
        return backend.isServerRunning();
    }

    public Backend backend() {
        return backend;
    }

    public Logger log() {
        return log;
    }

    public PermissionsImpl permissionsImpl() {
        return permissions;
    }

    public Permissions permissions() {
        return permissions;
    }

    public CommandRegistryImpl commands() {
        return commands;
    }

    public SchedulerImpl scheduler() {
        return scheduler;
    }

    public PluginManagerImpl pluginManager() {
        return plugins;
    }

    public PluginManager plugins() {
        return plugins;
    }

    Path pluginDataFolder(String id) {
        return plugins.pluginsFolder().resolve(id);
    }

    /** The context of a mod: data in {@code config/<id>/}, config defaults from {@code <id>.config.toml} in its jar. */
    public ApiContext modContext(String modId) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("mod id is empty");
        }
        return modContexts.computeIfAbsent(modId, id -> new ContextImpl(this, id, new LogAdapter(id),
                gameDir.resolve("config").resolve(id), () -> {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    return (cl != null ? cl : ApiRuntime.class.getClassLoader()).getResourceAsStream(id + ".config.toml");
                }));
    }

    /** Server stop: plugins off, then every mod registration and task undone. */
    public void shutdown() {
        plugins.disableAll();
        modContexts.values().forEach(ContextImpl::close);
        scheduler.shutdown();
    }
}
