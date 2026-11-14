package net.singularitymc.loader.classloading;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

/** A {@link ClassSource} backed by a jar file. Multi-release jars are honoured. */
final class JarClassSource implements ClassSource {
    private final Path path;
    private final JarFile jar;
    private final String urlPrefix;

    JarClassSource(Path path) throws IOException {
        this.path = path;
        this.jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
        this.urlPrefix = "jar:" + path.toUri() + "!/";
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public byte[] read(String entry) throws IOException {
        JarEntry e = jar.getJarEntry(entry);
        if (e == null || e.isDirectory()) {
            return null;
        }
        try (InputStream in = jar.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    @Override
    public java.util.List<String> classesUnder(String packagePrefix) {
        return jar.stream().map(JarEntry::getName)
            .filter(n -> n.startsWith(packagePrefix) && n.endsWith(".class"))
            .map(n -> n.substring(0, n.length() - ".class".length()))
            .toList();
    }

    @Override
    public InputStream openStream(String entry) throws IOException {
        JarEntry e = jar.getJarEntry(entry);
        return e == null || e.isDirectory() ? null : jar.getInputStream(e);
    }

    @Override
    public URL findResource(String entry) {
        JarEntry e = jar.getJarEntry(entry);
        if (e == null) {
            return null;
        }
        try {
            // Encode characters that are illegal in a URI path (spaces, '#', ...) but keep '/'.
            String encoded = new URI(null, null, entry, null).getRawPath();
            return URI.create(urlPrefix + encoded).toURL();
        } catch (URISyntaxException | java.net.MalformedURLException ex) {
            return null;
        }
    }

    @Override
    public Manifest manifest() {
        try {
            return jar.getManifest();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }

    @Override
    public String toString() {
        return "jar:" + path;
    }
}
