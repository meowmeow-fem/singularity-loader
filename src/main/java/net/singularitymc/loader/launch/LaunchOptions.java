package net.singularitymc.loader.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line of the loader. Loader options start with {@code --singularity-}; everything else is
 * handed to Minecraft unchanged.
 */
public final class LaunchOptions {
    static final String PREFIX = "--singularity-";

    Path gameDir = Path.of("").toAbsolutePath();
    /** Explicit server jar, or {@code null} to download Mojang's jar into .Singularity/VanillaServerJars. */
    Path serverJar;
    String gameVersion;
    String serverUrl;
    boolean offline;
    boolean setup;
    Path modsDir;
    boolean debug = Boolean.getBoolean("singularity.debug");
    boolean dump;
    boolean help;
    boolean version;
    final List<Path> extraMods = new ArrayList<>();
    final List<String> gameArgs = new ArrayList<>();

    public static LaunchOptions parse(String[] args) throws LaunchException {
        LaunchOptions o = new LaunchOptions();
        String propJar = System.getProperty("singularity.serverJar");
        if (propJar != null && !propJar.isBlank()) {
            o.serverJar = o.gameDir.resolve(propJar);
        }
        String propMods = System.getProperty("singularity.addMods");
        if (propMods != null && !propMods.isBlank()) {
            for (String p : propMods.split(java.io.File.pathSeparator)) {
                if (!p.isBlank()) {
                    o.extraMods.add(o.gameDir.resolve(p.trim()));
                }
            }
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith(PREFIX)) {
                o.gameArgs.add(arg);
                continue;
            }
            String body = arg.substring(PREFIX.length());
            String name = body;
            String value = null;
            int eq = body.indexOf('=');
            if (eq >= 0) {
                name = body.substring(0, eq);
                value = body.substring(eq + 1);
            }
            switch (name) {
                case "help" -> o.help = true;
                case "version" -> o.version = true;
                case "debug" -> o.debug = true;
                case "dump" -> o.dump = true;
                case "offline" -> o.offline = true;
                case "setup" -> o.setup = true;
                case "game-version", "server-url" -> {
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new LaunchException("Option " + PREFIX + name + " needs a value (" + PREFIX + name + "=<value>).");
                        }
                        value = args[++i];
                    }
                    if (name.equals("game-version")) {
                        o.gameVersion = value.trim();
                    } else {
                        o.serverUrl = value.trim();
                    }
                }
                case "server-jar", "mods-dir", "add-mod" -> {
                    if (value == null) {
                        if (i + 1 >= args.length) {
                            throw new LaunchException("Option " + PREFIX + name + " needs a value (" + PREFIX + name + "=<path>).");
                        }
                        value = args[++i];
                    }
                    Path path = o.gameDir.resolve(value);
                    switch (name) {
                        case "server-jar" -> {
                            o.serverJar = path;
                        }
                        case "mods-dir" -> o.modsDir = path;
                        default -> o.extraMods.add(path);
                    }
                }
                default -> throw new LaunchException("Unknown option " + arg + ". Run with " + PREFIX + "help to see the available options.");
            }
        }
        if (o.modsDir == null) {
            o.modsDir = o.gameDir.resolve("SingularityMods");
        }
        return o;
    }

    static String usage() {
        return String.join(System.lineSeparator(),
                "Usage: java -jar singularity-loader.jar [loader options] [Minecraft options]",
                "",
                "Run it from your server folder. Anything that is not a loader option (for example",
                "'nogui' or '--port 25566') is passed on to the Minecraft server.",
                "",
                "Loader options:",
                "  --singularity-game-version=<id>  Minecraft version to download and run (default: " + net.singularitymc.loader.game.ServerDownloader.DEFAULT_VERSION + ", or 'latest')",
                "  --singularity-server-jar=<path>  Use this server jar instead of downloading one",
                "  --singularity-server-url=<url>   Download the server jar from this URL (Mojang object URLs are verified)",
                "  --singularity-offline            Never use the network (needs a cached or provided server jar)",
                "  --singularity-mods-dir=<path>    Folder with mod jars (default: ./mods)",
                "  --singularity-add-mod=<path>     Load an extra mod jar or directory (repeatable; for development)",
                "  --singularity-setup              Download and unpack the game, print the folder to use for mod development, then exit",
                "  --singularity-debug              Verbose logging",
                "  --singularity-dump               Write every class changed by Mixin to .Singularity/dump",
                "  --singularity-version            Print the loader version and exit",
                "  --singularity-help               Show this help and exit",
                "",
                "System properties: -Dsingularity.debug=true, -Dsingularity.serverJar=<path>,",
                "                   -Dsingularity.addMods=<path>" + java.io.File.pathSeparator + "<path>");
    }
}
