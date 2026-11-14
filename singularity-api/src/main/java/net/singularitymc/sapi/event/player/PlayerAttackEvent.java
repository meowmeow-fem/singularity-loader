package net.singularitymc.sapi.event.player;

import net.singularitymc.api.event.CancellableEvent;
import net.singularitymc.sapi.entity.Entity;
import net.singularitymc.sapi.entity.Player;

/**
 * A player hits an entity (since API 4.2). Posted on the server thread before any damage is worked out; cancelling
 * drops the hit.
 */
public final class PlayerAttackEvent extends CancellableEvent {
    private final Player player;
    private final Entity target;
    private final double distance;
    private final double reach;

    public PlayerAttackEvent(Player player, Entity target, double distance, double reach) {
        this.player = player;
        this.target = target;
        this.distance = distance;
        this.reach = reach;
    }

    public Player player() {
        return player;
    }

    public Entity target() {
        return target;
    }

    /** Blocks from the player's eyes to the nearest point of the target's hit box, as the server sees both now. */
    public double distance() {
        return distance;
    }

    /** How far this player may reach entities (3 blocks in survival; the game's entity interaction range). */
    public double reach() {
        return reach;
    }
}
