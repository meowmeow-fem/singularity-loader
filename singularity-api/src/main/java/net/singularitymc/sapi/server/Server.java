package net.singularitymc.sapi.server;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.world.World;

/** The running server. Call it from the server thread unless a method says otherwise. */
public interface Server {
    /** Players online now. Safe from any thread (a snapshot). */
    Collection<Player> players();

    Optional<Player> player(String name);

    Optional<Player> player(UUID uuid);

    List<World> worlds();

    /** A world by dimension id ({@code minecraft:the_nether}; {@code the_nether} works too). */
    Optional<World> world(String id);

    World overworld();

    /** Sends a message to every player and the console. */
    void broadcast(String message);

    /**
     * Runs a command as the console ({@code "say hi"}, with or without {@code /}).
     *
     * @return whether it was handed to the server (its result is reported to the console)
     */
    boolean dispatchCommand(String command);

    CommandSender console();

    /** Ticks since the server started. */
    long tickCount();

    /** Whether the calling thread is the server thread. */
    boolean isServerThread();

    /** The world's block coordinate limit ({@code max_coordinate}); past 2^31 with 64-bit positions. */
    long maxCoordinate();

    /** Whether {@code experimental_long_positions} is on (block X/Z beyond the int range are real places). */
    boolean longPositions();

    String minecraftVersion();

    String loaderVersion();
}
