package net.singularitymc.sapi.event.player;

import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.world.Location;

/**
 * The server handled one movement packet of a player (since API 4.2). Posted on the server thread after the server
 * applied the packet, once per packet: a client sends one per client tick while it moves or turns, so a client that
 * caught up after a lag spike posts several in one server tick. {@link #from()} and {@link #to()} are the player's
 * position before and after the packet; they are equal when the player only turned or the server ignored the packet.
 * Teleports (commands, ender pearls, plugins) are not movement and post nothing.
 *
 * <p>Cancelling puts the player back at {@link #from()}.
 */
public final class PlayerMoveEvent extends CancellableEvent {
    private final Player player;
    private final Location from;
    private final Location to;

    public PlayerMoveEvent(Player player, Location from, Location to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public Player player() {
        return player;
    }

    public Location from() {
        return from;
    }

    public Location to() {
        return to;
    }

    /** Whether the position changed (and not only the direction the player looks in). */
    public boolean hasMoved() {
        return from.x() != to.x() || from.y() != to.y() || from.z() != to.z();
    }
}
