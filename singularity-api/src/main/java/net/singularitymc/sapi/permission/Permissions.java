package net.singularitymc.sapi.permission;

import java.util.Map;
import net.singularitymc.sapi.entity.Player;

/**
 * Permission nodes ({@code myplugin.command.hello}). Plugins declare theirs in {@code plugin.toml}; mods register them
 * here. Without a provider the default decides; nodes that were never registered are for operators only.
 */
public interface Permissions {
    void register(String node, PermissionDefault def, String description);

    PermissionDefault defaultOf(String node);

    /** Registered nodes and their defaults. */
    Map<String, PermissionDefault> all();

    /** Installs the provider (one at a time; {@code null} removes it). */
    void setProvider(PermissionProvider provider);

    boolean check(Player player, String node);
}
