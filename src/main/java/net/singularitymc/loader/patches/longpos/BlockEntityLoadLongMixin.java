package net.singularitymc.loader.patches.longpos;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * experimental_long_positions, 3.5.5: a block entity's saved x/y/z are ints; the chunk it is read for is remembered
 * first, so they are carried back into it (see {@link SerializableChunkDataLongMixin}).
 */
@Mixin(BlockEntity.class)
abstract class BlockEntityLoadLongMixin {
    @Inject(method = "getPosFromTag", at = @At("HEAD"))
    private static void Singularity$carryBlockEntity(ChunkPos base, CompoundTag tag, CallbackInfoReturnable<?> cir) {
        WidePacking.carryChunkKey(base.pack());
    }
}
