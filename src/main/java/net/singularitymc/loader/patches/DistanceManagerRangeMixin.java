package net.singularitymc.loader.patches;

import net.minecraft.server.level.DistanceManager;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 4.3: "is this chunk in entity / block ticking range" is asked once per entity and once per block entity in every
 * tick, each time a hash lookup that misses the CPU cache (7 % of the tick with 6,000 entities, 21 % with 9,600
 * hoppers). The answer is the same for everything in a chunk, so it is remembered per chunk for the tick.
 */
@Mixin(DistanceManager.class)
abstract class DistanceManagerRangeMixin {
    @Unique private final long[] singularity$entityKeys = new long[1024];
    @Unique private final byte[] singularity$entityValues = new byte[1024];
    @Unique private final int[] singularity$entityStamps = new int[1024];
    @Unique private final long[] singularity$blockKeys = new long[1024];
    @Unique private final byte[] singularity$blockValues = new byte[1024];
    @Unique private final int[] singularity$blockStamps = new int[1024];

    @Inject(method = "inEntityTickingRange", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$entityRangeCached(long chunk, CallbackInfoReturnable<Boolean> cir) {
        int cached = PerfHooks.tickRangeGet(singularity$entityKeys, singularity$entityValues, singularity$entityStamps, chunk);
        if (cached >= 0) {
            cir.setReturnValue(cached == 1);
        }
    }

    @Inject(method = "inEntityTickingRange", at = @At("RETURN"), require = 0)
    private void Singularity$entityRangeStore(long chunk, CallbackInfoReturnable<Boolean> cir) {
        PerfHooks.tickRangePut(singularity$entityKeys, singularity$entityValues, singularity$entityStamps, chunk, cir.getReturnValueZ());
    }

    @Inject(method = "inBlockTickingRange", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$blockRangeCached(long chunk, CallbackInfoReturnable<Boolean> cir) {
        int cached = PerfHooks.tickRangeGet(singularity$blockKeys, singularity$blockValues, singularity$blockStamps, chunk);
        if (cached >= 0) {
            cir.setReturnValue(cached == 1);
        }
    }

    @Inject(method = "inBlockTickingRange", at = @At("RETURN"), require = 0)
    private void Singularity$blockRangeStore(long chunk, CallbackInfoReturnable<Boolean> cir) {
        PerfHooks.tickRangePut(singularity$blockKeys, singularity$blockValues, singularity$blockStamps, chunk, cir.getReturnValueZ());
    }
}
