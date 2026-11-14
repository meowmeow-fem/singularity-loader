package net.singularitymc.api.mod;

import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionRange;

/**
 * A relationship from one mod to another, declared in {@code singularity.mod.json}.
 *
 * @param kind  how strongly the relationship is enforced
 * @param modId the id of the other mod
 * @param range which versions of the other mod the relationship applies to
 */
public record ModDependency(Kind kind, String modId, VersionRange range) {

    public enum Kind {
        /** The other mod must be present, and match the range, or the game will not start. */
        DEPENDS("depends"),
        /** The mod works without the other one, but a warning is logged if it is missing. */
        RECOMMENDS("recommends"),
        /** Purely informational. */
        SUGGESTS("suggests"),
        /** A warning is logged if the other mod is present (and matches the range). */
        CONFLICTS("conflicts"),
        /** The game will not start if the other mod is present (and matches the range). */
        BREAKS("breaks");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        /** The JSON key used for this kind of relationship. */
        public String key() {
            return key;
        }
    }

    /** Returns {@code true} if {@code version} satisfies this dependency's range. */
    public boolean matches(Version version) {
        return range.matches(version);
    }
}
