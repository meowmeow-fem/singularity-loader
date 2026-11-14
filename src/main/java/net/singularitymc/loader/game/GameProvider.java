package net.singularitymc.loader.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import net.singularitymc.loader.launch.LaunchException;
import net.singularitymc.loader.util.Log;

/**
 * Turns the official {@code server.jar} into a class path.
 *
 * <p>Mojang ships the server as a "bundler": a small launcher jar that contains the real server jar
 * and its libraries under {@code META-INF/}. We do what the bundler would do (extract and verify
 * those files) but into {@code .singularity/cache} and without running Mojang's launcher, so that
 * everything ends up in our class loader. A plain jar (no bundler metadata) is used as is.
 */
public final class GameProvider {
    private static final Log LOG = Log.get("Singularity");

    private GameProvider() {}

    private record BundleEntry(String sha256, String id, String path) {}

    /**
     * Unpacks the server bundle: the game jar goes to {@code versionsDir} and the libraries to {@code librariesDir}
     * (the same relative paths as inside the bundle, so Mojang's {@code versions} and {@code libraries} layout is kept).
     * Progress is shown as one loading bar instead of one log line per file.
     */
    public static GameInstall prepare(Path serverJar, Path versionsDir, Path librariesDir, Path gameDir) throws LaunchException {
        if (!Files.isRegularFile(serverJar)) {
            throw new LaunchException("Server jar not found: " + serverJar.toAbsolutePath());
        }
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            JsonObject versionJson = readVersionJson(jar);
            String versionId = versionJson != null && versionJson.has("id") ? versionJson.get("id").getAsString() : "unknown";
            int requiredJava = versionJson != null && versionJson.has("java_version") ? versionJson.get("java_version").getAsInt() : 0;

            ZipEntry mainClassEntry = jar.getEntry("META-INF/main-class");
            if (mainClassEntry == null) {
                return unpackedJar(jar, serverJar, gameDir, versionId, requiredJava);
            }
            String mainClass = readText(jar, mainClassEntry).trim();
            List<BundleEntry> versions = parseList(jar, "META-INF/versions.list");
            List<BundleEntry> libraries = parseList(jar, "META-INF/libraries.list");
            if (versions.isEmpty()) {
                throw new LaunchException("'" + serverJar.getFileName() + "' looks like a server bundle but contains no game jar (META-INF/versions.list is empty).");
            }

            List<Path> classpath = new ArrayList<>();
            try (Log.Progress bar = LOG.progress("Unpacking And Patching All Required Java Libraries", versions.size() + libraries.size())) {
                for (BundleEntry e : versions) {
                    classpath.add(extract(jar, "META-INF/versions/" + e.path(), versionsDir.resolve(e.path()), e.sha256()));
                    bar.step();
                }
                for (BundleEntry e : libraries) {
                    classpath.add(extract(jar, "META-INF/libraries/" + e.path(), librariesDir.resolve(e.path()), e.sha256()));
                    bar.step();
                }
            }
            LOG.debug("Server bundle: main class {}, {} jars on the class path", mainClass, classpath.size());
            return new GameInstall(versionId, requiredJava, mainClass, List.copyOf(classpath), serverJar);
        } catch (IOException e) {
            throw new LaunchException("Could not read server jar " + serverJar.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * A jar without bundler metadata: either a classic standalone server jar, or the inner
     * {@code versions/<id>/server-<id>.jar} that Mojang's launcher leaves behind after unpacking, which
     * keeps its libraries in a {@code libraries} folder next to {@code versions}.
     */
    private static GameInstall unpackedJar(JarFile jar, Path serverJar, Path gameDir, String versionId, int requiredJava)
            throws IOException, LaunchException {
        Manifest manifest = jar.getManifest();
        String mainClass = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        if (mainClass == null || mainClass.isBlank()) {
            mainClass = jar.getEntry("net/minecraft/server/Main.class") != null ? "net.minecraft.server.Main" : null;
        }
        if (mainClass == null) {
            throw new LaunchException("'" + serverJar.getFileName() + "' is not a Minecraft server jar (no main class found).");
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(serverJar.toAbsolutePath().normalize());
        Path libraries = Files.isDirectory(gameDir.resolve("GameLibraries")) ? gameDir.resolve("GameLibraries") : findLibrariesDir(serverJar, gameDir);
        if (libraries != null) {
            List<Path> libs = collectLibraries(libraries);
            classpath.addAll(libs);
            LOG.debug("Unpacked server: main class {}, {} libraries from {}", mainClass, libs.size(), libraries);
        } else if (jar.getEntry("com/google/common/collect/ImmutableList.class") == null && jar.getEntry("org/apache/logging/log4j/Logger.class") == null) {
            throw new LaunchException("'" + serverJar.getFileName() + "' is the game jar without its libraries.\n"
                    + "Either use the original server.jar (the large file you download from minecraft.net), or make sure the\n"
                    + "'libraries' folder that belongs to it exists next to the 'versions' folder.");
        }
        return new GameInstall(versionId, requiredJava, mainClass.trim(), List.copyOf(classpath), serverJar);
    }

    private static Path findLibrariesDir(Path serverJar, Path gameDir) {
        Path abs = serverJar.toAbsolutePath().normalize();
        Path versionDir = abs.getParent();
        Path versionsDir = versionDir == null ? null : versionDir.getParent();
        List<Path> candidates = new ArrayList<>();
        if (versionsDir != null && versionsDir.getFileName() != null && versionsDir.getFileName().toString().equals("versions") && versionsDir.getParent() != null) {
            candidates.add(versionsDir.getParent().resolve("libraries"));
        }
        candidates.add(gameDir.resolve("libraries"));
        if (versionDir != null) {
            candidates.add(versionDir.resolve("libraries"));
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Collects the jars of a Maven-style {@code libraries} folder. If several versions of the same
     * library are present (old leftovers from earlier Minecraft versions), only the newest is used.
     */
    static List<Path> collectLibraries(Path librariesDir) throws IOException {
        java.util.Map<Path, Path> newestVersionDir = new java.util.TreeMap<>();
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.walk(librariesDir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")).forEach(jars::add);
        }
        for (Path jar : jars) {
            Path versionDir = jar.getParent();
            Path artifactDir = versionDir.getParent();
            if (artifactDir == null) {
                continue;
            }
            newestVersionDir.merge(artifactDir, versionDir, (a, b) ->
                    net.singularitymc.api.version.Version.parseLenient(a.getFileName().toString())
                            .compareTo(net.singularitymc.api.version.Version.parseLenient(b.getFileName().toString())) >= 0 ? a : b);
        }
        List<Path> out = new ArrayList<>();
        for (Path jar : jars) {
            Path versionDir = jar.getParent();
            Path artifactDir = versionDir.getParent();
            if (artifactDir == null || newestVersionDir.get(artifactDir).equals(versionDir)) {
                out.add(jar.toAbsolutePath().normalize());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Looks for an unpacked server ({@code versions/<id>/*.jar}) in the game directory. */
    public static Path detectServerJar(Path gameDir) {
        Path versions = gameDir.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (var dirs = Files.list(versions)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Path jar = dir.resolve("server-" + dir.getFileName() + ".jar");
                if (Files.isRegularFile(jar)) {
                    found.add(jar);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    private static JsonObject readVersionJson(JarFile jar) throws IOException {
        ZipEntry entry = jar.getEntry("version.json");
        if (entry == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            LOG.warn("version.json in the server jar could not be parsed: {}", e.getMessage());
            return null;
        }
    }

    private static String readText(JarFile jar, ZipEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<BundleEntry> parseList(JarFile jar, String name) throws IOException, LaunchException {
        ZipEntry entry = jar.getEntry(name);
        List<BundleEntry> out = new ArrayList<>();
        if (entry == null) {
            return out;
        }
        for (String line : readText(jar, entry).split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length != 3) {
                throw new LaunchException("Malformed line in " + name + ": '" + line + "'");
            }
            out.add(new BundleEntry(parts[0].toLowerCase(java.util.Locale.ROOT), parts[1], parts[2]));
        }
        return out;
    }

    private static Path extract(JarFile jar, String entryName, Path target, String expectedSha256) throws IOException, LaunchException {
        ZipEntry entry = jar.getEntry(entryName);
        if (entry == null) {
            throw new LaunchException("The server bundle is incomplete: " + entryName + " is missing.");
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized) && Files.size(normalized) == entry.getSize()) {
            return normalized; // already extracted (verified when it was written)
        }
        Files.createDirectories(normalized.getParent());
        Path tmp = Files.createTempFile(normalized.getParent(), "extract-", ".tmp");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = jar.getInputStream(entry); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expectedSha256)) {
                throw new LaunchException("The server jar is corrupt: SHA-256 of " + entryName + " is " + actual + " but the bundle says " + expectedSha256 + ". Please download the server jar again.");
            }
            Files.move(tmp, normalized, StandardCopyOption.REPLACE_EXISTING);
            return normalized;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
