package net.singularitymc.loader.patches.longpos;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * experimental_long_positions, 3.5.8: a safety net. A mob asks whether it can walk straight to the next path nodes
 * (a few blocks away) by tracing the blocks in between, which loads the chunks on the line. A point that int code got
 * wrong far out makes that line billions of blocks long; such a line is never clear.
 */
@Mixin(PathNavigation.class)
abstract class PathClearanceLongMixin {
    private static final double MAX_STRAIGHT = 1024;

    @Inject(method = "isClearForMovementBetween", at = @At("HEAD"), cancellable = true)
    private static void Singularity$notAcrossTheWorld(Mob mob, Vec3 from, Vec3 to, boolean water, CallbackInfoReturnable<Boolean> cir) {
        if (Math.abs(from.x - to.x) > MAX_STRAIGHT || Math.abs(from.z - to.z) > MAX_STRAIGHT) {
            cir.setReturnValue(false);
        }
    }
}
