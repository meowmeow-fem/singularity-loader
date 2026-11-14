package net.singularitymc.loader.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The folders and files of a Singularity server (3.0 layout), all relative to the folder the server is started in:
 *
 * <pre>
 * .Singularity/ProcessedMods      mods after the loader has processed them
 * .Singularity/VanillaServerJars  Mojang's original server jar(s)
 * GameLibraries/                  Minecraft's libraries (unpacked from the server jar)
 * GameVersions/                   the Minecraft game jar(s) (unpacked from the server jar)
 * ServerLogs/                     Latest.log plus the logs of earlier starts
 * SingularityMods/                mods
 * SingularityPlugins/             plugins
 * ServerEula.txt                  Minecraft EULA answer
 * ServerConfig.toml               server settings (replaces server.properties)
 * </pre>
 */
public record ServerLayout(Path root, Path state, Path processedMods, Path vanillaJars, Path gameLibraries, Path gameVersions,
                           Path logs, Path mods, Path plugins, Path eulaFile, Path configFile) {

    public static ServerLayout at(Path root) {
        Path r = root.toAbsolutePath().normalize();
        Path state = r.resolve(".Singularity");
        return new ServerLayout(r, state, state.resolve("ProcessedMods"), state.resolve("VanillaServerJars"),
                r.resolve("GameLibraries"), r.resolve("GameVersions"), r.resolve("ServerLogs"),
                r.resolve("SingularityMods"), r.resolve("SingularityPlugins"), r.resolve("ServerEula.txt"), r.resolve("ServerConfig.toml"));
    }

    /** Where this start writes its log. */
    public Path logFile() {
        return logs.resolve("Latest.log");
    }

    /** Creates every folder of the layout (the two files are created when they are first needed). */
    public void createFolders() throws IOException {
        for (Path dir : new Path[] {state, processedMods, vanillaJars, gameLibraries, gameVersions, logs, mods, plugins}) {
            Files.createDirectories(dir);
        }
    }
}
