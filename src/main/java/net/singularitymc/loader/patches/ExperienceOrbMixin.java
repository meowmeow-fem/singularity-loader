package net.singularitymc.loader.patches;

import net.minecraft.world.entity.ExperienceOrb;
import net.singularitymc.loader.util.PatchSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimisation: experience orbs look for neighbours to merge with every 20 ticks. For orbs lying
 * still on the ground the search runs less often ({@code singularity.opt.xpMergeRest}, default
 * every 40 ticks). Orbs that are moving or following a player are not affected.
 */
@Mixin(ExperienceOrb.class)
abstract class ExperienceOrbMixin {
    @Inject(method = "scanForMerges", at = @At("HEAD"), cancellable = true, require = 0)
    private void Singularity$throttleRestingScan(CallbackInfo ci) {
        int mul = PatchSettings.XP_MERGE_REST;
        if (mul <= 1) {
            return;
        }
        ExperienceOrb self = (ExperienceOrb) (Object) this;
        // The vanilla call site only runs on ticks where tickCount % 20 == 1.
        if (PatchSettings.isResting(self.onGround(), self.getDeltaMovement().horizontalDistanceSqr())
                && (self.tickCount / 20) % mul != 0) {
            ci.cancel();
        }
    }
}
