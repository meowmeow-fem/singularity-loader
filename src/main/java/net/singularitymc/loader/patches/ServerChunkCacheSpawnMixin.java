package net.singularitymc.loader.patches;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural mob spawning, 4.2: with {@code mob_spawn_interval} above 1, or when the adaptive governor asks for it,
 * spawning sits out ticks. In those ticks the count of every entity of the world ({@code createState}) is not taken
 * either: the last one is kept. Mob caps are unchanged; they just fill more slowly.
 *
 * <p>The two calls have other signatures in 26.1 and 26.2 than in 26.3, so each has one wrapper per signature (all
 * optional); a wrapper calls the game's method through {@code Operation}, never by name.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheSpawnMixin {
    @Shadow private NaturalSpawner.SpawnState lastSpawnState;

    /**
     * In a tick without spawning the chunks near players are not collected (and shuffled, and walked) either. Their
     * "inhabited time", which raises the local difficulty, then grows that much more slowly.
     */
    @WrapOperation(method = "tickChunks*", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap;collectSpawningChunks(Ljava/util/List;)V"))
    private void Singularity$collectOnlyWhenSpawning(ChunkMap map, List<LevelChunk> out, Operation<Void> original) {
        if (PerfHooks.spawnThisTick()) {
            original.call(map, out);
        }
    }

    /** 26.3. */
    @WrapOperation(method = "tickChunks*", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState Singularity$keepSpawnState(int spawnableChunks, ServerLevel level, NaturalSpawner.ChunkGetter chunks,
                                                                 LocalMobCapCalculator caps, Operation<NaturalSpawner.SpawnState> original) {
        NaturalSpawner.SpawnState last = this.lastSpawnState;
        return last != null && !PerfHooks.spawnThisTick() ? last : original.call(spawnableChunks, level, chunks, caps);
    }

    /** 26.1 and 26.2: the entities are handed in. */
    @WrapOperation(method = "tickChunks*", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;"
            + "Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)"
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState Singularity$keepSpawnStateOld(int spawnableChunks, Iterable<Entity> entities, NaturalSpawner.ChunkGetter chunks,
                                                                    LocalMobCapCalculator caps, Operation<NaturalSpawner.SpawnState> original) {
        NaturalSpawner.SpawnState last = this.lastSpawnState;
        return last != null && !PerfHooks.spawnThisTick() ? last : original.call(spawnableChunks, entities, chunks, caps);
    }

    /** 26.2 and 26.3. */
    @WrapOperation(method = "tickChunks*", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories("
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZ)Ljava/util/List;"))
    private List<MobCategory> Singularity$spawnInterval(NaturalSpawner.SpawnState state, boolean friendlies, boolean enemies,
                                                        Operation<List<MobCategory>> original) {
        return PerfHooks.spawnThisTick() ? original.call(state, friendlies, enemies) : List.of();
    }

    /** 26.1: one more flag. */
    @WrapOperation(method = "tickChunks*", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories("
            + "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)Ljava/util/List;"))
    private List<MobCategory> Singularity$spawnIntervalOld(NaturalSpawner.SpawnState state, boolean friendlies, boolean enemies, boolean persistent,
                                                           Operation<List<MobCategory>> original) {
        return PerfHooks.spawnThisTick() ? original.call(state, friendlies, enemies, persistent) : List.of();
    }
}
