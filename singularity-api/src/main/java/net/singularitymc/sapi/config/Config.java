package net.singularitymc.sapi.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A TOML config file. Paths are dotted: {@code "messages.greeting"} is key {@code greeting} in table {@code [messages]}.
 *
 * <p>For a plugin it is {@code SingularityPlugins/<id>/config.toml}; if the file does not exist, the {@code config.toml}
 * in the root of the plugin jar is copied there first. For a mod it is {@code config/<id>/config.toml}, with defaults
 * from {@code <id>.config.toml} in the mod jar. {@link #addDefault} adds values that are missing (then call
 * {@link #save}).
 *
 * <p>Getters convert where it makes sense (an integer for {@code getDouble}, a number for {@code getString}) and
 * return the given default when the value is missing or of another type.
 */
public interface Config {
    Path file();

    boolean contains(String path);

    /** The raw value: String, Long, Double, Boolean, List or Map; {@code null} if missing. */
    Object get(String path);

    String getString(String path, String def);

    int getInt(String path, int def);

    long getLong(String path, long def);

    double getDouble(String path, double def);

    boolean getBoolean(String path, boolean def);

    /** A list of strings (other elements are converted with {@code toString}); empty if missing. */
    List<String> getStringList(String path);

    /** Keys directly inside a table ({@code ""} for the top level); empty if it is not a table. */
    Set<String> keys(String path);

    /** Sets a value (int/long, float/double, String, Boolean, List, Map); {@code null} removes it. */
    void set(String path, Object value);

    /** Sets the value only if the path is missing. */
    void addDefault(String path, Object value);

    /** Writes the file (comments of the original file are not kept). */
    void save();

    /** Reads the file again; unsaved changes are lost. */
    void reload();

    /** A copy of the whole document. */
    Map<String, Object> asMap();
}
