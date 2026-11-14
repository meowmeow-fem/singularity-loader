package net.singularitymc.api.mod;

import java.nio.file.Path;

/** A loaded mod (or one of the built-in pseudo mods {@code singularity-loader}, {@code minecraft} and {@code java}). */
public interface ModContainer {
    ModMetadata getMetadata();

    /** The jar or directory the mod was loaded from. */
    Path getOrigin();

    /** {@code true} for the pseudo mods provided by the loader itself. */
    boolean isBuiltin();

    default String getId() {
        return getMetadata().getId();
    }
}
