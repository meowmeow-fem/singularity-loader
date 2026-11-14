package net.singularitymc.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.singularitymc.api.mod.ModContainer;
import net.singularitymc.loader.LoaderImpl;

/**
 * Entry point to the loader's public API.
 *
 * <pre>{@code
 * if (SingularityLoader.getInstance().isModLoaded("examplemod")) { ... }
 * }</pre>
 */
public interface SingularityLoader {

    /**
     * Returns the running loader.
     *
     * @throws IllegalStateException if called before the loader has started (for example from a unit test)
     */
    static SingularityLoader getInstance() {
        return LoaderImpl.get();
    }

    /** The side the game is running on. */
    Environment getEnvironment();

    /** Version of Singularity Loader, e.g. {@code 0.1.0}. */
    String getLoaderVersion();

    /** Version of the Minecraft jar being run, e.g. {@code 26.3}. */
    String getGameVersion();

    /** The server's working directory. */
    Path getGameDirectory();

    /** {@code <game dir>/config}: the conventional place for mod configuration files. */
    Path getConfigDirectory();

    /** {@code <game dir>/mods}. */
    Path getModsDirectory();

    /** {@code true} if the loader was started with {@code --singularity-debug} or {@code -Dsingularity.debug=true}. */
    boolean isDebug();

    boolean isModLoaded(String id);

    Optional<ModContainer> getModContainer(String id);

    /** All mods in load order, including the built-in pseudo mods. */
    Collection<ModContainer> getAllMods();

    /**
     * Instantiates every entrypoint registered under {@code key} (in mod load order) that implements {@code type}.
     * Useful for mod-to-mod APIs: declare {@code "entrypoints": {"myapi": ["com.example.Plugin"]}} and
     * let other mods implement your interface.
     *
     * @throws IllegalStateException if an entrypoint cannot be created or does not implement {@code type}
     */
    <T> List<T> getEntrypoints(String key, Class<T> type);
}
