package net.singularitymc.api.version;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A version requirement as written in the {@code depends} / {@code breaks} sections of
 * {@code singularity.mod.json}.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code *} or an empty string: any version</li>
 *   <li>{@code >=1.2.3}, {@code >1.2.3}, {@code <=1.2.3}, {@code <1.2.3}, {@code =1.2.3} (or plain {@code 1.2.3})</li>
 *   <li>{@code ^1.2.3} (compatible: {@code >=1.2.3 <2.0.0}; {@code ^0.2.3} means {@code >=0.2.3 <0.3.0})</li>
 *   <li>{@code ~1.2.3} (patch updates: {@code >=1.2.3 <1.3.0})</li>
 *   <li>wildcards: {@code 1.x}, {@code 1.2.x}, {@code 1.2.*}</li>
 *   <li>space separated comparators are AND-ed: {@code >=1.2 <2.0}</li>
 *   <li>{@code ||} separates alternatives: {@code ^1.0 || ^2.0}</li>
 * </ul>
 */
public final class VersionRange {
    /** A range that matches every version. */
    public static final VersionRange ANY = new VersionRange("*", List.of(List.of()));

    private enum Op { GE, GT, LE, LT, EQ }

    private record Comparator(Op op, Version version) {
        boolean test(Version v) {
            int c = v.compareTo(version);
            return switch (op) {
                case GE -> c >= 0;
                case GT -> c > 0;
                case LE -> c <= 0;
                case LT -> c < 0;
                case EQ -> c == 0;
            };
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\s*(>=|<=|>|<|=|\\^|~)?\\s*([0-9xX*][^\\s|]*)");

    private final String source;
    /** Outer list = alternatives (OR); inner list = comparators that must all match (AND). */
    private final List<List<Comparator>> alternatives;

    private VersionRange(String source, List<List<Comparator>> alternatives) {
        this.source = source;
        this.alternatives = alternatives;
    }

    /**
     * Parses a range expression.
     *
     * @throws VersionParseException if the expression is malformed
     */
    public static VersionRange parse(String text) {
        if (text == null) {
            throw new VersionParseException("Version range must not be null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.equals("*")) {
            return ANY;
        }
        List<List<Comparator>> alternatives = new ArrayList<>();
        for (String alt : trimmed.split("\\|\\|", -1)) {
            alternatives.add(parseAlternative(alt, text));
        }
        return new VersionRange(trimmed, alternatives);
    }

    private static List<Comparator> parseAlternative(String alt, String full) {
        String s = alt.trim();
        if (s.isEmpty() || s.equals("*")) {
            return List.of();
        }
        List<Comparator> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        int pos = 0;
        while (pos < s.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new VersionParseException("Cannot parse version range '" + full + "' near '" + s.substring(pos) + "'");
            }
            addComparator(out, m.group(1), m.group(2), full);
            pos = m.end();
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
        return out;
    }

    private static void addComparator(List<Comparator> out, String op, String operand, String full) {
        boolean wildcard = operand.matches("(?i).*[x*].*");
        if (wildcard) {
            if (op != null && !op.equals("=")) {
                throw new VersionParseException("Wildcards cannot be combined with '" + op + "' in '" + full + "'");
            }
            String[] parts = operand.split("\\.");
            List<String> fixed = new ArrayList<>();
            for (String p : parts) {
                if (p.equalsIgnoreCase("x") || p.equals("*")) {
                    break;
                }
                fixed.add(p);
            }
            if (fixed.isEmpty()) {
                return; // "x" / "*" => any
            }
            Version lower = Version.parse(String.join(".", fixed));
            out.add(new Comparator(Op.GE, lower));
            out.add(new Comparator(Op.LT, bump(lower, fixed.size() - 1)));
            return;
        }
        Version v = Version.parse(operand);
        if (op == null || op.equals("=")) {
            out.add(new Comparator(Op.EQ, v));
        } else if (op.equals(">=")) {
            out.add(new Comparator(Op.GE, v));
        } else if (op.equals(">")) {
            out.add(new Comparator(Op.GT, v));
        } else if (op.equals("<=")) {
            out.add(new Comparator(Op.LE, v));
        } else if (op.equals("<")) {
            out.add(new Comparator(Op.LT, v));
        } else if (op.equals("~")) {
            out.add(new Comparator(Op.GE, v));
            out.add(new Comparator(Op.LT, bump(v, v.getComponentCount() <= 1 ? 0 : 1)));
        } else { // ^
            out.add(new Comparator(Op.GE, v));
            int idx = 0;
            while (idx < 2 && v.getComponent(idx) == 0) {
                idx++;
            }
            out.add(new Comparator(Op.LT, bump(v, idx)));
        }
    }

    /** Returns a version with component {@code index} incremented and everything after it dropped. */
    private static Version bump(Version v, int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= index; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(i == index ? v.getComponent(i) + 1 : v.getComponent(i));
        }
        return Version.parse(sb.toString());
    }

    /** Returns {@code true} if {@code version} satisfies this range. */
    public boolean matches(Version version) {
        for (List<Comparator> and : alternatives) {
            boolean ok = true;
            for (Comparator c : and) {
                if (!c.test(version)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    /** The range as written. */
    @Override
    public String toString() {
        return source;
    }
}
