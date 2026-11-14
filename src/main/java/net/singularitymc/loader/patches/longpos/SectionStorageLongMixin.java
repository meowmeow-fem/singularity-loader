package net.singularitymc.loader.patches.longpos;

import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.Function;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * experimental_long_positions, 3.5.5: POIs are decoded on a background thread; the chunk is remembered there for the
 * carry (see {@link SerializableChunkDataLongMixin}).
 */
@Mixin(SectionStorage.class)
abstract class SectionStorageLongMixin {
    @ModifyArg(method = "tryRead", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
        index = 0)
    private Function<Object, Object> Singularity$carryPois(Function<Object, Object> decode, @Local(argsOnly = true) ChunkPos pos) {
        return data -> {
            WidePacking.carryChunkKey(pos.pack());
            return decode.apply(data);
        };
    }
}
