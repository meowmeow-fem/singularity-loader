package net.singularitymc.sapi.entity;

import net.singularitymc.sapi.command.CommandSender;

/**
 * A player. The object stays valid across respawns and world changes (it is looked up by UUID); after the player
 * leaves, {@link #isOnline()} is {@code false} and the other methods throw {@link IllegalStateException}.
 */
public interface Player extends Entity, CommandSender {
    boolean isOnline();

    GameMode gameMode();

    void setGameMode(GameMode mode);

    /** Disconnects the player with a reason. */
    void kick(String reason);

    @Override
    default boolean isConsole() {
        return false;
    }

    @Override
    default java.util.Optional<Player> asPlayer() {
        return java.util.Optional.of(this);
    }
}
