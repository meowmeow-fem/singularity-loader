package net.singularitymc.api.version;

/** Thrown when a version or a version range cannot be parsed. */
public final class VersionParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public VersionParseException(String message) {
        super(message);
    }
}
