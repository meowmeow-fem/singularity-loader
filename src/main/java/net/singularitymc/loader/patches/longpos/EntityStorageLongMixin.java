package net.singularitymc.loader.patches.longpos;

import java.util.Optional;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * experimental_long_positions, 3.5.5: the entities of a chunk are read with the chunk remembered for the carry (see
 * {@link SerializableChunkDataLongMixin}).
 */
@Mixin(EntityStorage.class)
abstract class EntityStorageLongMixin {
    @Inject(method = "lambda$loadEntities$0", at = @At("HEAD"))
    private void Singularity$carryEntities(ChunkPos pos, Optional<?> data, CallbackInfoReturnable<?> cir) {
        WidePacking.carryChunkKey(pos.pack());
    }
}
