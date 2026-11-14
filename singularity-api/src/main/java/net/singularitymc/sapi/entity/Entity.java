package net.singularitymc.sapi.entity;

import java.util.Optional;
import java.util.UUID;
import net.singularitymc.sapi.world.BlockLocation;
import net.singularitymc.sapi.world.Location;
import net.singularitymc.sapi.world.World;

/** An entity in a world. Read and change it on the server thread. */
public interface Entity {
    UUID uuid();

    /** The entity type id, e.g. {@code minecraft:zombie}. */
    String type();

    /** The display name as plain text. */
    String name();

    /** Its position (true 64-bit coordinates, as doubles). */
    Location location();

    /** The block it stands in (exact 64-bit X/Z). */
    default BlockLocation blockLocation() {
        return location().block();
    }

    World world();

    /**
     * Moves it, also into another world.
     *
     * @return whether it was moved
     */
    boolean teleport(Location to);

    /** Whether it still exists (not removed or dead; for a player: online). */
    boolean isValid();

    default Optional<Player> asPlayer() {
        return this instanceof Player p ? Optional.of(p) : Optional.empty();
    }
}
