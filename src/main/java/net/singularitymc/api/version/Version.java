package net.singularitymc.api.version;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic-version-like value: dot separated numbers, an optional pre-release tag and optional
 * build metadata (for example {@code 1.2.3}, {@code 26.3}, {@code 2.0.0-beta.1+build.5}).
 *
 * <p>Comparison follows SemVer 2.0 precedence: missing numeric components count as zero (so
 * {@code 1.2 == 1.2.0}), a pre-release sorts before its release, and build metadata is ignored.
 */
public final class Version implements Comparable<Version> {
    private static final Pattern PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");

    private final long[] components;
    private final String[] preRelease;
    private final String build;
    private final String raw;

    private Version(long[] components, String[] preRelease, String build, String raw) {
        this.components = components;
        this.preRelease = preRelease;
        this.build = build;
        this.raw = raw;
    }

    /**
     * Parses a version string.
     *
     * @throws VersionParseException if the string is not a valid version
     */
    public static Version parse(String text) {
        if (text == null) {
            throw new VersionParseException("Version must not be null");
        }
        String trimmed = text.trim();
        Matcher m = PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw new VersionParseException("'" + text + "' is not a valid version (expected e.g. 1.2.3, 26.3 or 1.0.0-beta.1)");
        }
        String[] parts = m.group(1).split("\\.");
        long[] nums = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 18) {
                throw new VersionParseException("Version component '" + parts[i] + "' is too large in '" + text + "'");
            }
            nums[i] = Long.parseLong(parts[i]);
        }
        String[] pre = m.group(2) == null ? new String[0] : m.group(2).split("\\.", -1);
        for (String id : pre) {
            if (id.isEmpty()) {
                throw new VersionParseException("Empty pre-release identifier in '" + text + "'");
            }
        }
        return new Version(nums, pre, m.group(3), trimmed);
    }

    /**
     * Like {@link #parse(String)} but never throws: unparseable text (for example a snapshot id such
     * as {@code 26w14a}) becomes {@code 0.0.0} carrying the original text as its display form.
     */
    public static Version parseLenient(String text) {
        try {
            return parse(text);
        } catch (VersionParseException e) {
            return new Version(new long[] {0}, new String[0], null, String.valueOf(text).trim());
        }
    }

    /** Returns numeric component {@code index}, or {@code 0} if the version has fewer components. */
    public long getComponent(int index) {
        return index < components.length ? components[index] : 0L;
    }

    public long getMajor() {
        return getComponent(0);
    }

    public long getMinor() {
        return getComponent(1);
    }

    public long getPatch() {
        return getComponent(2);
    }

    public int getComponentCount() {
        return components.length;
    }

    public boolean isPreRelease() {
        return preRelease.length > 0;
    }

    /** Build metadata (the part after {@code +}), or {@code null}. */
    public String getBuildMetadata() {
        return build;
    }

    @Override
    public int compareTo(Version other) {
        int len = Math.max(components.length, other.components.length);
        for (int i = 0; i < len; i++) {
            int c = Long.compare(getComponent(i), other.getComponent(i));
            if (c != 0) {
                return c;
            }
        }
        if (preRelease.length == 0 && other.preRelease.length == 0) {
            return 0;
        }
        if (preRelease.length == 0) {
            return 1;
        }
        if (other.preRelease.length == 0) {
            return -1;
        }
        int n = Math.min(preRelease.length, other.preRelease.length);
        for (int i = 0; i < n; i++) {
            int c = comparePreReleaseIdentifier(preRelease[i], other.preRelease[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(preRelease.length, other.preRelease.length);
    }

    private static int comparePreReleaseIdentifier(String a, String b) {
        boolean an = a.chars().allMatch(Character::isDigit);
        boolean bn = b.chars().allMatch(Character::isDigit);
        if (an && bn) {
            return new BigInteger(a).compareTo(new BigInteger(b));
        }
        if (an) {
            return -1;
        }
        if (bn) {
            return 1;
        }
        return a.compareTo(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Version v)) {
            return false;
        }
        return compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        int end = components.length;
        while (end > 0 && components[end - 1] == 0) {
            end--;
        }
        return 31 * Arrays.hashCode(Arrays.copyOf(components, end)) + Arrays.hashCode(preRelease);
    }

    /** The version as originally written. */
    @Override
    public String toString() {
        return raw;
    }
}
