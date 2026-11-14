package net.singularitymc.loader.patches.longpos;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * experimental_long_positions, 3.5.7: mobs placed during generation get their X/Z as {@code clamp((double) x, ...)}
 * of the chunk's narrowed block coordinates; a double cannot be carried on its own, so these two are carried here.
 * Without it a polar bear's spawn check read a chunk 2^33 blocks away and generation failed at 10 billion.
 *
 * <p>3.5.8: the method retries a spot until it lies in {@code [minX, minX + 16)}, in int math on the chunk's narrowed
 * coordinates. For the chunk whose narrowed minimum is 2,147,483,632 (the one below every odd multiple of 2^31)
 * {@code minX + 16} overflows, no spot ever fits, and the worker loops forever (watchdog). That one chunk column gets
 * no mobs at generation; they still spawn there later.
 *
 * <p>4.1: the method's second argument is a {@code BlockPos} in 26.3 and the biome's {@code Holder} in 26.1-26.2, so the
 * guard has one handler per signature; the group makes sure exactly one of them applies.
 */
@Mixin(NaturalSpawner.class)
abstract class NaturalSpawnerLongMixin {
    /** 26.3: {@code spawnMobsForChunkGeneration(ServerLevelAccessor, BlockPos, ChunkPos, RandomSource)}. */
    @Group(name = "skipOverflowingChunk", min = 1, max = 1)
    @Inject(method = "spawnMobsForChunkGeneration(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/util/RandomSource;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void Singularity$skipOverflowingChunk(ServerLevelAccessor level, BlockPos center, ChunkPos chunk, RandomSource random, CallbackInfo ci) {
        Singularity$skip(chunk, ci);
    }

    /** 26.1 and 26.2: the second argument is the chunk's biome ({@code Holder<Biome>}). */
    @Group(name = "skipOverflowingChunk", min = 1, max = 1)
    @Inject(method = "spawnMobsForChunkGeneration(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/Holder;"
        + "Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/util/RandomSource;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void Singularity$skipOverflowingChunkOld(ServerLevelAccessor level, Holder<?> biome, ChunkPos chunk, RandomSource random, CallbackInfo ci) {
        Singularity$skip(chunk, ci);
    }

    @Unique
    private static void Singularity$skip(ChunkPos chunk, CallbackInfo ci) {
        if (chunk.getMinBlockX() > Integer.MAX_VALUE - 16 || chunk.getMinBlockZ() > Integer.MAX_VALUE - 16) {
            ci.cancel();
        }
    }

    @Redirect(method = "spawnMobsForChunkGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D"))
    private static double Singularity$carrySpawnPosition(double value, double min, double max) {
        return WidePacking.carryInDouble(Mth.clamp(value, min, max));
    }
}
