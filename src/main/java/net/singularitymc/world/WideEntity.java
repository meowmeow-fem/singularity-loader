package net.singularitymc.world;

import net.minecraft.world.entity.Entity;

/**
 * experimental_long_positions, 3.5.8: long twins of {@link Entity}'s int block coordinates, for widened code (see
 * {@code PositionWidening.TWINS}). Entities are not widened yet; their {@code getBlockX()} hands out the low 32 bits,
 * and widened code that turns it straight into a double ({@code getBlockX() + 0.5}) never carries it back: an
 * enderman at 35,000,000,000,000 traced a line to 311,504,852 to pick up a block, and the server loaded every chunk
 * on the way. The twins floor the entity's true position.
 */
public final class WideEntity {
    private WideEntity() {
    }

    public static long blockX(Entity entity) {
        return (long) Math.floor(entity.getX());
    }

    public static long blockZ(Entity entity) {
        return (long) Math.floor(entity.getZ());
    }
}
