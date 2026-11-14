package net.singularitymc.sapi;

/** A logger that writes to the server log under the mod's or plugin's name. Messages use {@code {}} placeholders. */
public interface Logger {
    void debug(String message, Object... args);

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    /** An error with its stack trace. */
    void error(String message, Throwable error);
}
