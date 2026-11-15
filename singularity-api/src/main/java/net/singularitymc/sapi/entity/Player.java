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

    // ---- identity and restriction (since API 4.4) ----

    /**
     * Whether this player proved to Mojang's session servers that they own the account: always on an online-mode
     * server, and on an offline-mode server for players a {@link net.singularitymc.sapi.login.LoginGate} sent
     * through Mojang. Such a player cannot be an impostor.
     */
    boolean isMojangVerified();

    /** The player's IP address as text, {@code ""} when unknown. */
    String address();

    /**
     * Shuts the player out of the game without kicking them: until {@link #unrestrict()}, the server ignores
     * everything their client sends except what the connection itself needs, answers to
     * {@link net.singularitymc.sapi.gui.Form}s and the commands named here (without the slash, e.g.
     * {@code "login"}). They cannot walk (falling is allowed), chat, use or drop items, open or click anything,
     * break or place blocks, or hit; they take no damage. What packets are let through is a short allow-list, so
     * anything not thought of is blocked. For "must log in first".
     */
    void restrict(java.util.Collection<String> allowedCommands);

    void unrestrict();

    boolean isRestricted();

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
