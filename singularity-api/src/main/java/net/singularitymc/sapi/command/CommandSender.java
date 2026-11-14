package net.singularitymc.sapi.command;

import java.util.Optional;
import net.singularitymc.sapi.entity.Player;

/** Who ran a command: a player, the console, or a command block / function. */
public interface CommandSender {
    String name();

    /** Sends a message. {@code &} colour codes are not translated; use {@link net.singularitymc.sapi.text.Text#colorize}. */
    void sendMessage(String message);

    /**
     * Whether the sender has a permission node. The console always has every node. For players the
     * {@link net.singularitymc.sapi.permission.PermissionProvider} decides first, then the node's default (unregistered
     * nodes: operators only).
     */
    boolean hasPermission(String node);

    /** Operator (permission level 2, "game master", or higher). */
    boolean isOp();

    boolean isConsole();

    default Optional<Player> asPlayer() {
        return this instanceof Player p ? Optional.of(p) : Optional.empty();
    }
}
