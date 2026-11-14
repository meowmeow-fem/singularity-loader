package net.singularitymc.sapi.world;

import java.util.List;
import net.singularitymc.sapi.entity.Player;

/**
 * A loaded dimension. Block access must happen on the server thread (use the scheduler from async code).
 * Positions are 64-bit: {@link BlockLocation} reaches past 2^31 when the server runs with
 * {@code experimental_long_positions}.
 */
public interface World {
    /** The dimension id, e.g. {@code minecraft:overworld}. */
    String id();

    /** The block id at a position, e.g. {@code minecraft:stone}. Loads (and if needed generates) the chunk. */
    String getBlock(BlockLocation pos);

    /**
     * Sets the default state of block {@code blockId} at a position (neighbours are updated, clients are told).
     *
     * @return whether the block changed
     * @throws IllegalArgumentException if there is no block with that id
     */
    boolean setBlock(BlockLocation pos, String blockId);

    /** Whether the chunk at this position is loaded. */
    boolean isLoaded(BlockLocation pos);

    /** Lowest and highest buildable Y. */
    int minY();

    int maxY();

    long seed();

    List<Player> players();
}
