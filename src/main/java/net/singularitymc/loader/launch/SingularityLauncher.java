package net.singularitymc.loader.launch;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import net.singularitymc.api.Environment;
import net.singularitymc.api.entrypoint.ModInitializer;
import net.singularitymc.api.entrypoint.PreLaunchEntrypoint;
import net.singularitymc.api.entrypoint.ServerModInitializer;
import net.singularitymc.loader.LoaderImpl;
import net.singularitymc.loader.classloading.ClassSource;
import net.singularitymc.loader.classloading.ClassTransformer;
import net.singularitymc.loader.classloading.MidpointRewriter;
import net.singularitymc.loader.asm.PositionWidening;
import net.singularitymc.loader.classloading.SingularityClassLoader;
import net.singularitymc.loader.game.GameCompat;
import net.singularitymc.loader.game.GameInstall;
import net.singularitymc.loader.game.GameProvider;
import net.singularitymc.loader.game.ServerDownloader;
import net.singularitymc.loader.mixin.MixinBootstrapper;
import net.singularitymc.loader.mod.BuiltinMods;
import net.singularitymc.loader.mod.EntrypointException;
import net.singularitymc.loader.mod.EntrypointManager;
import net.singularitymc.loader.mod.ModContainerImpl;
import net.singularitymc.loader.mod.ModDiscoverer;
import net.singularitymc.loader.mod.ModResolutionException;
import net.singularitymc.loader.mod.ModResolver;
import net.singularitymc.loader.server.EulaGate;
import net.singularitymc.loader.server.ServerConfig;
import net.singularitymc.loader.server.ServerLayout;
import net.singularitymc.loader.util.Log;
import net.singularitymc.loader.util.ServerState;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Starts the server: prepares the game jar, discovers and orders mods, sets up Mixin, runs the mod
 * entrypoints and finally calls Minecraft's {@code main}.
 */
public final class SingularityLauncher {
    private static final Log LOG = Log.get("Singularity");

    private SingularityLauncher() {}

    public static void run(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (LaunchException e) {
            System.err.println("Singularity Loader: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (options.help) {
            System.out.println(LaunchOptions.usage());
            return;
        }
        if (options.version) {
            System.out.println("Singularity Loader " + LoaderImpl.readVersion());
            return;
        }

        MainCall call;
        try {
            call = prepare(options);
        } catch (LaunchException e) {
            fatal("The server could not be started", e.getMessage(), e.getCause());
            return;
        } catch (EntrypointException e) {
            fatal("A mod failed to start", e.getMessage(), e.getCause());
            return;
        } catch (Throwable t) {
            fatal("Singularity Loader crashed", "Unexpected error: " + t
                    + "\nThis is a bug in the loader (or a mod). Please report it together with ServerLogs/Latest.log.", t);
            return;
        }

        // From here on exceptions belong to the game: let them propagate like they would without a loader.
        try {
            call.invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The game's {@code main}, ready to be called. */
    private record MainCall(MethodHandle handle, String[] args) {
        void invoke() throws Throwable {
            handle.invokeExact(args);
        }
    }

    private static MainCall prepare(LaunchOptions options) throws LaunchException {
        Path gameDir = options.gameDir;
        ServerLayout layout = ServerLayout.at(gameDir);
        Path stateDir = layout.state();
        try {
            layout.createFolders();
        } catch (IOException e) {
            throw new LaunchException("Could not create the server folders in " + gameDir + ": " + e.getMessage(), e);
        }
        Log.configure(options.debug, layout.logFile());
        String loaderVersion = LoaderImpl.readVersion();
        try (var banner = SingularityLauncher.class.getResourceAsStream("/net/singularitymc/loader/server/banner.txt")) {
            if (banner != null) {
                Log.banner(new String(banner.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the banner is decoration only
        }
        LOG.info("Singularity Loader {} on Java {} ({})", loaderVersion, Runtime.version(), System.getProperty("java.vendor", "unknown vendor"));
        cleanLegacyVanillaFolder(layout);

        // 1. The game
        Path serverJar = resolveServerJar(options, gameDir, layout);
        GameInstall game = GameProvider.prepare(serverJar, layout.gameVersions(), layout.gameLibraries(), gameDir);
        LOG.info("All Library Downloaded And Renamed! Running Singularity Server Setup.");
        if (options.setup) {
            printSetupResult(layout, game);
            System.exit(0);
        }
        String compatWarning = GameCompat.versionWarning(game.versionId());
        if (compatWarning != null) {
            LOG.warn("{} Supported versions: {} to {}. Optional patches that do not fit are skipped.", compatWarning, GameCompat.OLDEST, GameCompat.NEWEST);
        }
        boolean serverFilePatches = GameCompat.serverFilePatchesApply(game.classpath().get(0));
        setUpServerFiles(layout, game, serverFilePatches);
        int java = Runtime.version().feature();
        if (game.requiredJava() > java) {
            throw new LaunchException("Minecraft " + game.versionId() + " needs Java " + game.requiredJava() + " or newer, but this is Java " + java
                    + " (" + System.getProperty("java.home") + ").\nInstall a newer JDK and start the loader with it.");
        }

        // 2. Mods
        try {
            Files.createDirectories(options.modsDir);
        } catch (IOException e) {
            LOG.warn("Could not create the mods folder {}: {}", options.modsDir, e.getMessage());
        }
        LOG.info("Minecraft {} (server)", game.versionId());
        ModDiscoverer.Result found = ModDiscoverer.discover(options.modsDir, options.extraMods);
        if (!found.errors().isEmpty()) {
            throw new LaunchException("Some mods could not be read:\n  - " + String.join("\n  - ", found.errors()));
        }
        Path loaderJar = locateLoaderJar();
        List<ModContainerImpl> builtins = BuiltinMods.create(loaderVersion, loaderJar, game.versionId(), game.serverJar());
        List<ModContainerImpl> mods;
        try {
            mods = ModResolver.resolve(found.candidates(), builtins, Environment.SERVER);
        } catch (ModResolutionException e) {
            throw new LaunchException(e.getMessage());
        }
        if (mods.isEmpty()) {
            LOG.info("No mods loaded (put mod jars into {})", options.modsDir);
        } else {
            LOG.info("Loading {} mod{}:", mods.size(), mods.size() == 1 ? "" : "s");
            for (ModContainerImpl mod : mods) {
                LOG.info("  - {} {} ({})", mod.getMetadata().getName(), mod.getMetadata().getVersion(), mod.getId());
            }
        }

        // 3. The class loader that will run the game and the mods
        List<ClassSource> sources = new ArrayList<>();
        try {
            for (Path p : game.classpath()) {
                sources.add(ClassSource.of(p));
            }
            // The loader jar itself: the event classes (net.singularitymc.event) hold Minecraft types, so the
            // game class loader has to define them. Loader and API packages still always come from the parent.
            Path loaderSource = locateLoaderJar();
            if (Files.exists(loaderSource)) {
                sources.add(ClassSource.of(loaderSource));
            }
            for (ModContainerImpl mod : mods) {
                sources.add(ClassSource.of(mod.getOrigin()));
            }
        } catch (IOException e) {
            throw new LaunchException("Could not open a jar on the class path: " + e.getMessage(), e);
        }
        SingularityClassLoader classLoader = new SingularityClassLoader(SingularityLauncher.class.getClassLoader(), sources);
        List<ModContainerImpl> everything = new ArrayList<>(builtins);
        everything.addAll(mods);
        LoaderImpl loader = new LoaderImpl(Environment.SERVER, game.versionId(), gameDir, options.modsDir, options.debug, classLoader, everything);
        LoaderImpl.install(loader);

        // 4. Mixin
        IMixinTransformer transformer = MixinBootstrapper.start(loader, mods);
        ClassTransformer classTransformer = (name, bytes) -> transformer.transformClassBytes(name, name, bytes);
        if (ServerState.worldLimit() > MAX_WORLD_LIMIT) {
            classTransformer = new MidpointRewriter(classTransformer);
        }
        if (ServerState.longPositions()) {
            classTransformer = new PositionWidening(classTransformer, name -> readClass(sources, name), name -> {
                byte[] b = readClass(sources, name);
                if (b != null) return b;
                try (var in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    return in == null ? null : in.readAllBytes();
                } catch (IOException e) {
                    return null;
                }
            }, prefix -> {
                Set<String> names = new LinkedHashSet<>();
                for (ClassSource s : sources) {
                    try {
                        names.addAll(s.classesUnder(prefix));
                    } catch (IOException e) {
                        LOG.warn("Could not list {} in {}: {}", prefix, s.path(), e.toString());
                    }
                }
                return names;
            });
        }
        if (options.dump) {
            classTransformer = new ClassDumper(classTransformer, stateDir.resolve("dump"));
            LOG.info("Writing classes changed by Mixin to {}", stateDir.resolve("dump"));
        }
        classLoader.setTransformer(classTransformer);
        Thread.currentThread().setContextClassLoader(classLoader);

        // 5. Entrypoints
        EntrypointManager entrypoints = new EntrypointManager(classLoader, mods);
        loader.setEntrypoints(entrypoints);
        entrypoints.invoke("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
        entrypoints.invoke("main", ModInitializer.class, ModInitializer::onInitialize);
        entrypoints.invoke("server", ServerModInitializer.class, ServerModInitializer::onInitializeServer);

        // 6. Hand over to Minecraft
        try {
            Class<?> mainClass = Class.forName(game.mainClass(), true, classLoader);
            MethodHandle main = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
            LOG.info("Starting Minecraft Via {}.", game.mainClass());
            return new MainCall(main, options.gameArgs.toArray(new String[0]));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new LaunchException("Could not find the server's main method in " + game.mainClass() + ": " + e, e);
        }
    }

    /**
     * Decides which server jar to run: an explicit one, otherwise Mojang's jar from
     * {@code .Singularity/VanillaServerJars} (downloaded on first use). If the download is impossible, a server the
     * user already has in the folder (server.jar or an unpacked versions/ folder) is used instead.
     */
    private static Path resolveServerJar(LaunchOptions options, Path gameDir, ServerLayout layout) throws LaunchException {
        if (options.serverJar != null) {
            if (!Files.isRegularFile(options.serverJar)) {
                throw new LaunchException("The server jar was not found at " + options.serverJar + ".");
            }
            return options.serverJar;
        }
        String version = options.gameVersion != null ? options.gameVersion : ServerDownloader.DEFAULT_VERSION;
        java.net.URI url = null;
        if (options.serverUrl != null) {
            try {
                url = java.net.URI.create(options.serverUrl);
            } catch (IllegalArgumentException e) {
                throw new LaunchException("'" + options.serverUrl + "' is not a valid URL.");
            }
        }
        try {
            return ServerDownloader.ensureServerJar(layout.vanillaJars(), version, url, options.offline);
        } catch (LaunchException e) {
            Path local = Files.isRegularFile(gameDir.resolve("server.jar")) ? gameDir.resolve("server.jar") : GameProvider.detectServerJar(gameDir);
            if (local == null) {
                throw e;
            }
            LOG.warn("{}", e.getMessage().lines().findFirst().orElse("The server jar could not be downloaded."));
            LOG.warn("Using the server you already have instead: {}", gameDir.relativize(local));
            return local;
        }
    }

    /** Tells mod developers where the unpacked game is, for their build's compile class path. */
    private static void printSetupResult(ServerLayout layout, GameInstall game) {
        LOG.info("Minecraft {} is ready for mod development.", game.versionId());
        LOG.info("Point your mod project at these folders:\n    GameVersions  = {}\n    GameLibraries = {}", layout.gameVersions(), layout.gameLibraries());
    }

    /**
     * Loads {@code ServerConfig.toml}, gets the owner's answer to the Minecraft EULA ({@code ServerEula.txt}) and hands
     * both to the game patches. Declining the EULA stops the server cleanly.
     */
    private static void setUpServerFiles(ServerLayout layout, GameInstall game, boolean serverFilePatches) throws LaunchException {
        try {
            ServerConfig config = ServerConfig.loadOrCreate(layout.configFile(), layout.root().resolve("server.properties"));
            ServerState.setServerProperties(config.toProperties());
            boolean interactive = System.console() != null || System.getProperty("singularity.eulaFromStdin") != null;
            boolean accepted = EulaGate.ensure(layout.eulaFile(), LOG, interactive ? EulaGate.console() : null);
            if (!accepted) {
                LOG.error("The Minecraft EULA ({}) was not accepted, so the server cannot start. Set eula=true in ServerEula.txt (or answer Yes next time).", EulaGate.EULA_URL);
                System.exit(1);
            }
            ServerState.setEulaAccepted(true);
            applyFarlands(config, game);
            ServerState.setLongPositions(config.get("experimental_long_positions", "false").equalsIgnoreCase("true"));
            if (ServerState.longPositions()) {
                LOG.warn("experimental_long_positions is on: positions, geometry and world access get 64-bit X/Z at load"
                    + " (3.5.4 of docs/ROADMAP_64BIT.md); the rest of the game carries 32-bit coordinates. Back up your world.");
            }
            applyWorldLimit(config);
            ServerState.setBorderWarning(config.get("world_border_warning", "false").equalsIgnoreCase("true"));
            net.singularitymc.loader.util.Perf.configure(config::get);
            // read by net.singularitymc.gui (the server window); -D on the command line wins
            if (System.getProperty("singularity.gui.window") == null) {
                System.setProperty("singularity.gui.window", config.get("singularity_window", "true"));
            }
            if (System.getProperty("singularity.gui.animations") == null) {
                System.setProperty("singularity.gui.animations", config.get("window_animations", "true"));
            }
            if (!serverFilePatches) {
                // This Minecraft version does not have the methods our patches replace: give the game its own files instead.
                Files.writeString(layout.root().resolve("eula.txt"), "eula=true\n");
                Properties props = config.toProperties();
                try (var out = Files.newBufferedWriter(layout.root().resolve("server.properties"))) {
                    props.store(out, "Generated from ServerConfig.toml (compatibility mode). Edit ServerConfig.toml instead.");
                }
                ServerState.setServerProperties(null);
                LOG.warn("Compatibility mode: eula.txt and server.properties were generated from ServerEula.txt and ServerConfig.toml. Do not edit them.");
            }
            LOG.info("Minecraft Eula [{}] Accepted! Running Singularity Server Minecraft Version {}.", EulaGate.EULA_URL, game.versionId());
        } catch (IOException e) {
            throw new LaunchException("Could not prepare ServerConfig.toml / ServerEula.txt: " + e.getMessage(), e);
        }
    }

    /**
     * Highest accepted {@code max_coordinate}: 2^30 minus 256 chunks. Vanilla adds two block coordinates in many places
     * (for example the midpoint {@code (minX + maxX) / 2} in mineshaft generation, which crashed a test world at
     * x = 1.9 billion), so beyond 2^30 such sums overflow. Chunks past the world edge still load (view distance,
     * generation margin), hence the 256 chunks of headroom.
     */
    static final int MAX_WORLD_LIMIT = (1 << 30) - 256 * 16; // 1,073,737,728

    /**
     * With {@code full_int_range = true} (experimental): 2^31 minus 256 chunks. The overflowing two-coordinate
     * midpoints are rewritten to 64-bit math ({@code MidpointRewriter}); other sums may still overflow.
     */
    static final int MAX_WORLD_LIMIT_FULL_RANGE = Integer.MAX_VALUE - 256 * 16 + 1; // 2,147,479,552

    /** With {@code experimental_long_positions} and {@code full_int_range} (3.5.4): 2^52, where doubles still hold whole blocks. */
    static final long MAX_WORLD_LIMIT_LONG = 1L << 52; // 4,503,599,627,370,496

    /** Untransformed bytes of a class from the game's class sources, or null. */
    private static byte[] readClass(List<ClassSource> sources, String internalName) {
        for (ClassSource s : sources) {
            try {
                byte[] b = s.read(internalName + ".class");
                if (b != null) return b;
            } catch (IOException e) {
                // try the next source
            }
        }
        return null;
    }

    /**
     * A {@code max_coordinate} value, or -1. Digits with any separators or stray symbols around them (spaces, quotes,
     * {@code _}, {@code ,}, a bullet pasted along), or a power of two written {@code 2^52}.
     */
    static long parseCoordinate(String raw) {
        String s = raw.replaceAll("[^0-9^]", "");
        try {
            int caret = s.indexOf('^');
            if (caret < 0) return s.isEmpty() ? -1 : Long.parseLong(s);
            long base = Long.parseLong(s.substring(0, caret));
            int exp = Integer.parseInt(s.substring(caret + 1));
            if (base != 2 || exp < 0 || exp > 62) return -1;
            return 1L << exp;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** {@code max_coordinate}: how far the world reaches (stage 1 of docs/COORDINATES.md). */
    private static void applyWorldLimit(ServerConfig config) {
        String raw = config.get("max_coordinate", "30000000");
        long wanted = parseCoordinate(raw);
        if (wanted < 0) {
            LOG.error("max_coordinate '{}' is not a number; the world stays at 30000000 blocks. Write it as digits"
                + " (4503599627370496) or as a power of two (\"2^52\").", raw);
            return;
        }
        boolean fullRange = config.get("full_int_range", "false").equalsIgnoreCase("true");
        if (ServerState.longPositions() && fullRange && wanted > MAX_WORLD_LIMIT_FULL_RANGE) {
            long limit = Math.min(MAX_WORLD_LIMIT_LONG, wanted);
            if (limit != wanted) {
                LOG.warn("max_coordinate {} is past {} (doubles stop holding whole blocks beyond 2^52); using {}.", wanted, MAX_WORLD_LIMIT_LONG, limit);
            }
            ServerState.setWorldLimit64(limit);
            LOG.warn("max_coordinate {} is past the int range (experimental_long_positions). Chunks are saved up to +-17179869184;"
                + " vanilla clients play anywhere with a shifted frame (F3 shows the position minus the origin told in chat).", limit);
            return;
        }
        int max = fullRange ? MAX_WORLD_LIMIT_FULL_RANGE : MAX_WORLD_LIMIT;
        int limit = (int) Math.max(30_000_000L, Math.min(max, wanted));
        if (limit != wanted) {
            LOG.warn("max_coordinate {} is outside 30000000..{}; using {}. Block coordinates are 32-bit ints and the game adds"
                + " two of them in places (see docs/COORDINATES.md{}).", wanted, max, limit,
                fullRange ? "" : "; full_int_range = true goes up to " + MAX_WORLD_LIMIT_FULL_RANGE + ", experimental");
        }
        ServerState.setWorldLimit(limit);
        if (limit > MAX_WORLD_LIMIT) {
            LOG.warn("max_coordinate is past 2^30 (full_int_range, EXPERIMENTAL): two-coordinate midpoints are rewritten to"
                + " 64-bit math, but other sums in the game may still overflow out there. Back up your world.");
        }
        if (limit > 30_000_000) {
            LOG.info("The world reaches {} blocks (max_coordinate). Set max_world_size and the world border to match.", limit);
        }
        if (WidePacking.isNeeded(limit)) {
            LOG.warn("Past 33554432 blocks the server uses Singularity's wide position packing (experimental). Vanilla clients"
                + " stop at 29999999 on their own; players need the Singularity Wide Client mod (Fabric) to go further.");
        }
    }

    /**
     * {@code enable_farlands} / {@code farlands_style}: the classic Far Lands (Beta 1.7.3's terrain past 12,550,821 blocks,
     * docs/FARLANDS.md), or Mojang's own debug switch, which must be set before the game's SharedConstants class loads.
     */
    private static void applyFarlands(ServerConfig config, GameInstall game) {
        if (!config.get("enable_farlands", "true").equalsIgnoreCase("true")) {
            return;
        }
        String style = config.get("farlands_style", "classic").strip().toLowerCase(java.util.Locale.ROOT);
        if (!style.equals("mojang")) {
            if (!style.equals("classic")) {
                LOG.warn("farlands_style '{}' is not \"classic\" or \"mojang\"; using classic.", style);
            }
            ServerState.setClassicFarLands(true);
            LOG.info("The classic Far Lands are enabled: from 12,550,821 blocks out, terrain is generated like in Beta 1.7.3."
                + " Only chunks generated from now on.");
            return;
        }
        if (!GameCompat.hasFarlandsSwitch(game.classpath().get(0))) {
            LOG.warn("enable_farlands is on, but Minecraft {} has no Far Lands switch (it exists from 26.3). Skipped.", game.versionId());
            return;
        }
        System.setProperty("MC_DEBUG_ENABLED", "true");
        System.setProperty("MC_DEBUG_ENABLE_FARLANDS", "true");
        LOG.info("Mojang's Far Lands switch is on (farlands_style = \"mojang\"). It only affects chunks generated from now on.");
    }

    /**
     * Older Singularity versions let Mojang's launcher unpack inside {@code .Singularity/VanillaServerJars}. Move what
     * it left ({@code libraries}, {@code versions}) into the new folders and delete the useless rest.
     */
    private static void cleanLegacyVanillaFolder(ServerLayout layout) {
        Path v = layout.vanillaJars();
        try {
            moveInto(v.resolve("libraries"), layout.gameLibraries());
            moveInto(v.resolve("versions"), layout.gameVersions());
            for (String junk : new String[] {"eula.txt", "server.properties"}) {
                Files.deleteIfExists(v.resolve(junk));
            }
            deleteTree(v.resolve("logs"));
        } catch (IOException e) {
            LOG.warn("Could not tidy {}: {}", v, e.getMessage());
        }
    }

    private static void moveInto(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (var walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk.sorted()::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst);
                }
            }
        }
        deleteTree(from);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path locateLoaderJar() {
        try {
            return Path.of(SingularityLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    private static void fatal(String title, String message, Throwable cause) {
        LOG.error("{}", "==== " + title + " ====");
        for (String line : message.split("\\R")) {
            LOG.error("{}", line);
        }
        if (cause != null) {
            LOG.error("Caused by:", cause);
        }
        LOG.error("{}", "Log file: ServerLogs/Latest.log");
        System.exit(1);
    }
}
