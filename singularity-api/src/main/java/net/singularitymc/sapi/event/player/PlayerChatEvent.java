package net.singularitymc.sapi.event.player;

import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Player;

/**
 * A player sent a chat message (not a command). Cancel it and nobody receives it. The text cannot be changed: chat
 * messages are signed by the client.
 */
public final class PlayerChatEvent extends CancellableEvent {
    private final Player player;
    private final String message;

    public PlayerChatEvent(Player player, String message) {
        this.player = player;
        this.message = message;
    }

    public Player player() {
        return player;
    }

    public String message() {
        return message;
    }
}
