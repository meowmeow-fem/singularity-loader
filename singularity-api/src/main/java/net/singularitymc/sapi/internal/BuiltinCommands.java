package net.singularitymc.sapi.internal;

import java.util.ArrayList;
import java.util.List;
import net.singularitymc.sapi.ApiContext;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.permission.PermissionDefault;
import net.singularitymc.sapi.plugin.PluginInfo;
import net.singularitymc.sapi.plugin.PluginState;

/** Commands of the API mod itself. */
public final class BuiltinCommands {
    public static final String PLUGINS_PERMISSION = "singularity.command.plugins";

    private BuiltinCommands() {}

    public static void register(ApiRuntime runtime, ApiContext api) {
        runtime.permissions().register(PLUGINS_PERMISSION, PermissionDefault.TRUE, "See the list of plugins");
        api.commands().register(CommandSpec.of("plugins").aliases("pl").description("Lists the server's plugins")
                .usage("/plugins [id]").permission(PLUGINS_PERMISSION), (sender, label, args) -> {
                    if (args.length > 1) {
                        return false;
                    }
                    if (args.length == 1) {
                        PluginInfo p = runtime.plugins().plugin(args[0]).orElse(null);
                        if (p == null) {
                            sender.sendMessage("§cNo plugin with id '" + args[0] + "'.");
                            return true;
                        }
                        sender.sendMessage(describe(p));
                        return true;
                    }
                    sender.sendMessage(list(runtime.plugins().plugins()));
                    return true;
                }, (sender, label, args) -> runtime.plugins().plugins().stream().map(PluginInfo::id).toList());
    }

    /** {@code Plugins (2): exampleplugin, other} with green = enabled, red = failed, grey = other states. */
    public static String list(List<PluginInfo> plugins) {
        if (plugins.isEmpty()) {
            return "Plugins (0): none. Put plugin jars into SingularityPlugins/.";
        }
        List<String> names = new ArrayList<>();
        for (PluginInfo p : plugins) {
            names.add(color(p.state()) + p.descriptor().name() + "§r");
        }
        return "Plugins (" + plugins.size() + "): " + String.join(", ", names);
    }

    static String describe(PluginInfo p) {
        StringBuilder sb = new StringBuilder();
        sb.append(color(p.state())).append(p.descriptor().name()).append("§r ").append(p.descriptor().version())
                .append(" (").append(p.id()).append(") - ").append(p.state().name().toLowerCase());
        if (!p.descriptor().description().isEmpty()) {
            sb.append("\n").append(p.descriptor().description());
        }
        if (!p.descriptor().authors().isEmpty()) {
            sb.append("\nBy ").append(String.join(", ", p.descriptor().authors()));
        }
        p.error().ifPresent(e -> sb.append("\n§cError: ").append(e));
        return sb.toString();
    }

    private static String color(PluginState s) {
        return switch (s) {
            case ENABLED -> "§a";
            case FAILED -> "§c";
            default -> "§7";
        };
    }
}
