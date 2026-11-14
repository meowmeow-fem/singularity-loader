package net.singularitymc.loader.mod;

import java.nio.file.Path;
import java.util.List;
import net.singularitymc.api.version.Version;

/** The pseudo mods every installation has: the loader, the game and the JVM. Mods may depend on them. */
public final class BuiltinMods {
    public static final String LOADER_ID = "singularity-loader";
    public static final String GAME_ID = "minecraft";
    public static final String JAVA_ID = "java";

    private BuiltinMods() {}

    public static List<ModContainerImpl> create(String loaderVersion, Path loaderOrigin, String gameVersion, Path gameJar) {
        StringBuilder java = new StringBuilder();
        for (int part : Runtime.version().version()) {
            if (java.length() > 0) {
                java.append('.');
            }
            java.append(part);
        }
        Path javaHome = Path.of(System.getProperty("java.home", "."));
        return List.of(
                new ModContainerImpl(ModMetadataImpl.builtin(LOADER_ID, Version.parseLenient(loaderVersion), "Singularity Loader",
                        "The mod loader."), loaderOrigin, true),
                new ModContainerImpl(ModMetadataImpl.builtin(GAME_ID, Version.parseLenient(gameVersion), "Minecraft",
                        "The Minecraft server."), gameJar, true),
                new ModContainerImpl(ModMetadataImpl.builtin(JAVA_ID, Version.parseLenient(java.toString()), "Java",
                        "The Java runtime."), javaHome, true));
    }
}
