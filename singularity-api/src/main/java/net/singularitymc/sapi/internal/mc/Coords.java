package net.singularitymc.sapi.internal.mc;

import net.minecraft.core.BlockPos;
import net.singularitymc.loader.util.ServerState;
import net.singularitymc.loader.util.WidePacking;
import net.singularitymc.sapi.world.BlockLocation;

/**
 * {@link BlockLocation} to and from Minecraft's {@link BlockPos} with true 64-bit X/Z.
 *
 * <p>This class is not widened, so {@code pos.getX()} would give it only the low 32 bits past 2^31. With
 * {@code experimental_long_positions} it goes through the packed long instead: {@code BlockPos.asLong()} and
 * {@code BlockPos.of(long)} keep their signatures and run the widened bodies, which pack with
 * {@code WidePacking.blockAsLong(long, int, long)}; {@code WidePacking.blockX64/blockZ64} read the true values back.
 */
public final class Coords {
    private Coords() {}

    public static BlockPos toBlockPos(BlockLocation l) {
        if (ServerState.longPositions()) {
            return BlockPos.of(WidePacking.blockAsLong(l.x(), l.y(), l.z()));
        }
        return new BlockPos(Math.toIntExact(l.x()), l.y(), Math.toIntExact(l.z()));
    }

    public static BlockLocation of(BlockPos pos) {
        if (ServerState.longPositions()) {
            long packed = pos.asLong();
            return new BlockLocation(WidePacking.blockX64(packed), WidePacking.blockY(packed), WidePacking.blockZ64(packed));
        }
        return new BlockLocation(pos.getX(), pos.getY(), pos.getZ());
    }
}
