package net.singularitymc.loader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import net.singularitymc.api.Environment;
import net.singularitymc.api.SingularityLoader;
import net.singularitymc.api.mod.ModContainer;
import net.singularitymc.loader.classloading.SingularityClassLoader;
import net.singularitymc.loader.mod.EntrypointManager;
import net.singularitymc.loader.mod.ModContainerImpl;

/** The running loader. Reachable by mods through {@link SingularityLoader#getInstance()}. */
public final class LoaderImpl implements SingularityLoader {
    private static volatile LoaderImpl instance;

    private final Environment environment;
    private final String loaderVersion;
    private final String gameVersion;
    private final Path gameDir;
    private final Path modsDir;
    private final boolean debug;
    private final SingularityClassLoader gameClassLoader;
    private final Map<String, ModContainerImpl> mods = new LinkedHashMap<>();
    private volatile EntrypointManager entrypoints;

    public LoaderImpl(Environment environment, String gameVersion, Path gameDir, Path modsDir, boolean debug,
                      SingularityClassLoader gameClassLoader, List<ModContainerImpl> builtinsAndMods) {
        this.environment = environment;
        this.loaderVersion = readVersion();
        this.gameVersion = gameVersion;
        this.gameDir = gameDir;
        this.modsDir = modsDir;
        this.debug = debug;
        this.gameClassLoader = gameClassLoader;
        for (ModContainerImpl mod : builtinsAndMods) {
            mods.put(mod.getId(), mod);
        }
    }

    /** Version of this loader, read from the bundled {@code loader.properties}. */
    public static String readVersion() {
        Properties props = new Properties();
        try (InputStream in = LoaderImpl.class.getResourceAsStream("loader.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall through to the default
        }
        String v = props.getProperty("version", "0.0.0-dev").trim();
        return v.startsWith("${") ? "0.0.0-dev" : v;
    }

    public static void install(LoaderImpl loader) {
        instance = loader;
    }

    public static LoaderImpl get() {
        LoaderImpl loader = instance;
        if (loader == null) {
            throw new IllegalStateException("Singularity Loader has not been started yet");
        }
        return loader;
    }

    public void setEntrypoints(EntrypointManager entrypoints) {
        this.entrypoints = entrypoints;
    }

    public EntrypointManager getEntrypointManager() {
        return entrypoints;
    }

    public SingularityClassLoader getGameClassLoader() {
        return gameClassLoader;
    }

    @Override public Environment getEnvironment() { return environment; }
    @Override public String getLoaderVersion() { return loaderVersion; }
    @Override public String getGameVersion() { return gameVersion; }
    @Override public Path getGameDirectory() { return gameDir; }
    @Override public Path getConfigDirectory() { return gameDir.resolve("config"); }
    @Override public Path getModsDirectory() { return modsDir; }
    @Override public boolean isDebug() { return debug; }

    @Override
    public boolean isModLoaded(String id) {
        return mods.containsKey(id);
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        return Optional.ofNullable(mods.get(id));
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        return Collections.unmodifiableCollection(mods.values());
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        EntrypointManager manager = entrypoints;
        if (manager == null) {
            throw new IllegalStateException("Entrypoints are not available yet");
        }
        return manager.create(key, type);
    }
}
