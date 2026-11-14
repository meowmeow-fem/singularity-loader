package net.singularitymc.loader.patches.wide;

import net.minecraft.world.level.chunk.status.ChunkPyramid;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md). {@code MAX_CHUNK_COORDINATE_VALUE} (used by
 * {@code ChunkPos.isValid}, which guards chunk creation and {@code Level}'s bounds check) is derived from
 * {@code BlockPos.MAX_HORIZONTAL_COORDINATE}, i.e. from the 26-bit packing. With wide packing every int fits.
 *
 * <p>The field is in {@code ChunkPyramid} from 26.2 on; 26.1 to 26.1.2 have it in {@code ChunkPos}
 * ({@link ChunkPosWideMixin}). Both hooks are optional, since each game has only one of them.
 */
@Mixin(ChunkPyramid.class)
abstract class ChunkPyramidWideMixin {
    @Redirect(method = "<clinit>", require = 0, at = @At(value = "FIELD", target = "Lnet/minecraft/core/BlockPos;MAX_HORIZONTAL_COORDINATE:I", opcode = Opcodes.GETSTATIC))
    private static int Singularity$maxHorizontalCoordinate() {
        return Integer.MAX_VALUE;
    }
}
