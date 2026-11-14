package net.singularitymc.loader.mod;

/** A {@code singularity.mod.json} is missing, unreadable or invalid. */
public final class ModMetadataException extends Exception {
    private static final long serialVersionUID = 1L;

    public ModMetadataException(String message) {
        super(message);
    }

    public ModMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
