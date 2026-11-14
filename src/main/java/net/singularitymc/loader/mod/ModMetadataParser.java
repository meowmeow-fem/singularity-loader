package net.singularitymc.loader.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.singularitymc.api.mod.ModDependency;
import net.singularitymc.api.mod.ModEnvironment;
import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionParseException;
import net.singularitymc.api.version.VersionRange;

/** Reads and validates {@code singularity.mod.json}. */
public final class ModMetadataParser {
    /** Name of the metadata file in the root of a mod jar. */
    public static final String FILE_NAME = "singularity.mod.json";

    static final int SUPPORTED_SCHEMA = 1;
    static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    static final Set<String> RESERVED_IDS = Set.of("singularity-loader", "minecraft", "java");

    private ModMetadataParser() {}

    /**
     * @param reader     the JSON text
     * @param sourceName where it came from, used in error messages (for example the jar's file name)
     * @throws ModMetadataException if the file is not valid
     */
    public static ModMetadataImpl parse(Reader reader, String sourceName) throws ModMetadataException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new ModMetadataException(prefix(sourceName) + "the top level must be a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new ModMetadataException(prefix(sourceName) + "invalid JSON: " + e.getMessage(), e);
        }
        return new Parser(root, sourceName).parse();
    }

    private static String prefix(String sourceName) {
        return FILE_NAME + " in " + sourceName + ": ";
    }

    private static final class Parser {
        private final JsonObject root;
        private final String source;

        Parser(JsonObject root, String source) {
            this.root = root;
            this.source = source;
        }

        private ModMetadataException error(String message) {
            return new ModMetadataException(prefix(source) + message);
        }

        ModMetadataImpl parse() throws ModMetadataException {
            if (root.has("schemaVersion")) {
                int schema = (int) number(root.get("schemaVersion"), "schemaVersion");
                if (schema != SUPPORTED_SCHEMA) {
                    throw error("\"schemaVersion\" is " + schema + " but this loader only understands " + SUPPORTED_SCHEMA + ". Is the mod built for a newer Singularity Loader?");
                }
            }
            String id = requiredString("id");
            if (!ID_PATTERN.matcher(id).matches()) {
                throw error("invalid mod id '" + id + "': use 2-64 characters, starting with a lower-case letter, then lower-case letters, digits, '-' or '_'");
            }
            if (RESERVED_IDS.contains(id)) {
                throw error("the mod id '" + id + "' is reserved");
            }
            String versionText = requiredString("version");
            Version version;
            try {
                version = Version.parse(versionText);
            } catch (VersionParseException e) {
                throw error("\"version\": " + e.getMessage());
            }
            String name = optionalString("name", id);
            String description = optionalString("description", "");
            List<String> authors = people("authors");
            List<String> contributors = people("contributors");
            List<String> license = stringOrArray("license");
            Map<String, String> contact = contact();
            String icon = root.has("icon") ? string(root.get("icon"), "icon") : null;
            ModEnvironment environment;
            try {
                environment = ModEnvironment.parse(root.has("environment") ? string(root.get("environment"), "environment") : null);
            } catch (IllegalArgumentException e) {
                throw error("\"environment\": " + e.getMessage());
            }
            Map<String, List<String>> entrypoints = entrypoints();
            List<MixinConfigRef> mixins = mixins();
            List<ModDependency> dependencies = new ArrayList<>();
            for (ModDependency.Kind kind : ModDependency.Kind.values()) {
                dependencies.addAll(dependencies(kind, id));
            }
            Map<String, Object> custom = root.has("custom") ? customObject(root.get("custom")) : Map.of();
            return new ModMetadataImpl(id, version, name, description, authors, contributors, license, contact, icon,
                    environment, entrypoints, mixins, dependencies, custom);
        }

        // ---- primitives ------------------------------------------------------------------

        private String string(JsonElement e, String field) throws ModMetadataException {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw error("\"" + field + "\" must be a string");
            }
            return e.getAsString();
        }

        private long number(JsonElement e, String field) throws ModMetadataException {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw error("\"" + field + "\" must be a number");
            }
            return e.getAsLong();
        }

        private String requiredString(String field) throws ModMetadataException {
            if (!root.has(field)) {
                throw error("missing required field \"" + field + "\"");
            }
            String s = string(root.get(field), field).trim();
            if (s.isEmpty()) {
                throw error("\"" + field + "\" must not be empty");
            }
            return s;
        }

        private String optionalString(String field, String fallback) throws ModMetadataException {
            return root.has(field) ? string(root.get(field), field) : fallback;
        }

        private List<String> stringOrArray(String field) throws ModMetadataException {
            List<String> out = new ArrayList<>();
            if (!root.has(field)) {
                return out;
            }
            JsonElement e = root.get(field);
            if (e.isJsonArray()) {
                for (JsonElement item : e.getAsJsonArray()) {
                    out.add(string(item, field));
                }
            } else {
                out.add(string(e, field));
            }
            return out;
        }

        // ---- sections --------------------------------------------------------------------

        private List<String> people(String field) throws ModMetadataException {
            List<String> out = new ArrayList<>();
            if (!root.has(field)) {
                return out;
            }
            JsonElement e = root.get(field);
            if (!e.isJsonArray()) {
                throw error("\"" + field + "\" must be an array");
            }
            for (JsonElement item : e.getAsJsonArray()) {
                if (item.isJsonObject() && item.getAsJsonObject().has("name")) {
                    out.add(string(item.getAsJsonObject().get("name"), field + "[].name"));
                } else {
                    out.add(string(item, field));
                }
            }
            return out;
        }

        private Map<String, String> contact() throws ModMetadataException {
            Map<String, String> out = new LinkedHashMap<>();
            if (!root.has("contact")) {
                return out;
            }
            JsonElement e = root.get("contact");
            if (!e.isJsonObject()) {
                throw error("\"contact\" must be an object");
            }
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                out.put(entry.getKey(), string(entry.getValue(), "contact." + entry.getKey()));
            }
            return out;
        }

        private Map<String, List<String>> entrypoints() throws ModMetadataException {
            Map<String, List<String>> out = new LinkedHashMap<>();
            if (!root.has("entrypoints")) {
                return out;
            }
            JsonElement e = root.get("entrypoints");
            if (!e.isJsonObject()) {
                throw error("\"entrypoints\" must be an object like {\"main\": [\"com.example.MyMod\"]}");
            }
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                String field = "entrypoints." + entry.getKey();
                List<String> classes = new ArrayList<>();
                if (entry.getValue().isJsonArray()) {
                    for (JsonElement item : entry.getValue().getAsJsonArray()) {
                        classes.add(string(item, field));
                    }
                } else {
                    classes.add(string(entry.getValue(), field));
                }
                out.put(entry.getKey(), List.copyOf(classes));
            }
            return out;
        }

        private List<MixinConfigRef> mixins() throws ModMetadataException {
            List<MixinConfigRef> out = new ArrayList<>();
            if (!root.has("mixins")) {
                return out;
            }
            JsonElement e = root.get("mixins");
            if (!e.isJsonArray()) {
                throw error("\"mixins\" must be an array of config names or {\"config\": ..., \"environment\": ...} objects");
            }
            for (JsonElement item : e.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    JsonObject o = item.getAsJsonObject();
                    if (!o.has("config")) {
                        throw error("a \"mixins\" object needs a \"config\" field");
                    }
                    String config = string(o.get("config"), "mixins[].config");
                    try {
                        out.add(new MixinConfigRef(config, ModEnvironment.parse(o.has("environment") ? string(o.get("environment"), "mixins[].environment") : null)));
                    } catch (IllegalArgumentException ex) {
                        throw error("\"mixins\" entry '" + config + "': " + ex.getMessage());
                    }
                } else {
                    out.add(new MixinConfigRef(string(item, "mixins"), ModEnvironment.UNIVERSAL));
                }
            }
            return out;
        }

        private List<ModDependency> dependencies(ModDependency.Kind kind, String ownId) throws ModMetadataException {
            List<ModDependency> out = new ArrayList<>();
            String key = kind.key();
            if (!root.has(key)) {
                return out;
            }
            JsonElement e = root.get(key);
            if (!e.isJsonObject()) {
                throw error("\"" + key + "\" must be an object like {\"othermod\": \">=1.0.0\"}");
            }
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                String other = entry.getKey();
                if (other.equals(ownId)) {
                    throw error("\"" + key + "\" lists the mod itself ('" + ownId + "')");
                }
                List<String> ranges = new ArrayList<>();
                if (entry.getValue().isJsonArray()) {
                    for (JsonElement item : entry.getValue().getAsJsonArray()) {
                        ranges.add(string(item, key + "." + other));
                    }
                } else {
                    ranges.add(string(entry.getValue(), key + "." + other));
                }
                try {
                    out.add(new ModDependency(kind, other, VersionRange.parse(String.join(" || ", ranges))));
                } catch (VersionParseException ex) {
                    throw error("\"" + key + "\" entry '" + other + "': " + ex.getMessage());
                }
            }
            return out;
        }

        private Map<String, Object> customObject(JsonElement e) throws ModMetadataException {
            if (!e.isJsonObject()) {
                throw error("\"custom\" must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = (Map<String, Object>) toJava(e);
            return converted;
        }

        private static Object toJava(JsonElement e) {
            if (e.isJsonNull()) {
                return null;
            }
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isBoolean()) {
                    return p.getAsBoolean();
                }
                if (p.isNumber()) {
                    return p.getAsNumber();
                }
                return p.getAsString();
            }
            if (e.isJsonArray()) {
                JsonArray array = e.getAsJsonArray();
                List<Object> list = new ArrayList<>(array.size());
                for (JsonElement item : array) {
                    list.add(toJava(item));
                }
                return java.util.Collections.unmodifiableList(list);
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), toJava(entry.getValue()));
            }
            return java.util.Collections.unmodifiableMap(map);
        }
    }
}
