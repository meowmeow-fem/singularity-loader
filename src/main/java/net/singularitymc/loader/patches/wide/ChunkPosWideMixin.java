package net.singularitymc.loader.patches.wide;

import net.minecraft.world.level.ChunkPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The 26.1 half of {@link ChunkPyramidWideMixin}: up to 26.1.2, {@code MAX_CHUNK_COORDINATE_VALUE} is a field of
 * {@code ChunkPos} itself, computed in its static initialiser from {@code BlockPos.MAX_HORIZONTAL_COORDINATE}; 26.2 moved
 * it to {@code ChunkPyramid}. Each game has exactly one of the two places, so both hooks are optional.
 */
@Mixin(ChunkPos.class)
abstract class ChunkPosWideMixin {
    @Redirect(method = "<clinit>", require = 0,
        at = @At(value = "FIELD", target = "Lnet/minecraft/core/BlockPos;MAX_HORIZONTAL_COORDINATE:I", opcode = Opcodes.GETSTATIC))
    private static int Singularity$maxHorizontalCoordinate() {
        return Integer.MAX_VALUE;
    }
}
