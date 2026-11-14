package net.singularitymc.loader.classloading;

/** Rewrites class bytes right before the game class loader defines the class. */
@FunctionalInterface
public interface ClassTransformer {
    ClassTransformer NONE = (name, bytes) -> bytes;

    /**
     * @param name  binary class name with dots, e.g. {@code net.minecraft.server.Main}
     * @param bytes the class file as found on the class path
     * @return the class file to define (may be {@code bytes} itself)
     */
    byte[] transform(String name, byte[] bytes);
}
