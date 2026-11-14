package net.singularitymc.sapi.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.singularitymc.sapi.Logger;
import net.singularitymc.sapi.config.Config;
import net.singularitymc.sapi.config.Toml;
import net.singularitymc.sapi.config.TomlException;

/** A {@link Config} backed by a TOML file; loaded on first use, defaults copied from a jar resource. */
public final class ConfigImpl implements Config {
    private final Path file;
    private final Supplier<InputStream> defaults;
    private final Logger logger;
    private Map<String, Object> data;
    /** The file exists but could not be read: never overwrite it. */
    private boolean broken;

    public ConfigImpl(Path file, Supplier<InputStream> defaults, Logger logger) {
        this.file = file;
        this.defaults = defaults;
        this.logger = logger;
    }

    @Override
    public Path file() {
        return file;
    }

    private synchronized Map<String, Object> data() {
        if (data == null) {
            load();
        }
        return data;
    }

    private void load() {
        broken = false;
        try {
            if (!Files.exists(file) && defaults != null) {
                try (InputStream in = defaults.get()) {
                    if (in != null) {
                        Files.createDirectories(file.toAbsolutePath().getParent());
                        Files.write(file, in.readAllBytes());
                    }
                }
            }
            if (Files.exists(file)) {
                data = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
            } else {
                data = new LinkedHashMap<>();
            }
        } catch (TomlException e) {
            broken = true;
            data = new LinkedHashMap<>();
            logger.error("{} could not be read ({}); using defaults, the file is left as it is", file, e.getMessage());
        } catch (IOException e) {
            broken = true;
            data = new LinkedHashMap<>();
            logger.error("{} could not be read: {}", file, e.toString());
        }
    }

    @Override
    public synchronized boolean contains(String path) {
        return get(path) != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Object get(String path) {
        Object cur = data();
        if (path.isEmpty()) {
            return cur;
        }
        for (String k : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(k);
        }
        return cur;
    }

    @Override
    public String getString(String path, String def) {
        Object v = get(path);
        return v instanceof String || v instanceof Number || v instanceof Boolean ? v.toString() : def;
    }

    @Override
    public int getInt(String path, int def) {
        Object v = get(path);
        if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return (int) (long) l;
        }
        return def;
    }

    @Override
    public long getLong(String path, long def) {
        return get(path) instanceof Long l ? l : def;
    }

    @Override
    public double getDouble(String path, double def) {
        return get(path) instanceof Number n ? n.doubleValue() : def;
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return get(path) instanceof Boolean b ? b : def;
    }

    @Override
    public List<String> getStringList(String path) {
        Object v = get(path);
        if (!(v instanceof List<?> l)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : l) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @Override
    public Set<String> keys(String path) {
        return get(path) instanceof Map<?, ?> m ? new LinkedHashSet<>(cast(m).keySet()) : Set.of();
    }

    @Override
    public synchronized void set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = data();
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                if (value == null) {
                    return;
                }
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = cast((Map<?, ?>) next);
        }
        if (value == null) {
            cur.remove(parts[parts.length - 1]);
        } else {
            cur.put(parts[parts.length - 1], normalize(value));
        }
    }

    @Override
    public synchronized void addDefault(String path, Object value) {
        if (get(path) == null) {
            set(path, value);
        }
    }

    @Override
    public synchronized void save() {
        Map<String, Object> d = data();
        if (broken) {
            logger.warn("Not saving {}: it could not be read, fix or delete it first", file);
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, Toml.write(d), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save " + file, e);
        }
    }

    @Override
    public synchronized void reload() {
        data = null;
        data();
    }

    @Override
    public synchronized Map<String, Object> asMap() {
        return deepCopy(data());
    }

    private static Object normalize(Object v) {
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof Float f) {
            return f.doubleValue();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(normalize(o));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), normalize(val)));
            return out;
        }
        if (v instanceof String || v instanceof Long || v instanceof Double || v instanceof Boolean) {
            return v;
        }
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Map<String, Object> deepCopy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> sub ? deepCopy(cast(sub)) : v instanceof List<?> l ? new ArrayList<>(l) : v));
        return out;
    }
}
