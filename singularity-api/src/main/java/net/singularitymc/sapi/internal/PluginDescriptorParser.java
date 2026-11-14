package net.singularitymc.sapi.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionParseException;
import net.singularitymc.api.version.VersionRange;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.config.Toml;
import net.singularitymc.sapi.config.TomlException;
import net.singularitymc.sapi.permission.PermissionDefault;
import net.singularitymc.sapi.plugin.PluginDescriptor;

/** Reads {@code plugin.toml}. All problems are collected into one {@link PluginException}. */
public final class PluginDescriptorParser {
    public static final String FILE = "plugin.toml";
    /** Same rules as mod ids. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_\\-]{1,63}");
    private static final Pattern CLASS = Pattern.compile("([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)*[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Set<String> RESERVED = Set.of("minecraft", "java", "singularity-loader", "singularity-api", "singularity");
    private static final Set<String> KNOWN = Set.of("id", "name", "version", "main", "api", "description", "authors", "author",
            "depends", "softdepends", "loadbefore", "commands", "permissions", "website");

    private PluginDescriptorParser() {}

    public static PluginDescriptor parse(String text) throws PluginException {
        Map<String, Object> doc;
        try {
            doc = Toml.parse(text);
        } catch (TomlException e) {
            throw new PluginException(List.of(FILE + " is not valid TOML: " + e.getMessage()));
        }
        List<String> problems = new ArrayList<>();
        for (String k : doc.keySet()) {
            if (!KNOWN.contains(k)) {
                problems.add("unknown key '" + k + "'");
            }
        }

        String id = string(doc, "id", problems, true);
        if (id != null && !ID.matcher(id).matches()) {
            problems.add("id '" + id + "' must be 2-64 characters of a-z, 0-9, '-' or '_' and start with a letter");
        } else if (id != null && RESERVED.contains(id)) {
            problems.add("id '" + id + "' is reserved");
        }
        String name = string(doc, "name", problems, false);
        String versionText = string(doc, "version", problems, true);
        Version version = null;
        if (versionText != null) {
            try {
                version = Version.parse(versionText);
            } catch (VersionParseException e) {
                problems.add("version '" + versionText + "' is not a version (" + e.getMessage() + ")");
            }
        }
        String main = string(doc, "main", problems, true);
        if (main != null && !CLASS.matcher(main).matches()) {
            problems.add("main '" + main + "' is not a class name");
        }
        String apiText = string(doc, "api", problems, false);
        VersionRange api = VersionRange.ANY;
        if (apiText != null) {
            try {
                api = VersionRange.parse(apiText);
            } catch (VersionParseException e) {
                problems.add("api '" + apiText + "' is not a version range (" + e.getMessage() + ")");
            }
        }
        String description = string(doc, "description", problems, false);
        List<String> authors = strings(doc, "authors", problems);
        String author = string(doc, "author", problems, false);
        if (author != null) {
            authors = new ArrayList<>(authors);
            authors.add(0, author);
        }
        List<String> depends = ids(doc, "depends", problems);
        List<String> soft = ids(doc, "softdepends", problems);
        List<String> before = ids(doc, "loadbefore", problems);
        if (id != null && (depends.contains(id) || soft.contains(id) || before.contains(id))) {
            problems.add("the plugin names itself in depends / softdepends / loadbefore");
        }

        Map<String, CommandSpec> commands = new LinkedHashMap<>();
        Object cmds = doc.get("commands");
        if (cmds != null) {
            if (!(cmds instanceof Map<?, ?> m)) {
                problems.add("commands must be a table ([commands.<name>])");
            } else {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String cname = String.valueOf(e.getKey());
                    if (!(e.getValue() instanceof Map<?, ?> c)) {
                        problems.add("commands." + cname + " must be a table");
                        continue;
                    }
                    @SuppressWarnings("unchecked") Map<String, Object> cm = (Map<String, Object>) c;
                    List<String> local = new ArrayList<>();
                    for (String k : cm.keySet()) {
                        if (!Set.of("description", "usage", "aliases", "permission").contains(k)) {
                            local.add("unknown key '" + k + "'");
                        }
                    }
                    try {
                        CommandSpec spec = new CommandSpec(cname, strings(cm, "aliases", local), string(cm, "description", local, false),
                                string(cm, "usage", local, false), string(cm, "permission", local, false));
                        commands.put(spec.name(), spec);
                    } catch (IllegalArgumentException ex) {
                        local.add(ex.getMessage());
                    }
                    for (String p : local) {
                        problems.add("commands." + cname + ": " + p);
                    }
                }
            }
        }

        Map<String, PluginDescriptor.Permission> permissions = new LinkedHashMap<>();
        Object perms = doc.get("permissions");
        if (perms != null) {
            if (!(perms instanceof Map<?, ?> m)) {
                problems.add("permissions must be a table ([permissions.\"node\"])");
            } else {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String node = String.valueOf(e.getKey());
                    if (!(e.getValue() instanceof Map<?, ?> p)) {
                        problems.add("permissions." + node + " must be a table with default = \"true\" | \"false\" | \"op\" | \"not_op\"");
                        continue;
                    }
                    @SuppressWarnings("unchecked") Map<String, Object> pm = (Map<String, Object>) p;
                    if (!pm.containsKey("default") && !pm.containsKey("description") && !pm.isEmpty()) {
                        // [permissions.myplugin.hello] without quotes nests a table per dot
                        problems.add("permissions." + node + ": nodes with dots need quotes, e.g. [permissions.\"" + node + ".something\"]");
                        continue;
                    }
                    PermissionDefault def = PermissionDefault.OP;
                    Object d = pm.get("default");
                    if (d != null) {
                        try {
                            def = PermissionDefault.parse(String.valueOf(d));
                        } catch (IllegalArgumentException ex) {
                            problems.add("permissions." + node + ": " + ex.getMessage());
                        }
                    }
                    String desc = string(pm, "description", problems, false);
                    permissions.put(node, new PluginDescriptor.Permission(node, def, desc == null ? "" : desc));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new PluginException(problems);
        }
        return new PluginDescriptor(id, name == null ? id : name, version, main, api, description == null ? "" : description,
                authors, depends, soft, before, commands, permissions);
    }

    private static String string(Map<String, Object> m, String key, List<String> problems, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                problems.add("'" + key + "' is missing");
            }
            return null;
        }
        if (!(v instanceof String s)) {
            problems.add("'" + key + "' must be a string (in quotes)");
            return null;
        }
        if (required && s.isBlank()) {
            problems.add("'" + key + "' is empty");
            return null;
        }
        return s;
    }

    private static List<String> strings(Map<String, Object> m, String key, List<String> problems) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return List.of(s);
        }
        if (!(v instanceof List<?> l) || !l.stream().allMatch(o -> o instanceof String)) {
            problems.add("'" + key + "' must be a list of strings, e.g. [\"a\", \"b\"]");
            return List.of();
        }
        return l.stream().map(String.class::cast).toList();
    }

    private static List<String> ids(Map<String, Object> m, String key, List<String> problems) {
        List<String> out = strings(m, key, problems);
        for (String s : out) {
            if (!ID.matcher(s).matches()) {
                problems.add(key + ": '" + s + "' is not a plugin id");
            }
        }
        return out;
    }
}
