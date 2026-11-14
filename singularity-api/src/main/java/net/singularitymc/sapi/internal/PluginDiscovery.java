package net.singularitymc.sapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.singularitymc.sapi.plugin.PluginDescriptor;

/** Finds plugins: jars (and, for development, folders) with a {@code plugin.toml} at their root. */
public final class PluginDiscovery {
    private PluginDiscovery() {}

    public record Candidate(Path file, PluginDescriptor descriptor) {}

    public record Result(List<Candidate> candidates, List<String> problems) {}

    /**
     * @param folder {@code SingularityPlugins/} (missing = no plugins)
     * @param extra  more jars or folders ({@code -Dsingularity.addPlugins})
     */
    public static Result discover(Path folder, List<Path> extra) {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> s = Files.list(folder)) {
                s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(files::add);
            } catch (IOException e) {
                return new Result(List.of(), List.of("Could not list " + folder + ": " + e));
            }
        }
        files.addAll(extra);
        List<Candidate> candidates = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path f : files) {
            String text;
            boolean isMod;
            try {
                if (Files.isDirectory(f)) {
                    Path toml = f.resolve(PluginDescriptorParser.FILE);
                    text = Files.isRegularFile(toml) ? Files.readString(toml, StandardCharsets.UTF_8) : null;
                    isMod = Files.isRegularFile(f.resolve("singularity.mod.json"));
                } else {
                    try (ZipFile zip = new ZipFile(f.toFile())) {
                        ZipEntry e = zip.getEntry(PluginDescriptorParser.FILE);
                        if (e == null) {
                            text = null;
                        } else {
                            try (InputStream in = zip.getInputStream(e)) {
                                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            }
                        }
                        isMod = zip.getEntry("singularity.mod.json") != null;
                    }
                }
            } catch (IOException e) {
                problems.add(f.getFileName() + ": could not be read (" + e + ")");
                continue;
            }
            if (text == null) {
                problems.add(f.getFileName() + ": no plugin.toml" + (isMod ? " (it is a mod: put it into SingularityMods)" : ""));
                continue;
            }
            try {
                candidates.add(new Candidate(f, PluginDescriptorParser.parse(text)));
            } catch (PluginException e) {
                problems.add(f.getFileName() + ": " + String.join("; ", e.problems()));
            }
        }
        return new Result(candidates, problems);
    }
}
