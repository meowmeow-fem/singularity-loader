package net.singularitymc.event;

import net.minecraft.server.level.ServerPlayer;

/** A player is leaving the server (still in the world while listeners run). */
public final class PlayerLeaveEvent {
    private final ServerPlayer player;

    public PlayerLeaveEvent(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getPlayer() { return player; }
}
