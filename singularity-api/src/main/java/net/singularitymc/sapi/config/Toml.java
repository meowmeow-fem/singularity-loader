package net.singularitymc.sapi.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads and writes TOML (the format of {@code plugin.toml}, {@code config.toml} and {@code ServerConfig.toml}).
 *
 * <p>Supported: comments, tables {@code [a.b]} and arrays of tables {@code [[a]]}, bare / quoted / dotted keys, basic and
 * literal strings (also multi-line), integers (decimal, {@code 0x}, {@code 0o}, {@code 0b}, {@code _} separators),
 * floats ({@code inf}, {@code nan}), booleans, arrays and inline tables. Not supported: dates and times.
 *
 * <p>Values come back as {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@code List<Object>} and
 * {@code Map<String, Object>} (insertion ordered).
 */
public final class Toml {
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_\\-]+");

    private final String src;
    private int pos;
    private int line = 1;

    private Toml(String src) {
        this.src = src;
    }

    /** Parses a document. */
    public static Map<String, Object> parse(String text) {
        Toml t = new Toml(text.startsWith("﻿") ? text.substring(1) : text);
        return t.document();
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        java.util.Set<Map<String, Object>> explicitTables = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos >= src.length()) {
                return root;
            }
            char c = src.charAt(pos);
            if (c == '[') {
                boolean arrayOfTables = pos + 1 < src.length() && src.charAt(pos + 1) == '[';
                pos += arrayOfTables ? 2 : 1;
                skipSpaces();
                List<String> path = keyPath();
                skipSpaces();
                expect(']');
                if (arrayOfTables) {
                    expect(']');
                }
                endOfLine();
                if (arrayOfTables) {
                    Map<String, Object> parent = descend(root, path.subList(0, path.size() - 1));
                    String last = path.get(path.size() - 1);
                    Object existing = parent.get(last);
                    List<Object> list;
                    if (existing == null) {
                        list = new ArrayList<>();
                        parent.put(last, list);
                    } else if (existing instanceof List<?> l && (l.isEmpty() || l.get(0) instanceof Map)) {
                        @SuppressWarnings("unchecked") List<Object> cast = (List<Object>) l;
                        list = cast;
                    } else {
                        throw error("'" + String.join(".", path) + "' is already a value, not an array of tables");
                    }
                    current = new LinkedHashMap<>();
                    list.add(current);
                } else {
                    current = descend(root, path);
                    if (!explicitTables.add(current)) {
                        throw error("table [" + String.join(".", path) + "] is defined twice");
                    }
                }
            } else {
                keyValue(current);
                endOfLine();
            }
        }
    }

    /** The table at {@code path} below {@code from}, created where missing (the last element of an array of tables). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> descend(Map<String, Object> from, List<String> path) {
        Map<String, Object> t = from;
        for (String k : path) {
            Object v = t.get(k);
            if (v == null) {
                Map<String, Object> n = new LinkedHashMap<>();
                t.put(k, n);
                t = n;
            } else if (v instanceof Map<?, ?> m) {
                t = (Map<String, Object>) m;
            } else if (v instanceof List<?> l && !l.isEmpty() && l.get(l.size() - 1) instanceof Map<?, ?> m) {
                t = (Map<String, Object>) m;
            } else {
                throw error("'" + k + "' is already a value, not a table");
            }
        }
        return t;
    }

    private void keyValue(Map<String, Object> table) {
        List<String> path = keyPath();
        skipSpaces();
        expect('=');
        skipSpaces();
        Object value = value();
        Map<String, Object> target = descend(table, path.subList(0, path.size() - 1));
        String key = path.get(path.size() - 1);
        if (target.containsKey(key)) {
            throw error("key '" + String.join(".", path) + "' is defined twice");
        }
        target.put(key, value);
    }

    private List<String> keyPath() {
        List<String> parts = new ArrayList<>();
        while (true) {
            skipSpaces();
            parts.add(key());
            skipSpaces();
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
            } else {
                return parts;
            }
        }
    }

    private String key() {
        if (pos >= src.length()) {
            throw error("expected a key");
        }
        char c = src.charAt(pos);
        if (c == '"') {
            return basicString();
        }
        if (c == '\'') {
            return literalString();
        }
        int start = pos;
        while (pos < src.length() && isBareKeyChar(src.charAt(pos))) {
            pos++;
        }
        if (start == pos) {
            throw error("expected a key, found '" + c + "'");
        }
        return src.substring(start, pos);
    }

    private static boolean isBareKeyChar(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-';
    }

    private Object value() {
        if (pos >= src.length()) {
            throw error("expected a value");
        }
        char c = src.charAt(pos);
        if (c == '"') {
            return src.startsWith("\"\"\"", pos) ? multiLineBasic() : basicString();
        }
        if (c == '\'') {
            return src.startsWith("'''", pos) ? multiLineLiteral() : literalString();
        }
        if (c == '[') {
            return array();
        }
        if (c == '{') {
            return inlineTable();
        }
        if (src.startsWith("true", pos) && !isBareKeyChar(charAt(pos + 4))) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos) && !isBareKeyChar(charAt(pos + 5))) {
            pos += 5;
            return Boolean.FALSE;
        }
        return number();
    }

    private char charAt(int i) {
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "0123456789abcdefABCDEFxXoO_+-.ein".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty()) {
            throw error("expected a value, found '" + src.charAt(pos) + "'");
        }
        String n = raw.replace("_", "");
        try {
            switch (n) {
                case "inf", "+inf": return Double.POSITIVE_INFINITY;
                case "-inf": return Double.NEGATIVE_INFINITY;
                case "nan", "+nan", "-nan": return Double.NaN;
                default: break;
            }
            if (n.startsWith("0x")) return Long.parseLong(n.substring(2), 16);
            if (n.startsWith("0o")) return Long.parseLong(n.substring(2), 8);
            if (n.startsWith("0b")) return Long.parseLong(n.substring(2), 2);
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        } catch (NumberFormatException e) {
            throw error("'" + raw + "' is not a value (strings need quotes; dates are not supported)");
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments(true);
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skipWhitespaceAndComments(true);
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
            } else {
                skipWhitespaceAndComments(true);
                expect(']');
                return list;
            }
        }
    }

    private Map<String, Object> inlineTable() {
        expect('{');
        Map<String, Object> table = new LinkedHashMap<>();
        skipSpaces();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return table;
        }
        while (true) {
            skipSpaces();
            keyValue(table);
            skipSpaces();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
            } else {
                expect('}');
                return table;
            }
        }
    }

    private String basicString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length() || src.charAt(pos) == '\n') {
                throw error("string is not closed");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                escape(sb);
            } else {
                sb.append(c);
            }
        }
    }

    private void escape(StringBuilder sb) {
        if (pos >= src.length()) {
            throw error("string is not closed");
        }
        char e = src.charAt(pos++);
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'e' -> sb.append('\u001b');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case 'u' -> sb.appendCodePoint(hex(4));
            case 'U' -> sb.appendCodePoint(hex(8));
            default -> throw error("unknown escape \\" + e);
        }
    }

    private int hex(int digits) {
        if (pos + digits > src.length()) {
            throw error("unicode escape is too short");
        }
        try {
            int cp = Integer.parseInt(src.substring(pos, pos + digits), 16);
            pos += digits;
            return cp;
        } catch (NumberFormatException e) {
            throw error("bad unicode escape");
        }
    }

    private String literalString() {
        expect('\'');
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != '\'' && src.charAt(pos) != '\n') {
            pos++;
        }
        if (pos >= src.length() || src.charAt(pos) != '\'') {
            throw error("string is not closed");
        }
        return src.substring(start, pos++);
    }

    private String multiLineBasic() {
        pos += 3;
        skipFirstNewline();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("multi-line string is not closed");
            }
            if (src.startsWith("\"\"\"", pos)) {
                pos += 3;
                return sb.toString();
            }
            char c = src.charAt(pos++);
            if (c == '\n') {
                line++;
            }
            if (c == '\\') {
                // line-ending backslash: trim the newline and the whitespace after it
                int p = pos;
                while (p < src.length() && (src.charAt(p) == ' ' || src.charAt(p) == '\t' || src.charAt(p) == '\r')) p++;
                if (p < src.length() && src.charAt(p) == '\n') {
                    pos = p;
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                        if (src.charAt(pos) == '\n') line++;
                        pos++;
                    }
                } else {
                    escape(sb);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private String multiLineLiteral() {
        pos += 3;
        skipFirstNewline();
        int end = src.indexOf("'''", pos);
        if (end < 0) {
            throw error("multi-line string is not closed");
        }
        String s = src.substring(pos, end);
        line += (int) s.chars().filter(ch -> ch == '\n').count();
        pos = end + 3;
        return s;
    }

    private void skipFirstNewline() {
        if (src.startsWith("\r\n", pos)) {
            pos += 2;
            line++;
        } else if (src.startsWith("\n", pos)) {
            pos++;
            line++;
        }
    }

    private void skipSpaces() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private void skipWhitespaceAndComments(boolean newlines) {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
            } else if (c == '\n' && newlines) {
                pos++;
                line++;
            } else if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private void endOfLine() {
        skipWhitespaceAndComments(false);
        if (pos < src.length()) {
            if (src.charAt(pos) != '\n') {
                throw error("unexpected '" + src.charAt(pos) + "' after the value (one key = value per line)");
            }
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw error("expected '" + c + "'" + (pos < src.length() ? ", found '" + src.charAt(pos) + "'" : " at the end"));
        }
        pos++;
    }

    private TomlException error(String message) {
        return new TomlException(line, message);
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    /** Writes a document: plain values first, then the tables. */
    public static String write(Map<String, ?> document) {
        StringBuilder sb = new StringBuilder();
        writeTable(sb, "", document);
        String s = sb.toString();
        return s.startsWith("\n") ? s.substring(1) : s;
    }

    private static void writeTable(StringBuilder sb, String prefix, Map<String, ?> table) {
        List<Map.Entry<String, ?>> tables = new ArrayList<>();
        for (Map.Entry<String, ?> e : table.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Map<?, ?> || isArrayOfTables(v)) {
                tables.add(e);
            } else {
                sb.append(writeKey(e.getKey())).append(" = ").append(writeValue(v)).append('\n');
            }
        }
        for (Map.Entry<String, ?> e : tables) {
            String path = prefix.isEmpty() ? writeKey(e.getKey()) : prefix + "." + writeKey(e.getKey());
            if (e.getValue() instanceof Map<?, ?> m) {
                sb.append('\n').append('[').append(path).append("]\n");
                writeTable(sb, path, cast(m));
            } else {
                for (Object item : (List<?>) e.getValue()) {
                    sb.append('\n').append("[[").append(path).append("]]\n");
                    writeTable(sb, path, cast((Map<?, ?>) item));
                }
            }
        }
    }

    private static boolean isArrayOfTables(Object v) {
        return v instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(i -> i instanceof Map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> cast(Map<?, ?> m) {
        return (Map<String, ?>) m;
    }

    static String writeKey(String key) {
        return BARE_KEY.matcher(key).matches() ? key : quote(key);
    }

    static String writeValue(Object v) {
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return v.toString();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) return "nan";
            if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
            String s = Double.toString(d);
            return s.contains(".") || s.contains("E") ? s.replace("E", "e") : s + ".0";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(writeValue(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(writeKey(String.valueOf(e.getKey()))).append(" = ").append(writeValue(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (v instanceof Enum<?> e) {
            return quote(e.name().toLowerCase(Locale.ROOT));
        }
        return quote(String.valueOf(v));
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
