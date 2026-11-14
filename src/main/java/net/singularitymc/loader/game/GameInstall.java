package net.singularitymc.loader.game;

import java.nio.file.Path;
import java.util.List;

/**
 * A Minecraft server that is ready to be started.
 *
 * @param versionId    game version, e.g. {@code 26.3} ({@code unknown} if the jar has no version.json)
 * @param requiredJava major Java version the game needs ({@code 0} if unknown)
 * @param mainClass    the class whose {@code main} starts the server
 * @param classpath    the game jar followed by its libraries
 * @param serverJar    the jar the user supplied
 */
public record GameInstall(String versionId, int requiredJava, String mainClass, List<Path> classpath, Path serverJar) {}
