package net.singularitymc.loader.mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import net.singularitymc.loader.util.Log;

/** Finds mods in the {@code mods} folder and in any extra paths (for development). */
public final class ModDiscoverer {
    private static final Log LOG = Log.get("Singularity");

    /** Result of a scan: the mods that were found, and problems that must stop the launch. */
    public record Result(List<ModCandidate> candidates, List<String> errors) {}

    private ModDiscoverer() {}

    /**
     * @param modsDir    the mods folder (need not exist)
     * @param extraPaths additional jars or directories to treat as mods
     */
    public static Result discover(Path modsDir, List<Path> extraPaths) {
        List<ModCandidate> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> stream = Files.list(modsDir)) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .forEach(paths::add);
            } catch (IOException e) {
                errors.add("Could not list the mods folder " + modsDir.toAbsolutePath() + ": " + e.getMessage());
            }
        }
        paths.addAll(extraPaths);
        for (Path path : paths) {
            try {
                ModCandidate candidate = Files.isDirectory(path) ? readDirectory(path) : readJar(path);
                if (candidate != null) {
                    LOG.debug("Found mod {} in {}", candidate.metadata(), path);
                    candidates.add(candidate);
                }
            } catch (ModMetadataException e) {
                errors.add(e.getMessage());
            } catch (IOException e) {
                errors.add("Could not read " + path.toAbsolutePath() + ": " + e.getMessage());
            }
        }
        return new Result(candidates, errors);
    }

    private static ModCandidate readJar(Path path) throws IOException, ModMetadataException {
        try (JarFile jar = new JarFile(path.toFile())) {
            ZipEntry entry = jar.getEntry(ModMetadataParser.FILE_NAME);
            if (entry == null) {
                warnForeign(jar, path);
                return null;
            }
            try (InputStream in = jar.getInputStream(entry); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return new ModCandidate(path.toAbsolutePath(), ModMetadataParser.parse(reader, path.getFileName().toString()));
            }
        } catch (java.util.zip.ZipException e) {
            throw new ModMetadataException(path.getFileName() + " is not a valid jar file: " + e.getMessage(), e);
        }
    }

    private static ModCandidate readDirectory(Path dir) throws IOException, ModMetadataException {
        Path file = dir.resolve(ModMetadataParser.FILE_NAME);
        if (!Files.isRegularFile(file)) {
            LOG.warn("Ignoring directory {}: it has no {}", dir, ModMetadataParser.FILE_NAME);
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new ModCandidate(dir.toAbsolutePath(), ModMetadataParser.parse(reader, dir.getFileName().toString()));
        }
    }

    private static void warnForeign(JarFile jar, Path path) {
        String name = path.getFileName().toString();
        if (jar.getEntry("fabric.mod.json") != null) {
            LOG.warn("Ignoring {}: it is a Fabric mod. Singularity Loader only loads mods with a {}.", name, ModMetadataParser.FILE_NAME);
        } else if (jar.getEntry("quilt.mod.json") != null) {
            LOG.warn("Ignoring {}: it is a Quilt mod. Singularity Loader only loads mods with a {}.", name, ModMetadataParser.FILE_NAME);
        } else if (jar.getEntry("META-INF/neoforge.mods.toml") != null || jar.getEntry("META-INF/mods.toml") != null) {
            LOG.warn("Ignoring {}: it is a Forge/NeoForge mod. Singularity Loader only loads mods with a {}.", name, ModMetadataParser.FILE_NAME);
        } else {
            LOG.warn("Ignoring {}: it contains no {}.", name, ModMetadataParser.FILE_NAME);
        }
    }
}
