package net.singularitymc.loader.mod;

import java.nio.file.Path;
import net.singularitymc.api.mod.ModContainer;
import net.singularitymc.api.mod.ModMetadata;

/** A mod known to the loader. */
public final class ModContainerImpl implements ModContainer {
    private final ModMetadataImpl metadata;
    private final Path origin;
    private final boolean builtin;

    public ModContainerImpl(ModMetadataImpl metadata, Path origin, boolean builtin) {
        this.metadata = metadata;
        this.origin = origin;
        this.builtin = builtin;
    }

    @Override
    public ModMetadata getMetadata() {
        return metadata;
    }

    public ModMetadataImpl getMetadataImpl() {
        return metadata;
    }

    @Override
    public Path getOrigin() {
        return origin;
    }

    @Override
    public boolean isBuiltin() {
        return builtin;
    }

    @Override
    public String toString() {
        return metadata.toString();
    }
}
