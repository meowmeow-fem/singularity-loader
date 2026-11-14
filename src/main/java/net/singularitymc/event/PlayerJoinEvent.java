package net.singularitymc.event;

import net.minecraft.server.level.ServerPlayer;

/** A player has finished joining the server. */
public final class PlayerJoinEvent {
    private final ServerPlayer player;

    public PlayerJoinEvent(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getPlayer() { return player; }
}
