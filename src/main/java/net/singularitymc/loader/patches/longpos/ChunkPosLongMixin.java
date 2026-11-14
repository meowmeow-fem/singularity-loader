package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.level.ChunkPos;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * experimental_long_positions, 3.5.4 (docs/ROADMAP_64BIT.md): chunk keys go through the wide packing, whose int
 * methods are vanilla and whose 64-bit twins ({@code asm/PositionWidening}) give every chunk its own key.
 */
@Mixin(ChunkPos.class)
abstract class ChunkPosLongMixin {
    /**
     * @author Singularity Loader
     * @reason 64-bit chunk keys
     */
    @Overwrite
    public static long pack(int x, int z) {
        return WidePacking.chunkPack(x, z);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #pack(int, int)}
     */
    @Overwrite
    public static int getX(long key) {
        return WidePacking.chunkX(key);
    }

    /**
     * @author Singularity Loader
     * @reason see {@link #pack(int, int)}
     */
    @Overwrite
    public static int getZ(long key) {
        return WidePacking.chunkZ(key);
    }

    /**
     * @author Singularity Loader
     * @reason vanilla decodes the key inline ({@code (int) key, (int) (key >> 32)})
     */
    @Overwrite
    public static ChunkPos unpack(long key) {
        return new ChunkPos(WidePacking.chunkX(key), WidePacking.chunkZ(key));
    }

    /**
     * @author Singularity Loader
     * @reason vanilla limits chunks to +-2^31 / 16 blocks' worth; the world limit and border bound the world instead
     */
    @Overwrite
    public static boolean isValid(int x, int z) {
        return true;
    }
}
