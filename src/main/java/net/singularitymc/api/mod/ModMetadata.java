package net.singularitymc.api.mod;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.singularitymc.api.version.Version;

/** Read-only view of a mod's {@code singularity.mod.json}. */
public interface ModMetadata {
    /** Unique mod id: lower-case letters, digits, {@code -} and {@code _}; 2 to 64 characters. */
    String getId();

    Version getVersion();

    /** Human readable name; defaults to the id. */
    String getName();

    /** Short description; empty if none was given. */
    String getDescription();

    List<String> getAuthors();

    List<String> getContributors();

    /** SPDX license identifiers (or free text). */
    List<String> getLicense();

    /** Links such as {@code homepage}, {@code sources} and {@code issues}. */
    Map<String, String> getContact();

    /** Path of the mod's icon inside its jar, if declared. */
    Optional<String> getIcon();

    ModEnvironment getEnvironment();

    /** All {@code depends}, {@code recommends}, {@code suggests}, {@code conflicts} and {@code breaks} entries. */
    Collection<ModDependency> getDependencies();

    /** The {@code custom} object as plain Java values ({@link Map}, {@link List}, {@link String}, {@link Number}, {@link Boolean}). */
    Map<String, Object> getCustomValues();
}
