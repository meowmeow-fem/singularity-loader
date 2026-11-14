package net.singularitymc.loader.mod;

/** A mod's entrypoint could not be created or threw an exception. */
public final class EntrypointException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final String modId;

    public EntrypointException(String modId, String message, Throwable cause) {
        super(message, cause);
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }
}
