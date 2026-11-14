package net.singularitymc.api.mod;

import java.util.Locale;
import net.singularitymc.api.Environment;

/** Where a mod (or one of its Mixin configs) wants to be loaded. */
public enum ModEnvironment {
    /** Loaded everywhere. This is the default. */
    UNIVERSAL,
    CLIENT,
    SERVER;

    /** Returns {@code true} if something declared with this environment applies to {@code env}. */
    public boolean matches(Environment env) {
        return this == UNIVERSAL || (this == CLIENT && env == Environment.CLIENT) || (this == SERVER && env == Environment.SERVER);
    }

    /** Parses {@code "*"}, {@code "client"} or {@code "server"} (case-insensitive); {@code null} means universal. */
    public static ModEnvironment parse(String value) {
        if (value == null) {
            return UNIVERSAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "*", "", "universal", "both" -> UNIVERSAL;
            case "client" -> CLIENT;
            case "server" -> SERVER;
            default -> throw new IllegalArgumentException("Unknown environment '" + value + "' (expected \"*\", \"client\" or \"server\")");
        };
    }
}
