package net.singularitymc.api;

/**
 * The physical side the game is running on.
 *
 * <p>Singularity Loader currently supports {@link #SERVER} only; {@link #CLIENT} is reserved so
 * that mod metadata written today stays valid when client support lands.
 */
public enum Environment {
    CLIENT,
    SERVER;

    /** Lower-case id used in {@code singularity.mod.json}. */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
