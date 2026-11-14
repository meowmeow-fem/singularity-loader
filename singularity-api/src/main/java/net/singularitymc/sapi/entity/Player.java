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

    // ---- movement state (since API 4.2), as the server sees it; for an offline player all are false / 0 ----

    /** Flying as in creative mode (not gliding with an elytra). */
    boolean isFlying();

    /** Whether the player is allowed to fly (creative, spectator, or granted by a command). */
    boolean canFly();

    /** Gliding with an elytra. */
    boolean isGliding();

    /** In the spin of a riptide trident. */
    boolean isRiptiding();

    /** In water or lava. */
    boolean isInLiquid();

    /** On a ladder, vine, scaffolding or another climbable block. */
    boolean isClimbing();

    /** Riding a boat, minecart, horse or any other entity. */
    boolean isInVehicle();

    /**
     * Whether something solid is right under the player: a block or an entity one can stand on (boat, shulker),
     * within about half a block below the feet and a little around them. Worked out by the server from the world,
     * not taken from what the client says.
     */
    boolean isSupported();

    /**
     * The player's movement speed attribute: 0.1 walking, 0.13 sprinting; higher with Speed, Soul Speed and other
     * modifiers, lower with Slowness.
     */
    double movementSpeed();

    /** The level of a status effect (1 = level I), 0 without it; {@code id} like {@code "minecraft:jump_boost"}. */
    int effectLevel(String id);

    /** The round trip time to the client in milliseconds, as the server measured it last. */
    int ping();

    @Override
    default boolean isConsole() {
        return false;
    }

    @Override
    default java.util.Optional<Player> asPlayer() {
        return java.util.Optional.of(this);
    }
}
