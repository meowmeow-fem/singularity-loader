package net.singularitymc.sapi.permission;

import net.singularitymc.sapi.entity.Player;

/** A permissions mod or plugin: decides nodes per player. */
@FunctionalInterface
public interface PermissionProvider {
    /** {@code TRUE}/{@code FALSE} to decide, {@code null} to fall back to the node's default. */
    Boolean check(Player player, String node);
}
