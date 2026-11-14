package net.singularitymc.loader.patches.wide;

import net.minecraft.core.BlockPos;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md): lossless {@code BlockPos} packing. Only registered when
 * {@code max_coordinate} reaches past 2^25; inside +-2^25 the values are the same as vanilla.
 */
@Mixin(BlockPos.class)
abstract class BlockPosWideMixin {
    /**
     * @author Singularity Loader
     * @reason 26-bit X/Z fields alias beyond 2^25 blocks
     */
    @Overwrite
    public static long asLong(int x, int y, int z) {
        return WidePacking.blockAsLong(x, y, z);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int getX(long packed) {
        return WidePacking.blockX(packed);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int getY(long packed) {
        return WidePacking.blockY(packed);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #asLong(int, int, int)}
     */
    @Overwrite
    public static int getZ(long packed) {
        return WidePacking.blockZ(packed);
    }

    /**
     * @author Singularity Loader
     * @reason vanilla clears the low Y bits with {@code packed & -16}, which assumes the vanilla layout
     */
    @Overwrite
    public static long getFlatIndex(long packed) {
        return WidePacking.blockFlatIndex(packed);
    }
}
