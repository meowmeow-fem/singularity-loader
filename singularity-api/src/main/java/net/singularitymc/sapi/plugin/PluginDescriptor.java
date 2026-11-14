package net.singularitymc.sapi.plugin;

import java.util.List;
import java.util.Map;
import net.singularitymc.api.version.Version;
import net.singularitymc.api.version.VersionRange;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.permission.PermissionDefault;

/**
 * The contents of {@code plugin.toml}.
 *
 * <pre>{@code
 * id = "exampleplugin"
 * name = "Example Plugin"
 * version = "1.0.0"
 * main = "com.example.ExamplePlugin"
 * api = ">=4.0.0"
 * description = "What it does."
 * authors = ["You"]
 * depends = []          # plugin ids that must be there; they are enabled first
 * softdepends = []      # enabled first if they are there
 * loadbefore = []       # these are enabled after this one
 *
 * [commands.hello]
 * description = "Says hello"
 * usage = "/hello [name]"
 * aliases = ["hi"]
 * permission = "exampleplugin.hello"
 *
 * [permissions."exampleplugin.hello"]
 * default = "true"      # true | false | op | not_op
 * description = "Use /hello"
 * }</pre>
 */
public record PluginDescriptor(String id, String name, Version version, String main, VersionRange api, String description,
                               List<String> authors, List<String> depends, List<String> softDepends, List<String> loadBefore,
                               Map<String, CommandSpec> commands, Map<String, Permission> permissions) {

    public PluginDescriptor {
        authors = List.copyOf(authors);
        depends = List.copyOf(depends);
        softDepends = List.copyOf(softDepends);
        loadBefore = List.copyOf(loadBefore);
        commands = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(commands));
        permissions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(permissions));
    }

    /** A permission node declared in the descriptor. */
    public record Permission(String node, PermissionDefault def, String description) {}

    @Override
    public String toString() {
        return name + " " + version + " (" + id + ")";
    }
}
