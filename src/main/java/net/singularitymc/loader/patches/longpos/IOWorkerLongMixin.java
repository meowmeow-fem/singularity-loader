package net.singularitymc.loader.patches.longpos;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * experimental_long_positions, 3.5.4: chunks, entities and POIs past the int block range are neither saved nor
 * loaded (the save format follows in 3.5.5), so they regenerate after a restart and never overwrite the chunk their
 * truncated coordinates would name. IOWorker is widened ({@code asm/PositionWidening}), so {@code x()} / {@code z()}
 * are the 64-bit chunk coordinates here.
 */
@Mixin(IOWorker.class)
abstract class IOWorkerLongMixin {
    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), cancellable = true)
    private void Singularity$storeTag(ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!WidePacking.chunkSavable(pos.x(), pos.z())) {
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }

    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), cancellable = true)
    private void Singularity$storeSupplier(ChunkPos pos, Supplier<CompoundTag> tag, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!WidePacking.chunkSavable(pos.x(), pos.z())) {
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }

    @Inject(method = "loadAsync", at = @At("HEAD"), cancellable = true)
    private void Singularity$load(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        if (!WidePacking.chunkSavable(pos.x(), pos.z())) {
            cir.setReturnValue(CompletableFuture.completedFuture(Optional.empty()));
        }
    }

    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void Singularity$scan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (!WidePacking.chunkSavable(pos.x(), pos.z())) {
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
