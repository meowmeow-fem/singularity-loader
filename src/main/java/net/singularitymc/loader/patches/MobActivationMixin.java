package net.singularitymc.loader.patches;

import net.minecraft.world.entity.Mob;
import net.singularitymc.loader.util.ActivationAware;
import net.singularitymc.perf.PerfHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The entity activation range's memory on every mob (4.2, see {@code PerfHooks.skipTick}). */
@Mixin(Mob.class)
abstract class MobActivationMixin implements ActivationAware {
    @Unique private long singularity$nextNearCheck;
    @Unique private boolean singularity$near = true;

    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$despawnCheckOfFarMobs(CallbackInfo ci) {
        if (PerfHooks.skipDespawnCheck((Mob) (Object) this)) {
            ci.cancel();
        }
    }

    @Override
    public long singularity$nextNearCheck() {
        return singularity$nextNearCheck;
    }

    @Override
    public boolean singularity$near() {
        return singularity$near;
    }

    @Override
    public void singularity$setNear(boolean near, long nextCheck) {
        this.singularity$near = near;
        this.singularity$nextNearCheck = nextCheck;
    }
}
