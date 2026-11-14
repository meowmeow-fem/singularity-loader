package net.singularitymc.loader.classloading;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.jar.Manifest;

/** One entry of the game class path: a jar file or a directory of classes and resources. */
public interface ClassSource extends AutoCloseable {
    /** The jar file or directory. */
    Path path();

    /** The contents of {@code entry} (a {@code /} separated path), or {@code null} if absent. */
    byte[] read(String entry) throws IOException;

    /** A URL for {@code entry}, or {@code null} if absent. */
    URL findResource(String entry);

    /** The jar manifest, or {@code null}. */
    Manifest manifest();

    /** The internal names of the classes under {@code packagePrefix} (like {@code net/minecraft/world/}). */
    java.util.List<String> classesUnder(String packagePrefix) throws IOException;

    default InputStream openStream(String entry) throws IOException {
        byte[] bytes = read(entry);
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    @Override
    void close() throws IOException;

    static ClassSource of(Path path) throws IOException {
        return java.nio.file.Files.isDirectory(path) ? new DirectoryClassSource(path) : new JarClassSource(path);
    }
}
