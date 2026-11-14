package net.singularitymc.loader.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ServerConfig.toml}: the settings of a Singularity server, replacing {@code server.properties}.
 *
 * <p>The parser is deliberately lenient: {@code [section]} lines are only for humans and are never validated (so a
 * heading like {@code [gameplay & permissions]} is fine), every {@code key = value} line counts, and a later
 * duplicate wins. Values are strings ({@code "..."}), booleans or numbers. The keys use underscores
 * ({@code max_players}); {@link #toProperties()} turns them into the names Minecraft's own settings loader expects
 * ({@code max-players}, {@code rcon.port}, ...), so the game reads its settings from here.
 */
public final class ServerConfig {
    static final String TEMPLATE = "/net/singularitymc/loader/server/ServerConfig.default.toml";
    private static final Pattern LINE = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=\\s*(.*)$");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final Map<String, String> values;

    private ServerConfig(Map<String, String> values) {
        this.values = values;
    }

    /** Reads {@code file}, creating it from the built-in template (and {@code legacyProperties}, if given) when it does not exist. */
    public static ServerConfig loadOrCreate(Path file, Path legacyProperties) throws IOException {
        String text;
        if (Files.isRegularFile(file)) {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            text = template();
            if (legacyProperties != null && Files.isRegularFile(legacyProperties)) {
                text = importProperties(text, legacyProperties);
            }
        }
        // a management secret is mandatory for the management server; generate one the first time
        if (parse(text).getOrDefault("management_server_secret", "").isBlank()) {
            text = setValue(text, "management_server_secret", quote(randomSecret()));
        }
        if (!Files.isRegularFile(file) || !text.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        return new ServerConfig(parse(text));
    }

    public static ServerConfig of(String text) {
        return new ServerConfig(parse(text));
    }

    public String get(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public Map<String, String> values() {
        return java.util.Collections.unmodifiableMap(values);
    }

    /** The settings in {@code server.properties} form (what Minecraft's settings loader reads). */
    public Properties toProperties() {
        Properties p = new Properties();
        values.forEach((k, v) -> p.setProperty(propertyName(k), v));
        return p;
    }

    /** {@code max_players} -> {@code max-players}; the three dotted vanilla keys keep their dot. */
    static String propertyName(String key) {
        return switch (key) {
            case "rcon_port" -> "rcon.port";
            case "rcon_password" -> "rcon.password";
            case "query_port" -> "query.port";
            default -> key.replace('_', '-');
        };
    }

    /** The inverse of {@link #propertyName}. */
    static String tomlName(String property) {
        return switch (property) {
            case "rcon.port" -> "rcon_port";
            case "rcon.password" -> "rcon_password";
            case "query.port" -> "query_port";
            default -> property.replace('-', '_');
        };
    }

    // ---- parsing (pure) ---------------------------------------------------------------------------

    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("[")) {
                continue;
            }
            Matcher m = LINE.matcher(t);
            if (m.matches()) {
                out.put(m.group(1), parseValue(m.group(2)));
            }
        }
        return out;
    }

    static String parseValue(String raw) {
        String v = raw.strip();
        if (v.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) {
                    char n = v.charAt(++i);
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> n;
                    });
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (v.startsWith("'")) {
            int end = v.indexOf('\'', 1);
            return end < 0 ? v.substring(1) : v.substring(1, end);
        }
        int hash = v.indexOf('#');
        return (hash >= 0 ? v.substring(0, hash) : v).strip();
    }

    static String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** Replaces the value of {@code key} in the text, keeping comments and layout; appends the key if it is missing. */
    static String setValue(String text, String key, String tomlValue) {
        Pattern p = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=\\s*).*$");
        Matcher m = p.matcher(text);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + tomlValue));
        }
        return text + (text.endsWith("\n") ? "" : "\n") + key + " = " + tomlValue + "\n";
    }

    private static String importProperties(String template, Path legacy) throws IOException {
        Properties old = new Properties();
        try (var in = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            old.load(in);
        }
        Map<String, String> known = parse(template);
        String text = template;
        for (String name : old.stringPropertyNames()) {
            String key = tomlName(name);
            if (!known.containsKey(key)) {
                continue;
            }
            String value = old.getProperty(name).strip();
            String existing = known.get(key);
            boolean literal = value.equals("true") || value.equals("false") || value.matches("-?\\d+");
            boolean wasText = !(existing.equals("true") || existing.equals("false") || existing.matches("-?\\d+"));
            text = setValue(text, key, literal && !wasText ? value : (literal && existing.matches("true|false|-?\\d+") ? value : quote(value)));
        }
        return text;
    }

    static String template() throws IOException {
        try (InputStream in = ServerConfig.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IOException("Built-in ServerConfig template is missing from the loader jar.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String randomSecret() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
