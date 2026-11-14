package net.singularitymc.loader.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.singularitymc.loader.classloading.ClassTransformer;
import net.singularitymc.loader.util.Log;

/** Wraps a transformer and writes every class it changed to disk, so Mixin results can be inspected. */
final class ClassDumper implements ClassTransformer {
    private static final Log LOG = Log.get("Singularity");

    private final ClassTransformer delegate;
    private final Path dir;

    ClassDumper(ClassTransformer delegate, Path dir) {
        this.delegate = delegate;
        this.dir = dir;
    }

    @Override
    public byte[] transform(String name, byte[] bytes) {
        byte[] result = delegate.transform(name, bytes);
        if (!Arrays.equals(bytes, result)) {
            Path target = dir.resolve(name.replace('.', '/') + ".class");
            try {
                Files.createDirectories(target.getParent());
                Files.write(target, result);
                LOG.debug("Dumped transformed class {}", name);
            } catch (IOException e) {
                LOG.warn("Could not dump {}: {}", name, e.getMessage());
            }
        }
        return result;
    }
}
