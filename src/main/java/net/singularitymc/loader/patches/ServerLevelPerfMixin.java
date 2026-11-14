package net.singularitymc.loader.patches;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 4.2 (docs/PERFORMANCE.md): the entity activation range (mobs far from every player tick only now and then) and,
 * under heavy load, thinned random ticks.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelPerfMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$activationRange(Entity entity, CallbackInfo ci) {
        if (PerfHooks.skipTick((ServerLevel) (Object) this, entity)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int Singularity$thinRandomTicks(int tickSpeed, LevelChunk chunk) {
        return PerfHooks.randomTickSpeed(chunk, tickSpeed);
    }
}
