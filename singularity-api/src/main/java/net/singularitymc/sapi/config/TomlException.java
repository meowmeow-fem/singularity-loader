package net.singularitymc.sapi.config;

/** A TOML document that cannot be read. The message names the line. */
public class TomlException extends RuntimeException {
    private final int line;

    public TomlException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
