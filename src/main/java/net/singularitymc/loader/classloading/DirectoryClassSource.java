package net.singularitymc.loader.classloading;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;

/** A {@link ClassSource} backed by a directory (handy for mod development). */
final class DirectoryClassSource implements ClassSource {
    private final Path root;

    DirectoryClassSource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Path path() {
        return root;
    }

    private Path resolve(String entry) {
        Path p = root.resolve(entry).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    @Override
    public byte[] read(String entry) throws IOException {
        Path p = resolve(entry);
        return p == null ? null : Files.readAllBytes(p);
    }

    @Override
    public java.util.List<String> classesUnder(String packagePrefix) throws IOException {
        Path dir = root.resolve(packagePrefix).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return java.util.List.of();
        }
        try (var s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".class"))
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .map(n -> n.substring(0, n.length() - ".class".length()))
                .toList();
        }
    }

    @Override
    public URL findResource(String entry) {
        Path p = resolve(entry);
        if (p == null) {
            return null;
        }
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    @Override
    public Manifest manifest() {
        Path p = resolve("META-INF/MANIFEST.MF");
        if (p == null) {
            return null;
        }
        try (var in = Files.newInputStream(p)) {
            return new Manifest(in);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public String toString() {
        return "dir:" + root;
    }
}
